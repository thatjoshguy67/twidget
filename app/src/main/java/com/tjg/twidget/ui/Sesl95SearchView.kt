package com.tjg.twidget.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.inputmethod.InputMethodManager
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.tjg.twidget.R
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.view.KeyEvent
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.SearchView

/** In-place port of the newer SESL floating search button/bar interaction. */
class Sesl95SearchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    SearchView(context, attrs) {
    var isFloatingExpanded = false
        private set
    var onExpansionChanged: ((Boolean) -> Unit)? = null
    private val fabSize get() = (56f * resources.displayMetrics.density).toInt()
    private var surfaceWidth = 0f
    private var widthMotion: SpringAnimation? = null
    private var changingMode = false
    private var imeVisible = false
    private var expansionGeneration = 0
    private var trackingImeBack = false
    private var lastAvailableWidth = 0
    private val input get() = seslGetAutoCompleteView()
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var originalHintColors: ColorStateList? = null
    private var motion: ValueAnimator? = null
    private var elapsed = 0f
    private var played = false
    private var drawHint = false
    private val revealCurve = PathInterpolator(.6f, 0f, .83f, .83f)
    private val sweepCurve = PathInterpolator(.3f, .1f, .7f, .9f)
    private val start = IntArray(2)
    private val origin = IntArray(2)
    private val colors = intArrayOf(0xff7659ee.toInt(), 0xff3581ff.toInt(), 0xff31afff.toInt(), 0xff79ccca.toInt())
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!s.isNullOrEmpty()) { played = true; stopHint() }
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    init {
        input.addTextChangedListener(watcher)
        SeslKeyboardFocus.install(this, 28f)
        setIconifiedByDefault(true)
        isIconified = true
        surfaceWidth = fabSize.toFloat()
        val button = findViewById<View>(androidx.appcompat.R.id.search_button)
        button.layoutParams = button.layoutParams.apply { width = fabSize; height = fabSize }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.oneui_card_bg))
        }
        button.background = RippleDrawable(ColorStateList.valueOf(0x33777777), fill,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
        button.elevation = 4f * resources.displayMetrics.density
        SeslSurfaceHighlight.mark(button, fabSize / 2f)
        SeslSurfaceHighlight.applyTo(button)
        setOnSearchClickListener { if (!changingMode) expand() }
        seslSetUpButtonVisibility(View.GONE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (isFloatingExpanded) startHint() }
    }

    fun expand(showKeyboard: Boolean = true, animate: Boolean = true) {
        if (changingMode) return
        changingMode = true
        isFloatingExpanded = true
        expansionGeneration++
        seslSetNotCallShowSoftInput(!showKeyboard)
        isIconified = false
        seslSetUpButtonVisibility(View.GONE)
        changingMode = false
        resizeTo(targetWidth().toFloat(), animate)
        onExpansionChanged?.invoke(true)
        if (showKeyboard) {
            input.requestFocus()
            post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        } else clearFocus()
        seslSetNotCallShowSoftInput(false)
        post { startHint() }
    }

    fun collapse(animate: Boolean = true) {
        changingMode = true
        isFloatingExpanded = false
        imeVisible = false
        expansionGeneration++
        trackingImeBack = false
        stopHint()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        setQuery("", false)
        clearFocus()
        seslSetUpButtonVisibility(View.GONE)
        isIconified = true
        changingMode = false
        resizeTo(fabSize.toFloat(), animate)
        onExpansionChanged?.invoke(false)
    }

    /** Modern gesture Back is consumed by the IME before the activity callback. */
    fun onImeVisibilityChanged(visible: Boolean) {
        val dismissed = imeVisible && !visible
        imeVisible = visible
        if (!dismissed || !isFloatingExpanded || !hasWindowFocus()) return
        val generation = expansionGeneration
        post {
            if (generation == expansionGeneration && !imeVisible && isFloatingExpanded &&
                isAttachedToWindow && hasWindowFocus()) collapse()
        }
    }

    /** Key-based Back must also collapse instead of only clearing the EditText focus. */
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && isFloatingExpanded) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) trackingImeBack = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && trackingImeBack) {
                trackingImeBack = false
                if (!event.isCanceled) collapse()
                return true
            }
        }
        return super.dispatchKeyEventPreIme(event)
    }

    private fun targetWidth(): Int = minOf(
        resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_search_view_preferred_width),
        lastAvailableWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels - fabSize,
    ).coerceAtLeast(fabSize)

    private fun resizeTo(target: Float, animate: Boolean) {
        widthMotion?.cancel()
        if (!animate || !isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            surfaceWidth = target
            requestLayout()
            return
        }
        widthMotion = SpringAnimation(FloatValueHolder(surfaceWidth)).apply {
            spring = SpringForce(target).setDampingRatio(if (isFloatingExpanded) 1f else .84f)
                .setStiffness(if (isFloatingExpanded) 420f else 439f)
            setMinimumVisibleChange(.5f)
            addUpdateListener { _, value, _ -> surfaceWidth = value; requestLayout() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        if (available != lastAvailableWidth) {
            lastAvailableWidth = available
            widthMotion?.cancel()
            surfaceWidth = if (isFloatingExpanded) targetWidth().toFloat() else fabSize.toFloat()
        }
        val width = surfaceWidth.toInt().coerceIn(minOf(fabSize, available), available)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }

    private fun startHint() {
        if (!isFloatingExpanded || !isAttachedToWindow || played || windowVisibility != View.VISIBLE ||
            !input.text.isNullOrEmpty() || input.hint.isNullOrEmpty() || !ValueAnimator.areAnimatorsEnabled()) return
        played = true
        originalHintColors = input.hintTextColors
        input.setHintTextColor(android.graphics.Color.TRANSPARENT)
        drawHint = true
        motion = ValueAnimator.ofFloat(0f, 1900f).apply {
            duration = 1900
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { elapsed = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopHint() {
        motion?.cancel()
        motion = null
        drawHint = false
        originalHintColors?.let { input.setHintTextColor(it) }
        originalHintColors = null
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Can be called from View's constructor before our fields are initialized.
        if (visibility != View.VISIBLE && originalHintColors != null) stopHint()
    }

    override fun onDetachedFromWindow() {
        widthMotion?.cancel()
        stopHint()
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!drawHint || !input.text.isNullOrEmpty() || !input.isShown) return
        if (!ValueAnimator.areAnimatorsEnabled()) { stopHint(); return }
        val field = input
        val width = field.width - field.compoundPaddingLeft - field.compoundPaddingRight
        if (width <= 0) return
        hintPaint.set(field.paint)
        hintPaint.alpha = 255
        val text = TextUtils.ellipsize(field.hint, hintPaint, width.toFloat(), TextUtils.TruncateAt.END).toString()
        val textWidth = hintPaint.measureText(text)
        if (textWidth <= 0f) return
        field.getLocationInWindow(start)
        getLocationInWindow(origin)
        val rtl = field.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val left = (start[0] - origin[0] + field.compoundPaddingLeft).toFloat() +
            if (rtl) width - textWidth else 0f
        val baseline = (start[1] - origin[1] + field.baseline).toFloat()
        val reveal = revealCurve.getInterpolation(((elapsed - 350f) / 500f).coerceIn(0f, 1f))
        val save = canvas.save()
        val top = (start[1] - origin[1] + field.compoundPaddingTop).toFloat()
        val bottom = (start[1] - origin[1] + field.height - field.compoundPaddingBottom).toFloat()
        if (rtl) canvas.clipRect(left + textWidth * (1f - reveal), top, left + textWidth, bottom)
        else canvas.clipRect(left, top, left + textWidth * reveal, bottom)
        hintPaint.shader = LinearGradient(left, 0f, left + textWidth, 0f,
            if (rtl) colors.reversedArray() else colors, null, Shader.TileMode.CLAMP)
        canvas.drawText(text, left, baseline, hintPaint)
        if (elapsed in 600f..1899f) {
            val fraction = sweepCurve.getInterpolation(((elapsed - 600f) / 1300f).coerceIn(0f, 1f))
            val band = 140f * resources.displayMetrics.density
            val edge = left - band + (textWidth + band) * if (rtl) 1f - fraction else fraction
            hintPaint.shader = LinearGradient(edge, 0f, edge + band, 0f,
                intArrayOf(0x00ffffff, 0x99ffffff.toInt(), 0x00ffffff), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawText(text, left, baseline, hintPaint)
        }
        hintPaint.shader = null
        canvas.restoreToCount(save)
    }
}
