package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefOpenAiTest {
    @Test fun acceptsOnlyCompletedStreams() {
        assertEquals("Hello", BriefOpenAi.parseStream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\r\n\r\ndata: {\"type\":\"response.completed\"}\r\n\r\n"))
    }
    @Test(expected = BriefReviewException::class) fun interruptedStreamCannotBecomeVisibleCopy() {
        BriefOpenAi.parseStream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n")
    }
    @Test(expected = BriefReviewException::class) fun usageFailureAfterTextInvalidatesTheGeneration() {
        BriefOpenAi.parseStream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")
    }
    @Test(expected = BriefReviewException::class) fun incompleteResponseCannotBecomeVisibleCopy() {
        BriefOpenAi.parseStream("data: {\"type\":\"response.incomplete\"}\n\n")
    }
}
