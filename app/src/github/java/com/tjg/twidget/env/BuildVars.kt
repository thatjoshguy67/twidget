package com.tjg.twidget.env

object BuildVars {
    val DISTRIBUTION: Distribution = Distribution.GITHUB

    /** Compile-time constant, so R8 strips the APK updater when this is false. */
    const val IN_APP_UPDATES: Boolean = true
}
