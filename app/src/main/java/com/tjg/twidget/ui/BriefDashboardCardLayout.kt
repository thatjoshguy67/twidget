package com.tjg.twidget.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.tjg.twidget.R
import java.util.Random
import kotlin.math.roundToInt

/** Widget-style editorial copy with the dashboard generation and reveal effects. */
class BriefDashboardCardLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleShimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val glowBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val cardPath = Path()
    private val cardRect = RectF()
    private val glowDstRect = RectF()
    private var titleShimmerAnimator: ValueAnimator? = null
    private var titleShimmerProgress = -1f

    private val smartThingsAiColors = intArrayOf(
        0xFFBDFAA5.toInt(),
        0xFF88E6E3.toInt(),
        0xFF8BBDFF.toInt(),
        0xFFC2B2FF.toInt()
    )
    private val smartThingsAiStops = floatArrayOf(0f, 0.35f, 0.7f, 1f)
    private val shimmerMaskColors = intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT)
    private val shimmerMaskStops = floatArrayOf(0f, 0.5f, 1f)

    private var ambientEdgeShadow: CardShadow.Renderer? = null
    private var spotEdgeShadow: CardShadow.Renderer? = null
    private var lastShadowRadius = -1f
    private var lastShadowNight = false

    private fun ensureEdgeShadows(cardRadius: Float, isNight: Boolean) {
        if (ambientEdgeShadow == null || spotEdgeShadow == null || lastShadowRadius != cardRadius || lastShadowNight != isNight) {
            lastShadowRadius = cardRadius
            lastShadowNight = isNight
            ambientEdgeShadow = CardShadow.renderer(
                context, cardRadius,
                blurDp = 2.0f, offsetDp = 0.5f,
                shadowColor = if (isNight) Color.argb(0x24, 0, 0, 0) else Color.argb(0x0E, 0, 0, 0)
            )
            spotEdgeShadow = CardShadow.renderer(
                context, cardRadius,
                blurDp = 3.0f, offsetDp = 1.0f,
                shadowColor = if (isNight) Color.argb(0x34, 0, 0, 0) else Color.argb(0x16, 0, 0, 0)
            )
        }
    }

    override fun draw(canvas: Canvas) {
        if (width > 0 && height > 0) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cardRadius = 28f * resources.displayMetrics.density
            val hInset = dp(12)
            val vInset = dp(24)
            val bottomGlow = dp(12).toFloat()
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val glowPair = getDitheredGlowBitmaps(context, isLandscape)

            glowDstRect.set(-hInset.toFloat(), -vInset.toFloat(), w + hInset, h + vInset)

            if (glowPair != null) {
                val outerSave = canvas.save()
                canvas.clipRect(-w, 0f, w * 2f, h + bottomGlow)
                canvas.drawBitmap(glowPair.first, null, glowDstRect, glowBitmapPaint)
                canvas.restoreToCount(outerSave)
            }

            cardRect.set(0f, 0f, w, h)
            cardPath.rewind()
            cardPath.addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)

            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val surfaceColor = if (isNight) Color.rgb(0x25, 0x25, 0x28) else Color.rgb(0xFC, 0xFC, 0xFF)

            ensureEdgeShadows(cardRadius, isNight)
            val pad = (CardShadow.PADDING_DP * resources.displayMetrics.density).roundToInt()
            val shadowSave = canvas.save()
            canvas.clipOutPath(cardPath)
            canvas.translate(-pad.toFloat(), -pad.toFloat())
            ambientEdgeShadow?.draw(canvas, width + pad * 2, height + pad * 2)
            spotEdgeShadow?.draw(canvas, width + pad * 2, height + pad * 2)
            canvas.restoreToCount(shadowSave)

            basePaint.color = surfaceColor
            basePaint.alpha = if (isNight) 205 else 215
            canvas.drawPath(cardPath, basePaint)

            if (glowPair != null) {
                val save = canvas.save()
                canvas.clipPath(cardPath)
                canvas.drawBitmap(glowPair.second, null, glowDstRect, glowBitmapPaint)
                canvas.restoreToCount(save)
            }

            val overlayAlpha = if (isNight) 18 else 65
            overlayPaint.color = surfaceColor
            overlayPaint.alpha = overlayAlpha
            canvas.drawPath(cardPath, overlayPaint)
        }
        super.draw(canvas)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val face = TwidgetFonts.forApp(context, 700)
        findViewById<TextView>(R.id.brief_dashboard_title).apply {
            typeface = face
            paint.isFakeBoldText = false
        }
        findViewById<TextView>(R.id.brief_dashboard_message).apply {
            typeface = TwidgetFonts.forApp(context, 400)
            paint.isFakeBoldText = false
        }
        findViewById<TextView>(R.id.brief_dashboard_status).apply {
            typeface = TwidgetFonts.forApp(context, 400)
            paint.isFakeBoldText = false
        }
        findViewById<LottieAnimationView>(R.id.brief_dashboard_generating)?.let { lottie ->
            val tintColor = context.getColor(R.color.brief_explore_title)
            val applyTint = {
                lottie.addValueCallback(
                    KeyPath("**"),
                    LottieProperty.COLOR_FILTER,
                    LottieValueCallback<ColorFilter>(SimpleColorFilter(tintColor)),
                )
            }
            applyTint()
            lottie.addLottieOnCompositionLoadedListener { applyTint() }
        }
        clipToOutline = false
        val cardRadius = 28f * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
            }
        }
        elevation = 0f
        updateShadows()
        SeslSurfaceHighlight.attach(this, cardRadius)
    }

    private fun updateShadows() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            outlineAmbientShadowColor = if (isNight) Color.argb(0x40, 0, 0, 0) else Color.argb(0x14, 0, 0, 0)
            outlineSpotShadowColor = if (isNight) Color.argb(0x14, 0, 0, 0) else Color.argb(0x08, 0, 0, 0)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateShadows()
        var p = parent
        while (p is android.view.ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            if (p.id == R.id.main_toolbar_layout) break
            p = p.parent
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateShadows()
        invalidate()
    }

    /**
     * Authentic SmartThings NowCard AI transition: plays the signature ShimmerOnceText
     * animation on the card title, sweeping a 4-color vertical AI gradient (#BDFAA5,
     * #88E6E3, #8BBDFF, #C2B2FF) with a traveling specular highlight across the text.
     */
    fun startNowCardEntranceAnimation(onEnd: (() -> Unit)? = null) {
        titleShimmerAnimator?.cancel()
        titleShimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = PathInterpolator(0.33f, 0f, 0.24f, 1f)
            addUpdateListener { animator ->
                titleShimmerProgress = animator.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    titleShimmerProgress = -1f
                    titleShimmerAnimator = null
                    postInvalidateOnAnimation()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Compatibility alias for [startNowCardEntranceAnimation]. */
    fun startGradientSweep(onEnd: (() -> Unit)? = null) {
        startNowCardEntranceAnimation(onEnd)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawTitleShimmer(canvas)
    }

    private fun drawTitleShimmer(canvas: Canvas) {
        if (titleShimmerProgress !in 0f..1f) return
        val titleView = findViewById<TextView>(R.id.brief_dashboard_title) ?: return
        if (titleView.visibility != View.VISIBLE) return

        var accumulatedAlpha = 1f
        var v: View? = titleView
        while (v != null && v !== this) {
            accumulatedAlpha *= v.alpha
            v = v.parent as? View
        }
        if (accumulatedAlpha <= 0.01f) return

        val layout = titleView.layout ?: return
        val textWidth = titleView.width.toFloat()
        val textHeight = titleView.height.toFloat()
        if (textWidth <= 0f || textHeight <= 0f) return

        val verticalGradient = LinearGradient(
            0f, 0f, 0f, textHeight,
            smartThingsAiColors, smartThingsAiStops,
            Shader.TileMode.CLAMP
        )

        val bandWidth = textWidth * 0.35f
        val totalDistance = textWidth + bandWidth * 2f
        val startX = -bandWidth + titleShimmerProgress * totalDistance
        val endX = startX + bandWidth

        val horizontalBand = LinearGradient(
            startX, 0f, endX, 0f,
            shimmerMaskColors, shimmerMaskStops,
            Shader.TileMode.CLAMP
        )

        val combinedShader = ComposeShader(verticalGradient, horizontalBand, PorterDuff.Mode.DST_IN)

        titleShimmerPaint.set(titleView.paint)
        titleShimmerPaint.shader = combinedShader
        titleShimmerPaint.alpha = (accumulatedAlpha * 255).roundToInt().coerceIn(0, 255)

        var offsetX = 0f
        var offsetY = 0f
        var cur: View? = titleView
        while (cur != null && cur !== this) {
            offsetX += cur.left + cur.translationX
            offsetY += cur.top + cur.translationY
            cur = cur.parent as? View
        }

        val save = canvas.save()
        canvas.translate(offsetX + titleView.totalPaddingLeft, offsetY + titleView.totalPaddingTop)
        canvas.clipRect(0f, 0f, textWidth - titleView.totalPaddingLeft - titleView.totalPaddingRight,
            textHeight - titleView.totalPaddingTop - titleView.totalPaddingBottom)

        for (i in 0 until layout.lineCount) {
            val lineStart = layout.getLineStart(i)
            val lineEnd = layout.getLineEnd(i)
            val lineText = titleView.text.subSequence(lineStart, lineEnd).toString()
            val x = layout.getLineLeft(i)
            val y = layout.getLineBaseline(i).toFloat()
            canvas.drawText(lineText, x, y, titleShimmerPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        titleShimmerAnimator?.cancel()
        titleShimmerAnimator = null
        titleShimmerProgress = -1f
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        val icon = findViewById<View>(R.id.brief_dashboard_icon)
        fun artwork(size: Int, gap: Int) {
            icon?.apply {
                layoutParams.width = dp(size)
                layoutParams.height = dp(size)
                (layoutParams as LayoutParams).bottomMargin = dp(gap)
            }
        }
        artwork(32, 8)
        if (icon?.visibility == View.GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val title = findViewById<TextView>(R.id.brief_dashboard_title)
        val body = findViewById<TextView>(R.id.brief_dashboard_message)
        title?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                if ((MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).roundToInt() < 340) 22f else 24f)
            paint.isFakeBoldText = false
            maxLines = 3
        }
        body?.maxLines = 3
        if (title != null && body != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val width = MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - inset * 2).coerceAtLeast(1), MeasureSpec.EXACTLY)
            fun intrinsic(text: TextView, lines: Int): Int {
                text.maxLines = lines
                text.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                return text.measuredHeight
            }
            var available = (MeasureSpec.getSize(heightMeasureSpec) - inset * 2 - dp(32 + 8)).coerceAtLeast(0)
            var gap = dp(6)
            var titleLines = 3
            var titleHeight = intrinsic(title, titleLines)
            val bodyHeights = (1..3).associateWith { intrinsic(body, it) }
            val minimumBody = bodyHeights.getValue(1)
            val compactAvailable = (MeasureSpec.getSize(heightMeasureSpec) - inset * 2 - dp(28 + 4)).coerceAtLeast(0)
            // Measure the actual selected font and emphasis spans. Reserving two
            // nominal body line-heights used to force a perfectly usable title to one line.
            if (titleHeight + gap + minimumBody > available ||
                (bodyHeights.getValue(2) > minimumBody && titleHeight + gap + bodyHeights.getValue(2) > available &&
                    titleHeight + dp(4) + bodyHeights.getValue(2) <= compactAvailable)) {
                artwork(28, 4)
                available = compactAvailable
                gap = dp(4)
            }
            while (titleLines > 1 && titleHeight + gap + minimumBody > available) {
                titleHeight = intrinsic(title, --titleLines)
            }
            title.maxLines = titleLines
            val remaining = (available - titleHeight - gap).coerceAtLeast(0)
            body.maxLines = (3 downTo 1).firstOrNull { bodyHeights.getValue(it) <= remaining } ?: 1
            (body.layoutParams as LayoutParams).topMargin = gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    private companion object {
        private var cachedPortraitGlow: Pair<Bitmap, Bitmap>? = null
        private var cachedLandscapeGlow: Pair<Bitmap, Bitmap>? = null

        @Synchronized
        fun getDitheredGlowBitmaps(context: Context, isLandscape: Boolean): Pair<Bitmap, Bitmap>? {
            val existing = if (isLandscape) cachedLandscapeGlow else cachedPortraitGlow
            if (existing != null) return existing

            val resId = if (isLandscape) R.drawable.brief_insight_gradient_bg_horizontal else R.drawable.brief_insight_gradient_bg
            val drawable = ContextCompat.getDrawable(context, resId) ?: return null
            val bw = if (isLandscape) 312 else 256
            val bh = if (isLandscape) 80 else 164

            val srcBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val srcCanvas = Canvas(srcBitmap)
            drawable.setBounds(0, 0, bw, bh)
            drawable.draw(srcCanvas)

            val n = bw * bh
            val pixels = IntArray(n)
            srcBitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
            srcBitmap.recycle()

            // Convert to premultiplied 32-bit float to dissolve 8-bit quantization steps
            var pr = FloatArray(n)
            var pg = FloatArray(n)
            var pb = FloatArray(n)
            var pa = FloatArray(n)
            for (i in 0 until n) {
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                val f = a / 255f
                pr[i] = ((c ushr 16) and 0xFF) * f
                pg[i] = ((c ushr 8) and 0xFF) * f
                pb[i] = (c and 0xFF) * f
                pa[i] = a.toFloat()
            }

            // Two-pass separable box blur (approximates Gaussian) in float precision
            val tmpR = FloatArray(n)
            val tmpG = FloatArray(n)
            val tmpB = FloatArray(n)
            val tmpA = FloatArray(n)
            val radius = 5
            repeat(2) {
                for (y in 0 until bh) {
                    val rowOff = y * bw
                    for (x in 0 until bw) {
                        var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f; var cnt = 0
                        val x0 = (x - radius).coerceAtLeast(0)
                        val x1 = (x + radius).coerceAtMost(bw - 1)
                        for (kx in x0..x1) {
                            val idx = rowOff + kx
                            sr += pr[idx]; sg += pg[idx]; sb += pb[idx]; sa += pa[idx]
                            cnt++
                        }
                        val inv = 1f / cnt
                        val outIdx = rowOff + x
                        tmpR[outIdx] = sr * inv; tmpG[outIdx] = sg * inv
                        tmpB[outIdx] = sb * inv; tmpA[outIdx] = sa * inv
                    }
                }
                for (y in 0 until bh) {
                    val y0 = (y - radius).coerceAtLeast(0)
                    val y1 = (y + radius).coerceAtMost(bh - 1)
                    val cnt = (y1 - y0 + 1).toFloat()
                    val inv = 1f / cnt
                    val rowOff = y * bw
                    for (x in 0 until bw) {
                        var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
                        for (ky in y0..y1) {
                            val idx = ky * bw + x
                            sr += tmpR[idx]; sg += tmpG[idx]; sb += tmpB[idx]; sa += tmpA[idx]
                        }
                        val outIdx = rowOff + x
                        pr[outIdx] = sr * inv; pg[outIdx] = sg * inv
                        pb[outIdx] = sb * inv; pa[outIdx] = sa * inv
                    }
                }
            }

            val outerPixels = IntArray(n)
            val innerPixels = IntArray(n)
            val rng = Random(0x5F3759DFL)

            for (y in 0 until bh) {
                val fy = y.toFloat() / (bh - 1).coerceAtLeast(1)
                // Smooth vertical fade curve for the outer glow (0 above top edge, gentle taper at bottom)
                val outerFade = when {
                    fy <= 0.13f -> 0f
                    fy < 0.28f -> {
                        val t = (fy - 0.13f) / 0.15f
                        t * t * (3f - 2f * t) * 0.68f
                    }
                    fy < 0.52f -> {
                        val t = (fy - 0.28f) / 0.24f
                        0.68f - t * 0.08f
                    }
                    fy < 0.85f -> {
                        val t = (fy - 0.52f) / 0.33f
                        val s = t * t * (3f - 2f * t)
                        0.60f * (1f - s) + 0.16f * s
                    }
                    fy < 0.93f -> {
                        val t = (fy - 0.85f) / 0.08f
                        val s = t * t * (3f - 2f * t)
                        0.16f * (1f - s)
                    }
                    else -> 0f
                }

                val rowOff = y * bw
                for (x in 0 until bw) {
                    val idx = rowOff + x
                    val aOrig = (pa[idx] / 255f).coerceIn(0f, 1f)
                    if (aOrig <= 0.0005f) {
                        outerPixels[idx] = 0
                        innerPixels[idx] = 0
                        continue
                    }
                    val invA = 255f / pa[idx]
                    val rUnpre = (pr[idx] * invA).coerceIn(0f, 255f)
                    val gUnpre = (pg[idx] * invA).coerceIn(0f, 255f)
                    val bUnpre = (pb[idx] * invA).coerceIn(0f, 255f)

                    // Triangular PDF dither in [-0.9, +0.9] LSB to break up 8-bit banding
                    val ditherA = (rng.nextFloat() - rng.nextFloat()) * 0.9f
                    val ditherC = (rng.nextFloat() - rng.nextFloat()) * 0.9f

                    // Inner glow: exact 2-pass alpha composition in float precision
                    val aInner = (1f - (1f - aOrig) * (1f - aOrig)) * 255f
                    val ai = (aInner + ditherA).roundToInt().coerceIn(0, 255)
                    if (ai > 0) {
                        val ri = (rUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        val gi = (gUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        val bi = (bUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        innerPixels[idx] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }

                    // Outer glow: pre-faded in float precision
                    val aOuter = aOrig * outerFade * 255f
                    val ao = (aOuter + ditherA).roundToInt().coerceIn(0, 255)
                    if (ao > 0) {
                        val ro = (rUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        val go = (gUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        val bo = (bUnpre + ditherC).roundToInt().coerceIn(0, 255)
                        outerPixels[idx] = (ao shl 24) or (ro shl 16) or (go shl 8) or bo
                    }
                }
            }

            val outerBmp = Bitmap.createBitmap(outerPixels, bw, bh, Bitmap.Config.ARGB_8888)
            val innerBmp = Bitmap.createBitmap(innerPixels, bw, bh, Bitmap.Config.ARGB_8888)
            val pair = outerBmp to innerBmp
            if (isLandscape) cachedLandscapeGlow = pair else cachedPortraitGlow = pair
            return pair
        }
    }
}
