package com.tjg.twidget.brief

import android.app.Activity
import android.view.View
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.schedule.ScheduledPost
import com.tjg.twidget.ui.startRightSidePopOverActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Review entry points are ordinary tweet cards. Inference belongs to the review page. */
internal object BriefPostCoachSection {
    data class Items(val previews: List<View>, val libraryCard: View, val refreshDescriptions: () -> Unit)

    fun create(activity: Activity, username: String, analytics: PostAnalytics?,
        excludedPostUrls: Set<String> = emptySet(),
        openReview: (BriefReviewTarget) -> Unit = { activity.startRightSidePopOverActivity(BriefTweetReviewActivity.intent(activity, it)) },
        editDraft: (ScheduledPost) -> Unit): Items? {
        if (!BriefSettingsStore.contentEnabled(activity, BriefContentCategory.TWEET_REVIEWS)) return null
        val drafts = BriefPostCoach.readyDrafts(ScheduleStore(activity).listForAccount(username), username)
        val allPosts = BriefPostCoach.reviewablePosts(analytics, username, System.currentTimeMillis())
        val posts = allPosts.filterNot { it.url in excludedPostUrls }
        val savedReviews = BriefPublishedReviewStore.hasReviews(activity, username)
        if (drafts.isEmpty() && allPosts.isEmpty() && !savedReviews) return null
        val strings = BriefStrings.from(activity)
        val descriptions = mutableListOf<Pair<PostSummary, TextView>>()
        fun preview(label: CharSequence, card: View) = BriefReviewViews.column(activity).apply {
            addView(BriefReviewViews.label(activity, label).apply {
                // Each preview receives the Brief's normal section margin, including the first.
                setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            })
            addView(card, BriefReviewViews.params(activity, 10))
        }
        val visible = posts.filterNot(PostSummary::isReply).take(2) + posts.filter(PostSummary::isReply).take(1)
        val previews = visible.map { post ->
            val target = BriefReviewTarget(username, postUrl = post.url)
            preview(BriefReviewPreviewCopy.description(post, null, strings), BriefReviewTweetCard.create(activity, username, post = post, compact = true,
                actions = listOf(BriefReviewAction(R.string.review_view) { openReview(target) }), stackCompactActions = true)).also { section ->
                descriptions += post to (section.getChildAt(0) as TextView)
            }
        } + drafts.take(2).map { draft ->
            val target = BriefReviewTarget(username, draftId = draft.id)
            preview(activity.getString(R.string.review_intro_draft), BriefReviewTweetCard.create(activity, username, draft = draft, compact = true,
                actions = listOf(BriefReviewAction(R.string.coach_edit_draft, false) { editDraft(draft) },
                    BriefReviewAction(R.string.review_view) { openReview(target) }), stackCompactActions = true,
                onClick = { openReview(target) }))
        }
        val libraryCard = BriefReviewLibraryCard.create(activity) {
            activity.startRightSidePopOverActivity(BriefReviewListActivity.intent(activity, username))
        }
        var descriptionLoad: Job? = null
        val refreshDescriptions: () -> Unit = {
            descriptionLoad?.cancel()
            descriptionLoad = (activity as? LifecycleOwner)?.lifecycleScope?.launch {
                val copy = withContext(Dispatchers.IO) {
                    descriptions.map { (post, _) -> BriefReviewPreviewCopy.description(post,
                        BriefPublishedReviewStore.read(activity, BriefReviewTarget(username, postUrl = post.url)), strings) }
                }
                descriptions.zip(copy).forEach { (binding, text) -> binding.second.text = text }
            }
        }
        refreshDescriptions()
        return Items(previews, libraryCard, refreshDescriptions)
    }
}
