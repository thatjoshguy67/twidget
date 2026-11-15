package com.tjg.twidget.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Keep an external tweet above its originating Brief, including nested popovers. */
internal class TweetPopOverLauncher(private val activity: ComponentActivity) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun open(url: String) {
        fun launch(intent: Intent) {
            if (supportsPopOver(activity.resources.configuration.smallestScreenWidthDp,
                    activity.intent.getBooleanExtra(FoldablePopOverActivity.EXTRA_POPOVER, false))) {
                TweetPopOverOptions.launch(activity, intent, launcher)
            } else launcher.launch(intent)
        }
        try { launch(tweetIntent(activity, url)) }
        catch (_: ActivityNotFoundException) { launch(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: SecurityException) { launch(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        internal fun supportsPopOver(smallestWidth: Int, fromPopOver: Boolean) =
            fromPopOver || smallestWidth >= FoldablePopOverActivity.MIN_POPOVER_WIDTH_DP

        private fun tweetIntent(context: Context, url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.twitter.android")
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://${context.packageName}"))
            putExtra("com.x.deeplink.EXTRA_INTERNAL_DEEPLINK", true)
        }

        fun open(context: Context, url: String) {
            var owner = context
            while (owner is ContextWrapper && owner !is Activity) {
                val base = owner.baseContext
                if (base === owner) break
                owner = base
            }
            if (owner is FoldablePopOverActivity) {
                owner.tweetPopOverLauncher.open(url)
                return
            }
            // Non-activity callers retain ordinary link handling.
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (owner !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val tweet = tweetIntent(owner, url).apply { flags = fallback.flags }
            try { owner.startActivity(tweet) }
            catch (_: ActivityNotFoundException) { owner.startActivity(fallback) }
            catch (_: SecurityException) { owner.startActivity(fallback) }
        }
    }
}
