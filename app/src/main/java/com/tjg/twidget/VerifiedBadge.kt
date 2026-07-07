package com.tjg.twidget

import android.content.Context
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.appcompat.content.res.AppCompatResources
import dev.oneuiproject.oneui.R as OneUiIconR

// Appends status badges after a display name wherever it is rendered as text
// (header title, drawer items, account rows).
object VerifiedBadge {
    fun decorate(
        context: Context,
        name: CharSequence,
        verified: Boolean?,
        isPrivate: Boolean?,
        badgeSizePx: Int,
    ): CharSequence {
        if (name.isBlank() || (verified != true && isPrivate != true)) return name
        val builder = SpannableStringBuilder(name)
        if (verified == true) {
            builder.appendBadge(context, OneUiIconR.drawable.ic_oui_checkbox_checked, badgeSizePx, R.color.oneui_accent)
        }
        if (isPrivate == true) {
            builder.appendBadge(context, OneUiIconR.drawable.ic_oui_lock, badgeSizePx, R.color.oneui_text_secondary)
        }
        return builder
    }

    private fun SpannableStringBuilder.appendBadge(
        context: Context,
        drawableRes: Int,
        sizePx: Int,
        tintRes: Int,
    ) {
        val badge = AppCompatResources.getDrawable(context, drawableRes)?.mutate() ?: return
        badge.setTint(context.getColor(tintRes))
        badge.setBounds(0, 0, sizePx, sizePx)
        val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageSpan.ALIGN_CENTER
        } else {
            ImageSpan.ALIGN_BASELINE
        }
        append("  ")
        setSpan(ImageSpan(badge, alignment), length - 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
