package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefBackgroundMaterialInstrumentedTest {
    @Test fun dayPartsHaveDistinctPalettesAndTheSameMaterialBrightensContinuously() {
        val grid = Bitmap.createBitmap(1_000, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grid)
        try {
            for ((row, dark) in listOf(false, true).withIndex()) {
                val averages = mutableListOf<DoubleArray>()
                BriefBlueTheme.entries.forEachIndexed { index, theme ->
                    val material = BriefBlueBackground(theme, dark).apply { setBounds(0, 0, 200, 400); setMotionProgress(.25f) }
                    fun frame(progress: Float) = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888).also {
                        material.setUnlockProgress(progress); material.draw(Canvas(it))
                    }
                    val locked = frame(0f)
                    val middle = frame(.5f)
                    val open = frame(1f)
                    try {
                        fun light(image: Bitmap) = ColorUtils.calculateLuminance(image.getPixel(100, 200))
                        assertTrue("Unlock has a real intermediate tone: $theme/$dark", light(locked) < light(middle) && light(middle) < light(open))
                        val pixels = IntArray(80_000)
                        open.getPixels(pixels, 0, 200, 0, 0, 200, 400)
                        averages += doubleArrayOf(pixels.sumOf { Color.red(it).toDouble() } / pixels.size,
                            pixels.sumOf { Color.green(it).toDouble() } / pixels.size,
                            pixels.sumOf { Color.blue(it).toDouble() } / pixels.size)
                        canvas.drawBitmap(open, index * 200f, row * 400f, null)
                        canvas.drawText(theme.name, index * 200f + 12, row * 400f + 24,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = 18f })
                    } finally { locked.recycle(); middle.recycle(); open.recycle() }
                }
                for (a in averages.indices) for (b in a + 1 until averages.size) {
                    assertTrue("Day parts must differ in colour, not only glow position: $a/$b/$dark ${averages[a].toList()} ${averages[b].toList()}",
                        (0..2).maxOf { kotlin.math.abs(averages[a][it] - averages[b][it]) } > 10)
                }
            }
        } finally {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.getExternalFilesDir(null), "brief-day-part-palettes.png").outputStream().use {
                grid.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            grid.recycle()
        }
    }
}
