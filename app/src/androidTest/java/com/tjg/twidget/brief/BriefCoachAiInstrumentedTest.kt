package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefCoachAiInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun input(media: List<BriefCoachMediaRef>) = BriefCoachInput("draft", "Finally shipped this. Thoughts?", true,
        media.size, BriefCoachReport(listOf("The draft has one thread entry and one image."),
            emptyList(), emptyList(), listOf("There is no measured performance for this draft.")),
        emptyList(), null, "en-US", media)

    @Test fun mediaLoadingPreservesARealImageAndMarksMissingAttachmentsUnseen() = runBlocking {
        val file = fixture()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_files", file)
        val subject = input(listOf(BriefCoachMediaRef(uri.toString(), true, false, 1),
            BriefCoachMediaRef("content://invalid/missing", true, false, 2)))
        try {
            val images = withContext(Dispatchers.IO) { BriefCoachMedia.load(context, subject) }
            try {
                assertEquals(listOf("m0"), images.map { it.id })
                assertTrue(images.single().bitmap.width <= 1024)
                val packet = subject.packet(attachedMediaIds = images.map { it.id }.toSet())
                assertTrue(packet.data.getJSONArray("media").getJSONObject(0).getBoolean("imageAttached"))
                assertFalse(packet.data.getJSONArray("media").getJSONObject(1).getBoolean("imageAttached"))
            } finally { images.forEach { it.bitmap.recycle() } }
        } finally { file.delete() }
    }

    /** Explicit manual smoke check, never spends cloud credits or runs in normal CI. */
    @Test fun liveNanoUsesImageContextForAnOtherwiseVagueDraft() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coachLive") == "true")
        val host = Intent().setClassName(context.packageName, "com.tjg.twidget.brief.BriefCoachTestActivity")
        // Shared instrumented sources also compile for audit builds, which do not include debug fixtures.
        assumeTrue(host.resolveActivity(context.packageManager) != null)
        val file = fixture()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_files", file)
        try {
            // AICore rejects inference from a background app; reproduce the actual foreground dialog flow.
            ActivityScenario.launch<android.app.Activity>(host).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val responses = mutableListOf<String>()
                val result = withContext(Dispatchers.IO) {
                    BriefCoachAiRunner.generate(context, input(listOf(BriefCoachMediaRef(uri.toString(), true, false, 1))),
                        cloud = false, allowCloudFallback = false, onResponse = { responses += it })
                }
                val output = File(context.getExternalFilesDir(null), "coach-ai-smoke.txt")
                output.writeText(result.toString() + "\n\n" + responses.joinToString("\n\n")) // Synthetic fixture only.
                assertTrue("Expected real on-device feedback; synthetic result saved for inspection", result is BriefCoachGeneration.Success)
                result as BriefCoachGeneration.Success
                assertFalse(result.cloud)
                assertEquals(setOf("m0"), result.inspectedMediaIds)
                assertTrue(result.feedback.suggestions.isNotEmpty())
                val advice = result.feedback.toString().lowercase()
                assertTrue("Advice should use the product or feature context visible only in the image",
                    "twidget" in advice || ("compar" in advice && "age" in advice))
            }
        } finally { file.delete() }
    }

    /** Opt-in check using synthetic content only; preserves the app's language, AI mode and key. */
    @Test fun liveCloudKeepsAnEnglishRewriteWithFrenchReviewCommentsAndQuotedContext() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coachCloudLanguageLive") == "true")
        assumeTrue(BriefSettingsStore.cloudApiKey(context).isNotBlank())
        val subject = input(emptyList()).copy(language = "fr",
            report = BriefCoachReport(emptyList(), emptyList(), emptyList(), listOf("Ce brouillon n'a pas encore de résultats mesurés.")),
            quotedPost = com.tjg.twidget.analytics.QuotedPost(
            "https://x.com/source/status/123", "Twidget permet de comparer les performances de tweets observés au même âge.",
            "Quoted source", "source"))
        val result = withContext(Dispatchers.IO) { BriefCoachAiRunner.generate(context, subject, cloud = true) }
        File(context.getExternalFilesDir(null), "coach-language-smoke.txt").writeText(result.toString()) // Synthetic fixture only.
        assertTrue("Expected cloud feedback; synthetic result saved for inspection", result is BriefCoachGeneration.Success)
        result as BriefCoachGeneration.Success
        assertTrue(result.cloud)
        assertTrue(result.feedback.suggestions.all { it.rewrite == null })
    }

    /** Opt-in regression for the reported post. Uses existing Auto/Cloud settings, never changes keys or mode. */
    @Test fun liveCloudReviewsTheReportedExcerptWithItsImages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coachCloudExcerptLive") == "true")
        assumeTrue(BriefSettingsStore.provider(context) != BriefProviderMode.LOCAL && BriefSettingsStore.cloudApiKey(context).isNotBlank())
        val account = com.tjg.twidget.data.TwidgetStore.accounts(context).first()
        val analytics = requireNotNull(com.tjg.twidget.analytics.AnalyticsClient.cached(context, account))
        val post = (analytics.recentPosts + listOfNotNull(analytics.best, analytics.worst)).first {
            it.text.startsWith("Pixel Tablet > Apple HomeView")
        }
        val target = BriefReviewTarget(account, postUrl = post.url, fromDashboard = true)
        val subject = requireNotNull(BriefReviewSubjects.load(context, target))
        assertFalse("Reproduce the cache's missing full-text flag", subject.input.complete)
        val before = BriefPublishedReviewStore.read(context, target)
        val result = withContext(Dispatchers.IO) { BriefCoachAiRunner.generate(context, subject.input, cloud = true) }
        assertTrue("The real excerpt review must produce usable feedback: ${(result as? BriefCoachGeneration.Failure)?.reason}", result is BriefCoachGeneration.Success)
        result as BriefCoachGeneration.Success
        assertTrue(result.feedback.suggestions.isNotEmpty())
        assertTrue(result.feedback.suggestions.all { it.rewrite == null })
        assertEquals("Both comparison images reach the model", setOf("m0", "m1"), result.inspectedMediaIds)
        // Keep this requested review available for the user, following the same CAS rules as the page.
        assertNotNull(BriefPublishedReviewStore.save(context, subject, result,
            com.tjg.twidget.data.TwidgetStore.accountGeneration(context, account), before?.savedAt))
    }

    private fun fixture(): File {
        val bitmap = Bitmap.createBitmap(1400, 700, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f }
            drawText("Twidget: compare tweets at similar ages", 30f, 150f, paint)
            drawText("See whether low views or response differs", 30f, 250f, paint)
            drawText("For creators reviewing their own posts", 30f, 350f, paint)
        }
        val directory = File(context.filesDir, "scheduled-media").apply { mkdirs() }
        val file = File.createTempFile("coach-ai-fixture-", ".png", directory)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
