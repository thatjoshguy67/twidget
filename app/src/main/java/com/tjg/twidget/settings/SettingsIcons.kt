package com.tjg.twidget.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.util.SeslMisc
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.tjg.twidget.R
import com.tjg.twidget.ui.AppPaletteManager
import com.tjg.twidget.ui.AppPaletteMode

internal object SettingsIcons {
    // This pinned SESL API reads Samsung's palette-enabled setting without hidden APIs.
    @SuppressLint("RestrictedApi")
    fun load(context: Context, iconRes: Int): Drawable {
        val usePalette = AppPaletteManager.mode(context) != AppPaletteMode.SYSTEM ||
            SeslMisc.isColorPaletteApplied(context)
        return load(context, iconRes, if (usePalette) context.getColor(R.color.oneui_accent) else null)
    }

    /** Tint the background and glyph separately; tinting the whole icon hides its glyph. */
    internal fun load(context: Context, iconRes: Int, accent: Int?): Drawable {
        val about = iconRes == R.drawable.settings_icon_about
        if (about && accent == null) {
            return RoundedBitmapDrawableFactory.create(context.resources,
                BitmapFactory.decodeResource(context.resources, R.drawable.settings_about_twidget))
                .apply { isCircular = true }
        }
        val drawable = context.getDrawable(
            if (about) R.drawable.settings_icon_about_palette else iconRes
        )!!.mutate()
        if (accent != null) {
            val layers = drawable as LayerDrawable
            (layers.getDrawable(0) as GradientDrawable).setColor(accent)
            val foreground = if (ColorUtils.calculateContrast(Color.WHITE, accent) >=
                ColorUtils.calculateContrast(Color.BLACK, accent)) Color.WHITE else Color.BLACK
            layers.getDrawable(1).setTint(foreground)
        }
        return drawable
    }
}
