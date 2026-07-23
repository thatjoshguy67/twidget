package com.tjg.twidget.main

import com.tjg.twidget.core.HttpTransport
import org.junit.Assert.assertEquals
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

    @Test
    fun historySharingChoiceIsOnlyShownDuringInitialOnboarding() {
        assertTrue(shouldShowOnboardingHistoryOption(addAccountMode = false))
        assertFalse(shouldShowOnboardingHistoryOption(addAccountMode = true))
    }

    @Test
    fun onboardingCannotPassPermissionStepUntilEveryRequiredGrantIsReady() {
        assertTrue(OnboardingPermissionState(true, true).allGranted)
        assertFalse(OnboardingPermissionState(false, true).allGranted)
        assertFalse(OnboardingPermissionState(true, false).allGranted)
        assertFalse(OnboardingPermissionState(false, false).allGranted)
    }

    @Test
    fun permissionsAreRequestedInNotificationThenAlarmOrder() {
        assertEquals(
            OnboardingPermissionAction.NOTIFICATIONS,
            nextOnboardingPermissionAction(OnboardingPermissionState(false, false)),
        )
        assertEquals(
            OnboardingPermissionAction.EXACT_ALARMS,
            nextOnboardingPermissionAction(OnboardingPermissionState(true, false)),
        )
        assertEquals(
            OnboardingPermissionAction.COMPLETE,
            nextOnboardingPermissionAction(OnboardingPermissionState(true, true)),
        )
    }
}
