package com.tjg.twidget.settings

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.ui.AppPaletteManager
import com.tjg.twidget.ui.AppPaletteMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPaletteInstrumentedTest {
    @Test fun customAccentsCanChangeAndRestoreTheSystemPaletteAcrossContexts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalMode = AppPaletteManager.mode(context)
        val originalSeed = AppPaletteManager.customSeed(context)
        instrumentation.runOnMainSync {
            try {
                assertTrue(AppPaletteManager.applySelection(context, AppPaletteMode.SYSTEM).success)
                val light = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
                })
                val dark = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                })
                val systemLight = AppPaletteManager.resolvedColors(light)
                val systemDark = AppPaletteManager.resolvedColors(dark)
                for (mode in listOf(AppPaletteMode.CUSTOM, AppPaletteMode.TWIDGET_BLUE, AppPaletteMode.CUSTOM)) {
                    val result = AppPaletteManager.applySelection(context, mode, 0xFF8D40BE.toInt())
                    assertTrue(result.error, result.success)
                    for (target in listOf(light, dark)) {
                        val attached = AppPaletteManager.attachResources(target)
                        assertTrue(attached.error, attached.success)
                        val palette = AppPaletteManager.generatedPalette(target)
                        val colors = AppPaletteManager.resolvedColors(target).toMap()
                        assertEquals(palette.primaryLight, colors["SESL primary · light"])
                        assertEquals(palette.primaryDark, colors["SESL primary · dark"])
                    }
                    assertTrue(AppPaletteManager.debugState(context).customPaletteApplied)
                }
                assertTrue(AppPaletteManager.applySelection(context, AppPaletteMode.SYSTEM).success)
                assertEquals(systemLight, AppPaletteManager.resolvedColors(light))
                assertEquals(systemDark, AppPaletteManager.resolvedColors(dark))
                assertFalse(AppPaletteManager.debugState(context).customPaletteApplied)
            } finally {
                AppPaletteManager.applySelection(context, originalMode, originalSeed)
            }
        }
    }
}
