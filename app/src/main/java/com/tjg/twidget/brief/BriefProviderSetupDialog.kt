package com.tjg.twidget.brief

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.tjg.twidget.R

/** Setup is a choice, separate from the existing credential editor for each provider. */
internal object BriefProviderSetupDialog {
    private enum class Source(val method: String, val title: Int, val summary: Int) {
        CHATGPT("chatgpt", R.string.brief_setup_chatgpt, R.string.brief_setup_chatgpt_summary),
        OPENAI("key", R.string.brief_setup_openai, R.string.brief_setup_openai_summary),
        GEMINI("gemini", R.string.brief_setup_gemini, R.string.brief_setup_gemini_summary),
    }

    fun show(activity: FragmentActivity, onChatGptSignIn: () -> Unit, onConfigured: () -> Unit): AlertDialog {
        // ChatGPT is first and selected by default. Browsing/cancelling never changes the active provider.
        var selected = Source.CHATGPT
        val adapter = object : ArrayAdapter<Source>(activity, android.R.layout.simple_list_item_single_choice, Source.entries.toList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    val source = getItem(position)!!
                    val title = activity.getString(source.title)
                    val summary = activity.getString(source.summary)
                    text = SpannableString("$title\n$summary").apply {
                        setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(RelativeSizeSpan(.85f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(ForegroundColorSpan(activity.getColor(R.color.oneui_text_secondary)), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    setTextColor(activity.getColor(R.color.oneui_text_primary))
                    setSingleLine(false)
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    setPaddingRelative(padding, padding, padding, padding)
                }
        }
        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Twidget_ApiKeyDialog)
            .setTitle(R.string.brief_setup_provider_title)
            .setSingleChoiceItems(adapter, 0) { dialog, index ->
                selected = Source.entries[index]
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setText(
                    if (selected == Source.CHATGPT) R.string.brief_setup_chatgpt_continue else R.string.brief_setup_enter_key)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.brief_setup_chatgpt_continue) { _, _ ->
                if (selected == Source.CHATGPT) {
                    if (!BriefOpenAi.signedIn(activity)) onChatGptSignIn()
                    else {
                        BriefOpenAi.prefs(activity).edit().putString("method", selected.method).apply()
                        onConfigured()
                    }
                } else {
                    BriefApiKeyDialog.show(activity, required = true, method = selected.method) { onConfigured() }
                }
            }.create()
        dialog.setOnShowListener { BriefApiKeyDialog.styleWindow(activity, dialog) }
        dialog.show()
        return dialog
    }
}
