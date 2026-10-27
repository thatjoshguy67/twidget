package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.LocalUriMedia
import com.tjg.twidget.schedule.PublicUrlMedia
import com.tjg.twidget.schedule.ScheduleMediaSource
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduledPost
import com.tjg.twidget.schedule.XComposeIntents
import com.tjg.twidget.ui.MediaAspectImageView
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TweetMediaView
import com.tjg.twidget.ui.TwidgetFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object BriefReviewViews {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun params(context: Context, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, top) }
    fun text(context: Context, value: CharSequence, size: Float = 14f, bold: Boolean = false, secondary: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            includeFontPadding = false
            typeface = TwidgetFonts.forApp(context, if (bold) 700 else 400)
            setTextColor(context.getColor(if (secondary) R.color.oneui_text_secondary else R.color.oneui_text_primary))
            setLineSpacing(dp(context, 1).toFloat(), 1f)
        }
    fun card(context: Context) = column(context).apply {
        setBackgroundResource(R.drawable.brief_card_background)
        setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14))
    }
    fun label(context: Context, value: CharSequence) = text(context, value).apply {
        setPadding(dp(context, 13), dp(context, 10), dp(context, 13), 0)
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    fun icon(context: Context, res: Int, size: Int = 24) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.oneui_text_primary))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size))
    }
    fun button(context: Context, label: Int, primary: Boolean = true, click: () -> Unit): AppCompatButton =
        (LayoutInflater.from(context).inflate(R.layout.view_review_button, null) as AppCompatButton).apply {
            setText(label)
            typeface = TwidgetFonts.forApp(context, 700)
            if (!primary) {
                backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.oneui_card_bg))
                setTextColor(context.getColor(R.color.oneui_text_primary))
            }
            setOnClickListener { click() }
        }
    fun actions(context: Context, actions: List<BriefReviewAction>, compact: Boolean = false,
                stackCompactActions: Boolean = false) =
        (if (compact) BriefTweetActions(context, stackCompactActions) else LinearLayout(context)).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (compact) {
            clipChildren = false
            clipToPadding = false
        }
        actions.forEachIndexed { index, action ->
            addView(button(context, action.label, action.primary, action.click).apply {
                if (action.icon != 0) {
                    val icon = requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(context, action.icon)).mutate()
                    icon.setTint(currentTextColor)
                    icon.setBounds(0, 0, dp(context, 24), dp(context, 24))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(context, 10)
                    setPaddingRelative(dp(context, 12), paddingTop, dp(context, 12), paddingBottom)
                }
                if (action.outlined) foreground = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.brief_open_tweet_outline)
                if (compact) {
                    stateListAnimator = null
                    elevation = dp(context, 8).toFloat()
                }
            }, LinearLayout.LayoutParams(
                if (actions.size > 1 && !compact) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (actions.size > 1 && !compact) 1f else 0f).apply {
                if (index > 0 && !(compact && stackCompactActions)) {
                    marginStart = dp(context, if (compact) 20 else 10)
                }
            })
        }
    }
}

internal data class BriefReviewAction(val label: Int, val primary: Boolean = true, val outlined: Boolean = false,
    val icon: Int = 0, val click: () -> Unit)

/** Keep tweet buttons centred at their natural width; stack when labels need more room. */
private class BriefTweetActions(context: Context, private val stackActions: Boolean) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val naturalWidth = (0 until childCount).sumOf { index ->
            getChildAt(index).apply {
                measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }.measuredWidth
        } + BriefReviewViews.dp(context, 20) * (childCount - 1).coerceAtLeast(0)
        val stacked = stackActions || (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            naturalWidth > MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        orientation = if (stacked) VERTICAL else HORIZONTAL
        for (index in 0 until childCount) {
            (getChildAt(index).layoutParams as LayoutParams).apply {
                marginStart = if (!stacked && index > 0) BriefReviewViews.dp(context, 20) else 0
                marginEnd = 0
                topMargin = if (stacked && index > 0) BriefReviewViews.dp(context, 8) else 0
                gravity = if (stacked) Gravity.CENTER_HORIZONTAL else Gravity.NO_GRAVITY
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Shared native tweet surface for entry points, the review header and proposed rewrites. */
internal object BriefReviewTweetCard {
    fun create(context: Context, username: String, post: PostSummary? = null, draft: ScheduledPost? = null,
        rewrite: String? = null, rewriteEntry: Int = 0, compact: Boolean = false,
        actions: List<BriefReviewAction> = emptyList(), onClick: (() -> Unit)? = null,
        sourceAttribution: String = "", reviewPreview: Boolean = false, openOnCardTap: Boolean = false,
        stackCompactActions: Boolean = false): View {
        require((post == null) != (draft == null))
        val stats = TwidgetStore.currentStats(context, username)
        val ai = rewrite != null
        val quoted = post?.quotedPost
        val mediaHeight = when {
            reviewPreview && quoted?.media?.isNotEmpty() == true -> 96
            quoted != null -> 167
            ai && reviewPreview -> 218
            reviewPreview -> 172
            else -> TweetMediaView.MAX_HEIGHT_DP
        }
        val publicPost = post?.takeIf { rewrite == null }
        val openPost = publicPost?.takeIf { canOpen(it.url) }?.let { source -> onClick ?: { openTweet(context, source.url) } }
        val click = if (publicPost == null) onClick else openPost.takeIf { openOnCardTap }
        val cardActions = if (openPost != null && !openOnCardTap) {
            listOf(BriefReviewAction(R.string.brief_action_open_tweet, false, outlined = true) {
                openPost()
            }) + actions
        } else actions
        val result = BriefReviewViews.card(context)
        result.outlineProvider = ViewOutlineProvider.BACKGROUND
        result.clipToOutline = true
        result.clipToPadding = false
        result.clipChildren = false
        if (click != null) makeClickable(result, R.drawable.brief_review_card_ripple, click)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                ProfileImageLoader.loadInto(context, this, post?.authorAvatar?.ifBlank { stats.profileImage } ?: stats.profileImage)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(BriefReviewViews.dp(context, 40), BriefReviewViews.dp(context, 40)))
            addView(BriefReviewViews.column(context).apply {
                setPadding(BriefReviewViews.dp(context, 10), 0, BriefReviewViews.dp(context, 6), 0)
                addView(BriefReviewViews.text(context, (post?.authorName?.ifBlank { stats.fullName }
                    ?: stats.fullName).ifBlank { "@$username" }, bold = true))
                val time = post?.timestamp ?: draft?.updatedAt ?: 0L
                val date = if (time > 0) AppLocales.formatDate(time, "MMMdjm") else ""
                val source = if (draft != null) context.getString(R.string.review_draft) else date
                addView(BriefReviewViews.text(context, listOf("@$username", source, sourceAttribution).filter(String::isNotBlank).joinToString(" · "), 12f, secondary = true))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!ai) addView(BriefReviewViews.icon(context, when {
                draft?.provider == ScheduleProvider.BUFFER -> R.drawable.ic_buffer
                draft != null -> R.drawable.ic_settings_send
                else -> R.drawable.ic_logo_twitter
            }))
        }
        result.addView(header)
        val entries = draft?.thread?.withIndex()?.toList()?.let {
            when {
                ai && reviewPreview -> it.filter { item -> item.index == rewriteEntry }
                compact || reviewPreview -> it.take(1)
                else -> it
            }
        }.orEmpty()
        if (post != null) {
            result.addView(BriefReviewViews.text(context, rewrite ?: post.fullText ?: post.text, 13f).apply {
                setTextIsSelectable(!compact && click == null)
                if (!ai && (compact || reviewPreview)) { maxLines = if (reviewPreview && quoted != null) 3 else if (reviewPreview) 5 else 8; ellipsize = android.text.TextUtils.TruncateAt.END }
            }, BriefReviewViews.params(context, 10))
            if (post.media.isNotEmpty()) {
                val overlaidActions = if (quoted == null) cardActions else emptyList()
                result.addView(mediaWithActions(context,
                    TweetMediaView.create(context, post.media, mediaHeight).also { routeMediaTaps(it, result) },
                    overlaidActions, stackCompactActions), mediaParams(context, overlaidActions.isNotEmpty()))
            }
            quoted?.let { source ->
                result.addView(quotedCard(context, source, reviewPreview || compact), BriefReviewViews.params(context, 10))
            }
        } else entries.forEachIndexed { index, (entryIndex, entry) ->
            if (index > 0 || (ai && draft!!.thread.size > 1)) result.addView(BriefReviewViews.text(context,
                context.getString(R.string.review_thread_entry, entryIndex + 1), 12f, secondary = true), BriefReviewViews.params(context, 14))
            result.addView(BriefReviewViews.text(context, if (entryIndex == rewriteEntry && rewrite != null) rewrite else entry.text, 13f).apply {
                setTextIsSelectable(!compact && click == null)
                if (!ai && (compact || reviewPreview)) { maxLines = if (reviewPreview && quoted != null) 3 else if (reviewPreview) 5 else 8; ellipsize = android.text.TextUtils.TruncateAt.END }
            }, BriefReviewViews.params(context, 10))
            entry.media.firstOrNull()?.let { media ->
                val view = draftMedia(context, media, mediaHeight)
                val overlaidActions = if (index == entries.lastIndex) cardActions else emptyList()
                result.addView(mediaWithActions(context, view, overlaidActions, stackCompactActions), mediaParams(context, overlaidActions.isNotEmpty()))
            }
        }
        val lastHasMedia = if (post != null) post.media.isNotEmpty() && quoted == null else entries.lastOrNull()?.value?.media?.isNotEmpty() == true
        if (lastHasMedia && cardActions.isNotEmpty()) result.setPadding(result.paddingLeft, result.paddingTop, result.paddingRight, 0)
        if (!lastHasMedia && cardActions.isNotEmpty()) result.addView(BriefReviewViews.actions(context, cardActions,
            compact = true, stackCompactActions = stackCompactActions).apply {
            if (quoted != null) setPadding(0, BriefReviewViews.dp(context, 5), 0, BriefReviewViews.dp(context, 5))
        }, BriefReviewViews.params(context, if (quoted != null) 10 else 14))
        if (quoted != null && post?.media?.isNotEmpty() == true && cardActions.isNotEmpty()) result.setPadding(
            result.paddingLeft, result.paddingTop, result.paddingRight, BriefReviewViews.dp(context, 10))
        return result
    }

    internal fun canOpen(url: String): Boolean = BriefPublishedReviewStore.postId(url) != null

    internal fun openTweet(context: Context, url: String) {
        if (!canOpen(url)) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent(intent).setPackage(XComposeIntents.X_APP_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(intent)
        } catch (_: SecurityException) {
            context.startActivity(intent)
        }
    }

    private fun makeClickable(view: View, ripple: Int, click: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        view.setOnClickListener { click() }
        view.foreground = androidx.appcompat.content.res.AppCompatResources.getDrawable(view.context, ripple)
    }

    private fun quotedCard(context: Context, source: com.tjg.twidget.analytics.QuotedPost, compact: Boolean): View {
        fun dp(value: Int) = BriefReviewViews.dp(context, value)
        return BriefReviewViews.column(context).apply {
            id = R.id.review_quoted_tweet_card
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(context.getColor(R.color.brief_review_quote_surface))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (canOpen(source.url)) {
                makeClickable(this, R.drawable.brief_review_quote_ripple) { openTweet(context, source.url) }
                androidx.core.view.ViewCompat.replaceAccessibilityAction(this,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    context.getString(R.string.review_open_quoted_tweet)) { _, _ -> performClick() }
            } else {
                // An unavailable source must not turn a quote tap into a tap on its parent tweet.
                setOnClickListener { }
                isFocusable = false
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    ProfileImageLoader.loadInto(context, this, source.authorAvatar)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(BriefReviewViews.column(context).apply {
                    addView(BriefReviewViews.text(context, source.authorName.ifBlank { source.authorUserName }
                        .ifBlank { context.getString(R.string.review_quoted_tweet) }, 12f, bold = true).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    val date = source.timestamp.takeIf { it > 0 }?.let {
                        AppLocales.formatDate(it, "MMMdjm")
                    }
                    val attribution = listOfNotNull(source.authorUserName.takeIf(String::isNotBlank)?.let { "@$it" }, date).joinToString(" · ")
                    if (attribution.isNotBlank()) addView(BriefReviewViews.text(context, attribution, 10f, secondary = true).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            })
            if (source.text.isNotBlank()) addView(BriefReviewViews.text(context, source.text, 13f).apply {
                maxLines = if (compact) 3 else 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, BriefReviewViews.params(context, 10))
            if (source.media.isNotEmpty()) {
                val quoteOwner = this
                val height = if (compact) 96 else 172
                addView(TweetMediaView.create(context, source.media, height).apply {
                    if (this is ImageView) scaleType = ImageView.ScaleType.CENTER_CROP
                    routeMediaTaps(this, quoteOwner)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { topMargin = dp(10) })
            }
        }
    }

    /** Scrollable photo strips keep scrolling, while a stationary tap opens their owning tweet. */
    private fun routeMediaTaps(media: View, owner: View) {
        if (!owner.hasOnClickListeners()) return
        if (media is HorizontalScrollView) {
            var tapped = false
            media.setOnClickListener { owner.performClick() }
            val detector = GestureDetector(media.context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean { tapped = false; owner.isPressed = true; return true }
                override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    owner.isPressed = false; return false
                }
                override fun onSingleTapUp(event: MotionEvent): Boolean { tapped = true; return true }
                override fun onLongPress(event: MotionEvent) { owner.isPressed = false }
            })
            media.setOnTouchListener { view, event ->
                detector.onTouchEvent(event)
                if (tapped) { tapped = false; view.performClick() }
                if (event.actionMasked in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) owner.isPressed = false
                false
            }
        } else if (media is ViewGroup) for (index in 0 until media.childCount) routeMediaTaps(media.getChildAt(index), owner)
    }

    private fun draftMedia(context: Context, media: ScheduleMediaSource, maximumHeightDp: Int): View = MediaAspectImageView(context).apply {
        contentDescription = context.getString(R.string.post_media)
        scaleType = ImageView.ScaleType.FIT_CENTER
        maximumMediaHeightPx = BriefReviewViews.dp(context, maximumHeightDp)
        when (media) {
            is PublicUrlMedia -> ProfileImageLoader.loadMediaInto(context, this, media.displayUrl, BriefReviewViews.dp(context, 14))
            is LocalUriMedia -> {
                setBackgroundResource(R.drawable.schedule_media_preview_bg)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                (context as? LifecycleOwner)?.lifecycleScope?.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        BriefCoachMedia.localPreview(context.applicationContext, media.uri, media.mimeType?.startsWith("video/") == true)?.let { source ->
                            val scale = minOf(1.0, 512.0 / maxOf(source.width, source.height))
                            if (scale == 1.0) source else android.graphics.Bitmap.createScaledBitmap(source,
                                (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
                                .also { if (it !== source) source.recycle() }
                        }
                    }
                    if (bitmap != null) setImageBitmap(bitmap) else visibility = View.GONE
                }
            }
        }
    }

    private fun mediaWithActions(context: Context, media: View, actions: List<BriefReviewAction>,
                                 stackCompactActions: Boolean): View {
        if (actions.isEmpty()) return media
        return FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(media, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = BriefReviewViews.dp(context, 14)
                marginEnd = marginStart
                bottomMargin = marginStart
            })
            addView(BriefReviewViews.actions(context, actions, compact = true,
                stackCompactActions = stackCompactActions).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, context.getColor(R.color.brief_review_media_fade_bottom)))
                setPadding(BriefReviewViews.dp(context, 14), BriefReviewViews.dp(context, 26), BriefReviewViews.dp(context, 14), BriefReviewViews.dp(context, 25))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    private fun mediaParams(context: Context, hasActions: Boolean) = BriefReviewViews.params(context, 10).apply {
        if (hasActions) {
            marginStart = -BriefReviewViews.dp(context, 14)
            marginEnd = marginStart
        }
    }
}
