package com.tjg.twidget.brief

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefOptionalQuoteInstrumentedTest {
    @Test fun wholePostAdviceOmitsExcerptAndSpecificWordingStillShowsIt() {
        ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val post = PostSummary("https://x.com/example/status/100", "A useful new feature.", 100, 1, 1, 0, 0, 2,
                    System.currentTimeMillis() - 60_000, "", "Example", "example", "", fullText = "A useful new feature.")
                val conversation = BriefReviewConversations.collect(post, emptyList())
                val input = conversation.apply(BriefCoachInput("published", post.fullText!!, true, 0,
                    BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), System.currentTimeMillis(), "en"))
                val packet = input.packet(cloud = true)
                val subject = BriefReviewSubject(BriefReviewTarget("example", postUrl = post.url), input, post = post)
                val raw = """{"possibleExplanation":"Your post could identify its audience more clearly.","suggestions":[{"action":"Explain who the feature helps.","reason":"Your wording leaves the intended audience unstated.","evidenceIds":["subject"]}]}"""
                for (specific in listOf(false, true)) {
                    val json = org.json.JSONObject(raw)
                    if (specific) json.getJSONArray("suggestions").getJSONObject(0).put("quoteId", "q0")
                    val parsed = BriefCoachAi.parse(json.toString(), packet)!!
                    val screen = BriefReviewScreen(activity, BriefReviewCallbacks({}, {}, {}, {}))
                    try {
                        screen.render(BriefReviewState(subject, BriefReviewPhase.COMPLETE, BriefCoachGeneration.Success(parsed, true)))
                        activity.setContentView(screen.view)
                        val copy = texts(screen.view).toList()
                        assertTrue(copy.contains("Explain who the feature helps."))
                        assertEquals(specific, copy.contains("“A useful new feature.”"))
                        assertFalse(copy.contains(activity.getString(com.tjg.twidget.R.string.review_replies_unavailable)))
                        assertFalse(copy.contains(activity.getString(com.tjg.twidget.R.string.review_replies_empty)))
                    } finally { screen.dispose() }
                }
                assertFalse(activity.getString(com.tjg.twidget.R.string.review_add_key).contains("Gemini"))
            }
        }
    }
    private fun texts(view: View): Sequence<String> = sequence {
        if (view is TextView) yield(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(texts(view.getChildAt(i)))
    }
}
