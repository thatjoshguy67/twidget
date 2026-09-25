package com.tjg.twidget.brief

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts
import java.util.function.Consumer

object BriefApiKeyDialog {
    const val AI_STUDIO_API_KEY_URL = "https://aistudio.google.com/apikey"

    fun show(
        activity: FragmentActivity,
        required: Boolean,
        onSaved: (String) -> Unit,
    ): AlertDialog {
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_brief_api_key, null)
        val input = content.findViewById<EditText>(R.id.brief_api_key_input).apply {
            setText(BriefSettingsStore.cloudApiKey(activity))
        }
        fun openLink(url: String, error: Int) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(activity, error, Toast.LENGTH_SHORT).show() }
        }
        content.findViewById<TextView>(R.id.brief_api_key_message).apply {
            text = SpannableString(activity.getText(R.string.brief_api_key_dialog_body)).apply {
                getSpans(0, length, Annotation::class.java).filter { it.key == "link" && it.value == "privacy" }.forEach { annotation ->
                    setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openLink(activity.getString(R.string.link_privacy_policy), R.string.brief_api_key_privacy_open_failed)
                        }
                    }, getSpanStart(annotation), getSpanEnd(annotation), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
        content.findViewById<TextView>(R.id.brief_api_key_link).apply {
            typeface = TwidgetFonts.forApp(activity, 600)
            setOnClickListener { openLink(AI_STUDIO_API_KEY_URL, R.string.brief_api_key_open_failed) }
        }
        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Twidget_ApiKeyDialog)
            .setTitle(R.string.brief_ai_studio_key)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            styleWindow(activity, dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text?.toString().orEmpty().trim()
                if (required && key.isBlank()) {
                    input.error = activity.getString(R.string.brief_api_key_required_error)
                    return@setOnClickListener
                }
                BriefSettingsStore.setCloudApiKey(activity, key)
                onSaved(key)
                dialog.dismiss()
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        return dialog
    }

    private fun styleWindow(activity: FragmentActivity, dialog: AlertDialog) {
        val window = dialog.window ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val availableWidth = activity.window.decorView.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        window.setLayout(minOf(dp(392), availableWidth - dp(20)), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            typeface = TwidgetFonts.forApp(activity, 600)
            textSize = 20f
            setTextColor(activity.getColor(R.color.oneui_text_primary))
        }
        for (which in listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)) {
            dialog.getButton(which).setTextColor(activity.getColor(R.color.oneui_text_primary))
        }
        dialog.findViewById<View>(androidx.appcompat.R.id.middlePanel)?.setPadding(0, dp(22), 0, 0)
        dialog.findViewById<View>(androidx.appcompat.R.id.title_template)?.setPadding(dp(25), 0, dp(25), dp(22))
        dialog.findViewById<View>(androidx.appcompat.R.id.buttonBarLayout)?.setPadding(dp(20), 0, dp(20), dp(20))
        dialog.findViewById<View>(androidx.appcompat.R.id.sem_divider1)?.apply {
            layoutParams = layoutParams.apply { width = dp(2); height = dp(32) }
            setBackgroundResource(R.drawable.dialog_api_key_divider)
        }
        TwidgetFonts.observeWindow(window.decorView)
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = activity.getSystemService(WindowManager::class.java)
            val background = activity.getDrawable(R.drawable.dialog_api_key_background)!!.mutate()
            window.setBackgroundDrawable(background)
            val listener = Consumer<Boolean> { enabled ->
                background.alpha = if (enabled) 128 else 255
                window.setBackgroundBlurRadius(if (enabled) dp(24) else 0)
            }
            manager.addCrossWindowBlurEnabledListener(activity.mainExecutor, listener)
            window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    manager.removeCrossWindowBlurEnabledListener(listener)
                    view.removeOnAttachStateChangeListener(this)
                }
            })
        }
    }
}
