package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefChatGptLinkPolicyTest {
    @Test fun promotionOnlyTargetsExistingGeminiApiUsersWithoutChatGpt() {
        assertTrue(BriefChatGptLinkCard.eligible("gemini", true, false, false, false))
        assertFalse(BriefChatGptLinkCard.eligible("gemini", false, false, false, false))
        assertFalse(BriefChatGptLinkCard.eligible("gemini", true, true, false, false))
        assertFalse(BriefChatGptLinkCard.eligible("gemini", true, false, true, false))
        assertFalse(BriefChatGptLinkCard.eligible("gemini", true, false, false, true))
        assertFalse(BriefChatGptLinkCard.eligible("key", true, false, false, false))
        assertFalse(BriefChatGptLinkCard.eligible("chatgpt", true, false, false, false))
    }
}
