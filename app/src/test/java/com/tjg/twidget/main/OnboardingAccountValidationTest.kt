package com.tjg.twidget.main

import com.tjg.twidget.core.HttpTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAccountValidationTest {
    @Test
    fun onlyNotFoundResponsesArePresentedAsMissingAccounts() {
        assertTrue(onboardingAccountIsMissing(HttpTransport.HttpException(404, "missing")))
        assertFalse(onboardingAccountIsMissing(HttpTransport.HttpException(500, "offline")))
        assertFalse(onboardingAccountIsMissing(IllegalStateException("bad response")))
    }
}
