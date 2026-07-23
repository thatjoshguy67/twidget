package com.tjg.twidget.schedule

import android.content.Context
import android.net.Uri
import java.io.File

internal object ScheduleOwnedMedia {
    fun delete(context: Context, value: String): Boolean {
        val file = fileFor(context, value) ?: return false
        return !file.exists() || file.delete()
    }

    private fun fileFor(context: Context, value: String): File? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.update_files") return null
        if (uri.pathSegments.firstOrNull() != "scheduled_media") return null
        val fileName = uri.pathSegments.getOrNull(1)?.takeIf {
            it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it
        } ?: return null
        val directory = File(context.filesDir, "scheduled-media")
        val candidate = File(directory, fileName)
        return candidate.takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }
    }
}
