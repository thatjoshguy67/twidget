package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exports XML fallback layers from the live renderers. See docs/widget-previews.md. */
@RunWith(AndroidJUnit4::class)
class WidgetPreviewAssetsInstrumentedTest {
    @Test fun exportPickerArtwork() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            densityDpi = 480
            fontScale = 1f
            setLocale(Locale.ENGLISH)
        })
        for (dark in listOf(false, true)) {
            val directory = File(base.cacheDir, if (dark) "picker-assets-night" else "picker-assets").apply { mkdirs() }
            for (style in WidgetStyle.entries) for (brief in listOf(false, true)) {
                val settings = WidgetPreviews.settings(style).copy(language = "en")
                val colors = WidgetColors.resolve(context, settings, dark)
                for ((index, size) in WidgetPreviews.sizes.withIndex()) {
                    val (w, h) = size
                    val prefix = "picker_${style.storedValue}_${if (brief) "brief" else "followers"}_${listOf("strip", "row", "square", "large")[index]}"
                    val foreground = WidgetPreviews.artwork(context, settings, brief, w, h, dark, background = false)
                    val width = foreground.width
                    val height = foreground.height
                    val pixels = IntArray(width * height)
                    foreground.getPixels(pixels, 0, width, 0, 0, width, height)
                    val layers = List(3) { IntArray(pixels.size) }
                    fun distance(a: Int, b: Int) = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)))
                    pixels.forEachIndexed { i, pixel ->
                        if (Color.alpha(pixel) != 0) {
                            val p = distance(pixel, colors.primary)
                            val s = distance(pixel, colors.secondary)
                            val layer = if (minOf(p, s) > 8) 2 else if (p <= s) 0 else 1
                            layers[layer][i] = if (layer == 2) pixel else Color.argb(Color.alpha(pixel), 255, 255, 255)
                        }
                    }
                    fun save(bitmap: Bitmap, suffix: String) {
                        File(directory, "$prefix$suffix.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    layers.forEachIndexed { i, pixels ->
                        save(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
                            listOf("_primary", "_secondary", "_decoration")[i])
                    }
                    val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(background)
                    if (brief) {
                        val radius = (if (style == WidgetStyle.MATERIAL || h > 110) 26f else h / 2f) * 3
                        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.background })
                    } else WidgetArtworkRenderer.drawWidgetBackground(context, canvas, width, height, settings, dark)
                    background.getPixels(pixels, 0, width, 0, 0, width, height)
                    pixels.indices.forEach { i -> pixels[i] = Color.argb(Color.alpha(pixels[i]), 255, 255, 255) }
                    background.setPixels(pixels, 0, width, 0, 0, width, height)
                    save(background, "_background")
                    assertTrue("Title has visible content", layers[0].any { Color.alpha(it) > 0 })
                    foreground.recycle()
                }
            }
        }
    }
}
