package com.tjg.twidget.ui

import android.app.Activity
import android.content.Intent
import com.tjg.twidget.main.OnboardingActivity
import dev.oneuiproject.oneui.ktx.startPopOverActivity
import dev.oneuiproject.oneui.popover.PopOverOptions

fun Activity.startLeftSidePopOverActivity(intent: Intent) {
    startSidePopOverActivity(intent, PopOverSide.LEFT)
}

fun Activity.startRightSidePopOverActivity(intent: Intent) {
    startSidePopOverActivity(intent, PopOverSide.RIGHT)
}

private fun Activity.startSidePopOverActivity(intent: Intent, side: PopOverSide) {
    // Keep the preferred side even while the target is full-screen so a live
    // fold/unfold transition can recreate it on the intended side.
    intent.putExtra(FoldablePopOverActivity.EXTRA_POPOVER_SIDE, side.name)
    if (resources.configuration.smallestScreenWidthDp >= FoldablePopOverActivity.MIN_POPOVER_WIDTH_DP) {
        intent.putExtra(FoldablePopOverActivity.EXTRA_POPOVER, true)
        startPopOverActivity(
            intent,
            when (side) {
                PopOverSide.LEFT -> PopOverOptions.centerLeftAnchored(this)
                PopOverSide.RIGHT -> PopOverOptions.centerRightAnchored(this)
            },
        )
    } else {
        intent.removeExtra(FoldablePopOverActivity.EXTRA_POPOVER)
        startActivity(intent)
    }
}

enum class PopOverSide {
    LEFT,
    RIGHT,
}

fun Activity.startSettingsSubActivity(intent: Intent) {
    startLeftSidePopOverActivity(intent)
}

fun Activity.startAddAccountActivity() {
    startLeftSidePopOverActivity(
        Intent(this, OnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)
    )
}
