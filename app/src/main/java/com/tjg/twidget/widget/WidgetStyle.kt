package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.ui.TwidgetFonts

/** A widget's appearance is independent of its host launcher and the app theme. */
enum class WidgetStyle(val storedValue: String) {
    ONE_UI("one_ui"), MATERIAL("material");

    val defaultFont: String get() = if (this == MATERIAL) TwidgetStore.FONT_GOOGLE_SANS_FLEX else TwidgetStore.FONT_ONE_UI_SANS

    companion object {
        fun resolve(value: String?, oneUi: Boolean = TwidgetFonts.hasSystemOneUiSans): WidgetStyle =
            entries.firstOrNull { it.storedValue == value } ?: if (oneUi) ONE_UI else MATERIAL
    }
}

internal fun widgetUsesDarkTheme(colorMode: String): Boolean = when (colorMode) {
    TwidgetStore.COLOR_MODE_DARK -> true
    TwidgetStore.COLOR_MODE_LIGHT -> false
    else -> android.content.res.Resources.getSystem().configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
}

internal data class WidgetColors(val background: Int, val primary: Int, val secondary: Int) {
    /** Keep signed changes readable against the widget tint, including wallpaper palettes. */
    fun deltaColor(delta: Long, dark: Boolean): Int {
        val background = ColorUtils.setAlphaComponent(background, 255)
        val base = when {
            delta < 0 && dark -> Color.rgb(255, 105, 97)
            delta < 0 -> Color.rgb(190, 40, 29)
            dark -> Color.rgb(105, 220, 118)
            else -> Color.rgb(0, 128, 64)
        }
        if (ColorUtils.calculateContrast(base, background) >= 4.5) return base
        val endpoint = if (ColorUtils.calculateContrast(Color.WHITE, background) >
            ColorUtils.calculateContrast(Color.BLACK, background)) Color.WHITE else Color.BLACK
        var low = 0f
        var high = 1f
        repeat(12) {
            val fraction = (low + high) / 2f
            if (ColorUtils.calculateContrast(ColorUtils.blendARGB(base, endpoint, fraction), background) >= 4.5)
                high = fraction else low = fraction
        }
        return ColorUtils.blendARGB(base, endpoint, high)
    }

    companion object {
        fun resolve(context: Context, style: WidgetStyle, dark: Boolean, alpha: Int = 205): WidgetColors {
            if (style == WidgetStyle.ONE_UI) {
                val base = if (dark) 16 else 255
                val primary = if (dark) Color.WHITE else Color.BLACK
                return WidgetColors(Color.argb(alpha, base, base, base), primary,
                    Color.argb(204, Color.red(primary), Color.green(primary), Color.blue(primary)))
            }
            // Framework tones always follow the wallpaper, even when the app uses a custom accent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return WidgetColors(
                    context.getColor(if (dark) android.R.color.system_accent2_800 else android.R.color.system_accent2_50),
                    context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_800),
                    context.getColor(if (dark) android.R.color.system_accent2_300 else android.R.color.system_accent2_800),
                )
            }
            // Android 8–11 have no wallpaper palette API.
            return if (dark) WidgetColors(0xFF1D352C.toInt(), 0xFFA0D0BD.toInt(), 0xFF8FB5A4.toInt())
                else WidgetColors(0xFFDCF7E9.toInt(), 0xFF06513D.toInt(), 0xFF344C42.toInt())
        }

        fun resolve(context: Context, settings: TwidgetWidgetSettings, dark: Boolean) =
            resolve(context, settings.style, dark, settings.tintAlpha)
    }
}
