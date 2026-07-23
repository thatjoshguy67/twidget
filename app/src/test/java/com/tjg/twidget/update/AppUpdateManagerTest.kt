package com.tjg.twidget.update

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

    @Test
    fun debugChannelOnlyIncludesDebugBuilds() {
        val releases = listOf(
            AppRelease(AppVersion.parse("1.1.0-debug.31")!!, "debug.apk", "https://example.com/debug.apk", true),
            AppRelease(AppVersion.parse("1.1.0-beta.1")!!, "beta.apk", "https://example.com/beta.apk", true),
            AppRelease(AppVersion.parse("1.1.0")!!, "stable.apk", "https://example.com/stable.apk", false),
        )

        val versions = AppUpdateManager.eligibleReleases(releases, UpdateChannel.DEBUG)
            .map { it.version.toString() }

        assertEquals(listOf("1.1.0-debug.31"), versions)
    }

    @Test
    fun betaChannelOffersNew110BetaToPreviousPublicBeta() {
        val expected = AppRelease(
            AppVersion.parse("1.1.0-beta.1")!!,
            "twidget-v1.1.0-beta.1.apk",
            "https://example.com/twidget-v1.1.0-beta.1.apk",
            true,
        )

        val update = AppUpdateManager.newestEligibleUpdate(
            installedVersion = "1.0.0-beta.1",
            releases = listOf(expected),
            channel = UpdateChannel.BETA,
        )

        assertEquals(expected, update)
    }

    @Test
    fun currentBetaDoesNotOfferItselfAgain() {
        val current = AppRelease(
            AppVersion.parse("1.1.0-beta.1")!!,
            "twidget-v1.1.0-beta.1.apk",
            "https://example.com/twidget-v1.1.0-beta.1.apk",
            true,
        )

        val update = AppUpdateManager.newestEligibleUpdate(
            installedVersion = "1.1.0-beta.1",
            releases = listOf(current),
            channel = UpdateChannel.BETA,
        )

        assertEquals(null, update)
    }

    @Test
    fun stableChannelDoesNotOfferBetaOnlyRelease() {
        val beta = AppRelease(
            AppVersion.parse("1.1.0-beta.1")!!,
            "twidget-v1.1.0-beta.1.apk",
            "https://example.com/twidget-v1.1.0-beta.1.apk",
            true,
        )

        val update = AppUpdateManager.newestEligibleUpdate(
            installedVersion = "1.0.0",
            releases = listOf(beta),
            channel = UpdateChannel.STABLE,
        )

        assertEquals(null, update)
    }

    @Test
    fun debugBuildOffersPublishedBetaOfSameBaseVersion() {
        val beta = AppRelease(
            AppVersion.parse("1.1.0-beta.1")!!,
            "twidget-v1.1.0-beta.1.apk",
            "https://example.com/twidget-v1.1.0-beta.1.apk",
            true,
        )

        val update = AppUpdateManager.newestEligibleUpdate(
            installedVersion = "1.1.0-debug.30",
            releases = listOf(beta),
            channel = UpdateChannel.BETA,
        )

        assertEquals(beta, update)
    }

    @Test
    fun debugBuildDoesNotOfferOlderPublishedVersion() {
        val older = AppRelease(
            AppVersion.parse("1.0.0")!!,
            "twidget-v1.0.0.apk",
            "https://example.com/twidget-v1.0.0.apk",
            false,
        )

        val update = AppUpdateManager.newestEligibleUpdate(
            installedVersion = "1.1.0-debug.30",
            releases = listOf(older),
            channel = UpdateChannel.BETA,
        )

        assertEquals(null, update)
    }

    @Test
    fun buildTypeSelectsMatchingDefaultUpdateChannel() {
        assertEquals(UpdateChannel.DEBUG, AppUpdateManager.defaultUpdateChannel("1.1.0-debug.30"))
        assertEquals(UpdateChannel.BETA, AppUpdateManager.defaultUpdateChannel("1.1.0-beta.1"))
        assertEquals(UpdateChannel.STABLE, AppUpdateManager.defaultUpdateChannel("1.1.0"))
    }
}
