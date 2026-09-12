package com.tjg.twidget.brief

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.tjg.twidget.R

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
        content.findViewById<TextView>(R.id.brief_api_key_message).setText(
            if (required) R.string.brief_api_key_required_body else R.string.brief_api_key_settings_body,
        )
        content.findViewById<TextView>(R.id.brief_api_key_link).setOnClickListener {
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_API_KEY_URL)),
                )
            }.onFailure {
                Toast.makeText(activity, R.string.brief_api_key_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (required) R.string.brief_api_key_required_title else R.string.brief_ai_studio_key)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.brief_api_key_save, null)
            .create()
        dialog.setOnShowListener {
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
        dialog.show()
        return dialog
    }
}
