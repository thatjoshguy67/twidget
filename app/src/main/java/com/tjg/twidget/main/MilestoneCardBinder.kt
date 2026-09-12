package com.tjg.twidget.main

import android.content.res.Configuration
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import java.text.NumberFormat
import java.util.Locale

internal class MilestoneCardBinder(
    private val activity: MainActivity,
) {
    fun create(
        stats: ProfileStats,
        history: List<HistorySample>,
        account: String,
    ): View {
        val root = LayoutInflater.from(activity).inflate(R.layout.milestone_card, null, false)
        val settings = MilestoneGoalStore.read(activity, account)
        val arc = root.findViewById<MilestoneArcView>(R.id.milestone_arc)
        val edit = root.findViewById<ImageButton>(R.id.milestone_edit)
        val openSetup = {
            if (!activity.editModeController.editMode) {
                activity.startActivity(MilestoneGoalActivity.intent(activity, account))
            }
        }
        root.setOnClickListener { openSetup() }
        edit.setOnClickListener { openSetup() }

        if (!settings.configured || settings.target <= 0.0) {
            bindColors(root, arc, COLOR_BLUE, GLOW_BLUE)
            arc.progress = 0
            edit.visibility = View.GONE
            root.findViewById<TextView>(R.id.milestone_title)
                .setText(R.string.milestone_account_goals)
            root.findViewById<TextView>(R.id.milestone_message)
                .setText(R.string.milestone_setup_hint)
            root.contentDescription = activity.getString(R.string.milestone_setup_hint)
            return root
        }

        val snapshot = MilestoneMetricResolver.resolve(
            context = activity,
            account = account,
            metric = settings.metric,
            stats = stats,
            history = history,
            analytics = activity.analytics,
            imported = activity.importedAnalytics,
        )
        val progress = MilestonePolicy.progress(snapshot.value, settings.target) ?: 0
        val state = MilestonePolicy.performanceState(snapshot.history)
        val (accent, glow) = when (state) {
            MilestonePerformanceState.ACCELERATING -> COLOR_GREEN to GLOW_GREEN
            MilestonePerformanceState.DECELERATING -> COLOR_ORANGE to GLOW_ORANGE
            MilestonePerformanceState.NEUTRAL -> COLOR_BLUE to GLOW_BLUE
        }
        bindColors(root, arc, accent, glow)
        arc.progress = progress
        edit.visibility = View.VISIBLE

        val target = formatValue(settings.metric, settings.target)
        val noun = metricNoun(settings.metric)
        val message = MilestoneCopyFactory.message(activity, account, state, progress, target, noun)
        root.findViewById<TextView>(R.id.milestone_title).text = message.title
        root.findViewById<TextView>(R.id.milestone_message).text =
            boldTarget(message.body, target)
        root.contentDescription = "${message.title}. ${message.body}"
        return root
    }

    private fun bindColors(root: View, arc: MilestoneArcView, accent: Int, glow: Int) {
        arc.progressColor = accent
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        root.background = MilestoneCardBackgroundDrawable(
            glowColor = if (night) darkGlow(glow) else glow,
            surfaceColor = activity.getColor(R.color.oneui_card_bg),
            radiusPx = activity.dp(28).toFloat(),
        )
    }

    private fun darkGlow(color: Int): Int = android.graphics.Color.rgb(
        (android.graphics.Color.red(color) * DARK_GLOW_STRENGTH).toInt(),
        (android.graphics.Color.green(color) * DARK_GLOW_STRENGTH).toInt(),
        (android.graphics.Color.blue(color) * DARK_GLOW_STRENGTH).toInt(),
    )

    private fun boldTarget(body: String, target: String): CharSequence {
        val start = body.indexOf(target)
        if (start < 0) return body
        return SpannableString(body).apply {
            setSpan(StyleSpan(Typeface.BOLD), start, start + target.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun formatValue(metric: MilestoneMetric, target: Double): String =
        if (metric == MilestoneMetric.ENGAGEMENT_RATE) {
            "${(target * 100).toInt()}%"
        } else {
            NumberFormat.getIntegerInstance(Locale.getDefault()).format(target.toLong())
        }

    private fun metricNoun(metric: MilestoneMetric): String = when (metric) {
        MilestoneMetric.FOLLOWERS -> "follower"
        MilestoneMetric.VERIFIED_FOLLOWERS -> "verified follower"
        MilestoneMetric.ENGAGEMENT_RATE -> "engagement rate"
        MilestoneMetric.IMPRESSIONS -> "impression"
    }

    private companion object {
        const val DARK_GLOW_STRENGTH = 0.30f
        const val COLOR_BLUE = 0xFF1881FF.toInt()
        const val GLOW_BLUE = 0xFF8DCCFF.toInt()
        const val COLOR_GREEN = 0xFF0FCF6E.toInt()
        const val GLOW_GREEN = 0xFFADFFD5.toInt()
        const val COLOR_ORANGE = 0xFFFF671F.toInt()
        const val GLOW_ORANGE = 0xFFFFC4A8.toInt()
    }
}
