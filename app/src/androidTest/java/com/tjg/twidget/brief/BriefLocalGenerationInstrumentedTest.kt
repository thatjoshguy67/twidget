package com.tjg.twidget.brief

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefLocalGenerationInstrumentedTest {
    @Test fun liveBriefCompletesUsingNanoWithoutCloud() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("briefLocalLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val real = args.getString("briefLocalSource") == "real"
        val enabled = BriefSettingsStore.enabled(context)
        BriefSettingsStore.setEnabled(context, false) // Isolate the live request from foreground preparation.
        val output = File(context.getExternalFilesDir(null), "brief-local-smoke.txt")
        output.writeText("source=${if (real) "current Brief (metadata only)" else "synthetic"}\n")
        try {
            val host = Intent().setClassName(context.packageName, "com.tjg.twidget.brief.BriefCoachTestActivity")
            ActivityScenario.launch<android.app.Activity>(host).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                instrumentation.waitForIdleSync()
                val focusDeadline = System.currentTimeMillis() + 5_000
                var focused = false
                do {
                    scenario.onActivity { focused = it.hasWindowFocus() }
                    if (!focused) kotlinx.coroutines.delay(50)
                } while (!focused && System.currentTimeMillis() < focusDeadline)
                assertTrue("Nano requires the test host to have foreground access", focused)
                val result = withContext(Dispatchers.IO) {
                    val strings = BriefStrings.from(context)
                    val source = if (real) BriefEngine.rebuild(context, TwidgetStore.settings(context).username, force = true) else fixture(strings)
                    output.appendText("cards=${source.cards.map { it.type }}\n")
                    if (real) {
                        assertEquals("The main Brief must remain local", BriefProviderMode.LOCAL, BriefSettingsStore.provider(context))
                        BriefAiCoordinator.enrich(context, source, force = true).snapshot
                    } else GeminiNanoBriefProvider.generate(context, source, strings) { attempt ->
                        // Real account prompts and generated text are never written to logs or diagnostic files.
                        output.appendText("${attempt.stage}: attempt=${attempt.attempt}, chars=${attempt.candidate?.length ?: 0}, accepted=${attempt.rejection == null}\n")
                    }
                }
                output.appendText("complete=${result?.hasGeneratedNarrative == true}; provider=${result?.providerUsed}; generatedCards=${result?.cards?.count { it.narrativeGenerated }}\n")
                assertNotNull("Nano must produce a complete Brief; see safe stage diagnostics", result)
                assertTrue(result!!.hasGeneratedNarrative)
                assertEquals(BriefProviderUsed.LOCAL, result.providerUsed)
            }
        } finally { BriefSettingsStore.setEnabled(context, enabled) }
    }

    private fun fixture(strings: BriefStrings) = BriefSnapshot("nano_fixture", System.currentTimeMillis(), 1, 1, 0,
        1200, 100, 200, 2, -10,
        listOf(
            BriefCard("growth", BriefCardType.FOLLOWER_CONTEXT, "Your follower update", "You gained 2 followers today, but lost 10 this week.", 90,
                metrics = listOf(BriefMetric("Today", "+2"), BriefMetric("This week", "-10"))),
            BriefCard("activity", BriefCardType.POST_CONTEXT, "Your recent posts", "Your 8 recent posts received 1,600 views and 24 public interactions.", 80,
                metrics = listOf(BriefMetric("Views", "1,600"), BriefMetric("Public interactions", "24"))),
            BriefCard("streak", BriefCardType.STREAK, "Your posting rhythm", "Your posting streak is 3 days.", 70),
        ), topFollowerRanks = emptyMap(), language = strings.languageTag)
}
