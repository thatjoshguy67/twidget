package com.tjg.twidget.brief

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fill only the bounded comparison set, reusing saved reviews without recursively reviewing their peers. */
internal object BriefReviewComparisons {
    suspend fun enrich(context: Context, subject: BriefReviewSubject): BriefReviewSubject = withContext(Dispatchers.IO) {
        val notes = collect(subject.input.history, subject.post?.url, subject.target.username) { post ->
            val target = BriefReviewTarget(subject.target.username, postUrl = post.url, fromDashboard = subject.target.fromDashboard)
            val saved = BriefPublishedReviewStore.read(context, target)
            val review = if (saved != null && BriefReviewFreshness.canReuse(saved, subject.input.language,
                    algorithmVersion = BriefAlgorithmContext.current(context).key)) saved else {
                // Concurrent viewers must never form A -> B -> A waits.
                if (BriefPublishedReviewGeneration.isRunning(context, target)) return@collect null
                val peer = BriefReviewSubjects.load(context, target) ?: return@collect null
                val attempt = BriefPublishedReviewGeneration.attempt(context, peer,
                    generate = { current -> BriefAiCoordinator.coach(context, current.input) })
                if (attempt.failure == null) attempt.review else null
            }
            review?.let { BriefReviewCopy.clean(it.result.feedback, it.subject.input,
                BriefStrings.from(context)).possibleExplanation.take(900) }
        }
        subject.copy(input = subject.input.copy(peerReviewNotes = notes))
    }

    internal suspend fun collect(posts: List<com.tjg.twidget.analytics.PostSummary>, selectedUrl: String?, username: String,
        analyse: suspend (com.tjg.twidget.analytics.PostSummary) -> String?): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        for (post in posts.filter { it.url != selectedUrl && it.authorUserName.trimStart('@').equals(username.trimStart('@'), true) }
            .distinctBy { it.url }.take(4)) {
            try { analyse(post)?.takeIf { it.isNotBlank() }?.let { notes[post.url] = it.take(900) } }
            catch (error: Exception) { if (error is CancellationException) throw error }
        }
        return notes
    }
}
