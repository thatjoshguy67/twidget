package com.tjg.twidget.notices

import com.tjg.twidget.update.ReleaseNotice

/** Prefer a fresh changelog; keep the reader usable offline if one is already available. */
internal object LatestReleaseNotice {
    fun load(cached: ReleaseNotice?, fetch: () -> ReleaseNotice?): ReleaseNotice? = try {
        fetch() ?: cached
    } catch (error: Exception) {
        cached ?: throw error
    }
}
