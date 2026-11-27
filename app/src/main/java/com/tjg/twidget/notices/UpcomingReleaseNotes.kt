package com.tjg.twidget.notices

import android.content.Context
import com.tjg.twidget.update.AppVersion
import com.tjg.twidget.update.ReleaseNotice

/** Preview the changelog shipped with this debug build without requiring a release. */
internal object UpcomingReleaseNotes {
    fun read(context: Context): ReleaseNotice? = runCatching {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (debugBaseVersion(version) == null) return@runCatching null
        val markdown = context.assets.open("upcoming-changelog.md").bufferedReader().use { it.readText() }
        parse(version, markdown)
    }.getOrNull()

    internal fun parse(installedVersion: String, markdown: String): ReleaseNotice? {
        val version = debugBaseVersion(installedVersion)?.toString() ?: return null
        val heading = Regex("^## \\[([^]]+)](?:\\s.*)?$")
        val sections = markdown.lineSequence().mapNotNull {
            heading.matchEntire(it.trimEnd())?.groupValues?.get(1)
        }.toList()
        val previewVersion = if (version in sections) version else sections
            .mapNotNull(AppVersion::parse)
            .filter { it.prereleaseLabel == "beta" && (it.prereleaseNumber ?: 0) > 0 &&
                it.copy(prereleaseLabel = null, prereleaseNumber = null).toString() == version }
            .maxOrNull()?.toString() ?: return null
        var capture = false
        val body = buildList {
            for (line in markdown.lineSequence()) {
                val section = heading.matchEntire(line.trimEnd())
                if (section != null) {
                    if (capture) break
                    capture = section.groupValues[1] == previewVersion
                } else if (capture && !line.startsWith("[$previewVersion]:")) {
                    add(line)
                }
            }
        }.joinToString("\n").trim()
        if (body.isBlank()) return null
        return ReleaseNotice(
            tag = "upcoming-$previewVersion",
            title = "Twidget v$previewVersion",
            body = body,
            url = "",
            prerelease = previewVersion != version,
            publishedAt = "",
            upcoming = true,
        )
    }

    private fun debugBaseVersion(installedVersion: String): AppVersion? {
        AppVersion.parse(installedVersion)?.takeIf {
            it.prereleaseLabel.equals("debug", ignoreCase = true)
        }?.let { return it.copy(prereleaseLabel = null, prereleaseNumber = null) }
        val timestamped = Regex("^(\\d+\\.\\d+\\.\\d+)-dev\\.\\d{8}\\.\\d{4}$")
            .matchEntire(installedVersion) ?: return null
        return AppVersion.parse(timestamped.groupValues[1])
    }

    internal fun merge(preview: ReleaseNotice?, published: List<ReleaseNotice>): List<ReleaseNotice> {
        if (preview == null) return published
        val version = AppVersion.parse(preview.tag.removePrefix("upcoming-")) ?: return published
        val alreadyReleased = published.any {
            AppVersion.parse(it.tag)?.let { released -> released >= version } == true
        }
        return if (alreadyReleased) published else listOf(preview) + published
    }
}
