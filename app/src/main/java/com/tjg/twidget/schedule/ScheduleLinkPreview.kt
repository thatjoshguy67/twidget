package com.tjg.twidget.schedule

import com.tjg.twidget.core.HttpTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

internal data class ScheduleLinkPreview(
    val sourceUrl: String,
    val displayUrl: String,
    val title: String,
    val imageUrl: String?,
)

internal object ScheduleLinkPreviewParser {
    private val urlPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val metaPattern = Regex("<meta\\s+[^>]*>", RegexOption.IGNORE_CASE)
    private val titlePattern = Regex(
        "<title(?:\\s+[^>]*)?>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun firstUrl(text: String): String? = urlPattern.find(text)?.value
        ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\"', '\'')
        ?.takeIf(::isHttpUrl)

    fun parse(sourceUrl: String, html: String): ScheduleLinkPreview {
        val metadata = linkedMapOf<String, String>()
        metaPattern.findAll(html).forEach { match ->
            val tag = match.value
            val key = attribute(tag, "property") ?: attribute(tag, "name") ?: return@forEach
            val content = attribute(tag, "content") ?: return@forEach
            metadata.putIfAbsent(key.lowercase(), decodeHtml(content).trim())
        }
        val uri = URI(sourceUrl)
        val title = sequenceOf(
            metadata["og:title"],
            metadata["twitter:title"],
            titlePattern.find(html)?.groupValues?.getOrNull(1)?.let(::stripTags)?.let(::decodeHtml),
            uri.host,
        ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
        val rawImage = sequenceOf(
            metadata["og:image:secure_url"],
            metadata["og:image"],
            metadata["twitter:image"],
            metadata["twitter:image:src"],
        ).filterNotNull().firstOrNull(String::isNotBlank)
        val imageUrl = rawImage?.let { resolveUrl(sourceUrl, it) }?.takeIf(::isHttpUrl)
        val authority = buildString {
            append(uri.host.orEmpty())
            if (uri.port >= 0) append(':').append(uri.port)
        }
        val displayUrl = if (authority.isBlank()) sourceUrl else "${uri.scheme}://$authority"
        return ScheduleLinkPreview(sourceUrl, displayUrl, title, imageUrl)
    }

    private fun attribute(tag: String, name: String): String? {
        val escapedName = Regex.escape(name)
        val quoted = Regex(
            "(?:^|\\s)$escapedName\\s*=\\s*([\"'])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(tag)?.groupValues?.getOrNull(2)
        if (!quoted.isNullOrBlank()) return quoted
        return Regex(
            "(?:^|\\s)$escapedName\\s*=\\s*([^\\s>]+)",
            RegexOption.IGNORE_CASE,
        ).find(tag)?.groupValues?.getOrNull(1)
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]+>"), " ")

    private fun decodeHtml(value: String): String = value
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toIntOrNull(16)?.let(::codePointToString) ?: match.value
        }
        .replace(Regex("&#([0-9]+);")) { match ->
            match.groupValues[1].toIntOrNull()?.let(::codePointToString) ?: match.value
        }
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(Regex("\\s+"), " ")

    private fun codePointToString(value: Int): String =
        if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""

    private fun resolveUrl(baseUrl: String, value: String): String? = runCatching {
        URI(baseUrl).resolve(value.trim()).toString()
    }.getOrNull()

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", true) || uri.scheme.equals("http", true)
    }.getOrDefault(false)
}

internal object ScheduleLinkPreviewLoader {
    private const val MAX_HTML_BYTES = 512 * 1024
    private const val MAX_REDIRECTS = 5
    private val cache = ConcurrentHashMap<String, ScheduleLinkPreview>()

    fun cached(url: String): ScheduleLinkPreview? = cache[url]

    fun load(url: String): ScheduleLinkPreview? {
        cache[url]?.let { return it }
        val response = fetchHtml(url) ?: return null
        val parsed = ScheduleLinkPreviewParser.parse(response.finalUrl, response.html)
        return parsed.copy(
            sourceUrl = url,
            imageUrl = parsed.imageUrl?.takeIf(::isPublicHttpUrl),
        )
            .also { cache[url] = it }
    }

    private fun fetchHtml(initialUrl: String): HtmlResponse? {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!isPublicHttpUrl(currentUrl)) return null
            val connection = HttpTransport.openConnection(
                currentUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1",
                    "User-Agent" to "Mozilla/5.0 (Android) Twidget/1.0",
                ),
                connectTimeoutMs = 6_000,
                readTimeoutMs = 8_000,
            ).apply { instanceFollowRedirects = false }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) return null
                val contentType = connection.contentType.orEmpty()
                if (!contentType.contains("text/html", true) &&
                    !contentType.contains("application/xhtml+xml", true)
                ) return null
                if (connection.contentLengthLong > MAX_HTML_BYTES) return null
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_HTML_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val charset = contentType.substringAfter("charset=", "")
                    .substringBefore(';')
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                    ?: Charsets.UTF_8
                return HtmlResponse(currentUrl, bytes.toString(charset))
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun isPublicHttpUrl(value: String): Boolean {
        return runCatching {
            val url = URL(value)
            if (url.protocol != "https" && url.protocol != "http") return@runCatching false
            val host = url.host.trim().trimEnd('.')
            if (host.isBlank() || host.equals("localhost", true) || host.endsWith(".local", true)) {
                return@runCatching false
            }
            InetAddress.getAllByName(host).all(::isPublicAddress)
        }.getOrDefault(false)
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first >= 224 || (first == 100 && second in 64..127)) return false
        }
        if (address is Inet6Address && bytes.isNotEmpty() && (bytes[0].toInt() and 0xfe) == 0xfc) {
            return false
        }
        return true
    }

    private data class HtmlResponse(val finalUrl: String, val html: String)
}
