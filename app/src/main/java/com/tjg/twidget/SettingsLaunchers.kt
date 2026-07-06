package com.tjg.twidget

import android.app.Activity
import android.content.Intent
import dev.oneuiproject.oneui.ktx.startPopOverActivity
import dev.oneuiproject.oneui.popover.PopOverOptions

fun Activity.startLeftSidePopOverActivity(intent: Intent) {
    if (resources.configuration.smallestScreenWidthDp >= 600) {
        startPopOverActivity(intent, PopOverOptions.centerLeftAnchored(this))
    } else {
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
