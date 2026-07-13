package com.tjg.twidget

import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import dev.oneuiproject.oneui.R as OneUiIconR

internal class ScheduleComposeUi(
    private val activity: ScheduleComposeActivity,
    root: View,
) {
    private val threadContainer: LinearLayout = root.findViewById(R.id.schedule_compose_thread_container)
    private val timeSummary: AppCompatButton = root.findViewById(R.id.schedule_compose_time_summary)
    private val attachMediaButton: AppCompatImageButton = root.findViewById(R.id.schedule_compose_attach_media)
    private val cameraButton: AppCompatImageButton = root.findViewById(R.id.schedule_compose_camera)
    private val addThreadButton: AppCompatImageButton = root.findViewById(R.id.schedule_compose_add_thread)

    private var activeItem = 0

    fun bind() {
        attachMediaButton.setOnClickListener { activity.onComposeAttachMedia(activeItem) }
        cameraButton.setOnClickListener { activity.onComposeTakePhoto(activeItem) }
        timeSummary.setOnClickListener { activity.onComposePickTimeRequested() }
        addThreadButton.setOnClickListener { activity.onComposeAddThreadRequested() }
        refreshFromEditor()
    }

    fun refreshFromEditor(focusLast: Boolean = false) {
        threadContainer.removeAllViews()
        val count = activity.composeItemCount()
        if (focusLast) activeItem = count - 1 else activeItem = activeItem.coerceIn(0, count - 1)
        repeat(count) { index -> addThreadItem(index) }
        refreshTimeSummary()
        refreshSubmitState()
        if (focusLast) {
            threadContainer.getChildAt(count - 1)?.findViewById<EditText>(R.id.schedule_thread_input)?.apply {
                requestFocus()
                setSelection(text.length)
            }
        }
    }

    fun refreshTimeSummary() {
        timeSummary.text = activity.composeTimeSummaryText()
    }

    fun refreshSubmitState() {
        activity.invalidateOptionsMenu()
    }

    fun setBusy(busy: Boolean) {
        attachMediaButton.isEnabled = !busy
        cameraButton.isEnabled = !busy
        timeSummary.isEnabled = !busy
        addThreadButton.isEnabled = !busy
        refreshSubmitState()
    }

    fun refreshMediaForActiveItem() {
        refreshFromEditor()
    }

    private fun addThreadItem(index: Int) {
        val row = LayoutInflater.from(activity)
            .inflate(R.layout.item_schedule_thread_compose, threadContainer, false)
        val input = row.findViewById<EditText>(R.id.schedule_thread_input)
        val avatar = row.findViewById<ImageView>(R.id.schedule_thread_avatar)
        val connector = row.findViewById<View>(R.id.schedule_thread_connector)
        val limitNotice = row.findViewById<TextView>(R.id.schedule_thread_limit_notice)
        val strip = row.findViewById<HorizontalScrollView>(R.id.schedule_thread_media_strip)
        val mediaContainer = row.findViewById<LinearLayout>(R.id.schedule_thread_media_container)

        loadAvatar(avatar)
        avatar.visibility = if (index == 0) View.VISIBLE else View.INVISIBLE
        connector.visibility = View.VISIBLE
        (connector.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (index == 0) activity.composeDp(36) else 0
            connector.layoutParams = this
        }
        input.setText(activity.composeItemText(index))
        updateCharacterLimit(input, limitNotice)
        input.setOnFocusChangeListener { _, focused ->
            if (focused) {
                activeItem = index
            }
        }
        input.setOnLongClickListener {
            if (activity.composeItemCount() <= 1) return@setOnLongClickListener false
            AlertDialog.Builder(activity)
                .setMessage(R.string.schedule_remove_item)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.schedule_remove_item) { _, _ ->
                    activity.onComposeRemoveThreadRequested(index)
                }
                .show()
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                activity.composeUpdateItemText(index, s?.toString().orEmpty())
                updateCharacterLimit(input, limitNotice)
                refreshSubmitState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val media = activity.composeItemMedia(index)
        strip.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
        media.forEachIndexed { mediaIndex, source ->
            val preview = LayoutInflater.from(activity)
                .inflate(R.layout.item_schedule_media_preview, mediaContainer, false)
            bindMediaPreview(preview.findViewById(R.id.schedule_media_preview_image), source)
            preview.setOnLongClickListener {
                activeItem = index
                AlertDialog.Builder(activity)
                    .setItems(arrayOf(
                        activity.getString(R.string.schedule_remove_item),
                        activity.getString(R.string.schedule_download_media),
                    )) { _, action ->
                        if (action == 0) {
                            activity.composeRemoveMedia(index, mediaIndex)
                            refreshFromEditor()
                        } else {
                            activity.onComposeDownloadMedia(index, mediaIndex)
                        }
                    }
                    .show()
                true
            }
            mediaContainer.addView(preview)
        }
        threadContainer.addView(row)
    }

    private fun updateCharacterLimit(input: EditText, notice: TextView) {
        val text = input.text ?: return
        text.getSpans(0, text.length, ExcessCharacterSpan::class.java).forEach(text::removeSpan)
        val limit = activity.composeCharacterLimit()
        val length = SchedulePolicy.textLength(text.toString())
        val excess = (length - limit).coerceAtLeast(0)
        if (excess == 0) {
            notice.visibility = View.GONE
            return
        }
        val excessStart = text.toString().offsetByCodePoints(0, limit)
        text.setSpan(
            ExcessCharacterSpan(activity.getColor(R.color.schedule_character_limit)),
            excessStart,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        notice.text = activity.getString(R.string.schedule_character_limit_over, excess)
        notice.visibility = View.VISIBLE
    }

    private fun loadAvatar(view: ImageView) {
        val username = activity.composeAvatarUsername()
        val stats = if (username.isBlank()) TwidgetStore.currentStats(activity)
        else TwidgetStore.currentStats(activity, username)
        ProfileImageLoader.loadInto(activity, view, stats.profileImage)
    }

    private fun bindMediaPreview(image: ImageView, source: ScheduleMediaSource) {
        when (source) {
            is LocalUriMedia -> {
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.setImageURI(android.net.Uri.parse(source.uri))
            }
            is PublicUrlMedia -> ProfileImageLoader.loadMediaInto(activity, image, source.url, activity.composeDp(12))
            is PostponeLibraryMedia -> {
                val url = source.url.orEmpty()
                if (url.isBlank()) image.setImageResource(OneUiIconR.drawable.ic_oui_image_outline)
                else ProfileImageLoader.loadMediaInto(activity, image, url, activity.composeDp(12))
            }
        }
    }

    private class ExcessCharacterSpan(color: Int) : ForegroundColorSpan(color)
}
