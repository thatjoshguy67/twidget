package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.tjg.twidget.R

internal object BriefChatGptLinkCard {
    const val TAG = "brief_chatgpt_link"
    internal fun eligible(method: String, geminiKeyConfigured: Boolean, localOnly: Boolean, connected: Boolean, dismissed: Boolean): Boolean =
        method == "gemini" && geminiKeyConfigured && !localOnly && !connected && !dismissed
    fun shouldShow(context: Context): Boolean = canRemainVisible(context) &&
        !BriefOpenAi.prefs(context).getBoolean("link_card_seen", false)
    fun canRemainVisible(context: Context): Boolean {
        val accounts = BriefOpenAi.accounts(context)
        val connected = (0 until accounts.length()).any {
            accounts.getJSONObject(it).optString("refresh_token").isNotBlank()
        }
        return eligible(BriefOpenAi.method(context), BriefSettingsStore.geminiApiKey(context).isNotBlank(),
            BriefSettingsStore.provider(context) == BriefProviderMode.LOCAL, connected,
            BriefOpenAi.prefs(context).getBoolean("link_card_dismissed", false))
    }
    fun create(context: Context, preview: Boolean = false, onContinue: () -> Unit): View = BriefReviewViews.column(context).apply {
        tag = TAG
        if (!preview) addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                BriefOpenAi.prefs(context).edit().putBoolean("link_card_seen", true).apply()
                removeOnAttachStateChangeListener(this)
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        val section = this
        addView(BriefReviewViews.text(context, context.getString(R.string.brief_chatgpt_link_intro), 14f).apply {
            setPadding(dp(context, 19), 0, dp(context, 19), 0)
        })
        addView(BriefReviewViews.card(context).apply {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(BriefReviewViews.icon(context, R.drawable.brief_chatgpt_link, 40).apply {
                    setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 3))
                })
                addView(BriefReviewViews.text(context, context.getString(R.string.brief_chatgpt_link_title), 14f, bold = true),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(context, 10) })
            }
            addView(header)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(BriefReviewViews.button(context, R.string.brief_chatgpt_link_dismiss, primary = false) {
                    if (!preview) BriefOpenAi.prefs(context).edit().putBoolean("link_card_dismissed", true).apply()
                    (section.parent as? android.view.ViewGroup)?.removeView(section)
                })
                addView(BriefReviewViews.button(context, R.string.continue_button) {
                    if (!preview) {
                        onContinue()
                        context.startActivity(Intent(context, BriefChatGptActivity::class.java))
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(context, 20) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 14) })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 10) })
    }
    private fun dp(context: Context, value: Int) = BriefReviewViews.dp(context, value)
}
