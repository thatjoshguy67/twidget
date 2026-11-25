package com.tjg.twidget.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.preference.LayoutPreference

internal enum class AppIconStyle(val label: Int, val icon: Int, val preview: Int, val component: String) {
    DEFAULT(R.string.app_icon_default, R.mipmap.ic_launcher_default, R.drawable.app_icon_preview_default, ".MainActivity"),
    LIGHT(R.string.app_icon_light, R.mipmap.ic_launcher_light, R.drawable.app_icon_preview_light, ".LauncherLight"),
    DARK(R.string.app_icon_dark, R.mipmap.ic_launcher_dark, R.drawable.app_icon_preview_dark, ".LauncherDark");
    fun componentName(context: Context) = ComponentName(context.packageName, com.tjg.twidget.main.MainActivity::class.java.name.removeSuffix(".main.MainActivity") + component)
}

internal object AppIconSettings {
    fun selected(context: Context): AppIconStyle = AppIconStyle.entries.firstOrNull {
        context.packageManager.getComponentEnabledSetting(it.componentName(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: AppIconStyle.DEFAULT

    fun select(context: Context, selected: AppIconStyle) {
        val pm = context.packageManager
        fun state(style: AppIconStyle) = if (style == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(AppIconStyle.entries.map {
                PackageManager.ComponentEnabledSetting(it.componentName(context), state(it), PackageManager.DONT_KILL_APP)
            })
        } else {
            // Enable the replacement first so the package always has a launcher entry.
            pm.setComponentEnabledSetting(selected.componentName(context), state(selected), PackageManager.DONT_KILL_APP)
            AppIconStyle.entries.filter { it != selected }.forEach {
                pm.setComponentEnabledSetting(it.componentName(context), state(it), PackageManager.DONT_KILL_APP)
            }
        }
    }

    fun pickerView(context: Context): View {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val choices = mutableMapOf<AppIconStyle, LinearLayout>()
        fun update() {
            val selected = selected(context)
            choices.forEach { (style, tile) ->
                tile.isSelected = style == selected
                tile.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (tile.isSelected) context.getColor(R.color.oneui_divider) else android.graphics.Color.TRANSPARENT)
                }
            }
        }
        AppIconStyle.entries.forEach { style ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true; isFocusable = true
                clipToOutline = true
                contentDescription = context.getString(style.label)
                val typed = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
                foreground = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, typed.resourceId)
                addView(ImageView(context).apply {
                    setImageResource(style.preview)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(60), dp(60)))
                addView(TextView(context).apply {
                    text = context.getString(style.label); textSize = 14f
                    gravity = Gravity.CENTER; includeFontPadding = false
                    setTextColor(context.getColor(R.color.oneui_text_primary))
                    typeface = TwidgetFonts.forApp(context, 400)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
                setOnClickListener {
                    select(context, style)
                    update()
                    announceForAccessibility(context.getString(R.string.app_icon_selected, context.getString(style.label)))
                }
            }
            choices[style] = tile
            row.addView(tile, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        }
        update()
        return row
    }

    fun preference(context: Context) = LayoutPreference(context, pickerView(context)).apply {
        key = "settings_app_icon"
        isSelectable = false
        setAllowDividerAbove(false); setAllowDividerBelow(false)
    }
}
