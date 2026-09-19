package com.tjg.twidget.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceCategory
import androidx.preference.SeslSwitchPreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.social.label
import com.tjg.twidget.social.icon
import com.tjg.twidget.social.iconRes
import com.tjg.twidget.social.labelRes
import com.tjg.twidget.social.SocialPlatform
import com.tjg.twidget.social.SocialRepository
import com.tjg.twidget.social.SocialMetricCardFactory
import com.tjg.twidget.social.ProfileBriefEngine
import androidx.preference.Preference
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefContentCategory
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MilestoneGoalActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startRightSidePopOverActivity

class BriefContentSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fun centreIcon(row: View) {
            // Only switch-screen rows use a LinearLayout; standard SESL rows use a FrameLayout.
            (row.findViewById<View>(androidx.preference.R.id.icon_frame) as? LinearLayout)?.apply {
                gravity = Gravity.CENTER
                // Balance the row's leading inset inside the existing SESL icon column.
                setPaddingRelative(0, paddingTop, row.paddingStart, paddingBottom)
            }
        }
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = centreIcon(view)
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
        for (index in 0 until listView.childCount) centreIcon(listView.getChildAt(index))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val dependencies = mutableListOf<Pair<Preference, String>>()
        val platforms = SocialRepository(context).use { it.catalog() }.accounts.map { it.platform }.toSet().toMutableSet()
        if (TwidgetStore.settings(context).username.isNotBlank()) platforms += SocialPlatform.X
        screen.addPreference(Preference(context).apply {
            setSummary(R.string.social_content_scope); isSelectable = false; isPersistent = false
        })
        if (SocialPlatform.X in platforms) {
            screen.addPreference(PreferenceCategory(context).apply { title = SocialPlatform.X.label })
            screen.addPreference(platformSwitch(SocialPlatform.X))

            screen.addPreference(categorySwitch(BriefContentCategory.TOP_TWEET, R.string.brief_content_top_tweet))
            screen.addPreference(categorySwitch(BriefContentCategory.WORST_TWEET, R.string.brief_content_worst_tweet))
            screen.addPreference(explainedCategorySwitch(
                BriefContentCategory.POST_FOLLOW_THROUGH,
                R.string.brief_content_post_follow_through,
                R.string.brief_post_follow_through_explainer,
            ))
            screen.addPreference(explainedCategorySwitch(
                BriefContentCategory.POSTING_GUIDANCE,
                R.string.brief_content_posting_guidance,
                R.string.brief_posting_guidance_explainer,
            ))

            screen.addPreference(categorySwitch(BriefContentCategory.FOLLOWERS, R.string.brief_content_followers))
            screen.addPreference(categorySwitch(BriefContentCategory.TOP_FOLLOWERS, R.string.brief_content_top_followers))

            screen.addPreference(navigableCategorySwitch(
                category = BriefContentCategory.TWEET_ACTIVITY,
                titleRes = R.string.brief_content_tweet_activity,
            ) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.brief_content_tweet_activity)
                    .setMessage(R.string.brief_tweet_activity_explainer)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            })
            screen.addPreference(categorySwitch(
                BriefContentCategory.SCHEDULED_TWEETS,
                R.string.brief_content_scheduled_tweets,
            ))
            screen.addPreference(explainedCategorySwitch(
                BriefContentCategory.SCHEDULE_HEALTH,
                R.string.brief_content_schedule_health,
                R.string.brief_schedule_health_explainer,
            ))

            screen.addPreference(navigableCategorySwitch(
                category = BriefContentCategory.ACCOUNT_GOALS,
                titleRes = R.string.brief_content_account_goals,
            ) {
                val account = TwidgetStore.settings(requireContext()).username
                if (account.isNotBlank()) {
                    requireActivity().startRightSidePopOverActivity(
                        MilestoneGoalActivity.intent(requireContext(), account),
                    )
                }
            })

        }
        SocialPlatform.entries.filter { it != SocialPlatform.X && it in platforms }.forEach { platform ->
            screen.addPreference(PreferenceCategory(context).apply { title = platform.label })
            screen.addPreference(platformSwitch(platform))
            SocialMetricCardFactory.metrics(platform).forEach { metric ->
                screen.addPreference(SwitchPreferenceCompat(context).apply {
                    layoutResource = androidx.preference.R.layout.sesl_preference_switch_screen
                    key = "social_brief_${platform.storageId}_${metric.storageId}"
                    setTitle(metric.labelRes); isPersistent = false
                    icon = AppCompatResources.getDrawable(context, metric.iconRes)?.mutate()?.also {
                        DrawableCompat.setTint(it, context.getColor(R.color.oneui_text_primary))
                    }
                    isChecked = ProfileBriefEngine.metricEnabled(context, platform, metric)
                    setOnPreferenceChangeListener { _, value ->
                        ProfileBriefEngine.setMetricEnabled(context, platform, metric, value == true); true
                    }
                    dependencies += this to "social_brief_${platform.storageId}"
                })
            }
        }
        screen.addBottomInset()
        preferenceScreen = screen
        // SESL resolves dependencies immediately. Publish the complete hierarchy first.
        for (index in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(index)
            if (preference.key?.startsWith("brief_content_") == true) dependencies += preference to "social_brief_x"
        }
        dependencies.forEach { (preference, master) -> preference.dependency = master }
    }

    private fun platformSwitch(platform: SocialPlatform) = SwitchPreferenceCompat(requireContext()).apply {
        layoutResource = androidx.preference.R.layout.sesl_preference_switch_screen
        key = "social_brief_${platform.storageId}"; title = getString(R.string.social_include_platform, platform.label)
        icon = platform.icon(context); isPersistent = false
        isChecked = ProfileBriefEngine.enabled(context, platform.storageId)
        setOnPreferenceChangeListener { _, value -> ProfileBriefEngine.setEnabled(context, platform.storageId, value == true); true }
    }

    private fun categorySwitch(
        category: BriefContentCategory,
        titleRes: Int,
    ) = SwitchPreferenceCompat(requireContext()).apply {
        configureCategory(this, category, titleRes)
    }

    private fun navigableCategorySwitch(
        category: BriefContentCategory,
        titleRes: Int,
        onOpen: () -> Unit,
    ) = SeslSwitchPreferenceScreen(requireContext()).apply {
        configureCategory(this, category, titleRes)
        setOnPreferenceClickListener {
            onOpen()
            true
        }
    }

    private fun explainedCategorySwitch(
        category: BriefContentCategory,
        titleRes: Int,
        messageRes: Int,
    ) = navigableCategorySwitch(category, titleRes) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun configureCategory(
        preference: SwitchPreferenceCompat,
        category: BriefContentCategory,
        titleRes: Int,
    ) {
        val context = requireContext()
        // Use the same SESL icon column for plain switches and navigable switch rows.
        preference.layoutResource = androidx.preference.R.layout.sesl_preference_switch_screen
        preference.key = "brief_content_${category.storageId}"
        preference.title = getString(titleRes)
        preference.isPersistent = false
        preference.isChecked = BriefSettingsStore.contentEnabled(context, category)
        preference.icon = tintedIcon(category)
        preference.isIconSpaceReserved = true
        preference.setOnPreferenceChangeListener { _, newValue ->
            BriefSettingsStore.setContentEnabled(context, category, newValue as Boolean)
            true
        }
    }

    private fun tintedIcon(category: BriefContentCategory): Drawable? {
        val context = requireContext()
        val tinted = AppCompatResources.getDrawable(context, categoryIcon(category))
            ?.mutate()
            ?.also { DrawableCompat.setTint(it, context.getColor(iconColor(category))) }
            ?: return null
        return tinted
    }

    private fun iconColor(category: BriefContentCategory): Int = when (category) {
        BriefContentCategory.TOP_TWEET -> R.color.brief_icon_top_tweet
        BriefContentCategory.WORST_TWEET -> R.color.metric_red
        BriefContentCategory.TOP_FOLLOWERS,
        BriefContentCategory.TWEET_ACTIVITY,
        BriefContentCategory.POSTING_GUIDANCE -> R.color.oneui_accent
        BriefContentCategory.ACCOUNT_GOALS -> R.color.metric_green
        BriefContentCategory.POST_FOLLOW_THROUGH -> R.color.brief_icon_top_tweet
        BriefContentCategory.SCHEDULE_HEALTH -> R.color.metric_green
        BriefContentCategory.FOLLOWERS,
        BriefContentCategory.SCHEDULED_TWEETS -> R.color.oneui_text_primary
    }

    private fun categoryIcon(category: BriefContentCategory): Int = when (category) {
        BriefContentCategory.TOP_TWEET -> R.drawable.ic_settings_top_post
        BriefContentCategory.WORST_TWEET -> R.drawable.ic_settings_delete
        BriefContentCategory.FOLLOWERS -> R.drawable.ic_settings_community
        BriefContentCategory.TOP_FOLLOWERS -> R.drawable.ic_settings_diamond
        BriefContentCategory.TWEET_ACTIVITY -> R.drawable.ic_settings_send
        BriefContentCategory.SCHEDULED_TWEETS -> R.drawable.ic_settings_clock
        BriefContentCategory.SCHEDULE_HEALTH -> R.drawable.ic_settings_calendar
        BriefContentCategory.POST_FOLLOW_THROUGH -> R.drawable.ic_settings_repeat
        BriefContentCategory.POSTING_GUIDANCE -> R.drawable.ic_settings_star
        BriefContentCategory.ACCOUNT_GOALS -> R.drawable.ic_settings_flag
    }
}
