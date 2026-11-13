package com.tjg.twidget.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.tjg.twidget.ui.FoldablePopOverActivity

/** Open X above this dashboard instead of moving to its existing launcher task. */
internal class DashboardTweetLauncher(private val activity: MainActivity) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun open(url: String) {
        val intent = tweetIntent(activity.packageName, url)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            // Retain normal web-link handling on devices without X installed.
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun launch(intent: Intent) {
        if (activity.resources.configuration.smallestScreenWidthDp >= FoldablePopOverActivity.MIN_POPOVER_WIDTH_DP) {
            DashboardPopOverLauncher.launch(activity, intent, launcher)
        } else {
            launcher.launch(intent)
        }
    }

    companion object {
        internal fun tweetIntent(callerPackage: String, url: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.twitter.android")
                addCategory(Intent.CATEGORY_BROWSABLE)
                putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://$callerPackage"))
                // X otherwise injects external post links into Home, replacing the caller's task.
                // Its post-detail router accepts this hint to open the actual post destination.
                putExtra("com.x.deeplink.EXTRA_INTERNAL_DEEPLINK", true)
            }
    }
}
