package com.tjg.twidget.social

import android.os.Bundle
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.ui.FoldablePopOverActivity

/** Keep existing widget/deep-link entry points while using the original Brief screen. */
class ProfileBriefActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = intent.getStringExtra(EXTRA_PROFILE)
            ?: SocialRepository(this).use { it.catalog().defaultProfileId }
        if (profile != null) startActivity(TwidgetBriefActivity.profileIntent(this, profile))
        finish()
    }
    companion object { const val EXTRA_PROFILE = "social_profile_id" }
}
