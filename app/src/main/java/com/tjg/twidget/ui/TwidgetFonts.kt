package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.tjg.twidget.R
import dev.oneuiproject.oneui.utils.getBoldFont
import dev.oneuiproject.oneui.utils.getLightFont
import dev.oneuiproject.oneui.utils.getRegularFont
import dev.oneuiproject.oneui.utils.getSemiBoldFont

/** Uses the bundled One UI Sans variable font on devices without Samsung's `sec` family. */
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
                    Typeface.create(base, if (key.first >= 700) Typeface.BOLD else Typeface.NORMAL)
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

    fun applyTo(view: View) {
        if (view is TextView) {
            val current = view.typeface ?: Typeface.DEFAULT
            val isExpandedHeader = !hasSystemOneUiSans && runCatching {
                view.resources.getResourceEntryName(view.id) == "collapsing_appbar_extended_title"
            }.getOrDefault(false)
            val weight = if (isExpandedHeader) {
                700
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                current.weight
            } else if (current.isBold) {
                700
            } else {
                400
            }
            val desired = oneUiSans(view.context, weight, current.isItalic)
            if (current !== desired) view.typeface = desired
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTo(view.getChildAt(index))
        }
    }
}
