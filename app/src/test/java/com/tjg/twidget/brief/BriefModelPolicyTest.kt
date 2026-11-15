package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test

class BriefModelPolicyTest {
    @Test fun eachPurposeHasItsOwnModelAndReasoningBudget() {
        assertEquals("gpt-6-luna", BriefModelPolicy.openAiModel(false))
        assertEquals("low", BriefModelPolicy.reasoning(false))
        assertEquals("gpt-6.1-terra", BriefModelPolicy.openAiModel(true))
        assertEquals("medium", BriefModelPolicy.reasoning(true))
        assertEquals("gemini-3.5-flash-lite", BriefModelPolicy.geminiModel(false))
        assertEquals("gemini-3.8-flash", BriefModelPolicy.geminiModel(true))
    }
    @Test fun modelChoicesStaySeparateByPurposeAndChatGptAccount() {
        assertNotEquals(BriefModelPolicy.modelKey("chatgpt", "a", false), BriefModelPolicy.modelKey("chatgpt", "a", true))
        assertNotEquals(BriefModelPolicy.modelKey("chatgpt", "a", false), BriefModelPolicy.modelKey("chatgpt", "b", false))
        assertEquals(BriefModelPolicy.modelKey("key", "a", false), BriefModelPolicy.modelKey("key", "b", false))
    }
}
