package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONArray
import org.json.JSONObject

/** Debug reports never include credentials. Only an explicit evidence inspection reads source text. */
internal object BriefReviewDebug {
    data class Choice(val label: String, val target: BriefReviewTarget)

    fun choices(context: Context, username: String): List<Choice> {
        val analytics = AnalyticsClient.cached(context, username)
        val posts = (BriefPostCoach.reviewTargets(analytics, username, System.currentTimeMillis()) + listOfNotNull(analytics?.banger) +
            BriefPublishedReviewStore.posts(context, username, 100)).distinctBy { BriefPublishedReviewStore.postId(it.url) }
        return posts.map { Choice(context.getString(if (it.isReply) R.string.coach_reply_choice else R.string.coach_post_choice, it.text.replace('\n', ' ').take(80)),
            BriefReviewTarget(username, postUrl = it.url, fromDashboard = true)) }
    }

    fun subject(context: Context, target: BriefReviewTarget): BriefReviewSubject? =
        BriefReviewSubjects.load(context, target) ?: BriefPublishedReviewStore.read(context, target)?.let {
            BriefReviewSubjects.fromSaved(it, BriefStrings.from(context))
        }

    fun evidence(context: Context, target: BriefReviewTarget): String {
        val subject = subject(context, target) ?: return context.getString(R.string.l10n_debug_source_unavailable)
        val input = subject.input
        return buildString {
            appendLine(context.getString(R.string.l10n_debug_kind_language, input.kind, input.language))
            appendLine(context.getString(R.string.l10n_debug_source_complete, yes(context, input.complete), yes(context, input.packet().textComplete)))
            appendLine(context.getString(R.string.l10n_debug_history_media, AppLocales.integer(input.history.size.toLong()), AppLocales.integer(input.media.size.toLong())))
            appendLine(context.getString(R.string.l10n_debug_quote_observations, yes(context, input.quotedPost != null), AppLocales.integer(input.reviewEvidence?.observations?.size?.toLong() ?: 0)))
            appendLine(context.getString(R.string.l10n_debug_panels, input.reviewEvidence?.panelIds.orEmpty().joinToString()))
            appendLine(context.getString(R.string.l10n_debug_no_send) + "\n")
            append(input.packet().data.toString(2))
        }
    }

    fun cache(context: Context, target: BriefReviewTarget, now: Long = System.currentTimeMillis()): String {

        val saved = BriefPublishedReviewStore.read(context, target) ?: return context.getString(R.string.l10n_debug_cache_post_none)
        val language = BriefStrings.from(context).languageTag
        val lifetime = BriefReviewFreshness.lifetime(saved.subject.post?.timestamp ?: 0L, saved.checkedAt)
        return buildString {
            appendLine(context.getString(R.string.l10n_debug_saved_at, AppLocales.dateTime(context, saved.savedAt)))
            appendLine(context.getString(R.string.l10n_debug_checked_at, AppLocales.dateTime(context, saved.checkedAt)))
            appendLine(context.getString(R.string.l10n_debug_cache_age, AppLocales.integer(((now - saved.savedAt) / 60_000L).coerceAtLeast(0)), AppLocales.integer(lifetime / 3_600_000)))
            appendLine(context.getString(R.string.l10n_debug_cache_language, saved.subject.input.language, language))
            appendLine(context.getString(R.string.l10n_debug_language_matches, yes(context, BriefReviewFreshness.matchesLanguage(saved, language))))
            appendLine(context.getString(R.string.l10n_debug_fresh_reuse, yes(context, BriefReviewFreshness.isFresh(saved, now)), yes(context, BriefReviewFreshness.canReuse(saved, language, now))))
            appendLine(context.getString(R.string.l10n_debug_cloud_media, yes(context, saved.result.cloud), AppLocales.integer(saved.result.inspectedMediaIds.size.toLong())))
            appendLine(context.getString(R.string.l10n_debug_suggestions_panels, AppLocales.integer(saved.result.feedback.suggestions.size.toLong()), saved.result.feedback.panelIds.joinToString()))
            append(context.getString(R.string.l10n_debug_failed_refresh))
        }
    }

    private fun yes(context: Context, value: Boolean) = context.getString(if (value) R.string.l10n_debug_yes else R.string.l10n_debug_no)

    fun checks(context: Context): String {
        val checks = linkedMapOf(
            context.getString(R.string.l10n_debug_check_local) to (BriefReviewPolicy.blocked(BriefProviderMode.LOCAL, true) == BriefReviewFailure.LOCAL_ONLY),
            context.getString(R.string.l10n_debug_check_auto) to (BriefReviewPolicy.blocked(BriefProviderMode.AUTO, false) == BriefReviewFailure.MISSING_KEY),
            context.getString(R.string.l10n_debug_check_cloud) to (BriefReviewPolicy.blocked(BriefProviderMode.CLOUD, false) == BriefReviewFailure.MISSING_KEY),
            context.getString(R.string.l10n_debug_check_auto_ready) to (BriefReviewPolicy.blocked(BriefProviderMode.AUTO, true) == null),
            context.getString(R.string.l10n_debug_check_cloud_ready) to (BriefReviewPolicy.blocked(BriefProviderMode.CLOUD, true) == null),
            context.getString(R.string.l10n_debug_check_first) to (BriefReviewFreshness.lifetime(1, 3_600_001) == 4 * 3_600_000L),
            context.getString(R.string.l10n_debug_check_week) to (BriefReviewFreshness.lifetime(1, 48 * 3_600_000L) == 12 * 3_600_000L),
            context.getString(R.string.l10n_debug_check_old) to (BriefReviewFreshness.lifetime(1, 8 * 24 * 3_600_000L) == 48 * 3_600_000L),
        )
        return checks.entries.joinToString("\n") { "${context.getString(if (it.value) R.string.l10n_debug_pass else R.string.l10n_debug_fail)} · ${it.key}" } +
            "\n\n" + context.getString(R.string.l10n_debug_checks_note)
    }
}

/** Bounded metadata only: never prompts, tweet/draft text, model output, images or API keys. */
internal object BriefReviewDebugLog {
    private const val PREFS = "twidget_review_debug"
    @Synchronized fun record(context: Context, event: String, data: JSONObject) {
        if (!TwidgetStore.debugMenuUnlocked(context)) return
        // A damaged optional debug log must never fail a production review.
        runCatching {
            val list = entries(context)
            val next = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("event", event).put("data", data))
            for (index in 0 until minOf(list.length(), 39)) list.optJSONObject(index)?.let { next.put(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("events", next.toString()).apply()
        }
    }
    fun entries(context: Context): JSONArray = runCatching {
        val stored = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("events", "[]"))
        JSONArray().apply { for (index in 0 until stored.length()) stored.optJSONObject(index)?.takeIf {
            it.optString("event").isNotBlank() && it.optLong("at") > 0 && it.optJSONObject("data") != null
        }?.let { put(it) } }
    }.getOrElse { JSONArray() }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    fun state(context: Context, target: BriefReviewTarget, state: BriefReviewState) = record(context, state.phase.name,
        JSONObject().put("account", target.username).put("kind", if (target.draftId != null) "draft" else "tweet")
            .put("postId", target.postUrl?.let(BriefPublishedReviewStore::postId) ?: JSONObject.NULL)
            .put("language", state.subject?.input?.language ?: JSONObject.NULL)
            .put("failure", state.failure?.name ?: JSONObject.NULL).put("savedAt", state.savedAt ?: JSONObject.NULL)
            .put("mediaCount", state.subject?.input?.media?.size ?: 0).put("historyCount", state.subject?.input?.history?.size ?: 0))
}
