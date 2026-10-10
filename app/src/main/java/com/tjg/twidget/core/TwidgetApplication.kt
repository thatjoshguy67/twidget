package com.tjg.twidget.core

import android.app.Application
import androidx.work.Configuration
import com.tjg.twidget.ui.AppPaletteManager
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget

/**
 * Supplies WorkManager's configuration on demand. The manifest removes its
 * eager AndroidX Startup initializer so dashboard launches do not pay for the
 * worker database on the main thread; legacy scan cleanup initializes it on
 * the shared background executor when required.
 */
class TwidgetApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(
                { _, _ -> refreshHomeWidgets() }, android.os.Handler(mainLooper),
            )
        }
        AppLocales.initialize(this)
        com.tjg.twidget.ui.AppAppearance.apply(this)
        AppPaletteManager.reconcile(this)
        AppExecutors.execute {
            runCatching { com.tjg.twidget.followers.TopFollowersLocalScanCleanup.run(this) }
            com.tjg.twidget.widget.WidgetPreviews.publish(this)
        }
        if (AppPaletteManager.consumePendingWidgetRefresh(this)) {
            TwidgetWidget.updateAll(this)
            TwidgetBriefWidget.updateAll(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshHomeWidgets()
    }

    private fun refreshHomeWidgets() {
        AppExecutors.execute {
            TwidgetWidget.updateAll(this)
            TwidgetBriefWidget.updateAll(this)
            com.tjg.twidget.widget.WidgetPreviews.publish(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
