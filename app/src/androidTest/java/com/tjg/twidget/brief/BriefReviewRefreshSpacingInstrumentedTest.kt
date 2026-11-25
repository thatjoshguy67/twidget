package com.tjg.twidget.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BriefReviewRefreshSpacingInstrumentedTest {
    @Test fun previousReviewStartsBelowViewportAndRemainsScrollable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var screen: BriefReviewScreen? = null
        lateinit var scroll: NestedScrollView
        val ready = java.util.concurrent.CountDownLatch(1)
        var geometry = "No pre-draw received"
        var drawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
        fun texts(view: View): List<TextView> = (if(view is TextView) listOf(view) else emptyList()) +
            if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
        fun loading(view: View): BriefReviewLoadingLayout? = if(view is BriefReviewLoadingLayout) view else
            if(view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { loading(view.getChildAt(it)) } else null
        ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            try {
                scenario.onActivity { activity ->
                    val now = System.currentTimeMillis()
                    val post = PostSummary("https://x.com/example/status/100", "A useful new feature.",100,1,1,0,0,2,now-60000,"","Example","example","",fullText="A useful new feature.")
                    val input = BriefCoachInput("published",post.fullText!!,true,0,BriefCoachReport(emptyList(),emptyList(),emptyList(),emptyList()),emptyList(),now,"en")
                    val result = requireNotNull(BriefCoachAi.parse("""{"possibleExplanation":"Your post could identify its audience more clearly.","suggestions":[{"action":"Explain who the feature helps.","reason":"The audience is unstated.","evidenceIds":["subject"]}]}""",input.packet(cloud=true)))
                    screen = BriefReviewScreen(activity,BriefReviewCallbacks({}, {}, {}, {}))
                    scroll = NestedScrollView(activity).apply { isFillViewport=true; addView(screen!!.view) }
                    activity.setContentView(scroll)
                    screen!!.render(BriefReviewState(BriefReviewSubject(BriefReviewTarget("example",postUrl=post.url),input,post=post),BriefReviewPhase.GENERATING,BriefCoachGeneration.Success(result,true)))
                    drawListener = android.view.ViewTreeObserver.OnPreDrawListener {
                        val older = texts(screen!!.view).firstOrNull { it.text.toString().contains("Your post could identify its audience more clearly.") }
                        val a=IntArray(2); val b=IntArray(2)
                        older?.getLocationInWindow(a); scroll.getLocationInWindow(b)
                        geometry = "old=${a[1]}, viewport=${b[1]}+${scroll.height}, scroll=${scroll.scrollY}, loading=${loading(screen!!.view)?.height}, minimum=${loading(screen!!.view)?.minimumHeight}, focus=${scroll.hasWindowFocus()}"
                        if (older != null && scroll.height > 0 && a[1]>=b[1]+scroll.height) ready.countDown()
                        true
                    }
                    scroll.viewTreeObserver.addOnPreDrawListener(drawListener)

                }
                val settled = ready.await(3, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("Refresh layout settles: $geometry", settled)
                instrumentation.waitForIdleSync()
                var initialHeight = 0
                scenario.onActivity { activity ->
                    scroll.viewTreeObserver.removeOnPreDrawListener(drawListener)
                    val root = screen!!.view
                    assertFalse(texts(root).any { it.text.toString().contains(activity.getString(com.tjg.twidget.R.string.coach_ai_cloud_note)) })
                    val older = texts(root).first { it.text.toString().contains("Your post could identify its audience more clearly.") }
                    val previousLocation=IntArray(2); val viewportLocation=IntArray(2)
                    older.getLocationInWindow(previousLocation); scroll.getLocationInWindow(viewportLocation)
                    assertTrue("Previous review is below viewport: old=${previousLocation[1]}, viewport=${viewportLocation[1]}+${scroll.height}, scroll=${scroll.scrollY}, loading=${loading(root)?.height}, minimum=${loading(root)?.minimumHeight}", previousLocation[1]>=viewportLocation[1]+scroll.height)
                    initialHeight=requireNotNull(loading(root)).minimumHeight
                    val bitmap=Bitmap.createBitmap(scroll.width,scroll.height,Bitmap.Config.ARGB_8888)
                    scroll.draw(Canvas(bitmap))
                    File(activity.filesDir,"review-refresh-spacing.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    scroll.scrollTo(0,previousLocation[1]-viewportLocation[1])
                }
                instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(150); instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(scroll.scrollY>0)
                    val older = texts(screen!!.view).first { it.text.toString().contains("Your post could identify its audience more clearly.") }
                    assertTrue("Previous review is reachable by scrolling", older.getGlobalVisibleRect(android.graphics.Rect()))
                    assertEquals("Scrolling does not grow the spacer",initialHeight,requireNotNull(loading(screen!!.view)).minimumHeight)
                }
            } finally { scenario.onActivity { screen?.dispose() } }
        }
    }
}
