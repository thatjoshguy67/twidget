package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.tjg.twidget.R
import dev.oneuiproject.oneui.utils.getBoldFont
import dev.oneuiproject.oneui.utils.getLightFont
import dev.oneuiproject.oneui.utils.getRegularFont
import dev.oneuiproject.oneui.utils.getSemiBoldFont

/** Shared font families, with an independent selection for the app interface. */
object TwidgetFonts {
    /** Samsung exposes this framework field only on its One UI builds. */
    val hasSystemOneUiSans: Boolean by lazy {
        runCatching {
            Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) > 0
        }.getOrDefault(false)
    }

    private var baseTypeface: Typeface? = null
    private val weightedTypefaces = mutableMapOf<Pair<Int, Boolean>, Typeface>()
    private var googleTypeface: Typeface? = null
    private data class GoogleAxes(val weight: Int, val italic: Boolean, val width: Int, val roundness: Int)
    private val googleWeightedTypefaces = mutableMapOf<GoogleAxes, Typeface>()

    /** App typography from Figma 279:10249. Widget renderers retain their own axes. */
    @android.annotation.SuppressLint("ResourceType") // The font is a binary resource, readable as a raw stream on API 26–28.
    private fun googleAppTypeface(context: Context, weight: Int, italic: Boolean, width: Int = 100,
        roundness: Int = if (weight >= 700) 100 else 0): Typeface {
        val axes = GoogleAxes(weight.coerceIn(1, 1000), italic, width, roundness)
        return googleWeightedTypefaces.getOrPut(axes) {
            val variations = "'wght' ${axes.weight}, 'wdth' $width, 'ROND' $roundness, 'GRAD' 0, 'slnt' ${if (italic) -10 else 0}, 'opsz' 18"
            if (Build.VERSION.SDK_INT >= 29) {
                // Set outlines AND font metadata together, so Android neither loses
                // the weight on rebinding nor adds synthetic bold to variable bold.
                val slant = if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
                    else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT
                val face = android.graphics.fonts.Font.Builder(context.resources, R.font.google_sans_flex)
                    .setFontVariationSettings(variations).setWeight(axes.weight).setSlant(slant).build()
                Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(face).build())
                    .setStyle(android.graphics.fonts.FontStyle(axes.weight, slant))
                    .setSystemFallback("sans-serif").build()
            } else {
                // Android 8–9 lack the public resource Font builder. Use a cached
                // copy of the bundled face with the same explicit axes and metadata.
                val file = java.io.File(context.cacheDir, "google-sans-flex-${com.tjg.twidget.BuildConfig.VERSION_CODE}.ttf")
                if (!file.exists()) context.resources.openRawResource(R.font.google_sans_flex).use { source ->
                    file.outputStream().use { source.copyTo(it) }
                }
                Typeface.Builder(file).setFontVariationSettings(variations)
                    .setWeight(axes.weight).setItalic(italic).build()
            }
        }
    }

    enum class Role { LABEL, SUMMARY }

    fun setRole(view: TextView, role: Role) {
        view.setTag(R.id.app_font_role, role)
        applyTo(view)
    }

    private data class TextBaseline(
        val weight: Int, val italic: Boolean, val size: Float,
        var appliedFace: Typeface? = null, var appliedSize: Float = size,
    )

    fun forApp(context: Context, weight: Int = 400, italic: Boolean = false): Typeface =
        forApp(context, AppAppearance.font(context), weight, italic)

    private fun forApp(context: Context, font: AppAppearance.Font, weight: Int, italic: Boolean): Typeface =
        when (font) {
            AppAppearance.Font.DEFAULT -> oneUiSans(context, weight, italic)
            AppAppearance.Font.SYSTEM -> system(weight, italic)
            AppAppearance.Font.GOOGLE_SANS_FLEX -> googleAppTypeface(context, weight, italic)
        }

    /** Uses the device's default UI family while retaining the requested text styling. */
    fun system(weight: Int = 400, italic: Boolean = false): Typeface {
        val safeWeight = weight.coerceIn(1, 1_000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, safeWeight, italic)
        } else {
            val style = when {
                safeWeight >= 600 && italic -> Typeface.BOLD_ITALIC
                safeWeight >= 600 -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(Typeface.DEFAULT, style)
        }
    }

    fun oneUiSans(context: Context, weight: Int = 400, italic: Boolean = false): Typeface {
        val key = weight.coerceIn(1, 1_000) to italic
        return weightedTypefaces.getOrPut(key) {
            if (hasSystemOneUiSans) {
                val seslTypeface = when {
                    key.first >= 700 -> getBoldFont()
                    key.first >= 600 -> getSemiBoldFont()
                    key.first >= 400 -> getRegularFont()
                    else -> getLightFont()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(seslTypeface, key.first, italic)
                } else if (italic) {
                    Typeface.create(seslTypeface, Typeface.ITALIC)
                } else {
                    seslTypeface
                }
            } else {
                val base = baseTypeface ?: (ResourcesCompat.getFont(context, R.font.one_ui_sans)
                    ?: Typeface.DEFAULT).also { baseTypeface = it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Typeface.create(base, key.first, italic)
                } else {
                    val style = when {
                        key.first >= 700 && italic -> Typeface.BOLD_ITALIC
                        key.first >= 700 -> Typeface.BOLD
                        italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    }
                    Typeface.create(base, style)
                }
            }
        }
    }

    /**
     * Returns the untouched variable face for Canvas artwork. Widget renderers apply `wght` once on Paint;
     * creating a weighted Typeface first can make some Android renderers embolden the same axis twice.
     */
    fun oneUiSansVariable(context: Context): Typeface =
        baseTypeface ?: (ResourcesCompat.getFont(context, R.font.one_ui_sans)
            ?: Typeface.DEFAULT).also { baseTypeface = it }

    /** Returns the untouched Google Sans Flex variable face for Canvas artwork. */
    fun googleSansFlex(context: Context): Typeface =
        googleTypeface ?: (ResourcesCompat.getFont(context, R.font.google_sans_flex)
            ?: Typeface.DEFAULT).also { googleTypeface = it }

    /** Watch each window once, including dialog and popup windows inflated by AppCompat. */
    fun observeWindow(root: View) {
        if (root.getTag(R.id.app_font_observer) != null) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { applyTo(root) }
        root.setTag(R.id.app_font_observer, listener)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                view.setTag(R.id.app_font_observer, null)
                view.removeOnAttachStateChangeListener(this)
            }
        })
        applyTo(root)
    }

    fun applyTo(view: View) = applyTo(view, AppAppearance.font(view.context))

    private fun applyTo(view: View, font: AppAppearance.Font) {
        if (view is TextView) {
            val current = view.typeface ?: Typeface.DEFAULT
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            val isExpandedHeader = name == "collapsing_appbar_extended_title"
            val stored = view.getTag(R.id.app_font_baseline) as? TextBaseline
            val baseline = if (stored != null && current === stored.appliedFace && view.textSize == stored.appliedSize) stored
            else TextBaseline(
                if (isExpandedHeader && !hasSystemOneUiSans) 700
                else if (Build.VERSION.SDK_INT >= 28) current.weight else if (current.isBold) 700 else 400,
                current.isItalic, view.textSize,
            ).also { view.setTag(R.id.app_font_baseline, it) }
            val section = view.tag == "preferencecategory" || view is dev.oneuiproject.oneui.widget.Separator
            val heading = isExpandedHeader || view.parent is androidx.appcompat.widget.Toolbar || name == "alertTitle"
            val label = view.getTag(R.id.app_font_role) == Role.LABEL || view.id == android.R.id.title || name in setOf("cardview_title", "titleView", "icon_title", "title", "opacity_label", "switch_card_title")
            val summary = view.getTag(R.id.app_font_role) == Role.SUMMARY || view.id == android.R.id.summary || name in setOf("cardview_summary", "sub_title")
            val google = font == AppAppearance.Font.GOOGLE_SANS_FLEX
            val weight = if (!google) baseline.weight else when {
                section || heading -> 700
                label -> 500
                summary -> 400
                else -> baseline.weight
            }
            val desired = if (google && section) googleAppTypeface(view.context, 700, baseline.italic, 60, 100)
                else forApp(view.context, font, weight, baseline.italic)
            // Retain the size already resolved by TextView/SESL. Recomputing it
            // from scaledDensity overrides per-view sizes and Android's nonlinear
            // accessibility font scaling when the font family changes.
            if (current !== desired) view.typeface = desired
            baseline.appliedFace = view.typeface
            baseline.appliedSize = view.textSize
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTo(view.getChildAt(index), font)
        }
    }
}
