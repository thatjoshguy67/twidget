package com.tjg.twidget.social

import org.junit.Assert.*
import org.junit.Test

class SocialProfilePolicyTest {
    private fun account(id: String, platform: SocialPlatform = SocialPlatform.X, remote: String = id) =
        PlatformAccount(id, platform, remote, "samehandle", "Name $id", "https://example.com/$id.png")

    private fun catalog(vararg accounts: PlatformAccount): SocialCatalog =
        accounts.fold(SocialCatalog()) { catalog, account -> SocialProfilePolicy.add(catalog, account) }

    @Test fun sameHandleAcrossPlatformsRemainsDistinct() {
        val initial = catalog(account("x"), account("github", SocialPlatform.GITHUB))
        assertEquals(2, initial.accounts.size)
        assertEquals(2, initial.profiles.size)
        val linked = SocialProfilePolicy.link(initial, initial.profiles[0].id, setOf(initial.profiles[1].id))
        assertEquals(listOf("x", "github"), linked.profiles.single().accountIds)
        assertTrue(linked.profiles.single().linked)
    }

    @Test fun repeatedRemoteIdentityUpdatesNameAndHandleWithoutReplacingBindings() {
        val initial = catalog(account("x")).copy(widgets = listOf(SocialWidgetBinding(7, "x")))
        val changed = SocialProfilePolicy.add(initial, account("temporary", remote = "x").copy(handle = "newname"))
        assertEquals("x", changed.accounts.single().id)
        assertEquals("newname", changed.accounts.single().handle)
        assertEquals(initial.profiles, changed.profiles)
        assertEquals(initial.widgets, changed.widgets)
    }

    @Test fun displayNameAndAvatarAreIndependentAndUnlinkRetainsWidgets() {
        var state = catalog(account("x"), account("gh", SocialPlatform.GITHUB))
        state = SocialProfilePolicy.link(state, state.profiles.first().id, setOf(state.profiles.last().id))
        val linkedId = state.profiles.single().id
        state = SocialProfilePolicy.display(state, linkedId, "x", "gh", " Our profile ")
            .copy(widgets = listOf(SocialWidgetBinding(2, "gh")))
        assertEquals("Our profile", state.profiles.single().displayName(state.accountsById))
        assertEquals("https://example.com/gh.png", state.profiles.single().avatarUrl(state.accountsById))
        val unlinked = SocialProfilePolicy.unlink(state, "gh")
        assertEquals(2, unlinked.profiles.size)
        assertEquals("gh", unlinked.widgets.single().accountId)
        assertEquals("x", unlinked.profiles.first().avatarAccountId)
        assertEquals(linkedId, unlinked.defaultProfileId)
        assertEquals(3, unlinked.profiles.first().membershipVersion)
    }

    @Test fun removeRepairsDisplaySourcesAndMarksPinnedWidgetForConfiguration() {
        var state = catalog(account("x"), account("gh", SocialPlatform.GITHUB))
        state = SocialProfilePolicy.link(state, state.profiles.first().id, setOf(state.profiles.last().id))
            .copy(widgets = listOf(SocialWidgetBinding(4, "x")))
        state = SocialProfilePolicy.remove(state, "x")
        assertEquals("gh", state.profiles.single().nameAccountId)
        assertEquals("gh", state.profiles.single().avatarAccountId)
        assertNull(state.widgets.single().accountId)
        assertFalse(state.widgets.single().followsDefault)
        state = SocialProfilePolicy.remove(state, "gh")
        assertTrue(state.accounts.isEmpty())
        assertTrue(state.profiles.isEmpty())
        assertNull(state.defaultProfileId)
    }

    @Test fun linkingTheDefaultSourceMovesDefaultToTarget() {
        val state = catalog(account("x"), account("gh", SocialPlatform.GITHUB))
        val next = SocialProfilePolicy.link(state, state.profiles.last().id, setOf(state.profiles.first().id))
        assertEquals(state.profiles.last().id, next.defaultProfileId)
    }

    @Test(expected = IllegalArgumentException::class) fun samePlatformMembersCannotBeLinked() {
        val state = catalog(account("x1"), account("x2"))
        SocialProfilePolicy.link(state, state.profiles.first().id, setOf(state.profiles.last().id))
    }

    @Test(expected = IllegalArgumentException::class) fun anotherProfilesAvatarCannotBeSelected() {
        val state = catalog(account("x"), account("gh", SocialPlatform.GITHUB))
        SocialProfilePolicy.display(state, state.profiles.first().id, "x", "gh")
    }

    @Test(expected = IllegalArgumentException::class) fun accountCannotBelongToMultipleProfiles() {
        val state = catalog(account("x"))
        state.copy(profiles = state.profiles + SocialProfile.standalone("x")).validate()
    }

    @Test fun platformValidationDoesNotUseTwitterRulesForBluesky() {
        assertTrue(SocialPlatform.BLUESKY.validPublicHandle("@very-long-custom-name.example.com"))
        assertFalse(SocialPlatform.X.validPublicHandle("very-long-custom-name.example.com"))
        assertFalse(SocialPlatform.BLUESKY.validPublicHandle("bad..bsky.social"))
        assertFalse(SocialPlatform.BLUESKY.validPublicHandle("-bad.bsky.social"))
        assertFalse(SocialPlatform.BLUESKY.validPublicHandle("user.123"))
        assertTrue(SocialPlatform.GITHUB.validPublicHandle("name-with-dashes"))
        assertFalse(SocialPlatform.GITHUB.validPublicHandle("name--dashes"))
    }

    @Test fun profileUrlsUseStableChannelAndBlueskyIdentity() {
        val youtube = account("yt", SocialPlatform.YOUTUBE, "UC123")
        val bluesky = account("bs", SocialPlatform.BLUESKY, "did:plc:123")
        assertEquals("https://www.youtube.com/channel/UC123", youtube.platform.profileUrl(youtube))
        assertEquals("https://bsky.app/profile/did%3Aplc%3A123", bluesky.platform.profileUrl(bluesky))
    }
}
