package com.tjg.twidget.brief

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefChatGptLinkPolicyTest {
    @Test fun loginCardUsesTheActiveSessionRatherThanRetainedIdentityOrPromotionHistory() {
        assertTrue(BriefChatGptLinkCard.eligible(BriefOpenAi.hasSession(null)))
        val signedOut = JSONObject().put("client_id", "retained-client").put("subject", "retained-person")
        assertTrue(BriefChatGptLinkCard.eligible(BriefOpenAi.hasSession(signedOut)))
        val signedIn = JSONObject(signedOut.toString()).put("refresh_token", "test-token")
        assertFalse(BriefChatGptLinkCard.eligible(BriefOpenAi.hasSession(signedIn)))
        signedIn.remove("refresh_token")
        assertTrue(BriefChatGptLinkCard.eligible(BriefOpenAi.hasSession(signedIn)))
    }
}
