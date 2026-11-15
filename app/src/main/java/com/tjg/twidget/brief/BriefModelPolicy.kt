package com.tjg.twidget.brief

/** Purpose-specific defaults keep frequent Briefs economical and reviews more analytical. */
internal object BriefModelPolicy {
    fun openAiModel(review: Boolean) = if (review) "gpt-6.1-terra" else "gpt-6-luna"
    fun reasoning(review: Boolean) = if (review) "medium" else "low"
    fun geminiModel(review: Boolean) = if (review) "gemini-3.8-flash" else "gemini-3.5-flash-lite"
    fun geminiModel(context: android.content.Context, review: Boolean): String =
        if (com.tjg.twidget.data.TwidgetStore.debugMenuUnlocked(context))
            BriefOpenAi.prefs(context).getString("gemini_model_$review", null) ?: geminiModel(review)
        else geminiModel(review)
    fun modelKey(method: String, account: String, review: Boolean) =
        "model_${if (review) "review" else "brief"}_${method}_${if (method == "chatgpt") account else "api"}"
}
