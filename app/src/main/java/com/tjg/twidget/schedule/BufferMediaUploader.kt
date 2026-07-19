package com.tjg.twidget.schedule

import android.content.Context
import android.net.Uri
import com.tjg.twidget.core.HttpTransport
import org.json.JSONArray
import org.json.JSONObject

internal data class BufferMediaUploadResult(
    val post: ScheduledPost? = null,
    val errors: List<String> = emptyList(),
)

/**
 * Turns locally picked media into URLs Buffer can fetch, using the same
 * pipeline as Buffer's own web composer: an upload slot from the
 * s3PreSignedURL GraphQL query followed by a direct PUT of the bytes to
 * Buffer's media bucket.
 */
internal class BufferMediaUploader(
    context: Context,
    private val client: BufferClient,
) {
    private val appContext = context.applicationContext

    /**
     * Returns a copy of [post] with every [LocalUriMedia] replaced by the
     * [PublicUrlMedia] produced by uploading it, or the upload errors. Posts
     * without local media pass through untouched.
     */
    fun withUploadedMedia(post: ScheduledPost): BufferMediaUploadResult {
        val locals = post.thread.flatMap { it.media }
            .filterIsInstance<LocalUriMedia>()
            .distinctBy { it.uri }
        if (locals.isEmpty()) return BufferMediaUploadResult(post)
        val organization = client.organizationIdForChannel(post.accountUsername)
        val organizationId = organization.value
            ?: return BufferMediaUploadResult(
                errors = organization.errors.map { "Buffer channel lookup: ${it.message}" },
            )
        val uploads = mutableMapOf<String, PublicUrlMedia>()
        locals.forEach { media ->
            val outcome = runCatching { upload(organizationId, media) }
            uploads[media.uri] = outcome.getOrElse {
                return BufferMediaUploadResult(
                    errors = listOf(it.message ?: "Unable to upload media to Buffer"),
                )
            }
        }
        val thread = post.thread.map { item ->
            item.copy(
                media = item.media.map { source ->
                    if (source is LocalUriMedia) uploads.getValue(source.uri) else source
                },
            )
        }
        return BufferMediaUploadResult(post.copy(thread = thread))
    }

    private fun upload(organizationId: String, media: LocalUriMedia): PublicUrlMedia {
        val uri = Uri.parse(media.uri)
        val mimeType = media.mimeType
            ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        val fileName = media.displayName?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "twidget-upload"
        val slotResult = client.preSignedUpload(organizationId, fileName, mimeType)
        val slot = slotResult.value ?: error(
            "Buffer upload slot: ${slotResult.errors.firstOrNull()?.message ?: "no upload URL returned"}",
        )
        putBytes(slot.url, mimeType, uri)
        val url = registerUpload(slot.key)
            ?: "https://${slot.bucket}.s3.amazonaws.com/${slot.key}"
        return PublicUrlMedia(url = url, mimeType = mimeType)
    }

    private fun putBytes(url: String, mimeType: String, uri: Uri) {
        val resolver = appContext.contentResolver
        // S3 rejects chunked PUTs without a length, so size the stream up front.
        val length = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L
        val connection = HttpTransport.openConnection(
            url,
            method = "PUT",
            headers = mapOf("Content-Type" to mimeType),
            connectTimeoutMs = 20_000,
            readTimeoutMs = 120_000,
        )
        try {
            connection.doOutput = true
            if (length > 0) {
                connection.setFixedLengthStreamingMode(length)
            } else {
                connection.setChunkedStreamingMode(0)
            }
            resolver.openInputStream(uri)?.use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            } ?: error("Unable to read the selected media")
            val code = connection.responseCode
            if (code !in 200..299) error("Buffer media upload failed with HTTP $code")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Best-effort registration of the uploaded object with Buffer's media
     * pipeline; the web composer follows its S3 PUT with
     * /i/uploads/upload_media.json. That endpoint may not accept OAuth
     * clients, in which case the raw bucket URL is used instead — Buffer
     * fetches it at publish time like any other public URL asset.
     */
    private fun registerUpload(key: String): String? = runCatching {
        val token = BufferOAuth.accessToken(appContext)
        val body = JSONObject()
            .put("key", key)
            .put("serviceForceTranscodeVideo", false)
        val response = HttpTransport.post(
            "${BufferClient.API_URL}/i/uploads/upload_media.json",
            body.toString(),
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Authorization" to "Bearer $token",
            ),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 30_000,
        )
        if (response.code !in 200..299) return@runCatching null
        findMediaUrl(JSONObject(response.body))
    }.getOrNull()

    private fun findMediaUrl(root: JSONObject): String? =
        PREFERRED_URL_KEYS.firstNotNullOfOrNull { key -> findByKey(root, key) }

    private fun findByKey(value: Any?, key: String): String? = when (value) {
        is JSONObject ->
            value.optString(key).takeIf { it.startsWith("https://") }
                ?: value.keys().asSequence().firstNotNullOfOrNull { findByKey(value.opt(it), key) }
        is JSONArray ->
            (0 until value.length()).asSequence().firstNotNullOfOrNull { findByKey(value.opt(it), key) }
        else -> null
    }

    private companion object {
        val PREFERRED_URL_KEYS = listOf("location", "fullsize", "url", "photo", "picture", "video")
    }
}
