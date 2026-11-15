package com.tjg.twidget.brief

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.tjg.twidget.settings.BriefSettingsActivity

/** Navigation only; this deep link never accepts codes, tokens or account changes. */
class BriefOAuthReturnActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = if (BriefChatGptActivity.awaitingReturn) BriefChatGptActivity::class.java else BriefSettingsActivity::class.java
        startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
