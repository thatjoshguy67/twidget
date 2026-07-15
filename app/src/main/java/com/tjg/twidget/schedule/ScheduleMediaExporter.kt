package com.tjg.twidget.schedule

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@file:Suppress("UseKtx")



enum class ScheduleMediaExportResult {
    SAVED,
    NOTHING_TO_SAVE,
    FAILED,
}

data class ScheduleMediaExportOutcome(
    val result: ScheduleMediaExportResult,
    val savedCount: Int = 0,
    val detail: String? = null,
)

object ScheduleMediaExporter {
    private const val DOWNLOADS_SUBDIR = "Twidget"
    private const val MAX_BYTES = 100L * 1024L * 1024L

    fun downloadItem(context: Context, item: ScheduleThreadItem): ScheduleMediaExportOutcome {
        if (item.media.isEmpty()) {
            return ScheduleMediaExportOutcome(ScheduleMediaExportResult.NOTHING_TO_SAVE)
        }
        var saved = 0
        var lastError: String? = null
        item.media.forEachIndexed { index, source ->
            runCatching {
                when (source) {
                    is LocalUriMedia -> saveLocal(context, source, index)
                    is PublicUrlMedia -> saveRemote(context, source.url, source.mimeType, index)
                    is PostponeLibraryMedia -> {
                        val url = source.url
                        if (url.isNullOrBlank()) error("Missing media URL")
                        saveRemote(context, url, source.mimeType, index, source.name)
                    }
                }
            }.onSuccess { saved++ }
                .onFailure { lastError = it.message ?: "Save failed" }
        }
        return when {
            saved == 0 -> ScheduleMediaExportOutcome(
                ScheduleMediaExportResult.FAILED,
                detail = lastError,
            )
            saved < item.media.size -> ScheduleMediaExportOutcome(
                ScheduleMediaExportResult.SAVED,
                savedCount = saved,
                detail = lastError,
            )
            else -> ScheduleMediaExportOutcome(
                ScheduleMediaExportResult.SAVED,
                savedCount = saved,
            )
        }
    }

    private fun saveLocal(context: Context, media: LocalUriMedia, index: Int) {
        val uri = Uri.parse(media.uri)
        require(uri.scheme == "content") { "Unsupported local media URI" }
        context.contentResolver.openInputStream(uri)?.use { input ->
            val fileName = media.displayName?.takeIf { it.isNotBlank() }
                ?: defaultFileName(media.mimeType, index)
            writeToDownloads(
                context = context,
                fileName = fileName,
                mimeType = media.mimeType ?: "*/*",
                write = { output -> input.copyTo(output) },
            )
        } ?: error("Couldn't read saved media")
    }

    private fun saveRemote(
        context: Context,
        url: String,
        mimeType: String?,
        index: Int,
        preferredName: String? = null,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Twidget/0.1")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("Download HTTP $code")
        }
        if (connection.contentLengthLong > MAX_BYTES) {
            connection.disconnect()
            error("Media file is too large")
        }
        val resolvedMime = mimeType
            ?: connection.contentType?.substringBefore(';')?.trim()
            ?: "*/*"
        val fileName = preferredName?.takeIf { it.isNotBlank() }
            ?: fileNameFromUrl(url)
            ?: defaultFileName(resolvedMime, index)
        connection.inputStream.use { input ->
            writeToDownloads(
                context = context,
                fileName = fileName,
                mimeType = resolvedMime,
                write = { output -> copyLimited(input, output) },
            )
        }
        connection.disconnect()
    }

    private fun writeToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ) {
        val safeName = sanitizeFileName(fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBDIR",
                )
            }
            val target = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Couldn't create download")
            context.contentResolver.openOutputStream(target)?.use(write)
                ?: error("Couldn't write download")
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOADS_SUBDIR,
            )
            if (!directory.exists() && !directory.mkdirs()) {
                error("Couldn't create Downloads folder")
            }
            File(directory, safeName).outputStream().use(write)
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) error("Media file is too large")
            output.write(buffer, 0, read)
        }
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)

    private fun defaultFileName(mimeType: String?, index: Int): String {
        val extension = when {
            mimeType?.startsWith("video/") == true -> "mp4"
            mimeType?.startsWith("image/") == true -> mimeType.substringAfter('/').ifBlank { "jpg" }
            else -> "bin"
        }
        return "twidget-media-${System.currentTimeMillis()}-$index.$extension"
    }

    private fun fileNameFromUrl(url: String): String? =
        Uri.parse(url).lastPathSegment?.takeIf { it.contains('.') }
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
}
