package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BriefViewsRankInstrumentedTest {
    @Test fun rankingUsesAvailableEligiblePosts() = renderRanking(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    @Test fun rankingAndOutlinesWorkInLightMode() = renderRanking(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
    private fun renderRanking(mode: Int) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        val previousOutline = com.tjg.twidget.ui.AppAppearance.outlineEffects(context)
        instrumentation.runOnMainSync {
            com.tjg.twidget.ui.AppAppearance.setMode(context, mode)
            com.tjg.twidget.ui.AppAppearance.setOutlineEffects(context, true)
        }
        instrumentation.waitForIdleSync()
        try {
        ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val now = System.currentTimeMillis()
                val post = PostSummary("https://x.com/example/status/100", "A useful new feature.", 100,1,1,0,0,2,
                    now-60_000,"","Example","example","",fullText="A useful new feature.")
                val peers = (1..9).map { post.copy(url="https://x.com/example/status/$it", views=it*10L, timestamp=now-it*60000) }
                for (count in listOf(0,2,8,9)) {
                    val evidence = BriefReviewEvidence(post, peers.take(count), emptyList(), now)
                    val input = BriefCoachInput("published",post.fullText!!,true,0,
                        BriefCoachReport(emptyList(),emptyList(),emptyList(),emptyList()),emptyList(),now,"en",reviewEvidence=evidence)
                    val result = requireNotNull(BriefCoachAi.parse("""{"possibleExplanation":"Your post could identify its audience more clearly.","suggestions":[{"action":"Explain who the feature helps.","reason":"The audience is unstated.","evidenceIds":["subject"]}]}""",input.packet(cloud=true)))
                    val screen = BriefReviewScreen(activity,BriefReviewCallbacks({}, {}, {}, {}))
                    try {
                        screen.render(BriefReviewState(BriefReviewSubject(BriefReviewTarget("example",postUrl=post.url),input,post=post),BriefReviewPhase.COMPLETE,BriefCoachGeneration.Success(result,true)))
                        fun texts(view: View): List<TextView> = (if(view is TextView) listOf(view) else emptyList()) +
                            if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
                        val ranking = texts(screen.view).firstOrNull { it.text == activity.getString(com.tjg.twidget.R.string.review_views_rank,1,count+1) }
                        assertNotNull(ranking)
                        ranking?.let {
                            val card = it.parent as View
                            val width=(400*activity.resources.displayMetrics.density).toInt()
                            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                            card.layout(0,0,width,card.measuredHeight)
                            val bitmap=Bitmap.createBitmap(width,card.height,Bitmap.Config.ARGB_8888)
                            card.draw(Canvas(bitmap))
                            File(activity.filesDir,"tweet-review-ranking-${if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) "light" else "dark"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                            bitmap.recycle()
                            assertNotNull(card.getTag(com.tjg.twidget.R.id.sesl_surface_highlight))
                            com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, false)
                            assertNull("Brief outlines follow the global toggle", card.getTag(com.tjg.twidget.R.id.sesl_surface_highlight))
                            com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, true)
                        }
                    } finally { screen.dispose() }
                }
            }
        }
        } finally { instrumentation.runOnMainSync {
            com.tjg.twidget.ui.AppAppearance.setOutlineEffects(context, previousOutline)
            com.tjg.twidget.ui.AppAppearance.setMode(context, previousMode)
        } }
    }
}
