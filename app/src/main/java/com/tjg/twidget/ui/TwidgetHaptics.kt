package com.tjg.twidget.ui

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibrationAttributes
import android.media.AudioAttributes
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.tjg.twidget.R
import android.content.Context
import com.tjg.twidget.data.TwidgetStore

/** Primitive hold feedback and platform action effects, respecting touch-feedback settings. */
internal object TwidgetHaptics {
    private const val FORCE_WAVEFORMS = "debug_haptics_force_waveforms"

    fun forceWaveforms(context: Context): Boolean =
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).getBoolean(FORCE_WAVEFORMS, false)

    fun setForceWaveforms(context: Context, enabled: Boolean) {
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(FORCE_WAVEFORMS, enabled).apply()
    }

    fun canPreviewPrimitive(view: View, primitive: Int): Boolean =
        primitive in 1..8 && view.context.getSystemService(Vibrator::class.java)?.hasVibrator() == true

    fun quickRise(view: View) {
        previewPrimitive(view, 5) // PRIMITIVE_QUICK_RISE; waveform previews also work before API 30.
    }

    fun supportsPrimitive(view: View, primitive: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val vibrator = view.context.getSystemService(Vibrator::class.java) ?: return false
        return vibrator.hasVibrator() && vibrator.arePrimitivesSupported(primitive).all { it }
    }

    /** Debug previews use the same touch settings and attributes as production feedback. */
    fun previewPrimitive(view: View, primitive: Int, scale: Float = 0.7f): () -> Unit {
        if (!canPreviewPrimitive(view, primitive) ||
            !view.isHapticFeedbackEnabled || Settings.System.getInt(view.context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return {}
        val vibrator = view.context.getSystemService(Vibrator::class.java) ?: return {}
        if (forceWaveforms(view.context) || !supportsPrimitive(view, primitive)) {
            vibrateTouch(vibrator, primitiveWaveform(vibrator, primitive, scale))
        } else if (Build.VERSION.SDK_INT >= 30) {
            vibrateTouch(vibrator, VibrationEffect.startComposition().addPrimitive(primitive, scale).compose())
        }
        return { vibrator.cancel() }
    }

    /** Give scrolling 90 ms to cancel before feedback begins; keep the original hold deadline. */
    fun startHoldRamp(view: View, durationMs: Long): () -> Unit {
        val delayMs = minOf(90L, durationMs.coerceAtLeast(0))
        var cancelActiveRamp: (() -> Unit)? = null
        val start = Runnable {
            if (view.isAttachedToWindow) cancelActiveRamp = playHoldRamp(view, durationMs - delayMs)
        }
        view.postDelayed(start, delayMs)
        return {
            view.removeCallbacks(start)
            cancelActiveRamp?.invoke()
            cancelActiveRamp = null
        }
    }

    /** Cancellable primitive pulses build in strength; the transition supplies the final pop. */
    @Suppress("DEPRECATION")
    private fun playHoldRamp(view: View, durationMs: Long): () -> Unit {
        if (!view.isHapticFeedbackEnabled || Settings.System.getInt(view.context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return {}
        val vibrator = view.context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return {}
        if (!forceWaveforms(view.context) && Build.VERSION.SDK_INT >= 30) {
            val primitive = if (Build.VERSION.SDK_INT >= 31 &&
                vibrator.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK).all { it }) {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            } else VibrationEffect.Composition.PRIMITIVE_TICK
            if (vibrator.arePrimitivesSupported(primitive).all { it }) {
                val primitiveDuration = if (Build.VERSION.SDK_INT >= 31)
                    vibrator.getPrimitiveDurations(primitive).first().coerceAtLeast(1) else 12
                val steps = (durationMs / maxOf(primitiveDuration, 35)).toInt().coerceIn(2, 8)
                val interval = (durationMs / steps).toInt()
                val composition = VibrationEffect.startComposition()
                repeat(steps) { index ->
                    val progress = index.toFloat() / (steps - 1)
                    composition.addPrimitive(primitive, 0.04f + 0.48f * progress * progress,
                        if (index == 0) 0 else (interval - primitiveDuration).coerceAtLeast(0))
                }
                vibrateTouch(vibrator, composition.compose())
                return { vibrator.cancel() }
            }
        }
        val steps = 14
        val timings = LongArray(steps + 2) { durationMs.coerceAtLeast(steps.toLong()) / steps }
        timings[0] = 1
        timings[steps] += durationMs % steps
        timings[steps + 1] = 6
        val amplitudes = IntArray(steps + 2) { index ->
            if (index == 0 || index == steps + 1) 0 else {
                val progress = (index - 1).toFloat() / (steps - 1)
                (12 + 56 * progress * progress).toInt()
            }
        }
        val effect = if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
            else VibrationEffect.createWaveform(longArrayOf(0, 5, durationMs / 3, 8, durationMs / 3, 12), -1)
        vibrateTouch(vibrator, effect)
        return { vibrator.cancel() }
    }

    fun editModePop(view: View, entering: Boolean) {
        previewPrimitive(view, 1, if (entering) 0.8f else 0.5f)
    }

    @Suppress("DEPRECATION")
    private fun vibrateTouch(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }

    /** Waveform fallbacks approximate primitives on hardware without composition support. */
    private fun primitiveWaveform(vibrator: Vibrator, primitive: Int, scale: Float): VibrationEffect {
        if (!vibrator.hasAmplitudeControl()) {
            return VibrationEffect.createWaveform(longArrayOf(0, if (primitive == 8 || primitive == 7) 5 else 12, 6), -1)
        }
        val envelope = when (primitive) {
            8 -> floatArrayOf(0f, 0.2f, 0f)
            7 -> floatArrayOf(0f, 0.4f, 0f)
            1 -> floatArrayOf(0f, 1f, 0.4f, 0f)
            2 -> floatArrayOf(0f, 1f, 0.8f, 0.5f, 0.2f, 0f)
            3 -> floatArrayOf(0f, 0.3f, 0.8f, 0.3f, 0.8f, 0.3f, 0f)
            4, 5 -> FloatArray(12) { index -> if (index == 11) 0f else index / 10f }
            6 -> FloatArray(12) { index -> if (index == 0) 0f else (11 - index) / 10f }
            else -> floatArrayOf(0f, 1f, 0f)
        }
        return VibrationEffect.createWaveform(
            LongArray(envelope.size) { if (primitive == 4) 16L else 8L },
            IntArray(envelope.size) { (envelope[it] * scale * 255).toInt().coerceIn(0, 255) }, -1,
        )
    }

    fun longPress(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Older versions supply LONG_PRESS through View.performLongClick instead. */
    fun dragPickup(view: View) {
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        }
    }

    /** Soft repeated feedback while a dashboard tile is held. */
    fun dragHold(view: View) {
        // Older platforms cannot request this soft effect; retain their pickup and reorder ticks.
        if (Build.VERSION.SDK_INT >= 34) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }

    fun selection(view: View) {
        // Position changes can arrive on every drag frame. Keep ticks distinct, with no queued effects.
        val now = SystemClock.uptimeMillis()
        val previous = view.getTag(R.id.haptic_last_selection_time) as? Long
        if (previous != null && now - previous < 60L) return
        view.setTag(R.id.haptic_last_selection_time, now)
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    fun reject(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }

    fun refresh(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
}
