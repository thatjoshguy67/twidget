package com.tjg.twidget

import android.app.Activity
import android.content.Intent
import dev.oneuiproject.oneui.ktx.startPopOverActivity
import dev.oneuiproject.oneui.popover.PopOverOptions

fun Activity.startLeftSidePopOverActivity(intent: Intent) {
    if (resources.configuration.smallestScreenWidthDp >= FoldablePopOverActivity.MIN_POPOVER_WIDTH_DP) {
        intent.putExtra(FoldablePopOverActivity.EXTRA_POPOVER, true)
        startPopOverActivity(intent, PopOverOptions.centerLeftAnchored(this))
    } else {
        intent.removeExtra(FoldablePopOverActivity.EXTRA_POPOVER)
        startActivity(intent)
    }
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
