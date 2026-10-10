package com.tjg.twidget.main

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.PathInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetHaptics
import com.tjg.twidget.ui.TwidgetFonts

internal enum class DashboardCardGroup(val title: Int, val icon: Int, val color: Int) {
    BRIEF(R.string.brief_title, R.drawable.ic_card_catalogue_brief, 0),
    METRICS(R.string.card_group_metrics, R.drawable.ic_card_catalogue_audience, 0xff3e7dfc.toInt()),
    GROWTH(R.string.card_group_growth, R.drawable.ic_card_catalogue_growth, 0xff0fcf6e.toInt()),
    POST_ANALYTICS(R.string.card_group_posts, R.drawable.ic_card_catalogue_tweet, 0xff38acff.toInt());
}

internal fun DashboardCardType.catalogueGroup(): DashboardCardGroup = when (this) {
    DashboardCardType.MILESTONE -> DashboardCardGroup.BRIEF
    DashboardCardType.FOLLOWERS, DashboardCardType.FOLLOWING, DashboardCardType.FOLLOWER_RATIO,
    DashboardCardType.AUDIENCE_BALANCE, DashboardCardType.TOP_FOLLOWERS -> DashboardCardGroup.METRICS
    DashboardCardType.DAILY_STREAK, DashboardCardType.GROWTH_PACE, DashboardCardType.BEST_DAY,
    DashboardCardType.MOMENTUM, DashboardCardType.ACCOUNT_HEALTH -> DashboardCardGroup.GROWTH
    else -> DashboardCardGroup.POST_ANALYTICS
}

/** A category drawer with live card previews, modelled on Samsung Health's add-card catalogue. */
internal class MainAddCardDrawer(
    private val activity: MainActivity,
    private val controller: MainEditModeController,
) {
    private val dialog = BottomSheetDialog(activity)
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        transitionName = "catalogue_content"
        setPadding(0, 0, 0, 0)
    }
    private val scroll = object : NestedScrollView(activity) {
        init {
            transitionName = "catalogue_scroll"
            // SESL's window-inset renderer creates opaque bands in a resizing sheet.
            // Draw the same edge blend in this viewport's own coordinates instead.
            seslSetFadingEdgeEnabled(false)
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val fadeHeight = minOf(activity.dp(24), height / 2).toFloat()
            if (fadeHeight <= 0f) return
            val surface = activity.getColor(R.color.oneui_drawer_bg)
            val transparent = surface and 0x00ffffff
            val saved = canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            if (canScrollVertically(-1)) {
                edgePaint.shader = LinearGradient(0f, 0f, 0f, fadeHeight, surface, transparent, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width.toFloat(), fadeHeight, edgePaint)
            }
            if (canScrollVertically(1)) {
                edgePaint.shader = LinearGradient(0f, height - fadeHeight, 0f, height.toFloat(), transparent, surface, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, height - fadeHeight, width.toFloat(), height.toFloat(), edgePaint)
            }
            canvas.restoreToCount(saved)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = maxSheetHeight() - activity.dp(24)
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maximum
                else minOf(maximum, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        }
    }

    private fun sheetWidth(): Int {
        val windowWidth = activity.resources.configuration.screenWidthDp
        return activity.dp(if (windowWidth >= 600) (windowWidth * 0.84f).toInt() else (windowWidth - 24).coerceAtLeast(0))
    }

    private fun maxSheetHeight(): Int = minOf(activity.dp(746), (activity.resources.displayMetrics.heightPixels * 0.84f).toInt())
    private var expandedGroup: DashboardCardGroup? = null
    private var drawerRoot: LinearLayout? = null
    private var heightAnimator: ValueAnimator? = null
    private val pendingPanels = mutableMapOf<DashboardCardGroup, () -> Unit>()
    private val sections = linkedMapOf<DashboardCardGroup, AccordionSection>()

    private inner class AccordionSection : FrameLayout(activity) {
        val panels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var revealHeight = 0
        var naturalHeight = 0
        init {
            clipChildren = true
            addView(panels, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            panels.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            naturalHeight = panels.measuredHeight
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), revealHeight)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            panels.layout(0, 0, width, naturalHeight)
        }
    }

    fun show() {
        val root = LinearLayout(activity).apply {
            transitionName = "catalogue_root"
            orientation = LinearLayout.VERTICAL
            background = rounded(activity.getColor(R.color.oneui_drawer_bg), 28)
            clipToOutline = true
        }
        drawerRoot = root
        dialog.setOnDismissListener { heightAnimator?.cancel() }
        root.contentDescription = activity.getString(R.string.add_cards_title)
        root.addView(View(activity).apply {
            background = rounded((activity.getColor(R.color.oneui_text_primary) and 0x00ffffff) or 0x4d000000, 2)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = activity.dp(10)
            bottomMargin = activity.dp(10)
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.behavior.maxWidth = sheetWidth()
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            sheet.transitionName = "catalogue_sheet"
            sheet.background = rounded(activity.getColor(R.color.oneui_drawer_bg), 28)
            sheet.clipToOutline = true
            val height = maxSheetHeight()
            sheet.layoutParams = sheet.layoutParams.apply { this.height = ViewGroup.LayoutParams.WRAP_CONTENT }
            dialog.behavior.apply {
                peekHeight = activity.dp(244).coerceAtMost(height)
                maxWidth = sheetWidth()
                maxHeight = height
                isFitToContents = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.setDismissWithAnimation(true)
        rebuild()
        dialog.show()
    }

    private fun animateSections(startHeights: Map<DashboardCardGroup, Int> = sections.mapValues { it.value.revealHeight }) {
        heightAnimator?.cancel()
        expandedGroup?.let { pendingPanels.remove(it)?.invoke() }
        val targetHeights = sections.mapValues { (group, section) ->
            section.panels.measure(View.MeasureSpec.makeMeasureSpec(content.width.takeIf { it > 0 } ?: sheetWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (group == expandedGroup) section.panels.measuredHeight else 0
        }
        sections.forEach { (group, section) ->
            section.revealHeight = startHeights[group] ?: 0
            section.panels.visibility = if (section.revealHeight > 0 || targetHeights.getValue(group) > 0) View.VISIBLE else View.GONE
            section.panels.importantForAccessibility = if (group == expandedGroup) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun applyFraction(fraction: Float) {
            sections.forEach { (group, section) ->
                val start = startHeights[group] ?: 0
                section.revealHeight = (start + (targetHeights.getValue(group) - start) * fraction).toInt()
                section.requestLayout()
            }
        }
        if (!dialog.isShowing || !ValueAnimator.areAnimatorsEnabled()) {
            applyFraction(1f)
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = PathInterpolator(0.22f, 0.1f, 0.18f, 1f)
            addUpdateListener { applyFraction(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (heightAnimator !== animation) return
                    sections.forEach { (group, section) ->
                        if (targetHeights.getValue(group) == 0) section.panels.visibility = View.GONE
                    }
                    heightAnimator = null
                }
            })
        }
        heightAnimator = animator
        animator.start()
    }

    private fun rebuild() {
        val startHeights = sections.mapValues { it.value.revealHeight }
        heightAnimator?.cancel()
        heightAnimator = null
        sections.clear()
        pendingPanels.clear()
        content.removeAllViews()
        val current = TwidgetStore.dashboardCards(activity).toSet()
        val eligible = controller.availableDashboardCards()
        DashboardCardGroup.entries.forEach { group ->
            val cards = eligible.filter { it.catalogueGroup() == group }
            if (cards.isEmpty()) return@forEach
            val hidden = cards.filterNot { it.id in current }
            val row = LinearLayout(activity).apply {
                transitionName = "catalogue_row_${group.name}"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(20), activity.dp(14), activity.dp(20), activity.dp(14))
                minimumHeight = activity.dp(52)
                isEnabled = hidden.isNotEmpty()
                alpha = if (isEnabled) 1f else 0.5f
                if (isEnabled) {
                    isFocusable = true
                    setOnClickListener {
                        TwidgetHaptics.selection(this)
                        expandedGroup = if (expandedGroup == group) null else group
                        animateSections()
                        scroll.post {
                            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
                        }
                    }
                }
                contentDescription = activity.getString(group.title) + ", " +
                    if (hidden.isEmpty()) activity.getString(R.string.card_catalogue_all_added)
                    else activity.resources.getQuantityString(R.plurals.card_catalogue_available, hidden.size, hidden.size)
            }
            val icon = FrameLayout(activity).apply {
                background = if (group == DashboardCardGroup.BRIEF) GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xff66a1f3.toInt(), 0xff22c9a6.toInt()),
                ).apply { cornerRadius = activity.dp(20).toFloat() } else rounded(group.color, 20)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(ImageView(activity).apply {
                    setImageResource(group.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(activity.dp(18), activity.dp(18), Gravity.CENTER))
            }
            row.addView(icon, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)).apply { marginEnd = activity.dp(20) })
            val title = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(activity.getString(group.title), 18))
            }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (group == DashboardCardGroup.BRIEF && hidden.isNotEmpty()) {
                title.addView(View(activity).apply {
                    background = rounded(activity.getColor(R.color.schedule_ready), 3)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(activity.dp(6), activity.dp(6)).apply {
                    marginStart = activity.dp(2)
                    gravity = Gravity.TOP
                    topMargin = activity.dp(6)
                })
            }
            row.addView(label(if (hidden.isEmpty()) activity.getString(R.string.card_catalogue_all_added)
                else hidden.size.toString(), 18).apply {
                setTextColor(activity.getColor(R.color.oneui_text_secondary))
                typeface = TwidgetFonts.forApp(activity, 200)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(24)).apply {
                marginStart = activity.dp(20)
            })
            content.addView(row)
            val section = AccordionSection().apply {
                transitionName = "catalogue_section_${group.name}"
            }
            pendingPanels[group] = { hidden.forEach { card -> section.panels.addView(cardPanel(card)) } }
            sections[group] = section
            content.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (group != DashboardCardGroup.entries.last()) content.addView(View(activity).apply {
                transitionName = "catalogue_divider_${group.name}"
                background = rounded(activity.getColor(R.color.oneui_divider), 1)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply {
                marginStart = activity.dp(20)
                marginEnd = activity.dp(20)
            })
        }
        animateSections(startHeights)
    }

    private fun cardPanel(card: DashboardCardType): View {
        val panel = object : LinearLayout(activity) {
            var holdFeedback: com.tjg.twidget.ui.CardHoldFeedback? = null
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
                holdFeedback?.onTouch(event) == true || super.dispatchTouchEvent(event)
            override fun onDetachedFromWindow() {
                holdFeedback?.detach()
                super.onDetachedFromWindow()
            }
        }.apply {
            transitionName = "catalogue_panel_${card.id}"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(activity.dp(14), activity.dp(21), activity.dp(14), activity.dp(21))
            background = rounded(activity.getColor(R.color.card_catalogue_preview_bg), 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = activity.dp(20)
                marginEnd = activity.dp(20)
                bottomMargin = activity.dp(10)
            }
        }
        panel.addView(label(activity.getString(card.labelRes), 14, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = activity.dp(10) })
        val cardView = activity.dashboardBinder.createCardPreview(card)
        // SESL rounded layouts paint the dashboard background into their corners.
        // A thumbnail has a different surround; its Canvas clip supplies the outline.
        (cardView as? dev.oneuiproject.oneui.delegates.ViewRoundedCorner)?.roundedCorners = 0
        TwidgetFonts.applyTo(cardView)
        // Render the actual card without exposing its buttons or links inside the catalogue.
        val sourceWidth = activity.dp(if (card.size.span == 1) 189 else 392)
        val sourceHeight = if (card.size == DashboardCardSize.POST) View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            else View.MeasureSpec.makeMeasureSpec(activity.dp(if (card == DashboardCardType.MILESTONE) 160 else card.size.heightDp), View.MeasureSpec.EXACTLY)
        cardView.measure(View.MeasureSpec.makeMeasureSpec(sourceWidth, View.MeasureSpec.EXACTLY), sourceHeight)
        cardView.layout(0, 0, sourceWidth, cardView.measuredHeight)
        val availableWidth = minOf(activity.resources.displayMetrics.widthPixels, activity.dp(392)) - activity.dp(68)
        val scale = minOf(if (card.size.span == 1) 1f else 318f / 392f,
            activity.dp(260).toFloat() / cardView.height.coerceAtLeast(1), availableWidth.toFloat() / sourceWidth)
        val preview = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val saved = canvas.save()
                canvas.scale(scale, scale)
                val radius = (cardView.background as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
                    ?: activity.dp(if (card == DashboardCardType.TOP_FOLLOWERS || card == DashboardCardType.MILESTONE) 28 else 22).toFloat()
                canvas.clipPath(Path().apply {
                    addRoundRect(0f, 0f, sourceWidth.toFloat(), cardView.height.toFloat(),
                        radius, radius, Path.Direction.CW)
                })
                cardView.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val shadowPadding = activity.dp(com.tjg.twidget.ui.CardShadow.PADDING_DP)
        val previewWidth = (sourceWidth * scale).toInt()
        val previewHeight = (cardView.height * scale).toInt()
        val previewRadius = ((cardView.background as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
            ?: activity.dp(if (card == DashboardCardType.TOP_FOLLOWERS || card == DashboardCardType.MILESTONE) 28 else 22).toFloat()) * scale
        val shadow = object : FrameLayout(activity) {
            private val renderer = com.tjg.twidget.ui.CardShadow.renderer(activity, previewRadius)
            override fun dispatchDraw(canvas: Canvas) {
                renderer.draw(canvas, width, height)
                super.dispatchDraw(canvas)
            }
        }.apply {
            addView(preview, FrameLayout.LayoutParams(previewWidth, previewHeight).apply {
                leftMargin = shadowPadding
                topMargin = shadowPadding
            })
        }
        panel.addView(shadow, LinearLayout.LayoutParams(previewWidth + shadowPadding * 2, previewHeight + shadowPadding * 2).apply {
            topMargin = -shadowPadding
            bottomMargin = -shadowPadding
        })
        panel.addView(label(activity.getString(if (card.size.span == 1) R.string.card_catalogue_half_width else R.string.card_catalogue_full_width), 14).apply {
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.oneui_text_secondary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(10) })
        panel.holdFeedback = com.tjg.twidget.ui.CardHoldFeedback(panel, shadow)
        // The entire preview panel is a generous tap target, including the thumbnail.
        panel.isFocusable = true
        panel.contentDescription = activity.getString(R.string.card_catalogue_add_named, activity.getString(card.labelRes))
        panel.setOnLongClickListener(object : View.OnLongClickListener {
            override fun onLongClick(view: View): Boolean {
                if (!controller.startDrawerCardDrag(card, panel, preview)) return true
                dialog.setDismissWithAnimation(false)
                dialog.dismiss()
                return true
            }
            override fun onLongClickUseDefaultHapticFeedback(view: View): Boolean = false
        })
        panel.setOnClickListener {
            controller.addDashboardCard(card)
            content.announceForAccessibility(activity.getString(R.string.card_catalogue_added, activity.getString(card.labelRes)))
            rebuild()
        }
        return panel
    }

    private fun label(textValue: String, size: Int, bold: Boolean = false) = TextView(activity).apply {
        text = textValue
        textSize = size.toFloat()
        setTextColor(activity.getColor(R.color.oneui_text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        TwidgetFonts.applyTo(this)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = activity.dp(radius).toFloat()
    }
}
