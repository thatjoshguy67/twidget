package com.tjg.twidget.brief

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BriefAlgorithmContextInstrumentedTest {
    private fun withCache(block: (android.content.Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("brief_algorithm_context", 0)
        val snapshot = prefs.getString("snapshot", null)
        val nextCheck = prefs.getLong("nextCheck", 0)
        try { prefs.edit().clear().commit(); block(context) } finally {
            prefs.edit().clear().putLong("nextCheck", nextCheck).apply { snapshot?.let { putString("snapshot", it) } }.commit()
        }
    }
    @Test fun dailyCacheAndFailedRefreshKeepVerifiedContext() = withCache { context ->
        val now = 1_800_000_000_000L
        var requests = 0
        val result = BriefAlgorithmContext.refresh(context, now) { url ->
            requests++
            assertEquals("https://api.github.com/repos/xai-org/x-algorithm/git/ref/heads/main", url)
            """{"object":{"type":"commit","sha":"${BriefAlgorithmContext.REVISION}"}}"""
        }
        assertEquals(BriefAlgorithmContext.bundled, result)
        assertEquals(result, BriefAlgorithmContext.refresh(context, now + 23 * 3_600_000) { error("Cached check must not fetch") })
        val failed = BriefAlgorithmContext.refresh(context, now + 24 * 3_600_000) { requests++; error("Offline") }
        assertEquals(result, failed)
        assertEquals(result, BriefAlgorithmContext.refresh(context, now + 24 * 3_600_000 + 1) { error("Failure must back off") })
        assertEquals(2, requests)
    }
    @Test fun officialSourceCanRefreshAndParseOnThePhone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("algorithmContextLive") == "true")
        androidx.test.core.app.ActivityScenario.launch(com.tjg.twidget.settings.BriefContentSettingsActivity::class.java).use {
        withCache { context ->
            val prefs = context.getSharedPreferences("brief_algorithm_context", 0)
            prefs.edit().putString("snapshot", JSONObject().put("revision", "0".repeat(40))
                .put("weights", org.json.JSONArray(BriefAlgorithmContext.bundled.weights)).toString()).commit()
            val now = System.currentTimeMillis()
            val result = BriefAlgorithmContext.refresh(context, now)
            assertNotEquals("Live source refresh must replace the fixture", "0".repeat(40), result.revision)
            assertTrue(prefs.getLong("nextCheck", 0) >= now + 86_400_000L)
            assertTrue(result.prompt().contains("NOT evidence about this tweet"))
        }
        }
    }
}
