package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The overflow library is separate from the ranked Brief; opening it never generates AI feedback. */
class BriefReviewListActivity : FoldablePopOverActivity() {
    private var load: Job? = null
    private var savedReviewLimit = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedReviewLimit = savedInstanceState?.getInt("savedReviewLimit", 50) ?: 50
        setContentView(R.layout.activity_brief_tweet_review)
        val root = findViewById<View>(R.id.review_root)
        applyEdgeToEdgeInsets(root) { bottom -> BriefPageChrome.navigationInset(findViewById(R.id.review_scroll), bottom) }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) configureWidth()
        }
        BriefPageChrome.review(root, R.string.review_list_title) { onBackPressedDispatcher.onBackPressed() }
        configureWidth()
    }

    override fun onResume() {
        super.onResume()
        loadCards()
    }

    private fun loadCards() {
        load?.cancel()
        load = lifecycleScope.launch {
            val username = TwidgetStore.canonicalAccount(this@BriefReviewListActivity, intent.getStringExtra(EXTRA_USERNAME).orEmpty())
            if (!BriefSettingsStore.contentEnabled(this@BriefReviewListActivity, BriefContentCategory.TWEET_REVIEWS) ||
                TwidgetStore.accounts(this@BriefReviewListActivity).none { it.equals(username, true) }) { finish(); return@launch }
            val excludedPosts = intent.getStringArrayListExtra(EXTRA_POSTS).orEmpty().toSet()
            val (savedPosts, liveCards) = withContext(Dispatchers.IO) {
                BriefPublishedReviewStore.posts(this@BriefReviewListActivity, username, savedReviewLimit + 1) to (
                BriefPostCoach.reviewablePosts(AnalyticsClient.cached(this@BriefReviewListActivity, username), username,
                    System.currentTimeMillis()))
            }
            val excludedIds = excludedPosts.mapNotNull(BriefPublishedReviewStore::postId).toSet()
            val posts = (liveCards + savedPosts.take(savedReviewLimit))
                .filterNot { it.url in excludedPosts || BriefPublishedReviewStore.postId(it.url) in excludedIds }
                .distinctBy { BriefPublishedReviewStore.postId(it.url) ?: it.url }
            val content = findViewById<LinearLayout>(R.id.review_content)
            content.removeAllViews()
            fun open(target: BriefReviewTarget) = startRightSidePopOverActivity(BriefTweetReviewActivity.intent(this@BriefReviewListActivity, target))
            posts.forEach { post ->
                val target = BriefReviewTarget(username, postUrl = post.url)
                content.addView(BriefReviewTweetCard.create(this@BriefReviewListActivity, username, post = post, compact = true,
                    actions = listOf(BriefReviewAction(R.string.review_view) { open(target) })),
                    BriefReviewViews.params(this@BriefReviewListActivity, 10))
            }

            if (posts.isEmpty()) content.addView(BriefReviewViews.card(this@BriefReviewListActivity).apply {
                addView(BriefReviewViews.text(this@BriefReviewListActivity, getString(R.string.review_list_empty)))
            })
            if (savedPosts.size > savedReviewLimit) content.addView(BriefReviewViews.button(this@BriefReviewListActivity,
                R.string.review_more_saved, false) { savedReviewLimit += 50; loadCards() }, BriefReviewViews.params(this@BriefReviewListActivity, 14))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("savedReviewLimit", savedReviewLimit)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureWidth()
    }

    private fun configureWidth() {
        val root = findViewById<View>(R.id.review_root)
        val available = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val width = minOf((available - root.paddingLeft - root.paddingRight).coerceAtLeast(1), BriefReviewViews.dp(this, 600))
        findViewById<View>(R.id.review_content).apply { layoutParams = layoutParams.apply { this.width = width } }
    }

    companion object {
        private const val EXTRA_USERNAME = "review_list_username"
        private const val EXTRA_POSTS = "review_list_excluded_posts"
        private const val EXTRA_DRAFTS = "review_list_excluded_drafts"
        internal fun intent(context: Context, username: String, excludedPosts: Set<String> = emptySet(), excludedDrafts: Set<String> = emptySet()) =
            Intent(context, BriefReviewListActivity::class.java).putExtra(EXTRA_USERNAME, username)
                .putStringArrayListExtra(EXTRA_POSTS, ArrayList(excludedPosts))
                .putStringArrayListExtra(EXTRA_DRAFTS, ArrayList(excludedDrafts))
    }
}
