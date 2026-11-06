package com.tjg.twidget.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.appcompat.widget.AppCompatButton
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.*
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import dev.oneuiproject.oneui.preference.LayoutPreference

internal object WidgetSettingsCards {
    fun populate(fragment: SettingsCategoryPreferenceFragment, screen: PreferenceScreen) {
        val context = fragment.requireContext()
        WidgetKind.entries.forEachIndexed { index, kind ->
            if (index > 0) screen.addPreference(InsetPreferenceCategory(context))
            val supported = kind != WidgetKind.LOCK_SCREEN || TwidgetFonts.hasSystemOneUiSans
            val card = WidgetSettingsCard(context, kind, supported,
                edit = {
                    fragment.requireActivity().startSettingsSubActivity(
                        Intent(context, WidgetConfigActivity::class.java)
                            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, kind.name),
                    )
                },
                add = {
                    val manager = AppWidgetManager.getInstance(context)
                    val provider = ComponentName(context, if (kind == WidgetKind.BRIEF)
                        TwidgetBriefWidget::class.java else com.tjg.twidget.TwidgetWidget::class.java)
                    if (!manager.isRequestPinAppWidgetSupported ||
                        !manager.requestPinAppWidget(provider, null, null)) {
                        Toast.makeText(context, R.string.add_widget_not_supported, Toast.LENGTH_LONG).show()
                    }
                },
            )
            screen.addPreference(LayoutPreference(context, card).apply {
                key = "widget_card_${kind.name.lowercase()}"
                isSelectable = false
                setAllowDividerAbove(false)
                setAllowDividerBelow(false)
            })
        }
    }
}

/** Original Figma device/wallpaper assets behind adaptive live widget artwork and SESL actions. */
internal class WidgetSettingsCard(
    context: Context,
    private val kind: WidgetKind,
    private val supported: Boolean,
    edit: () -> Unit,
    add: () -> Unit,
    private val oneUi: Boolean = TwidgetFonts.hasSystemOneUiSans,
) : FrameLayout(context) {
    private val phone = ImageView(context)
    private val artwork = ImageView(context)
    private val smallLockArtwork = ImageView(context)
    private val clock = TextClock(context)
    private val date = TextView(context)
    private val title = TextView(context)
    private val actions = LinearLayout(context)
    private val unavailable = TextView(context)

    init {
        background = surface(context.getColor(R.color.oneui_card_bg), 28f)
        clipToOutline = true
        phone.setImageResource(deviceAsset(oneUi))
        phone.scaleType = ImageView.ScaleType.FIT_XY
        phone.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(phone)
        val defaults = TwidgetStore.widgetSettings(context, kind = kind)
        val style = if (oneUi) WidgetStyle.ONE_UI else WidgetStyle.MATERIAL
        val settings = defaults.copy(style = style,
            fontFamily = if (defaults.fontFamily == defaults.style.defaultFont) style.defaultFont else defaults.fontFamily)
        val account = settings.accountUsername.ifBlank { TwidgetStore.settings(context).username }
        val dark = widgetUsesDarkTheme(settings.colorMode)
        val colors = WidgetColors.resolve(context, settings, dark)
        artwork.scaleType = ImageView.ScaleType.FIT_XY
        artwork.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        artwork.background = surface(colors.background, 20f)
        artwork.clipToOutline = true
        when (kind) {
            WidgetKind.FOLLOWERS -> artwork.setImageBitmap(WidgetArtworkRenderer.render(
                context, dp(352), dp(176), TwidgetStore.currentStats(context, account), settings,
                TwidgetWidget.LAYOUT_MODE_LARGE, dark, TwidgetStore.followersDelta(context, account)))
            WidgetKind.BRIEF -> artwork.setImageBitmap(BriefWidgetArtworkRenderer.render(
                context = com.tjg.twidget.core.AppLocales.wrap(context, settings.language),
                widthPx = dp(352), heightPx = dp(176), account = account,
                snapshot = com.tjg.twidget.brief.BriefStore.read(context, account), dark = dark,
                fontFamily = settings.fontFamily, style = settings.style))
            WidgetKind.LOCK_SCREEN -> {
                artwork.scaleType = ImageView.ScaleType.FIT_CENTER
                clock.format12Hour = "h:mm"
                clock.format24Hour = "HH:mm"
                clock.textSize = 60f
                clock.setTextColor(Color.WHITE)
                clock.gravity = Gravity.CENTER
                clock.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(clock)
                date.text = com.tjg.twidget.core.AppLocales.formatDate(System.currentTimeMillis(), "EMMMd",
                    com.tjg.twidget.core.AppLocales.resolve(settings.language), context)
                date.textSize = 16f
                date.setTextColor(Color.WHITE)
                date.gravity = Gravity.CENTER
                date.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(date)
                artwork.background = surface(0x66FFFFFF, 18f)
                artwork.setImageBitmap(LockScreenFollowerViews.previewArt(context, true, settings))
                smallLockArtwork.setImageBitmap(LockScreenFollowerViews.previewArt(context, false, settings))
                smallLockArtwork.background = surface(0x66FFFFFF, 18f)
                smallLockArtwork.scaleType = ImageView.ScaleType.FIT_CENTER
                smallLockArtwork.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(smallLockArtwork)
            }
        }
        addView(artwork)
        title.setText(when (kind) {
            WidgetKind.FOLLOWERS -> R.string.followers
            WidgetKind.BRIEF -> R.string.brief_widget_label
            WidgetKind.LOCK_SCREEN -> R.string.widget_lock_screen
        })
        title.setTextColor(context.getColor(R.color.oneui_text_primary))
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.typeface = TwidgetFonts.forApp(context, 700)
        TwidgetFonts.setRole(title, TwidgetFonts.Role.LABEL)
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true)
        addView(title)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        fun button(label: Int, primary: Boolean, action: () -> Unit) = AppCompatButton(context).apply {
            setText(label)
            isAllCaps = false
            textSize = 18f
            setPadding(dp(8), 0, dp(8), 0)
            val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val color = if (primary) context.getColor(R.color.oneui_accent)
                else if (dark) context.getColor(R.color.oneui_card_bg) else 0x99FCFCFF.toInt()
            backgroundTintList = null
            background = RippleDrawable(android.content.res.ColorStateList.valueOf(
                if (primary || dark) 0x33FFFFFF else 0x22000000), surface(color, 1000f), null)
            setTextColor(if (primary || dark) Color.WHITE else Color.BLACK)
            setOnClickListener { action() }
        }
        if (supported) {
            actions.addView(button(R.string.schedule_edit, false, edit),
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            if (kind != WidgetKind.LOCK_SCREEN) {
                actions.addView(button(R.string.schedule_add, true, add),
                    LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(10) })
            }
            addView(actions)
        } else {
            phone.alpha = 0.35f
            artwork.alpha = 0.35f
            smallLockArtwork.alpha = 0.35f
            clock.alpha = 0.35f
            date.alpha = 0.35f
            title.alpha = 0.45f
            unavailable.setText(R.string.widget_one_ui_only)
            unavailable.setTextColor(context.getColor(R.color.oneui_text_secondary))
            unavailable.textSize = 16f
            unavailable.gravity = Gravity.CENTER
            unavailable.setPadding(dp(20), 0, dp(20), 0)
            unavailable.background = surface(context.getColor(R.color.oneui_card_bg), 0f)
            addView(unavailable)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val scale = width / 392f
        fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
            view.layoutParams = LayoutParams((w * scale).toInt(), (h * scale).toInt()).apply {
                leftMargin = (x * scale).toInt()
                topMargin = (y * scale).toInt()
            }
        }
        place(phone, 43, 67, 306, 650)
        place(title, 10, 10, 372, 42)
        if (kind == WidgetKind.LOCK_SCREEN) {
            artwork.setPadding((8 * scale).toInt(), (5 * scale).toInt(),
                (8 * scale).toInt(), (5 * scale).toInt())
            smallLockArtwork.setPadding((5 * scale).toInt(), (5 * scale).toInt(),
                (5 * scale).toInt(), (5 * scale).toInt())
            place(date, 66, 133, 260, 28)
            place(clock, 56, 147, 280, 80)
            place(artwork, 105, 235, 113, 50)
            place(smallLockArtwork, 229, 235, 57, 50)
            place(actions, 133, 315, 126, 52)
        } else {
            place(artwork, 65, 146, 262, 131)
            place(actions, 65, 315, 262, 52)
        }
        place(unavailable, 0, 312, 392, 72)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((384 * scale).toInt(), MeasureSpec.EXACTLY))
    }

    internal companion object {
        fun deviceAsset(oneUi: Boolean): Int = if (oneUi)
            R.drawable.widget_device_one_ui else R.drawable.widget_device_material
    }

    private fun surface(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * resources.displayMetrics.density
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
