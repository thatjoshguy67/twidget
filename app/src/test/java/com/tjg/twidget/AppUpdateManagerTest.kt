package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun stableVersionIsNewerThanPrereleaseOfSameVersion() {
        assertTrue(AppVersion.parse("1.2.0")!! > AppVersion.parse("1.2.0-beta.9")!!)
    }

    @Test
    fun betaChannelIncludesStableAndPrereleaseBuilds() {
        val releases = listOf(
            AppRelease(AppVersion.parse("1.1.0-beta.4")!!, "beta.apk", "https://example.com/beta.apk", true),
            AppRelease(AppVersion.parse("1.0.1")!!, "stable.apk", "https://example.com/stable.apk", false),
        )

        val versions = AppUpdateManager.eligibleReleases(releases, UpdateChannel.BETA)
            .map { it.version.toString() }

        assertEquals(listOf("1.1.0-beta.4", "1.0.1"), versions)
    }

    @Test
    fun stableChannelExcludesPrereleases() {
        val releases = listOf(
            AppRelease(AppVersion.parse("1.1.0-beta.4")!!, "beta.apk", "https://example.com/beta.apk", true),
            AppRelease(AppVersion.parse("1.0.1")!!, "stable.apk", "https://example.com/stable.apk", false),
        )

        val versions = AppUpdateManager.eligibleReleases(releases, UpdateChannel.STABLE)
            .map { it.version.toString() }

        assertEquals(listOf("1.0.1"), versions)
    }
}
