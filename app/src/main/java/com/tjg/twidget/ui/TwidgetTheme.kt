package com.tjg.twidget.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import dev.oneuiproject.oneui.design.R as OneUiDesignR

/**
 * App-wide theme mode and accent palettes aligned with
 * [TJG-Website ThemeProvider](https://github.com/thatjoshguy67/TJG-Website).
 *
 * Accent themes in [values/themes_accent.xml] parent [Theme.Twidget] and are applied
 * via [applyActivityTheme]. Accent or light/dark changes bump [themeGeneration] and
 * trigger [android.app.Activity.recreate] on every activity in the back stack.
 */
object TwidgetTheme {
    const val ACCENT_BLUE = "blue"
    const val ACCENT_CORAL = "coral"
    const val ACCENT_MINT = "mint"
    const val ACCENT_LILAC = "lilac"
    const val ACCENT_MONO = "mono"

    val ACCENT_OPTIONS = listOf(
        ACCENT_BLUE,
        ACCENT_CORAL,
        ACCENT_MINT,
        ACCENT_LILAC,
        ACCENT_MONO,
    )

    val THEME_MODES = listOf(
        TwidgetStore.COLOR_MODE_SYSTEM,
        TwidgetStore.COLOR_MODE_LIGHT,
        TwidgetStore.COLOR_MODE_DARK,
    )

    data class Palette(
        @ColorInt val accent: Int,
        @ColorInt val accentTranslucent: Int,
        @ColorInt val background: Int,
        @ColorInt val cardBackground: Int,
        @ColorInt val drawerBackground: Int,
        @ColorInt val drawerSelectedBackground: Int,
        @ColorInt val divider: Int,
        @ColorInt val textPrimary: Int,
        @ColorInt val textSecondary: Int,
    )

    /** Accent hex values shared with the website design tokens. */
    val ACCENT_HEX = mapOf(
        ACCENT_BLUE to 0xFF387AFF.toInt(),
        ACCENT_CORAL to 0xFFFF6B6B.toInt(),
        ACCENT_MINT to 0xFF4ECDC4.toInt(),
        ACCENT_LILAC to 0xFFA78BFA.toInt(),
        ACCENT_MONO to 0xFF808080.toInt(),
    )

    private val lightPalettes = mapOf(
        ACCENT_BLUE to Palette(0xFF387AFF.toInt(), 0x22387AFF, 0xFFF1F1F3.toInt(), 0xFFFCFCFF.toInt(), 0xFFFCFCFF.toInt(), 0x0D010102, 0xFFE4E4E6.toInt(), 0xFF000000.toInt(), 0xFF848487.toInt()),
        ACCENT_CORAL to Palette(0xFFFF6B6B.toInt(), 0x22FF6B6B, 0xFFF2E5E6.toInt(), 0xFFFFFAFA.toInt(), 0xFFFFFAFA.toInt(), 0x0D010102, 0xFFE4E4E6.toInt(), 0xFF000000.toInt(), 0xFF848487.toInt()),
        ACCENT_MINT to Palette(0xFF4ECDC4.toInt(), 0x224ECDC4, 0xFFD0EAEA.toInt(), 0xFFEBFFFD.toInt(), 0xFFEBFFFD.toInt(), 0x0D010102, 0xFFE4E4E6.toInt(), 0xFF000000.toInt(), 0xFF848487.toInt()),
        ACCENT_LILAC to Palette(0xFFA78BFA.toInt(), 0x22A78BFA, 0xFFEAE5F2.toInt(), 0xFFFCF8FF.toInt(), 0xFFFCF8FF.toInt(), 0x0D010102, 0xFFE4E4E6.toInt(), 0xFF000000.toInt(), 0xFF848487.toInt()),
        ACCENT_MONO to Palette(0xFF808080.toInt(), 0x22808080, 0xFFF1F1F3.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x0D010102, 0xFFE4E4E6.toInt(), 0xFF000000.toInt(), 0xFF848487.toInt()),
    )

    private val darkPalettes = mapOf(
        ACCENT_BLUE to Palette(0xFF387AFF.toInt(), 0x33387AFF, 0xFF010102.toInt(), 0xFF17171A.toInt(), 0xFF17171A.toInt(), 0x14FFFFFF, 0xFF3A3A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3A3A7.toInt()),
        ACCENT_CORAL to Palette(0xFFFF6B6B.toInt(), 0x33FF6B6B, 0xFF1A0F0F.toInt(), 0xFF251A1A.toInt(), 0xFF251A1A.toInt(), 0x14FFFFFF, 0xFF3A3A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3A3A7.toInt()),
        ACCENT_MINT to Palette(0xFF4ECDC4.toInt(), 0x334ECDC4, 0xFF0A1414.toInt(), 0xFF1A2525.toInt(), 0xFF1A2525.toInt(), 0x14FFFFFF, 0xFF3A3A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3A3A7.toInt()),
        ACCENT_LILAC to Palette(0xFFA78BFA.toInt(), 0x33A78BFA, 0xFF120F1A.toInt(), 0xFF1F1A25.toInt(), 0xFF1F1A25.toInt(), 0x14FFFFFF, 0xFF3A3A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3A3A7.toInt()),
        ACCENT_MONO to Palette(0xFF808080.toInt(), 0x33808080, 0xFF000000.toInt(), 0xFF17171A.toInt(), 0xFF17171A.toInt(), 0x14FFFFFF, 0xFF3A3A3D.toInt(), 0xFFFFFFFF.toInt(), 0xFFA3A3A7.toInt()),
    )

    @Volatile
    var themeGeneration: Int = 0
        private set

    fun applyToApplication(context: Context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(resolvedThemeMode(context)))
    }

    fun publishChange(context: Context) {
        applyToApplication(context)
        themeGeneration++
        TwidgetThemeChanges.notifyChanged(context)
    }

    /** System default, or locked manual light/dark when System default is off. */
    fun resolvedThemeMode(context: Context): String {
        if (TwidgetStore.themeSystemDefault(context)) {
            return TwidgetStore.COLOR_MODE_SYSTEM
        }
        return TwidgetStore.themeManualMode(context)
    }

    /**
     * Applies night mode + accent theme in one shot (no overlay stacking).
     * Must run before [android.app.Activity.setContentView].
     *
     * Samsung OneUI often ignores [AppCompatDelegate.setDefaultNightMode] alone
     * for the current activity; [AppCompatDelegate.localNightMode] forces it.
     */
    fun applyActivityTheme(activity: Activity) {
        val night = nightMode(resolvedThemeMode(activity))
        AppCompatDelegate.setDefaultNightMode(night)
        val appCompat = activity as? AppCompatActivity
        if (appCompat != null && appCompat.delegate.localNightMode != night) {
            // Setting this may schedule a recreate; callers must not also recreate immediately.
            appCompat.delegate.localNightMode = night
        }
        activity.setTheme(accentStyle(TwidgetStore.appAccentColor(activity)))
    }

    @StyleRes
    private fun accentStyle(accent: String): Int = when (normalizeAccent(accent)) {
        ACCENT_CORAL -> R.style.TwidgetAccentCoral
        ACCENT_MINT -> R.style.TwidgetAccentMint
        ACCENT_LILAC -> R.style.TwidgetAccentLilac
        ACCENT_MONO -> R.style.TwidgetAccentMono
        else -> R.style.TwidgetAccentBlue
    }

    fun nightMode(mode: String): Int = when (mode) {
        TwidgetStore.COLOR_MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        TwidgetStore.COLOR_MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun normalizeAccent(accent: String?): String =
        accent?.takeIf { it in ACCENT_OPTIONS } ?: ACCENT_BLUE

    fun normalizeThemeMode(mode: String?): String =
        mode?.takeIf { it in THEME_MODES } ?: TwidgetStore.COLOR_MODE_SYSTEM

    fun isDark(context: Context): Boolean {
        return when (resolvedThemeMode(context)) {
            TwidgetStore.COLOR_MODE_DARK -> true
            TwidgetStore.COLOR_MODE_LIGHT -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun palette(context: Context): Palette {
        val accent = normalizeAccent(TwidgetStore.appAccentColor(context))
        return if (isDark(context)) darkPalettes.getValue(accent) else lightPalettes.getValue(accent)
    }

    /** Updates drawer chrome and re-themes custom views after accent/theme changes. */
    fun applySurfaces(activity: Activity) {
        applyDrawerSurfaces(activity)
        rethemeCustomViews(activity.findViewById(android.R.id.content), palette(activity.applicationContext))
    }

    /** Paints the drawer panel and the status-bar gutter above it with the accent drawer color. */
    fun applyDrawerSurfaces(activity: Activity) {
        val drawerColor = palette(activity.applicationContext).drawerBackground
        activity.findViewById<View>(R.id.drawer_nav)?.setBackgroundColor(drawerColor)
        val drawerPanel = activity.findViewById<View>(OneUiDesignR.id.drawer_panel)
        drawerPanel?.setBackgroundColor(drawerColor)
        // SemDrawerLayout sits above drawer_panel; its top margin is the status-bar strip.
        (drawerPanel?.parent as? View)?.setBackgroundColor(drawerColor)
    }

    private fun rethemeCustomViews(root: View?, @Suppress("UNUSED_PARAMETER") palette: Palette) {
        if (root == null) return
        if (root is TwidgetThemeAware) {
            root.applyTwidgetTheme()
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                rethemeCustomViews(root.getChildAt(index), palette)
            }
        }
    }

    @ColorInt
    fun resolveColorResource(context: Context, @ColorRes id: Int): Int? {
        val palette = palette(context)
        return when (id) {
            R.color.oneui_accent -> palette.accent
            R.color.oneui_accent_translucent -> palette.accentTranslucent
            R.color.oneui_bg -> palette.background
            R.color.oneui_card_bg -> palette.cardBackground
            R.color.oneui_drawer_bg -> palette.drawerBackground
            R.color.oneui_drawer_selected_bg -> palette.drawerSelectedBackground
            R.color.oneui_divider -> palette.divider
            R.color.oneui_text_primary -> palette.textPrimary
            R.color.oneui_text_secondary -> palette.textSecondary
            else -> null
        }
    }

    @ColorInt
    fun color(context: Context, @AttrRes attr: Int): Int {
        val palette = palette(context)
        return when (attr) {
            android.R.attr.colorPrimary -> palette.accent
            R.attr.twidgetAccentTranslucent -> palette.accentTranslucent
            R.attr.twidgetBackground -> palette.background
            R.attr.twidgetCardBackground -> palette.cardBackground
            R.attr.twidgetDrawerBackground -> palette.drawerBackground
            R.attr.twidgetDrawerSelectedBackground -> palette.drawerSelectedBackground
            R.attr.twidgetDivider -> palette.divider
            R.attr.twidgetTextPrimary -> palette.textPrimary
            R.attr.twidgetTextSecondary -> palette.textSecondary
            else -> {
                val typed = TypedValue()
                context.theme.resolveAttribute(attr, typed, true)
                typed.data
            }
        }
    }

    @ColorInt fun accent(context: Context): Int = palette(context).accent

    @ColorInt fun accentTranslucent(context: Context): Int = palette(context).accentTranslucent

    @ColorInt fun background(context: Context): Int = palette(context).background

    @ColorInt fun cardBackground(context: Context): Int = palette(context).cardBackground

    @ColorInt fun textPrimary(context: Context): Int = palette(context).textPrimary

    @ColorInt fun textSecondary(context: Context): Int = palette(context).textSecondary

    @ColorInt fun divider(context: Context): Int = palette(context).divider

    fun accentLabel(context: Context, accent: String): String = when (normalizeAccent(accent)) {
        ACCENT_CORAL -> context.getString(R.string.accent_coral)
        ACCENT_MINT -> context.getString(R.string.accent_mint)
        ACCENT_LILAC -> context.getString(R.string.accent_lilac)
        ACCENT_MONO -> context.getString(R.string.accent_mono)
        else -> context.getString(R.string.accent_blue)
    }

    fun themeModeLabel(context: Context, mode: String): String = when (normalizeThemeMode(mode)) {
        TwidgetStore.COLOR_MODE_LIGHT -> context.getString(R.string.theme_mode_light)
        TwidgetStore.COLOR_MODE_DARK -> context.getString(R.string.theme_mode_dark)
        else -> context.getString(R.string.theme_mode_system_default)
    }
}

@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int = TwidgetTheme.color(this, attr)

@ColorInt
fun Context.oneUiAccent(): Int = TwidgetTheme.accent(this)

@ColorInt
fun Context.oneUiAccentTranslucent(): Int = TwidgetTheme.accentTranslucent(this)

@ColorInt
fun Context.oneUiBackground(): Int = TwidgetTheme.background(this)

@ColorInt
fun Context.oneUiCardBackground(): Int = TwidgetTheme.cardBackground(this)

@ColorInt
fun Context.oneUiTextPrimary(): Int = TwidgetTheme.textPrimary(this)

@ColorInt
fun Context.oneUiTextSecondary(): Int = TwidgetTheme.textSecondary(this)

@ColorInt
fun Context.oneUiDivider(): Int = TwidgetTheme.divider(this)
