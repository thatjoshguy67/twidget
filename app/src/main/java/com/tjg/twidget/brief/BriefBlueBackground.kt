package com.tjg.twidget.brief

import android.graphics.*
import android.graphics.drawable.Drawable
import java.time.DayOfWeek
import java.time.ZonedDateTime

internal enum class BriefBlueTheme { MORNING, AFTERNOON, EVENING, NIGHT, WEEKEND;
    companion object {
        fun current(now: ZonedDateTime = ZonedDateTime.now()): BriefBlueTheme = when {
            now.hour < 5 || now.hour >= 23 -> NIGHT
            now.hour >= 17 -> EVENING
            now.hour >= 12 -> AFTERNOON
            now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> WEEKEND
            else -> MORNING
        }
    }
}

/** Shared by the Brief page and its dashboard material. */
internal fun briefBackgroundPalette(theme: BriefBlueTheme, night: Boolean, subdued: Boolean = false): List<Int> {
        return (if (night) when (theme) {
            BriefBlueTheme.MORNING -> longArrayOf(0xFF123650, 0xFF142B3D, 0xFF4AA1F4, 0xFFE3A974, 0xFF63BACA)
            BriefBlueTheme.AFTERNOON -> longArrayOf(0xFF003B63, 0xFF112F4A, 0xFF008FE5, 0xFF15B6D5, 0xFF2978E6)
            BriefBlueTheme.EVENING -> longArrayOf(0xFF2B1645, 0xFF171A36, 0xFF9B58D0, 0xFFCD7D9B, 0xFF4660C9)
            BriefBlueTheme.NIGHT -> longArrayOf(0xFF02091C, 0xFF0C1029, 0xFF194A86, 0xFF423F87, 0xFF126276)
            BriefBlueTheme.WEEKEND -> longArrayOf(0xFF073B3E, 0xFF162B43, 0xFF24BDA5, 0xFF367FE0, 0xFF7CA897)
        } else when (theme) {
            BriefBlueTheme.MORNING -> longArrayOf(0xFFE9E9E0, 0xFFFFF3DE, 0xFF65B6F4, 0xFFFFD6A3, 0xFFA8DCD6)
            BriefBlueTheme.AFTERNOON -> longArrayOf(0xFFAFE5F5, 0xFFEAF6FF, 0xFF32B3EC, 0xFF4ED9DC, 0xFF75ACF5)
            BriefBlueTheme.EVENING -> longArrayOf(0xFFDDD3F2, 0xFFFBE8E6, 0xFFA885DD, 0xFFE6AFBA, 0xFF91A5EA)
            BriefBlueTheme.NIGHT -> longArrayOf(0xFFCDD4E8, 0xFFE2E7F1, 0xFF789AC9, 0xFF9B96D2, 0xFF81ADB8)
            BriefBlueTheme.WEEKEND -> longArrayOf(0xFFD1F0E4, 0xFFE5EFF9, 0xFF56C5AA, 0xFF7CB9F3, 0xFFB8D49E)
        }).map { colour ->
            val softened = if (night) colour.toInt() else androidx.core.graphics.ColorUtils.blendARGB(
                colour.toInt(), 0xFFF4F5F8.toInt(), .2f)
            if (subdued) androidx.core.graphics.ColorUtils.blendARGB(softened,
                if (night) 0xFF0C1119.toInt() else 0xFFECEFF6.toInt(), .55f) else softened
        }
}

/** Distinct blue light pools. Cached shaders move without allocating new gradients each frame. */
internal class BriefBlueBackground(private val theme: BriefBlueTheme, private val night: Boolean,
    private val subdued: Boolean = false, private val density: Float = 1f,
    private val paletteOverride: List<Int>? = null,
    private val lightPoolRadiusScale: Float = 1f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layers = mutableListOf<Pair<Shader, Int>>()
    private val motionMatrix = Matrix()
    private var motion = 0f
    private var motionSpan = 1f
    private var entranceProgress = 1f
    fun setEntranceProgress(value: Float) { entranceProgress = value.coerceIn(0f, 1f); invalidateSelf() }
    fun setMotionProgress(value: Float) { motion = value; invalidateSelf() }
    var revealProgress: Float = 1f
        private set
    fun setUnlockProgress(value: Float) { revealProgress = value.coerceIn(0f, 1f); invalidateSelf() }
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        layers.clear()
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val scale = density.coerceAtLeast(.1f)
        val spread = ((w / scale - 400f) / 600f).coerceIn(0f, 1f)
        motionSpan = minOf(w, 480f * scale)
        // Day parts change the material's palette, not merely the position of identical blue glows.
        val palette = paletteOverride ?: briefBackgroundPalette(theme, night, subdued)
        layers += LinearGradient(0f, 0f, 0f, h, palette[0], palette[1], Shader.TileMode.CLAMP) to 255
        val positions = when (theme) {
            BriefBlueTheme.MORNING -> listOf(.05f to .05f, 1f to .27f, .25f to .64f)
            BriefBlueTheme.AFTERNOON -> listOf(.9f to .1f, .05f to .38f, .9f to .65f)
            BriefBlueTheme.EVENING -> listOf(.15f to .26f, 1f to .05f, .7f to .72f)
            BriefBlueTheme.NIGHT -> listOf(.9f to .05f, .1f to .3f, .5f to .7f)
            BriefBlueTheme.WEEKEND -> listOf(.5f to .03f, 0f to .42f, 1f to .5f)
        }
        val widePositions = when (theme) {
            BriefBlueTheme.MORNING -> listOf(.05f to .05f, .86f to .34f, .44f to .86f)
            BriefBlueTheme.AFTERNOON -> listOf(.96f to .02f, .12f to .58f, .7f to .92f)
            BriefBlueTheme.EVENING -> listOf(.1f to .18f, .9f to .06f, .56f to .9f)
            BriefBlueTheme.NIGHT -> listOf(.95f to 0f, .06f to .4f, .68f to .88f)
            BriefBlueTheme.WEEKEND -> listOf(.38f to 0f, .02f to .76f, 1f to .6f)
        }
        positions.forEachIndexed { index, (x, y) ->
            val (wideX, wideY) = widePositions[index]
            val centerX = (x + (wideX - x) * spread) * w
            val centerY = (y + (wideY - y) * spread) * h
            val color = palette[index + 2]
            val radius = maxOf(motionSpan * .65f, h * .28f) * floatArrayOf(1f, .83f, 1.15f)[index] * lightPoolRadiusScale
            layers += RadialGradient(centerX, centerY, radius,
                intArrayOf(color, color, color and 0x00FFFFFF), floatArrayOf(0f, .18f, 1f), Shader.TileMode.CLAMP) to
                if (night && theme == BriefBlueTheme.NIGHT) 110 else if (night) 160 else 170
        }
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val entrance = entranceProgress * entranceProgress * (3f - 2f * entranceProgress)
        layers.forEachIndexed { index, (shader, strength) ->
            if (index > 0) {
                val angle = motion * Math.PI * 2 + index * 2.1
                val scale = .55f + .45f * entrance
                motionMatrix.setScale(scale, scale, bounds.width() * .5f, bounds.height() * .65f)
                motionMatrix.postTranslate(
                    (kotlin.math.sin(angle) * motionSpan * .20).toFloat() +
                        (if (index % 2 == 0) 1f else -1f) * motionSpan * .18f * (1f - entrance),
                    (kotlin.math.cos(angle) * bounds.height() * .10).toFloat() + bounds.height() * .24f * (1f - entrance),
                )
                shader.setLocalMatrix(motionMatrix)
            }
            paint.shader = shader; paint.alpha = opacity * strength / 255; paint.colorFilter = filter
            if (index > 0) paint.alpha = (paint.alpha * (.05f + .95f * entrance)).toInt()
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        if (revealProgress < 1f) {
            paint.shader = null
            paint.color = Color.BLACK
            paint.alpha = (opacity * (if (night) .58f else .20f) * (1f - revealProgress)).toInt()
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val MOTION_PERIOD_MS = 18_000L
        fun motionPhase(at: Long = android.os.SystemClock.elapsedRealtime()): Float =
            (at % MOTION_PERIOD_MS).toFloat() / MOTION_PERIOD_MS
    }
}
