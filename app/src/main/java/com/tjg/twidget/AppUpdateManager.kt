package com.tjg.twidget

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateChannel {
    STABLE,
    BETA,
}

data class AppRelease(
    val version: AppVersion,
    val assetName: String,
    val downloadUrl: String,
    val prerelease: Boolean,
)

data class AppReleaseCheck(
    val update: AppRelease?,
    val notices: List<ReleaseNotice>,
)

data class ReleaseNotice(
    val tag: String,
    val title: String,
    val body: String,
    val url: String,
    val prerelease: Boolean,
    val publishedAt: String,
)

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prereleaseLabel: String?,
    val prereleaseNumber: Int?,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prereleaseLabel == null && other.prereleaseLabel != null) return 1
        if (prereleaseLabel != null && other.prereleaseLabel == null) return -1
        compareValues(prereleaseLabel, other.prereleaseLabel).takeIf { it != 0 }?.let { return it }
        return compareValues(prereleaseNumber ?: 0, other.prereleaseNumber ?: 0)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        prereleaseLabel?.let {
            append("-$it")
            prereleaseNumber?.let { number -> append(".$number") }
        }
    }

    companion object {
        private val VERSION_PATTERN = Regex(
            "^(?:twidget-v|v)?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z][A-Za-z0-9-]*)(?:\\.(\\d+))?)?$",
        )

        fun parse(value: String): AppVersion? {
            val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
            return AppVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                prereleaseLabel = match.groupValues[4].ifBlank { null },
                prereleaseNumber = match.groupValues[5].ifBlank { null }?.toIntOrNull(),
            )
        }
    }
}

object AppUpdateManager {
    private const val RELEASES_URL =
        "https://api.github.com/repos/thatjoshguy67/twidget/releases?per_page=30"
    private const val USER_AGENT = "Twidget-Android-Updater"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L

    fun findUpdate(installedVersion: String, channel: UpdateChannel): AppRelease? {
        return checkReleases(installedVersion, channel).update
    }

    fun checkReleases(installedVersion: String, channel: UpdateChannel): AppReleaseCheck {
        val current = AppVersion.parse(installedVersion)
        val connection = request(RELEASES_URL)
        val releases = try {
            if (connection.responseCode !in 200..299) {
                error("GitHub releases request failed with HTTP ${connection.responseCode}")
            }
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }

        val update = current?.let {
            eligibleReleases(parseReleases(releases), channel)
                .filter { release -> release.version > current }
                .maxByOrNull(AppRelease::version)
        }
        return AppReleaseCheck(update, parseReleaseNotices(releases))
    }

    fun fetchReleaseNotices(): List<ReleaseNotice> {
        val connection = request(RELEASES_URL)
        val releases = try {
            if (connection.responseCode !in 200..299) {
                error("GitHub releases request failed with HTTP ${connection.responseCode}")
            }
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
        return parseReleaseNotices(releases)
    }

    internal fun parseReleaseNotices(releases: JSONArray): List<ReleaseNotice> = buildList {
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name").trim()
            val url = release.optString("html_url").trim()
            if (tag.isBlank() || !url.startsWith(RELEASE_PAGE_PREFIX)) continue
            add(ReleaseNotice(
                tag = tag,
                title = release.optString("name").trim().ifBlank { tag },
                body = release.optString("body").trim(),
                url = url,
                prerelease = release.optBoolean("prerelease"),
                publishedAt = release.optString("published_at").trim(),
            ))
        }
    }

    internal fun eligibleReleases(
        releases: List<AppRelease>,
        channel: UpdateChannel,
    ): List<AppRelease> = releases.filter { channel == UpdateChannel.BETA || !it.prerelease }

    private fun parseReleases(releases: JSONArray): List<AppRelease> {
        return buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft")) continue
                val prerelease = release.optBoolean("prerelease")
                val version = AppVersion.parse(release.optString("tag_name")) ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.optJSONObject(assetIndex) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    val assetVersion = name
                        .takeIf { File(it).name == it && it.endsWith(".apk", ignoreCase = true) }
                        ?.dropLast(4)
                        ?.let(AppVersion::parse)
                    if (assetVersion == version && url.startsWith("https://")) {
                        add(AppRelease(version, name, url, prerelease))
                        break
                    }
                }
            }
        }
    }

    fun download(release: AppRelease, destinationDirectory: File): File {
        destinationDirectory.mkdirs()
        val target = File(destinationDirectory, release.assetName)
        val temporary = File(destinationDirectory, "${release.assetName}.part")
        temporary.delete()
        val connection = request(release.downloadUrl)
        try {
            if (connection.responseCode !in 200..299) {
                error("APK download failed with HTTP ${connection.responseCode}")
            }
            val expectedSize = connection.contentLengthLong
            if (expectedSize > MAX_APK_BYTES) error("APK is unexpectedly large")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) error("APK is unexpectedly large")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        target.delete()
        check(temporary.renameTo(target)) { "Unable to finish APK download" }
        return target
    }

    private fun request(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private const val RELEASE_PAGE_PREFIX =
        "https://github.com/thatjoshguy67/twidget/releases/"
}
