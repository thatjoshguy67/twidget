package com.tjg.twidget.ui

/** A finite fade-in envelope; timing remainders keep it aligned with the animation. */
internal data class AnimationHapticRamp(val timings: LongArray, val amplitudes: IntArray) {
    companion object {
        /** Rise gently through the full visual reveal, then fade to silence with its final frame. */
        fun reveal(durationMs: Long, startProgress: Float, amplitudeControl: Boolean): AnimationHapticRamp {
            require(durationMs > 0)
            val start = startProgress.coerceIn(0f, 1f)
            val steps = ((durationMs + 19) / 20).toInt().coerceIn(1, 512)
            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            repeat(steps) { index ->
                val length = durationMs / steps + if (index < durationMs % steps) 1 else 0
                val progress = if (steps == 1) 1f else start + (1f - start) * index / (steps - 1)
                val strength = kotlin.math.sin(Math.PI * progress).coerceAtLeast(0.0)
                val amplitude = if (index == steps - 1) 0 else (10 * (1 - progress) + 62 * strength).toInt()
                if (amplitudeControl || amplitude == 0) {
                    timings += length
                    amplitudes += amplitude
                } else {
                    val on = (length * amplitude / 255.0).toLong().coerceIn(1, length)
                    timings += on
                    amplitudes += -1
                    if (length > on) { timings += length - on; amplitudes += 0 }
                }
            }
            return AnimationHapticRamp(timings.toLongArray(), amplitudes.toIntArray())
        }

        fun create(durationMs: Long, amplitudeControl: Boolean): AnimationHapticRamp {
            require(durationMs > 0)
            val steps = ((durationMs + 19) / 20).toInt().coerceIn(1, 512)
            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            repeat(steps) { index ->
                val length = durationMs / steps + if (index < durationMs % steps) 1 else 0
                val progress = if (steps == 1) 1f else index.toFloat() / (steps - 1)
                val strength = progress * progress
                if (amplitudeControl) {
                    timings += length
                    amplitudes += (6 + 114 * strength).toInt()
                } else {
                    // Fixed-strength motors approximate the rise with increasing duty cycle.
                    val on = (length * (.06f + .54f * strength)).toLong().coerceIn(1, length)
                    timings += on
                    amplitudes += -1 // VibrationEffect.DEFAULT_AMPLITUDE
                    if (length > on) {
                        timings += length - on
                        amplitudes += 0
                    }
                }
            }
            return AnimationHapticRamp(timings.toLongArray(), amplitudes.toIntArray())
        }
    }
}
