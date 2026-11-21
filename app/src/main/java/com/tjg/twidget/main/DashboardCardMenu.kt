package com.tjg.twidget.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.transition.Transition
import android.transition.TransitionValues
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.SemBlurCompat
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.R as OneUiIconR
import kotlin.math.ceil

/** SmartThings-style bubble quick-option menu anchored above (or below) the held dashboard card. */
internal class DashboardCardMenu(private val activity: MainActivity) {
    private data class BubbleAction(
        val labelRes: Int,
        val iconRes: Int,
        val onClick: () -> Unit,
    )

    private var popup: PopupWindow? = null
    private var account: String? = null

    fun show(card: DashboardCardType, anchor: View, pointerHeld: Boolean) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val profile = activity.selectedAccount
        val expanded = card.span(activity, profile) == 2
        val actions = buildList {
            if (card.supportsSizeVariants) {
                add(
                    BubbleAction(
                        labelRes = if (expanded) R.string.dashboard_size_shrink else R.string.dashboard_size_enlarge,
                        iconRes = if (expanded) R.drawable.ic_quick_option_collapse else R.drawable.ic_quick_option_expand,
                        onClick = {
                            if ((card.span(activity, profile) == 2) == expanded) {
                                activity.editModeController.resizeDashboardCard(card)
                            }
                        },
                    ),
                )
            }
            add(
                BubbleAction(
                    labelRes = R.string.dashboard_remove_card,
                    iconRes = OneUiIconR.drawable.ic_oui_delete_outline,
                    onClick = { activity.editModeController.removeDashboardCard(card.id) },
                ),
            )
        }

        val inflater = LayoutInflater.from(activity)
        val root = inflater.inflate(R.layout.dashboard_card_bubble_menu, null, false)
        val cardContainer = root.findViewById<FrameLayout>(R.id.popup_card_container)
        val blurView = root.findViewById<View>(R.id.popup_blur_view)
        val titleView = root.findViewById<TextView>(R.id.popup_title).apply {
            text = activity.getString(card.labelRes)
            typeface = TwidgetFonts.forApp(activity, 500)
        }
        val itemLayout = root.findViewById<LinearLayout>(R.id.popup_item_layout)
        val arrowTop = root.findViewById<ImageView>(R.id.popup_arrow_top)
        val arrowBottom = root.findViewById<ImageView>(R.id.popup_arrow_bottom)

        val window = PopupWindow(root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isFocusable = !pointerHeld
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 0f
        }

        actions.forEach { action ->
            val cell = inflater.inflate(R.layout.dashboard_card_bubble_item, itemLayout, false)
            cell.findViewById<ImageView>(R.id.popup_option_image).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(0xFFFCFCFF.toInt())
            }
            cell.findViewById<TextView>(R.id.popup_option_text).apply {
                text = activity.getString(action.labelRes)
                typeface = TwidgetFonts.forApp(activity, 400)
            }
            cell.setOnClickListener {
                window.dismiss()
                if (activity.selectedAccount == profile &&
                    activity.editModeController.editMode &&
                    card.id in activity.dashboardBinder.cardIds()
                ) {
                    action.onClick()
                }
            }
            itemLayout.addView(cell)
        }

        val decor = activity.window.decorView
        val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
        val origin = IntArray(2)
        val location = IntArray(2)
        val gap = activity.dp(12)
        val outerPad = root.paddingLeft + root.paddingRight
        val titleNeededWidth = ceil(titleView.paint.measureText(titleView.text.toString())).toInt() +
            activity.dp(32)
        val baseCardWidth = activity.dp(if (actions.size <= 1) 128 else 180)
        val maxCardWidth = (frame.width() - gap * 2 - outerPad).coerceAtLeast(activity.dp(120))
        val cardWidth = maxOf(baseCardWidth, titleNeededWidth).coerceAtMost(maxCardWidth)

        cardContainer.layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

        arrowBottom.visibility = View.VISIBLE
        arrowTop.visibility = View.GONE
        root.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth + outerPad, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupWidth = root.measuredWidth
        val popupHeight = root.measuredHeight

        val arrowGap = activity.dp(3) // SmartThings quick_option_bottom_margin
        val aboveArrowGap = activity.dp(6) // Compensates downward 3dp elevation shadow so visual gap matches below
        val arrowWidth = activity.dp(10)
        val cornerGuard = activity.dp(20)

        var lastX = 0
        var lastY = 0
        var lastShowAbove = true
        var lastArrowOffset = 0

        fun computePlacement(): Boolean {
            decor.getWindowVisibleDisplayFrame(frame)
            decor.getLocationOnScreen(origin)
            anchor.getLocationOnScreen(location)
            val cardLeft = (location[0] - anchor.pivotX * (1f - anchor.scaleX)).toInt()
            val cardTop = (location[1] - anchor.pivotY * (1f - anchor.scaleY)).toInt()
            val anchorCenterX = cardLeft + anchor.width / 2
            val left = (anchorCenterX - popupWidth / 2)
                .coerceIn(frame.left + gap, (frame.right - popupWidth - gap).coerceAtLeast(frame.left + gap))
            val aboveTop = cardTop - (popupHeight - root.paddingBottom) - aboveArrowGap
            val showAbove = aboveTop >= frame.top + gap
            val arrowOffset = (anchorCenterX - left - root.paddingLeft - arrowWidth / 2)
                .coerceIn(cornerGuard, (cardWidth - cornerGuard - arrowWidth).coerceAtLeast(cornerGuard))
            val top = if (showAbove) {
                aboveTop
            } else {
                (cardTop + anchor.height + arrowGap - root.paddingTop)
                    .coerceIn(frame.top + gap, (frame.bottom - popupHeight - gap).coerceAtLeast(frame.top + gap))
            }
            val x = left - origin[0]
            val y = top - origin[1]
            val changed = x != lastX || y != lastY || showAbove != lastShowAbove || arrowOffset != lastArrowOffset
            lastX = x
            lastY = y
            lastShowAbove = showAbove
            lastArrowOffset = arrowOffset
            return changed
        }

        computePlacement()
        val applyArrowState = {
            arrowBottom.visibility = if (lastShowAbove) View.VISIBLE else View.GONE
            arrowTop.visibility = if (lastShowAbove) View.GONE else View.VISIBLE
            val activeArrow = if (lastShowAbove) arrowBottom else arrowTop
            (activeArrow.layoutParams as LinearLayout.LayoutParams).marginStart = lastArrowOffset
            activeArrow.requestLayout()
            activeArrow
        }
        val activeArrow = applyArrowState()

        // Apply SmartThings Samsung live blur (with fallback to #F23A3A3D when unsupported).
        val blurApplied = applySamsungBlur(blurView, activity.dp(20).toFloat())
        if (!blurApplied) {
            (cardContainer.background?.mutate() as? GradientDrawable)?.setColor(0xF23A3A3D.toInt())
            arrowBottom.alpha = 0.95f
            arrowTop.alpha = 0.95f
            activeArrow.alpha = 0.95f
        }

        val pivotX = root.paddingLeft + lastArrowOffset + arrowWidth / 2f
        val pivotY = if (lastShowAbove) popupHeight.toFloat() else 0f
        window.enterTransition = BubbleOptionTransition(entering = true, pivotX = pivotX, pivotY = pivotY)
        window.exitTransition = BubbleOptionTransition(entering = false, pivotX = pivotX, pivotY = pivotY)

        window.width = popupWidth
        window.height = popupHeight

        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (window.isShowing && anchor.isAttachedToWindow && computePlacement()) {
                applyArrowState()
                window.update(lastX, lastY, popupWidth, popupHeight)
            }
            true
        }
        decor.viewTreeObserver.addOnPreDrawListener(preDrawListener)

        val detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detached)
        window.setOnDismissListener {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            anchor.removeOnAttachStateChangeListener(detached)
            anchor.setTag(R.id.dashboard_card_held, false)
            DashboardCardMotion.release(anchor)
            if (popup === window) {
                popup = null
                account = null
            }
        }

        popup = window
        account = profile
        window.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, lastX, lastY)
        TwidgetFonts.observeWindow(root.rootView)
    }

    @SuppressLint("RestrictedApi")
    private fun applySamsungBlur(blurView: View, cornerRadiusPx: Float): Boolean = runCatching {
        val builderClass = Class.forName("android.view.SemBlurInfo\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        if (SemBlurCompat.isBlurEffectPresetSupport()) {
            builderClass.getMethod("setColorCurvePreset", Int::class.javaPrimitiveType)
                .invoke(builder, 124)
        } else {
            builderClass.getMethod(
                "setColorCurve",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).invoke(builder, 0.5f, 15.0f, 0.0f, 255.0f, 13.5f, 133.4f)
            builderClass.getMethod("setRadius", Int::class.javaPrimitiveType)
                .invoke(builder, 300)
        }
        builderClass.getMethod("setBackgroundCornerRadius", Float::class.javaPrimitiveType)
            .invoke(builder, cornerRadiusPx)
        val blurInfo = builderClass.getMethod("build").invoke(builder)
        val semBlurInfoClass = Class.forName("android.view.SemBlurInfo")
        View::class.java.getMethod("semSetBlurInfo", semBlurInfoClass).invoke(blurView, blurInfo)
        true
    }.getOrDefault(false)

    private class BubbleOptionTransition(
        private val entering: Boolean,
        private val pivotX: Float,
        private val pivotY: Float,
    ) : Transition() {
        init { addTarget(R.id.popup_root_layout) }

        override fun captureStartValues(transitionValues: TransitionValues) = Unit
        override fun captureEndValues(transitionValues: TransitionValues) = Unit
        override fun isTransitionRequired(startValues: TransitionValues?, endValues: TransitionValues?): Boolean = true

        override fun createAnimator(
            sceneRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?,
        ): Animator? {
            val view = endValues?.view ?: startValues?.view ?: return null
            val originalVisibility = view.visibility
            if (Build.VERSION.SDK_INT >= 29) view.setTransitionVisibility(View.VISIBLE)
            else view.visibility = View.VISIBLE
            view.pivotX = pivotX
            view.pivotY = pivotY
            val fromAlpha = if (entering) 0f else 1f
            val toAlpha = if (entering) 1f else 0f
            val fromScale = if (entering) 0.8f else 1f
            val toScale = if (entering) 1f else 0.8f

            val alphaAnim = ObjectAnimator.ofFloat(view, View.ALPHA, fromAlpha, toAlpha).apply {
                duration = 200L
                interpolator = LinearInterpolator()
            }
            val scaleXAnim = ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, toScale).apply {
                duration = if (entering) 350L else 250L
                interpolator = SIN_IN_90
            }
            val scaleYAnim = ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, toScale).apply {
                duration = if (entering) 350L else 250L
                interpolator = SIN_IN_90
            }

            if (entering) {
                view.alpha = 0f
                view.scaleX = 0.8f
                view.scaleY = 0.8f
            }

            return AnimatorSet().apply {
                playTogether(alphaAnim, scaleXAnim, scaleYAnim)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (Build.VERSION.SDK_INT >= 29) view.setTransitionVisibility(originalVisibility)
                        else view.visibility = originalVisibility
                    }
                })
            }
        }

        private companion object {
            val SIN_IN_90 = PathInterpolator(0.22f, 0f, 0.5f, 1f)
        }
    }

    fun release() {
        popup?.takeIf { it.isShowing }?.apply {
            isFocusable = true
            update() // Only window flags change; no anchor lookup or remeasurement.
        }
    }

    fun validate(profile: String, editing: Boolean) {
        if (!editing || account?.let { it != profile } == true) dismiss()
    }

    fun dismissIfShowing(): Boolean {
        if (popup?.isShowing != true) return false
        dismiss()
        return true
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
        account = null
    }
}
