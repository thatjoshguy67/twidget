package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BriefAiCachePolicyTest {
    private val now = 20_000_000L

    @Test
    fun `local response is retained across engine refreshes for four hours`() {
        val cached = snapshot(
            generatedAt = now - 3 * 60 * 60 * 1000L,
            provider = BriefProviderUsed.LOCAL,
            cards = listOf(
                card("streak", "Keep it rolling", "You have posted for 4 days.", 80),
                card("growth", "You are flying", "You gained 25 followers today.", 90),
            ),
        )
        val refreshed = snapshot(
            generatedAt = now,
            provider = BriefProviderUsed.TEMPLATE,
            cards = listOf(
                card("growth", "Momentum is building", "You gained 25 followers today.", 96),
                card("streak", "Four day streak", "You have posted for 4 days.", 70),
            ),
        )

        val result = BriefAiCachePolicy.retain(cached, refreshed, now)

        assertEquals(BriefProviderUsed.LOCAL, result.providerUsed)
        assertEquals(listOf("growth", "streak"), result.cards.map(BriefCard::id))
        assertEquals("You are flying", result.cards[0].title)
        assertEquals(96, result.cards[0].score)
        assertEquals(now - 3 * 60 * 60 * 1000L, result.aiGeneratedAt)
    }

    @Test
    fun `fresh facts replace cached wording without triggering a whole response refresh`() {
        val cached = snapshot(
            generatedAt = now - 60_000L,
            provider = BriefProviderUsed.LOCAL,
            cards = listOf(
                card("growth", "A strong day", "You gained 25 followers today.", 90),
                card("streak", "Keep it rolling", "You have posted for 4 days.", 80),
            ),
        )
        val refreshed = snapshot(
            generatedAt = now,
            provider = BriefProviderUsed.TEMPLATE,
            cards = listOf(
                card("growth", "Momentum is building", "You gained 26 followers today.", 95),
                card("streak", "Four day streak", "You have posted for 4 days.", 75),
            ),
        )

        val result = BriefAiCachePolicy.retain(cached, refreshed, now)

        assertEquals(BriefProviderUsed.LOCAL, result.providerUsed)
        assertEquals("Momentum is building", result.cards[0].title)
        assertEquals("You gained 26 followers today.", result.cards[0].body)
        assertEquals("Keep it rolling", result.cards[1].title)
    }

    @Test
    fun `expired local response is discarded`() {
        val cached = snapshot(
            generatedAt = now - BriefAiCachePolicy.LOCAL_TTL_MS,
            provider = BriefProviderUsed.LOCAL,
            cards = listOf(card("growth", "Cached", "You gained 25 followers today.", 90)),
        )
        val refreshed = snapshot(
            generatedAt = now,
            provider = BriefProviderUsed.TEMPLATE,
            cards = listOf(card("growth", "Fresh", "You gained 25 followers today.", 90)),
        )

        assertSame(refreshed, BriefAiCachePolicy.retain(cached, refreshed, now))
    }

    @Test
    fun `engine changes discard cached wording`() {
        val cached = snapshot(
            generatedAt = now - 60_000L,
            provider = BriefProviderUsed.LOCAL,
            cards = listOf(card("goal", "Slipping off", "Keep working for your 8,000 follower goal.", 90)),
            engineVersion = 7,
        )
        val refreshed = snapshot(
            generatedAt = now,
            provider = BriefProviderUsed.TEMPLATE,
            cards = listOf(card("goal", "Almost there", "Your 8,000 follower goal is within reach.", 90)),
            engineVersion = 8,
        )

        assertSame(refreshed, BriefAiCachePolicy.retain(cached, refreshed, now))
    }

    private fun snapshot(
        generatedAt: Long,
        provider: BriefProviderUsed,
        cards: List<BriefCard>,
        engineVersion: Int = 0,
    ) = BriefSnapshot(
        username = "tester",
        generatedAt = generatedAt,
        sourceSyncedAt = generatedAt,
        analyticsCachedAt = 0L,
        followerScanCompletedAt = 0L,
        followers = 1_000L,
        following = 100L,
        posts = 200L,
        followersToday = 25L,
        followersWeek = 50L,
        cards = cards,
        topFollowerRanks = emptyMap(),
        providerUsed = provider,
        providerMessage = if (provider == BriefProviderUsed.LOCAL) "Gemini Nano" else "Template",
        aiGeneratedAt = if (provider == BriefProviderUsed.TEMPLATE) 0L else generatedAt,
        engineVersion = engineVersion,
    )

    private fun card(id: String, title: String, body: String, score: Int) = BriefCard(
        id = id,
        type = if (id == "streak") BriefCardType.STREAK else BriefCardType.GROWTH,
        title = title,
        body = body,
        score = score,
    )
}
