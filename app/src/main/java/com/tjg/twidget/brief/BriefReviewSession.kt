package com.tjg.twidget.brief

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.banger.BangerClient
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.schedule.ScheduledPost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal data class BriefReviewTarget(val username: String, val draftId: String? = null, val postUrl: String? = null,
    val fromDashboard: Boolean = false)
internal data class BriefReviewSubject(val target: BriefReviewTarget, val input: BriefCoachInput,
    val draft: ScheduledPost? = null, val post: PostSummary? = null) {
    fun isCurrent(context: Context): Boolean = if (draft != null) ScheduleStore(context).get(draft.id) == draft
        else true
}
internal enum class BriefReviewPhase { LOADING, GENERATING, COMPLETE, FAILED, STALE, MISSING }
internal data class BriefReviewState(val subject: BriefReviewSubject? = null,
    val phase: BriefReviewPhase = BriefReviewPhase.LOADING, val result: BriefCoachGeneration.Success? = null,
    val savedAt: Long? = null, val failure: BriefReviewFailure? = null)

internal object BriefReviewSubjects {
    /** Only a missing/stale cache merits a recovery fetch; deleted accounts and disabled content do not. */
    fun needsAnalyticsRefresh(context: Context, target: BriefReviewTarget): Boolean {
        val account = TwidgetStore.canonicalAccount(context, target.username)
        if (target.draftId != null || target.postUrl?.let(BriefPublishedReviewStore::postId) == null ||
            (!target.fromDashboard && !BriefSettingsStore.contentEnabled(context, BriefContentCategory.TWEET_REVIEWS)) ||
            TwidgetStore.accounts(context).none { it.equals(account, true) }) return false
        val cached = AnalyticsClient.cached(context, account)
        val now = System.currentTimeMillis()
        return cached == null || !cached.userName.equals(account, true) || cached.cachedAt !in 1..now ||
            now - cached.cachedAt > 2 * 86_400_000L
    }

    internal suspend fun recoverMissing(target: BriefReviewTarget, load: () -> BriefReviewSubject?,
        needsRefresh: () -> Boolean, refresh: () -> Unit): BriefReviewSubject? {
        load()?.let { return it }
        if (target.draftId != null || !needsRefresh()) return null
        try { refresh() } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw BriefReviewException(BriefReviewFailure.ANALYTICS)
        }
        return load()
    }
    /** Rebuild native evidence copy too when the analytics cache has already gone away. */
    fun fromSaved(saved: BriefSavedReview, strings: BriefStrings): BriefReviewSubject {
        if (BriefReviewFreshness.matchesLanguage(saved, strings.languageTag)) return saved.subject
        val subject = saved.subject
        val post = requireNotNull(subject.post)
        val evidence = requireNotNull(subject.input.reviewEvidence)
        val observedAt = evidence.observedAt.takeIf { it > 0L } ?: saved.savedAt
        val posts = (listOf(post) + evidence.peers + subject.input.history).distinctBy(PostSummary::url)
        val analytics = AnalyticsClient.analyticsFromPosts(post.authorUserName, 0, posts.size, false,
            posts.filterNot(PostSummary::isReply), bestCandidates = posts, cachedAt = observedAt).copy(banger = post)
        val report = requireNotNull(BriefPostCoach.review(post, analytics, strings, observedAt))
        val input = BriefCoachAi.review(post, analytics, report, strings, observedAt).copy(
            history = subject.input.history, observedAt = evidence.observedAt, reviewEvidence = evidence, reviewCitations = evidence.citations(strings))
        return subject.copy(input = input)
    }

    fun load(context: Context, target: BriefReviewTarget): BriefReviewSubject? {
        val account = TwidgetStore.canonicalAccount(context, target.username)
        if ((!target.fromDashboard && !BriefSettingsStore.contentEnabled(context, BriefContentCategory.TWEET_REVIEWS)) ||
            TwidgetStore.accounts(context).none { it.equals(account, true) } ||
            target.draftId != null || target.postUrl == null) return null
        val strings = BriefStrings.from(context)
        val analytics = AnalyticsClient.cached(context, account)?.let { canonicalAuthors(context, it, account, target) }
        val now = System.currentTimeMillis()

        analytics ?: return null
        val post = BriefPostCoach.reviewTargets(analytics, account, now).firstOrNull { BriefPublishedReviewStore.postId(it.url) == BriefPublishedReviewStore.postId(target.postUrl.orEmpty()) } ?: return null
        val report = BriefPostCoach.review(post, analytics, strings, now) ?: return null
        val evidence = BriefReviewEvidence.create(post, analytics, AnalyticsClient.observations(context, account, post.url))
        val input = BriefCoachAi.review(post, analytics, report, strings, now)
            .copy(observedAt = evidence.observedAt, reviewEvidence = evidence, reviewCitations = evidence.citations(strings))
        return BriefReviewSubject(target.copy(username = account), input, post = post)
    }

    /** A verified handle migration changes account identity, not the original tweet URL or quoted author. */
    private fun canonicalAuthors(context: Context, analytics: PostAnalytics, account: String, target: BriefReviewTarget): PostAnalytics {
        // Legacy handle changes moved the account-scoped winner without recording a redirect.
        // Trust only that retained winner, selected explicitly from this account's dashboard.
        // Never use a URL's author as proof of ownership for arbitrary recent/quoted tweets.
        val winner = if (target.fromDashboard && analytics.userName.equals(account, true))
            BangerClient.cached(context, account)?.post?.takeIf {
                val id = BriefPublishedReviewStore.postId(it.url)
                id != null && id == BriefPublishedReviewStore.postId(target.postUrl.orEmpty()) &&
                    id == BriefPublishedReviewStore.postId(analytics.banger?.url.orEmpty())
            } else null
        fun own(post: PostSummary): PostSummary = if (TwidgetStore.canonicalAccount(context, post.authorUserName).equals(account, true) ||
            (winner != null && post == analytics.banger && post.authorUserName.equals(winner.authorUserName, true)))
            post.copy(authorUserName = account) else post
        return analytics.copy(best = analytics.best?.let(::own), worst = analytics.worst?.let(::own),
            banger = analytics.banger?.let(::own), recentPosts = analytics.recentPosts.map(::own),
            recentReplies = analytics.recentReplies.map(::own))
    }
}

/** Small memory front cache for draft revisions; durable advice lives in the review database. */
internal object BriefReviewResults {
    private data class Entry(val result: BriefCoachGeneration.Success, val at: Long)
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    internal fun key(subject: BriefReviewSubject, mode: BriefProviderMode, nano: BriefNanoModelMode): String {
        val content = listOf(subject.target, subject.draft?.updatedAt, mode, nano, subject.input.language,
            subject.input.packet().data.toString(), subject.input.media).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun get(key: String, now: Long = System.currentTimeMillis()): BriefCoachGeneration.Success? {
        entries.entries.removeAll { now < it.value.at || now - it.value.at >= 60 * 60_000L }
        return entries[key]?.result
    }
    @Synchronized fun put(key: String, result: BriefCoachGeneration.Success, now: Long = System.currentTimeMillis()) {
        entries[key] = Entry(result, now)
        while (entries.size > 8) entries.remove(entries.keys.first())
    }
}

internal class BriefReviewSession(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(BriefReviewState())
    val state = mutableState.asStateFlow()
    private var generation: Job? = null
    private var activePolicy = ""
    private var lastAttemptAt = 0L
    private var debugTarget: BriefReviewTarget? = null

    init {
        viewModelScope.launch {
            state.collect { value -> (value.subject?.target ?: debugTarget)?.let { BriefReviewDebugLog.state(getApplication(), it, value) } }
        }
    }

    fun open(target: BriefReviewTarget, force: Boolean = false) {
        debugTarget = target
        val context = getApplication<Application>()
        val strings = BriefStrings.from(context)
        val language = strings.languageTag
        val policy = listOf(target, BriefSettingsStore.provider(context),
            language, BriefSettingsStore.cloudApiKey(context).isNotBlank(),
            BriefSettingsStore.contentEnabled(context, BriefContentCategory.TWEET_REVIEWS)).joinToString("|")
        // Pulling again cannot interrupt or duplicate an active cloud request.
        if (generation?.isActive == true && policy == activePolicy) return
        val now = System.currentTimeMillis()
        if (!force && policy == activePolicy && mutableState.value.phase == BriefReviewPhase.FAILED &&
            now >= lastAttemptAt && now - lastAttemptAt < 15 * 60_000L) return
        activePolicy = policy
        generation?.cancel()
        // A retained ViewModel must not render its previous-language advice on recreation.
        if (mutableState.value.subject?.input?.language?.equals(language, ignoreCase = true) == false)
            mutableState.value = BriefReviewState()
        generation = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    if (target.fromDashboard || BriefSettingsStore.contentEnabled(context, BriefContentCategory.TWEET_REVIEWS))
                        BriefPublishedReviewStore.read(context, target) else null
                }
                if (saved != null && !force && BriefReviewFreshness.canReuse(saved, language)) {
                    mutableState.value = BriefReviewState(saved.subject, BriefReviewPhase.COMPLETE, saved.result, saved.savedAt)
                    return@launch
                }
                lastAttemptAt = System.currentTimeMillis()
                val subject = withContext(Dispatchers.IO) {
                    if (saved != null) BriefReviewSubjects.load(context, target) ?: BriefReviewSubjects.fromSaved(saved, strings)
                    else BriefReviewSubjects.recoverMissing(target,
                        load = { BriefReviewSubjects.load(context, target) },
                        needsRefresh = { BriefReviewSubjects.needsAnalyticsRefresh(context, target) },
                        refresh = { AnalyticsClient.refresh(context, target.username) })
                }
                if (subject == null) { mutableState.value = BriefReviewState(phase = BriefReviewPhase.MISSING); return@launch }
                val matching = saved?.takeIf { BriefReviewFreshness.matchesLanguage(it, language) }
                val key = BriefReviewResults.key(subject, BriefProviderMode.CLOUD, BriefNanoModelMode.AUTO_STABLE)
                val draftSaved = if (subject.draft == null) null else withContext(Dispatchers.IO) {
                    BriefPublishedReviewStore.readDraft(context, subject, key)
                }
                val cached = draftSaved?.result ?: BriefReviewResults.get(key)
                val retained = BriefReviewState(matching?.subject ?: subject, BriefReviewPhase.GENERATING,
                    matching?.result ?: cached?.takeIf { subject.draft != null }, matching?.savedAt ?: draftSaved?.savedAt)
                if (!force && subject.draft != null && cached != null) {
                    mutableState.value = BriefReviewState(subject, BriefReviewPhase.COMPLETE, cached, draftSaved?.savedAt)
                    return@launch
                }
                lastAttemptAt = System.currentTimeMillis()
                val blocked = BriefReviewPolicy.blocked(BriefSettingsStore.provider(context), BriefSettingsStore.cloudApiKey(context).isNotBlank())
                if (blocked != null) {
                    mutableState.value = retained.copy(phase = BriefReviewPhase.FAILED, failure = blocked)
                    return@launch
                }
                mutableState.value = retained
                if (subject.post != null) {
                    val attempt = BriefPublishedReviewGeneration.attempt(context, subject, force,
                        prepare = {
                            val analytics = AnalyticsClient.cached(context, target.username)
                            if (force || saved != null || analytics == null || System.currentTimeMillis() - analytics.cachedAt >= 15 * 60_000L) {
                                try { AnalyticsClient.refresh(context, target.username) }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    throw BriefReviewException(BriefReviewFailure.ANALYTICS)
                                }
                            }
                            BriefReviewSubjects.load(context, target) ?: saved?.let { BriefReviewSubjects.fromSaved(it, strings) }
                                ?: throw BriefReviewException(BriefReviewFailure.POST_UNAVAILABLE)
                        }, generate = { current -> BriefAiCoordinator.coach(context, current.input) })
                    val review = attempt.review?.takeIf { BriefReviewFreshness.matchesLanguage(it, language) }
                    mutableState.value = BriefReviewState(review?.subject ?: subject,
                        if (attempt.failure == null && review != null) BriefReviewPhase.COMPLETE else BriefReviewPhase.FAILED,
                        review?.result, review?.savedAt, attempt.failure)
                    return@launch
                }
                val accountGeneration = TwidgetStore.accountGeneration(context, subject.target.username)
                val result = BriefAiCoordinator.coach(context, subject.input)
                if (!withContext(Dispatchers.IO) { subject.isCurrent(context) }) {
                    mutableState.value = BriefReviewState(subject, BriefReviewPhase.STALE)
                } else if (result is BriefCoachGeneration.Success) {
                    val stored = withContext(Dispatchers.IO) {
                        BriefPublishedReviewStore.saveDraft(context, subject, key, result, accountGeneration)
                    }
                    if (stored == null) {
                        mutableState.value = BriefReviewState(phase = BriefReviewPhase.MISSING)
                        return@launch
                    }
                    BriefReviewResults.put(key, result)
                    mutableState.value = BriefReviewState(subject, BriefReviewPhase.COMPLETE, result, stored?.savedAt)
                } else mutableState.value = retained.copy(phase = BriefReviewPhase.FAILED,
                    failure = (result as? BriefCoachGeneration.Failure)?.reason ?: BriefReviewFailure.UNKNOWN)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(phase = BriefReviewPhase.FAILED, failure = reviewFailure(error))
            }
        }
    }
}
