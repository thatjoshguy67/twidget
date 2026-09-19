package com.tjg.twidget.social

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The non-exported flow validates state and proof before accepting a callback. */
class SocialOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme == "twidget" && uri.host == "oauth" && uri.path in setOf("/github", "/instagram")) {
            startActivity(Intent(this, SocialOnboardingActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
