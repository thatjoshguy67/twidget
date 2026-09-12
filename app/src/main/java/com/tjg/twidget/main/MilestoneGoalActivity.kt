package com.tjg.twidget.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.R as OneUiIconR
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.SwitchItemView

class MilestoneGoalActivity : EdgeToEdgeActivity() {
    private lateinit var account: String
    private lateinit var rows: LinearLayout
    private lateinit var autoSwitch: SwitchItemView
    private var bindingAutoSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = intent.getStringExtra(EXTRA_ACCOUNT)
            ?.trim()?.trimStart('@')
            .orEmpty()
            .ifBlank { com.tjg.twidget.data.TwidgetStore.settings(this).username }
        if (account.isBlank()) {
            finish()
            return
        }

        setContentView(R.layout.activity_milestone_goal)
        findViewById<ToolbarLayout>(R.id.milestone_setup_root).apply {
            setNavigationButtonOnClickListener { finish() }
            applyEdgeToEdgeInsets(this)
        }
        rows = findViewById(R.id.milestone_metric_rows)
        autoSwitch = findViewById(R.id.milestone_auto_row)

        autoSwitch.onCheckedChangedListener = { _, checked ->
            if (!bindingAutoSwitch) {
                MilestoneGoalStore.setAutoAdjust(this, account, checked)
                TwidgetWidget.updateAll(this)
                setResult(RESULT_OK)
            }
        }
        refresh()
    }

    private fun refresh() {
        val goals = MilestoneGoalStore.readAll(this, account).associateBy(AccountGoalSettings::metric)
        bindingAutoSwitch = true
        autoSwitch.isChecked = MilestoneGoalStore.autoAdjust(this, account)
        bindingAutoSwitch = false

        rows.removeAllViews()
        MilestoneMetric.entries.forEachIndexed { index, metric ->
            rows.addView(goalRow(metric, goals[metric], index > 0))
        }
    }

    private fun goalRow(
        metric: MilestoneMetric,
        goal: AccountGoalSettings?,
        showDivider: Boolean,
    ): CardItemView = CardItemView(this).apply {
        title = getString(metricLabel(metric))
        summary = goal?.let {
            getString(
                R.string.milestone_current_goal,
                MilestoneGoalDialog.formatValue(metric, it.target),
            )
        } ?: getString(metricSummary(metric))
        icon = AppCompatResources.getDrawable(this@MilestoneGoalActivity, iconFor(metric))?.apply {
            setTint(getColor(R.color.oneui_text_primary))
        }
        showTopDivider = showDivider
        getEndImageView().apply {
            setImageResource(OneUiIconR.drawable.ic_oui_edit_outline)
            imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            contentDescription = getString(R.string.milestone_edit_goal_title, metric.goalNoun)
        }
        contentDescription = buildString {
            append(getString(metricLabel(metric)))
            append(". ")
            append(goal?.let {
                getString(
                    R.string.milestone_current_goal,
                    MilestoneGoalDialog.formatValue(metric, it.target),
                )
            } ?: getString(metricSummary(metric)))
        }
        setOnClickListener {
            MilestoneGoalDialog.show(this@MilestoneGoalActivity, account, metric) {
                setResult(RESULT_OK)
                refresh()
            }
        }
    }

    private fun metricLabel(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> R.string.milestone_metric_followers
        MilestoneMetric.VERIFIED_FOLLOWERS -> R.string.milestone_metric_verified
        MilestoneMetric.ENGAGEMENT_RATE -> R.string.milestone_metric_engagement
        MilestoneMetric.IMPRESSIONS -> R.string.milestone_metric_impressions
    }

    private fun metricSummary(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> R.string.milestone_metric_followers_summary
        MilestoneMetric.VERIFIED_FOLLOWERS -> R.string.milestone_metric_verified_summary
        MilestoneMetric.ENGAGEMENT_RATE -> R.string.milestone_metric_engagement_summary
        MilestoneMetric.IMPRESSIONS -> R.string.milestone_metric_impressions_summary
    }

    private fun iconFor(metric: MilestoneMetric): Int = when (metric) {
        MilestoneMetric.FOLLOWERS -> OneUiIconR.drawable.ic_oui_community
        MilestoneMetric.VERIFIED_FOLLOWERS -> OneUiIconR.drawable.ic_oui_checkbox_checked_outline
        MilestoneMetric.ENGAGEMENT_RATE -> OneUiIconR.drawable.ic_oui_equalizer_2
        MilestoneMetric.IMPRESSIONS -> OneUiIconR.drawable.ic_oui_eyes
    }

    companion object {
        private const val EXTRA_ACCOUNT = "account"

        fun intent(context: Context, account: String): Intent =
            Intent(context, MilestoneGoalActivity::class.java)
                .putExtra(EXTRA_ACCOUNT, account)
    }
}
