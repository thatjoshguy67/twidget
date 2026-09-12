package com.tjg.twidget.notices

import android.content.Context
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.AppVersion
import com.tjg.twidget.update.ReleaseNotice

/** Preview the changelog shipped with this debug build without requiring a release. */
internal object UpcomingReleaseNotes {
    fun read(context: Context): ReleaseNotice? = runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (!AppUpdateManager.isDebugBuild(version)) return@runCatching null
        val markdown = context.assets.open("upcoming-changelog.md").bufferedReader().use { it.readText() }
        parse(version, markdown)
    }.getOrNull()

    internal fun parse(installedVersion: String, markdown: String): ReleaseNotice? {
        if (!AppUpdateManager.isDebugBuild(installedVersion)) return null
        val version = AppVersion.parse(installedVersion)
            ?.copy(prereleaseLabel = null, prereleaseNumber = null)?.toString() ?: return null
        val heading = Regex("^## \\[([^]]+)](?:\\s.*)?$")
        var capture = false
        val body = buildList {
            for (line in markdown.lineSequence()) {
                val section = heading.matchEntire(line.trimEnd())
                if (section != null) {
                    if (capture) break
                    capture = section.groupValues[1] == version
                } else if (capture && !line.startsWith("[$version]:")) {
                    add(line)
                }
            }
        }.joinToString("\n").trim()
        if (body.isBlank()) return null
        return ReleaseNotice(
            tag = "upcoming-$version",
            title = "Twidget v$version",
            body = body,
            url = "",
            prerelease = false,
            publishedAt = "",
            upcoming = true,
        )
    }

    internal fun merge(preview: ReleaseNotice?, published: List<ReleaseNotice>): List<ReleaseNotice> {
        if (preview == null) return published
        val version = AppVersion.parse(preview.tag.removePrefix("upcoming-")) ?: return published
        val alreadyReleased = published.any {
            !it.prerelease && AppVersion.parse(it.tag)?.let { released -> released >= version } == true
        }
        return if (alreadyReleased) published else listOf(preview) + published
    }
}
