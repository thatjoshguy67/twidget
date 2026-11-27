package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary

internal enum class BriefReviewPostType(val icon: Int, val label: Int) {
    POST(R.drawable.ic_settings_send, R.string.review_type_post),
    REPLY(R.drawable.ic_review_reply, R.string.review_reply),
    THREAD(R.drawable.ic_review_thread, R.string.review_type_thread);

    companion object {
        fun resolve(post: PostSummary, candidates: List<PostSummary> = emptyList(), savedKind: String? = null): BriefReviewPostType = when {
            savedKind == "thread" || BriefReviewConversations.cached(post, candidates).isThread -> THREAD
            post.isReply -> REPLY
            else -> POST
        }
    }
}
