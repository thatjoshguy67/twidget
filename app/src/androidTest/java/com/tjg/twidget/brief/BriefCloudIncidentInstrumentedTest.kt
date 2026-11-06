package com.tjg.twidget.brief

import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.TwidgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit live regression only: uses the existing key and selected public tweet, never normal CI. */
class BriefCloudIncidentInstrumentedTest {
    @Test fun selectedPublishedTweetProducesASavedReview() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("reviewCloudLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BriefSettingsStore.provider(context) != BriefProviderMode.LOCAL)
        assumeTrue(BriefSettingsStore.cloudApiKey(context).isNotBlank())
        val id = requireNotNull(arguments.getString("reviewPostId"))
        require(id.matches(Regex("[0-9]+")))
        val account = TwidgetStore.accounts(context).first()
        val target = BriefReviewTarget(account, postUrl = "https://x.com/$account/status/$id", fromDashboard = true)
        val subject = requireNotNull(BriefReviewSubjects.load(context, target))
        val before = BriefPublishedReviewStore.read(context, target)
        val generation = TwidgetStore.accountGeneration(context, account)
        val result = withContext(Dispatchers.IO) { BriefCoachAiRunner.generate(context, subject.input, cloud = true) }
        assertTrue("Selected tweet must produce usable feedback: ${(result as? BriefCoachGeneration.Failure)?.reason}",
            result is BriefCoachGeneration.Success)
        result as BriefCoachGeneration.Success
        assertEquals(subject.input.media.take(4).indices.map { "m$it" }.toSet(), result.inspectedMediaIds)
        assertNotNull(BriefPublishedReviewStore.save(context, subject, result, generation, before?.savedAt))
    }
}
