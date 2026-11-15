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
            if (index > 0 && TwidgetFonts.hasSystemOneUiSans) screen.addPreference(InsetPreferenceCategory(context))
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
                clock.textSize = 56f
                clock.typeface = TwidgetFonts.forApp(context, 300)
                clock.includeFontPadding = false
                clock.maxLines = 1
                clock.fontFeatureSettings = "'tnum' 1"
                clock.setTextColor(Color.WHITE)
                clock.gravity = Gravity.CENTER
                clock.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(clock)
                date.text = com.tjg.twidget.core.AppLocales.formatDate(System.currentTimeMillis(), "EMMMd",
                    com.tjg.twidget.core.AppLocales.resolve(settings.language), context)
                date.textSize = 14f
                date.includeFontPadding = false
                date.maxLines = 1
                date.setTextColor(Color.WHITE)
                date.gravity = Gravity.CENTER
                date.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(date)
                artwork.background = surface(0x40FFFFFF, 26f)
                artwork.setImageBitmap(LockScreenFollowerViews.previewArt(context, true, settings))
                smallLockArtwork.setImageBitmap(LockScreenFollowerViews.previewArt(context, false, settings))
                smallLockArtwork.background = surface(0x40FFFFFF, 26f)
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
            setPadding(dp(8), dp(if (oneUi) 0 else 4), dp(8), dp(if (oneUi) 0 else 4))
            if (!oneUi) {
                minHeight = dp(48)
                setSingleLine(false)
                maxLines = 2
                ellipsize = null
            }
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
        // A full-width preference must never magnify the device artwork beyond
        // its native design size, including a single wide cell after resizing.
        val designWidth = if (oneUi) width else minOf(width, dp(392))
        val scale = designWidth / 392f
        val start = (width - designWidth) / 2
        fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
            view.layoutParams = LayoutParams((w * scale).toInt(), (h * scale).toInt()).apply {
                leftMargin = start + (x * scale).toInt()
                topMargin = (y * scale).toInt()
            }
        }
        var minimumHeight = (384 * scale).toInt()
        var messageTop = (312 * scale).toInt()
        place(phone, 43, 67, 306, 650)
        place(title, 10, 10, 372, 42)
        if (kind == WidgetKind.LOCK_SCREEN) {
            artwork.setPadding((8 * scale).toInt(), (5 * scale).toInt(),
                (8 * scale).toInt(), (5 * scale).toInt())
            smallLockArtwork.setPadding((5 * scale).toInt(), (5 * scale).toInt(),
                (5 * scale).toInt(), (5 * scale).toInt())
            // These are part of the device illustration, so text and shapes
            // scale with its artwork rather than the surrounding settings UI.
            clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 56f * scale)
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * scale)
            (artwork.background as? GradientDrawable)?.cornerRadius = 26f * scale
            (smallLockArtwork.background as? GradientDrawable)?.cornerRadius = 26f * scale
            place(date, 66, 127, 260, 24)
            place(clock, 56, 155, 280, 68)
            // Account for the chosen font's actual line metrics. Some app fonts
            // need a taller line even at the same illustrated text size.
            fun fitText(view: TextView) {
                view.measure(MeasureSpec.makeMeasureSpec(view.layoutParams.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                view.layoutParams.height = maxOf(view.layoutParams.height, view.measuredHeight)
            }
            fitText(date)
            fitText(clock)
            val dateParams = date.layoutParams as LayoutParams
            val clockParams = clock.layoutParams as LayoutParams
            clockParams.topMargin = maxOf(clockParams.topMargin, dateParams.topMargin + dateParams.height + (4 * scale).toInt())
            val sampleTop = maxOf((239 * scale).toInt(), clockParams.topMargin + clockParams.height + (16 * scale).toInt())
            place(artwork, 105, 239, 118, 52)
            place(smallLockArtwork, 235, 239, 52, 52)
            (artwork.layoutParams as LayoutParams).topMargin = sampleTop
            (smallLockArtwork.layoutParams as LayoutParams).topMargin = sampleTop
            place(actions, 133, 315, 126, 52)
            val actionParams = actions.layoutParams as LayoutParams
            actionParams.topMargin = maxOf(actionParams.topMargin, sampleTop + artwork.layoutParams.height + (24 * scale).toInt())
            messageTop = maxOf(messageTop, sampleTop + artwork.layoutParams.height + (24 * scale).toInt())
            minimumHeight = maxOf(minimumHeight, if (supported) actionParams.topMargin + actionParams.height + (17 * scale).toInt()
                else messageTop + (72 * scale).toInt())
        } else {
            place(artwork, 65, 146, 262, 131)
            place(actions, 65, 315, 262, 52)
        }
        place(unavailable, 0, 312, 392, 72)
        (unavailable.layoutParams as LayoutParams).topMargin = messageTop
        var height = minimumHeight
        if (!oneUi) {
            fun naturalHeight(view: View): Int {
                view.measure(MeasureSpec.makeMeasureSpec(view.layoutParams.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                return view.measuredHeight
            }
            val titleParams = title.layoutParams as LayoutParams
            titleParams.height = maxOf(titleParams.height, naturalHeight(title))
            val phoneTop = (phone.layoutParams as LayoutParams).topMargin
            val shift = (titleParams.topMargin + titleParams.height + dp(8) - phoneTop).coerceAtLeast(0)
            for (view in listOf(phone, artwork, smallLockArtwork, clock, date, actions, unavailable))
                (view.layoutParams as? LayoutParams)?.let { it.topMargin += shift }
            height += shift
            if (supported) {
                val params = actions.layoutParams as LayoutParams
                params.height = maxOf(dp(48), naturalHeight(actions))
                height = maxOf(height, params.topMargin + params.height + dp(16))
            } else {
                val params = unavailable.layoutParams as LayoutParams
                params.height = maxOf(params.height, naturalHeight(unavailable))
                height = maxOf(height, params.topMargin + params.height)
            }
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
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
