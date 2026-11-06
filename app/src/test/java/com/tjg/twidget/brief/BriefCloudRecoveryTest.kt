package com.tjg.twidget.brief

import com.tjg.twidget.core.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BriefCloudRecoveryTest {
    private val input = BriefCoachInput("published", "I shipped a comparison tool.", true, 0,
        BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), 1, "en")
    private val response = """{"possibleExplanation":"The purpose is clear.","suggestions":[{"action":"Ask about a specific use case.","reason":"That gives readers a focused question.","quoteId":"q0","evidenceIds":["subject"]}]}"""

    @Test fun transientServerFailureRetriesTheIdenticalRequestAndStillValidatesTheAnswer() = runBlocking {
        val requests = mutableListOf<String>()
        val statuses = mutableListOf<Int>()
        var waits = 0
        val result = BriefCoachAiRunner.generateWith(input, true, onTransientFailure = statuses::add,
            retryDelay = { waits++ }) { prompt, _ ->
            requests += prompt
            if (requests.size == 1) throw HttpTransport.HttpException(503, "fixture")
            response
        }
        assertTrue(result is BriefCoachGeneration.Success)
        assertEquals(2, requests.size)
        assertEquals(requests.first(), requests.last())
        assertEquals(listOf(503), statuses)
        assertEquals(1, waits)
    }

    @Test fun serviceRetryBudgetIsSharedAcrossValidationAttempts() = runBlocking {
        var calls = 0
        try {
            BriefCoachAiRunner.generateWith(input, true, retryDelay = {}) { _, _ ->
                calls++
                when (calls) {
                    1, 3 -> throw HttpTransport.HttpException(500, "fixture")
                    else -> response.replace("The purpose is clear.", "You received 99999 views.")
                }
            }
            fail("Repeated service failure must remain a service failure")
        } catch (error: HttpTransport.HttpException) {
            assertEquals(500, error.code)
            assertEquals(3, calls)
        }
    }

    @Test fun authenticationQuotaAndLocalRequestsDoNotRetry() = runBlocking {
        for ((cloud, code) in listOf(true to 400, true to 401, true to 403, true to 429, false to 503)) {
            var calls = 0
            try {
                BriefCoachAiRunner.generateWith(input, cloud, retryDelay = { fail("Unexpected delay") }) { _, _ ->
                    calls++
                    throw HttpTransport.HttpException(code, "fixture")
                }
                fail("Provider error must propagate")
            } catch (error: HttpTransport.HttpException) {
                assertEquals(code, error.code)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun cancellingTheDelayPreventsAnotherRequest() = runBlocking {
        var calls = 0
        try {
            BriefCoachAiRunner.generateWith(input, true, retryDelay = { throw CancellationException() }) { _, _ ->
                calls++
                throw HttpTransport.HttpException(503, "fixture")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }
}
