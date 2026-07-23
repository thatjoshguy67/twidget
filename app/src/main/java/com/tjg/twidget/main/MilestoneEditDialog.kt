package com.tjg.twidget.main

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.widget.TwidgetWidget
import java.text.NumberFormat
import java.util.Locale

internal object MilestoneEditDialog {
    fun show(activity: MainActivity, username: String, onSaved: () -> Unit) {
        val account = username.trim().trimStart('@')
        if (account.isBlank()) return
        val current = TwidgetStore.milestoneSettings(activity, account)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(4))
        }

        val targetInput = EditText(activity).apply {
            setText(current.labelRaw.ifBlank { current.target?.toString().orEmpty() })
            hint = activity.getString(R.string.milestone_target_hint)
            setSelectAllOnFocus(true)
        }
        container.addView(sectionLabel(activity, R.string.milestone_target))
        container.addView(targetInput)

        val showPercentSwitch = SwitchCompat(activity).apply {
            text = activity.getString(R.string.milestone_show_percent)
            isChecked = current.showPercent
            setPadding(0, activity.dp(8), 0, activity.dp(4))
        }
        container.addView(showPercentSwitch)

        val stats = TwidgetStore.currentStats(activity, account)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.milestone_edit_title)
            .setMessage(R.string.milestone_edit_message)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.milestone_use_auto, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                TwidgetStore.clearMilestoneSettings(activity, account)
                TwidgetWidget.updateAll(activity)
                onSaved()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!saveMilestoneEdit(
                        activity = activity,
                        account = account,
                        raw = targetInput.text?.toString()?.trim().orEmpty(),
                        showPercent = showPercentSwitch.isChecked,
                        followersCount = stats.followersCount,
                        followersKnown = stats.followersKnown,
                    )
                ) {
                    return@setOnClickListener
                }
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveMilestoneEdit(
        activity: MainActivity,
        account: String,
        raw: String,
        showPercent: Boolean,
        followersCount: Long,
        followersKnown: Boolean,
    ): Boolean {
        if (raw.isBlank()) {
            TwidgetStore.saveMilestoneSettings(
                activity,
                account,
                MilestoneSettings(
                    showPercent = showPercent,
                ),
            )
            TwidgetWidget.updateAll(activity)
            return true
        }
        val parsed = MilestonePolicy.parseInput(raw)
        if (!parsed.valid || parsed.target == null) {
            Toast.makeText(activity, R.string.milestone_invalid_target, Toast.LENGTH_SHORT).show()
            return false
        }
        if (!MilestonePolicy.isTargetAboveFollowers(parsed.target, followersCount, followersKnown)) {
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.milestone_target_below_followers,
                    NumberFormat.getIntegerInstance(Locale.US).format(followersCount),
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        TwidgetStore.saveMilestoneSettings(
            activity,
            account,
            MilestoneSettings(
                target = parsed.target,
                labelRaw = parsed.labelRaw,
                showPercent = showPercent,
            ),
        )
        TwidgetWidget.updateAll(activity)
        return true
    }

    fun createEditButton(activity: MainActivity, username: String, onSaved: () -> Unit): TextView =
        TextView(activity).apply {
            text = activity.getString(R.string.milestone_edit_button)
            textSize = 13f
            typeface = Typeface.create("sec", Typeface.BOLD)
            setTextColor(activity.getColor(R.color.oneui_accent))
            gravity = Gravity.CENTER
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    (activity.getColor(R.color.oneui_accent) and 0x00FFFFFF) or 0x24000000,
                ),
                GradientDrawable().apply {
                    cornerRadius = activity.dp(16).toFloat()
                    setColor(activity.getColor(R.color.oneui_card_bg))
                },
                null,
            )
            contentDescription = activity.getString(R.string.milestone_edit_title)
            setOnClickListener { show(activity, username, onSaved) }
        }

    private fun sectionLabel(activity: MainActivity, titleRes: Int): TextView =
        TextView(activity).apply {
            text = activity.getString(titleRes)
            textSize = 13f
            typeface = Typeface.create("sec", Typeface.BOLD)
            setTextColor(activity.getColor(R.color.oneui_text_secondary))
            setPadding(0, activity.dp(12), 0, activity.dp(4))
        }
}
