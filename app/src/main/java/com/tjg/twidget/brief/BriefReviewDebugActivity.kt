package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostObservation
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.QuotedPost
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import java.io.File
import java.security.MessageDigest

/** Production review rendering with isolated fixtures: never calls a model or writes review storage. */
class BriefReviewDebugActivity : FoldablePopOverActivity() {
    private lateinit var screen: BriefReviewScreen
    private var scenario = "quoted"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TwidgetStore.debugMenuUnlocked(this)) { finish(); return }
        scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: "quoted"
        setContentView(R.layout.activity_brief_tweet_review)
        applyEdgeToEdgeInsets(findViewById(R.id.review_root)) { bottom ->
            BriefPageChrome.navigationInset(findViewById(R.id.review_scroll), bottom)
        }
        BriefPageChrome.review(findViewById(R.id.review_root), R.string.debug_review_preview) { onBackPressedDispatcher.onBackPressed() }
        screen = BriefReviewScreen(this, BriefReviewCallbacks(
            retry = { render("complete") },
            info = { AlertDialog.Builder(this).setTitle(R.string.review_stat_info_title).setMessage(R.string.review_stat_info_body)
                .setPositiveButton(android.R.string.ok, null).show() },
            addKey = ::sampleAction, openPost = { sampleAction() },
            changeMode = ::sampleAction, limits = { body -> AlertDialog.Builder(this).setMessage(body).setPositiveButton(android.R.string.ok, null).show() }))
        val content = findViewById<LinearLayout>(R.id.review_content)
        content.addView(BriefReviewViews.text(this, getString(R.string.debug_synthetic_note), 12f, secondary = true), BriefReviewViews.params(this, 10))
        content.addView(screen.view, BriefReviewViews.params(this, 10))
        content.layoutParams = content.layoutParams.apply { width = minOf(resources.displayMetrics.widthPixels, BriefReviewViews.dp(this@BriefReviewDebugActivity, 600)) }
        val appBar = findViewById<AppBarLayout>(R.id.review_app_bar)
        appBar.setExpanded(false, false)
        findViewById<SwipeRefreshLayout>(R.id.review_refresh).apply {
            isEnabled = true
            setOnChildScrollUpCallback { _, _ -> appBar.top < 0 || findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll).canScrollVertically(-1) }
            setOnRefreshListener { isRefreshing = false; render("refreshing") }
        }
        render(scenario)
    }
    private fun render(value: String) { scenario = value; screen.render(BriefReviewDebugFixtures.state(this, value)) }
    private fun sampleAction() { Toast.makeText(this, R.string.debug_synthetic_note, Toast.LENGTH_SHORT).show() }
    override fun onDestroy() { if (::screen.isInitialized) screen.dispose(); super.onDestroy() }
    companion object {
        internal const val EXTRA_SCENARIO = "review_preview_scenario"
        internal fun intent(context: Context, scenario: String) = Intent(context, BriefReviewDebugActivity::class.java).putExtra(EXTRA_SCENARIO, scenario)
    }
}

internal object BriefReviewDebugFixtures {
    fun scenarios(context: Context): List<Pair<String, String>> = listOf(
        "complete" to R.string.l10n_preview_complete, "quoted" to R.string.l10n_preview_quote,
        "loading" to R.string.l10n_preview_loading,
        "generating" to R.string.l10n_preview_generating, "refreshing" to R.string.l10n_preview_refreshing,
        "stale" to R.string.l10n_preview_stale, "missing" to R.string.l10n_preview_missing,
    ).map { it.first to context.getString(it.second) } + BriefReviewFailure.entries.map {
        "error_${it.name}" to context.getString(R.string.l10n_preview_error, it.name)
    }


    fun state(context: Context, scenario: String, now: Long = System.currentTimeMillis()): BriefReviewState {
        val username = "sample"
        val text = "Finally shipped this. Thoughts?"
        val quote = if (scenario == "quoted") QuotedPost("https://x.com/sample/status/900", "A comparison tool for your tweets, with totals recorded at similar ages.",
            "Sample team", "sampleteam", timestamp = now - 40 * 3_600_000L) else null
        val post = PostSummary("https://x.com/sample/status/901", text, 1164, 12, 4, 5, 2, 23,
            now - 30 * 3_600_000L, "", context.getString(R.string.debug_sample_account), username, "", fullText = text,
            quotedPost = quote, media = if (quote != null) listOf(PostMedia("photo", media(context), "Sample comparison chart", 960, 540)) else emptyList())
        val peers = List(4) { index -> post.copy(url = "https://x.com/sample/status/${910 + index}", text = "Compare your tweets at similar ages.",
            fullText = "Compare your tweets at similar ages.", views = 2000L + index * 100, likes = 40) }
        val analytics = AnalyticsClient.analyticsFromPosts(username, 100, 5, false, listOf(post) + peers, cachedAt = now)
        val strings = BriefStrings.from(context)
        val observations = listOf(PostObservation(now - 12 * 3_600_000L, 500, 3, 1, 1, 0),
            PostObservation(now - 6 * 3_600_000L, 900, 8, 2, 3, 1), PostObservation(now, 1164, 12, 4, 5, 2))
        val evidence = BriefReviewEvidence.create(post, analytics, observations)
        val subject = BriefReviewSubject(BriefReviewTarget(username, postUrl = post.url),
            BriefCoachAi.review(post, analytics, requireNotNull(BriefPostCoach.review(post, analytics, strings, now)), strings, now)
                .copy(reviewEvidence = evidence, reviewCitations = evidence.citations(strings)), post = post)
        val result = BriefCoachGeneration.Success(BriefCoachFeedback(context.getString(
            if (quote != null) R.string.debug_sample_quote_explanation else R.string.debug_sample_explanation),
            listOf(BriefCoachSuggestion(context.getString(R.string.debug_sample_suggestion), context.getString(R.string.debug_sample_reason),
                text, null, false,
                subject.input.reviewCitations)), null, if (subject.post != null) evidence.panelIds.toList() else emptyList()), cloud = true)
        val failure = scenario.removePrefix("error_").takeIf { scenario.startsWith("error_") }?.let { name -> BriefReviewFailure.entries.firstOrNull { it.name == name } }
        val phase = when {
            failure != null -> BriefReviewPhase.FAILED
            scenario in setOf("generating", "refreshing") -> BriefReviewPhase.GENERATING
            scenario == "stale" -> BriefReviewPhase.STALE
            scenario == "missing" -> BriefReviewPhase.MISSING
            scenario == "loading" -> BriefReviewPhase.LOADING
            else -> BriefReviewPhase.COMPLETE
        }
        val showResult = phase == BriefReviewPhase.COMPLETE || scenario == "refreshing"
        return BriefReviewState(if (scenario == "missing") null else subject, phase, if (showResult) result else null,
            if (showResult && subject.post != null) now - 60_000L else null, failure)
    }

    private fun media(context: Context): String {
        val url = "https://example.invalid/twidget-debug-review-preview.png"
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "tweet_media_$hash.img")
        if (!file.isFile) {
            val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(12, 27, 43))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f }
                drawText("Sample · compare tweets at similar ages", 40f, 85f, paint)
                paint.color = Color.rgb(161, 213, 255)
                drawRoundRect(40f, 130f, 450f, 490f, 28f, 28f, paint)
                drawRoundRect(490f, 130f, 920f, 490f, 28f, 28f, paint)
                paint.color = Color.rgb(12, 27, 43); paint.textSize = 36f
                drawText("Views", 70f, 200f, paint); drawText("Interactions", 520f, 200f, paint)
                paint.textSize = 96f
                drawText("1,164", 70f, 360f, paint); drawText("23", 520f, 360f, paint)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        file.setLastModified(System.currentTimeMillis())
        return url
    }
}
