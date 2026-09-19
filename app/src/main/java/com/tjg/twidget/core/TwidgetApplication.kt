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
        com.tjg.twidget.ui.AppAppearance.apply(this)
        AppPaletteManager.reconcile(this)
        com.tjg.twidget.social.LegacySocialBridge.initialize(this)
        AppExecutors.execute {
            runCatching { com.tjg.twidget.followers.TopFollowersLocalScanCleanup.run(this) }
        }
        if (AppPaletteManager.consumePendingWidgetRefresh(this)) {
            TwidgetWidget.updateAll(this)
            TwidgetBriefWidget.updateAll(this)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
