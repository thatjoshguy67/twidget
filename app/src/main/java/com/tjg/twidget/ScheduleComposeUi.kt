package com.tjg.twidget

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.util.Size
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
import kotlin.math.roundToInt

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
        val removeThread = row.findViewById<AppCompatImageButton>(R.id.schedule_thread_remove)
        val limitNotice = row.findViewById<TextView>(R.id.schedule_thread_limit_notice)
        val strip = row.findViewById<HorizontalScrollView>(R.id.schedule_thread_media_strip)
        val mediaContainer = row.findViewById<LinearLayout>(R.id.schedule_thread_media_container)
        val media = activity.composeItemMedia(index)

        loadAvatar(avatar)
        avatar.visibility = if (index == 0) View.VISIBLE else View.INVISIBLE
        connector.visibility = View.VISIBLE
        (connector.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = if (index == 0) activity.composeDp(36) else 0
            connector.layoutParams = this
        }
        input.setText(activity.composeItemText(index))
        updateComposerTokenHighlights(input)
        updateCharacterLimit(input, limitNotice)
        updateRemoveThreadButton(index, input, removeThread, media.isEmpty())
        removeThread.setOnClickListener { activity.onComposeRemoveThreadRequested(index) }
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
                updateComposerTokenHighlights(input)
                updateCharacterLimit(input, limitNotice)
                updateRemoveThreadButton(index, input, removeThread, media.isEmpty())
                refreshSubmitState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        strip.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
        bindHorizontalMediaScroll(strip)
        media.forEachIndexed { mediaIndex, source ->
            val preview = LayoutInflater.from(activity)
                .inflate(R.layout.item_schedule_media_preview, mediaContainer, false)
            bindMediaPreview(preview.findViewById(R.id.schedule_media_preview_image), source)
            preview.findViewById<AppCompatImageButton>(R.id.schedule_media_preview_remove).setOnClickListener {
                activeItem = index
                activity.composeRemoveMedia(index, mediaIndex)
                refreshFromEditor()
            }
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

    private fun updateRemoveThreadButton(
        index: Int,
        input: EditText,
        button: AppCompatImageButton,
        mediaEmpty: Boolean,
    ) {
        button.visibility = if (index > 0 && input.text.isNullOrBlank() && mediaEmpty) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun bindHorizontalMediaScroll(strip: HorizontalScrollView) {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        strip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > touchSlop && dx > dy) view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    strip.post { snapMediaStrip(strip) }
                }
            }
            false
        }
    }

    private fun snapMediaStrip(strip: HorizontalScrollView) {
        val content = strip.getChildAt(0) as? LinearLayout ?: return
        val first = content.getChildAt(0) ?: return
        val params = first.layoutParams as? LinearLayout.LayoutParams ?: return
        val stride = first.width + params.marginStart + params.marginEnd
        if (stride <= 0) return
        val maxScroll = (content.width - strip.width).coerceAtLeast(0)
        val target = (strip.scrollX.toFloat() / stride).roundToInt() * stride
        strip.smoothScrollTo(target.coerceIn(0, maxScroll), 0)
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

    private fun updateComposerTokenHighlights(input: EditText) {
        val text = input.text ?: return
        text.getSpans(0, text.length, ComposerTokenSpan::class.java).forEach(text::removeSpan)
        COMPOSER_TOKEN_PATTERN.findAll(text).forEach { match ->
            text.setSpan(
                ComposerTokenSpan(activity.getColor(R.color.oneui_accent)),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
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
                val uri = Uri.parse(source.uri)
                val mimeType = source.mimeType
                    ?: runCatching { activity.contentResolver.getType(uri) }.getOrNull()
                if (mimeType?.startsWith("video/") == true) {
                    bindLocalVideoThumbnail(image, source)
                } else {
                    runCatching { image.setImageURI(uri) }
                        .onFailure { showMediaPlaceholder(image) }
                }
            }
            is PublicUrlMedia -> ProfileImageLoader.loadMediaInto(activity, image, source.url, activity.composeDp(12))
            is PostponeLibraryMedia -> {
                val url = source.url.orEmpty()
                if (url.isBlank()) image.setImageResource(OneUiIconR.drawable.ic_oui_image_outline)
                else ProfileImageLoader.loadMediaInto(activity, image, url, activity.composeDp(12))
            }
        }
    }

    private fun bindLocalVideoThumbnail(image: ImageView, source: LocalUriMedia) {
        val uri = Uri.parse(source.uri)
        image.tag = source.uri
        showMediaPlaceholder(image)
        AppExecutors.execute {
            val thumbnail = loadVideoThumbnail(uri)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || image.tag != source.uri) {
                    return@runOnUiThread
                }
                if (thumbnail == null) {
                    showMediaPlaceholder(image)
                } else {
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setImageBitmap(thumbnail)
                }
            }
        }
    }

    private fun loadVideoThumbnail(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                activity.contentResolver.loadThumbnail(
                    uri,
                    Size(activity.composeDp(440), activity.composeDp(438)),
                    null,
                )
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(activity, uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun showMediaPlaceholder(image: ImageView) {
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        image.setImageResource(OneUiIconR.drawable.ic_oui_image_outline)
    }

    private class ComposerTokenSpan(color: Int) : ForegroundColorSpan(color)
    private class ExcessCharacterSpan(color: Int) : ForegroundColorSpan(color)

    private companion object {
        val COMPOSER_TOKEN_PATTERN = Regex(
            "(?<![A-Za-z0-9_])@[A-Za-z0-9_]{1,15}|(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_]+"
        )
    }
}
