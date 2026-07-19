package com.tjg.twidget.schedule

import android.content.Context
import android.net.Uri
import com.tjg.twidget.core.HttpTransport
import java.io.OutputStream
import java.util.UUID
import org.json.JSONObject

internal data class BufferMediaUploadResult(
    val post: ScheduledPost? = null,
    val errors: List<String> = emptyList(),
)

/**
 * Turns locally picked media into URLs Buffer can fetch. Buffer's API only
 * accepts media as public URLs and points API clients at external hosts, so
 * attachments are uploaded to the user's Cloudinary account via an unsigned
 * upload preset configured in Buffer settings.
 */
internal class BufferMediaUploader(context: Context) {
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
        val cloudName = ScheduleSettingsStore.cloudinaryCloudName(appContext)
        val uploadPreset = ScheduleSettingsStore.cloudinaryUploadPreset(appContext)
        if (cloudName == null || uploadPreset == null) {
            return BufferMediaUploadResult(
                errors = listOf(
                    "Add your Cloudinary cloud name and upload preset in Buffer settings " +
                        "to attach device media to Buffer posts",
                ),
            )
        }
        val uploads = mutableMapOf<String, PublicUrlMedia>()
        locals.forEach { media ->
            val outcome = runCatching { upload(cloudName, uploadPreset, media) }
            uploads[media.uri] = outcome.getOrElse {
                return BufferMediaUploadResult(
                    errors = listOf(it.message ?: "Unable to upload media to Cloudinary"),
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

    private fun upload(cloudName: String, uploadPreset: String, media: LocalUriMedia): PublicUrlMedia {
        val uri = Uri.parse(media.uri)
        val mimeType = media.mimeType
            ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        val fileName = media.displayName?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "twidget-upload"
        val boundary = "twidget-${UUID.randomUUID()}"
        val connection = HttpTransport.openConnection(
            "https://api.cloudinary.com/v1_1/$cloudName/auto/upload",
            method = "POST",
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
            connectTimeoutMs = 20_000,
            readTimeoutMs = 120_000,
        )
        try {
            connection.doOutput = true
            connection.setChunkedStreamingMode(0)
            connection.outputStream.use { output ->
                writeMultipart(output, boundary, uploadPreset, fileName, mimeType, uri)
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrNull()
                error("Cloudinary upload: ${message ?: "HTTP $code"}")
            }
            val payload = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            val url = payload.optString("secure_url").ifBlank { payload.optString("url") }
            if (url.isBlank()) error("Cloudinary upload: no media URL returned")
            return PublicUrlMedia(url = url, mimeType = mimeType)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeMultipart(
        output: OutputStream,
        boundary: String,
        uploadPreset: String,
        fileName: String,
        mimeType: String,
        uri: Uri,
    ) {
        fun writeText(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n")
        writeText("$uploadPreset\r\n")
        val safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "")
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
        writeText("Content-Type: $mimeType\r\n\r\n")
        appContext.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(output) }
            ?: error("Unable to read the selected media")
        writeText("\r\n--$boundary--\r\n")
    }
}
