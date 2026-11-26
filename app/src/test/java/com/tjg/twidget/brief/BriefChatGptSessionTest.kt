package com.tjg.twidget.brief

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefChatGptSessionTest {
    @Test fun signedOutAccountRecordDoesNotHideSignIn() {
        assertFalse(BriefOpenAi.hasSession(null))
        val retainedIdentity = JSONObject().put("client_id", "test-client").put("subject", "test-subject")
        assertFalse(BriefOpenAi.hasSession(retainedIdentity))
        assertFalse(BriefOpenAi.hasSession(JSONObject(retainedIdentity.toString()).put("refresh_token", "")))
        assertTrue(BriefOpenAi.hasSession(JSONObject(retainedIdentity.toString()).put("refresh_token", "test-token")))
    }
}
