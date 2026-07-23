package com.tjg.twidget.core

import android.app.Application
import androidx.work.Configuration

/**
 * Supplies WorkManager's configuration on demand. The manifest removes its
 * eager AndroidX Startup initializer so dashboard launches do not pay for the
 * worker database before the first frame; the first worker access initializes
 * it after the launch skeleton is already visible.
 */
class TwidgetApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
