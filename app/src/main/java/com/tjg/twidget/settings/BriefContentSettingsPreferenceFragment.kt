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
            row.findViewById<LinearLayout>(androidx.preference.R.id.icon_frame)?.apply {
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
        screen.addPreference(PreferenceCategory(context).apply { title = "Twitter/X" })

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

        screen.addPreference(spacerCategory())
        screen.addPreference(categorySwitch(BriefContentCategory.FOLLOWERS, R.string.brief_content_followers))
        screen.addPreference(categorySwitch(BriefContentCategory.TOP_FOLLOWERS, R.string.brief_content_top_followers))

        screen.addPreference(spacerCategory())
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

        screen.addPreference(spacerCategory())
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

        screen.addPreference(PreferenceCategory(requireContext()).apply { title = getString(R.string.social_accounts) })
        val socialChoices = com.tjg.twidget.social.SocialPlatform.entries.map { it.storageId to it.label }
        socialChoices.forEach { (id, name) ->
            screen.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = "social_brief_$id"; title = name; isPersistent = false
                isChecked = com.tjg.twidget.social.ProfileBriefEngine.enabled(context, id)
                setOnPreferenceChangeListener { _, value -> com.tjg.twidget.social.ProfileBriefEngine.setEnabled(context, id, value == true); true }
            })
        }
        listOf("combined_audience" to R.string.social_all_audience, "github_repositories" to R.string.social_repositories).forEach { (id, label) ->
            screen.addPreference(SwitchPreferenceCompat(requireContext()).apply {
                key = id; setTitle(label); isPersistent = false
                isChecked = com.tjg.twidget.social.ProfileBriefEngine.enabled(context, id)
                setOnPreferenceChangeListener { _, value -> com.tjg.twidget.social.ProfileBriefEngine.setEnabled(context, id, value == true); true }
            })
        }
        screen.addBottomInset()
        preferenceScreen = screen
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

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
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
