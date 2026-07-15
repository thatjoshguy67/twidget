package com.tjg.twidget.ui

import android.content.Intent
import android.content.res.Configuration

/**
 * Keeps secondary screens in the same presentation as the display changes:
 * full-screen on the folded display and a One UI pop-over on the inner display.
 */
abstract class FoldablePopOverActivity : EdgeToEdgeActivity() {
    private var isChangingPresentation = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePresentationIfNeeded()
    }

    private fun updatePresentationIfNeeded() {
        if (isChangingPresentation) return

        val shouldUsePopOver = resources.configuration.smallestScreenWidthDp >= MIN_POPOVER_WIDTH_DP
        val isPopOver = intent.getBooleanExtra(EXTRA_POPOVER, false)
        if (shouldUsePopOver == isPopOver) return

        isChangingPresentation = true
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            val restartIntent = Intent(intent)
            if (popoverSide() == PopOverSide.RIGHT) {
                startRightSidePopOverActivity(restartIntent)
            } else {
                startLeftSidePopOverActivity(restartIntent)
            }
            finish()
        }
    }

    private fun popoverSide(): PopOverSide = runCatching {
        PopOverSide.valueOf(intent.getStringExtra(EXTRA_POPOVER_SIDE) ?: PopOverSide.LEFT.name)
    }.getOrDefault(PopOverSide.LEFT)

    companion object {
        const val EXTRA_POPOVER = "com.tjg.twidget.extra.POPOVER"
        const val EXTRA_POPOVER_SIDE = "com.tjg.twidget.extra.POPOVER_SIDE"
        const val MIN_POPOVER_WIDTH_DP = 600
    }
}
