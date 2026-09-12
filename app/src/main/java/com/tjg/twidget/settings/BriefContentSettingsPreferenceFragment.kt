package com.tjg.twidget.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeslSwitchPreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefContentCategory
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefVisuals
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MilestoneGoalActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startRightSidePopOverActivity

class BriefContentSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

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
    ) = AlignedSwitchPreferenceScreen(requireContext()).apply {
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
        val tinted = AppCompatResources.getDrawable(context, BriefVisuals.categoryIcon(category))
            ?.mutate()
            ?.also { DrawableCompat.setTint(it, context.getColor(iconColor(category))) }
            ?: return null
        return SizedDrawable(tinted, (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt())
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

    private class SizedDrawable(
        private val delegate: Drawable,
        private val sizePx: Int,
    ) : Drawable() {
        override fun draw(canvas: Canvas) = delegate.draw(canvas)

        override fun onBoundsChange(bounds: Rect) {
            delegate.bounds = bounds
        }

        override fun setAlpha(alpha: Int) {
            delegate.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            delegate.colorFilter = colorFilter
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = sizePx

        override fun getIntrinsicHeight(): Int = sizePx

        override fun isStateful(): Boolean = delegate.isStateful

        override fun onStateChange(state: IntArray): Boolean = delegate.setState(state)
    }

    /**
     * Samsung's switch-screen layout starts its icon at the leading edge of a
     * 56dp slot with 16dp end padding. The regular switch layout centres its
     * 24dp icon in the remaining 40dp, so apply the missing 8dp half-gap.
     */
    private class AlignedSwitchPreferenceScreen(context: Context) :
        SeslSwitchPreferenceScreen(context) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.findViewById(android.R.id.icon)?.let { icon ->
                val offset = NAVIGABLE_ICON_OFFSET_DP * icon.resources.displayMetrics.density
                icon.translationX = if (icon.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    -offset
                } else {
                    offset
                }
            }
        }
    }

    companion object {
        private const val ICON_SIZE_DP = 24f
        private const val NAVIGABLE_ICON_OFFSET_DP = 8f
    }
}
