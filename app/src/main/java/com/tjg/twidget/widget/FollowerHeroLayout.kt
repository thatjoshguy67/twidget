package com.tjg.twidget.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Fits actual glyphs and font axes to a hero rectangle. Each occurrence owns its styling. */
internal class FollowerHeroLayout(
    private val words: List<String>,
    private val makePaint: (index: Int, fullness: Float) -> Paint,
) {
    internal data class Run(val index: Int, val text: String, val paint: Paint) {
        private var measuredSize = Float.NaN
        private val ink = Rect()
        var advance = 0f
            private set

        fun measure(size: Float): Rect {
            paint.textSize = size
            if (measuredSize != size) {
                paint.getTextBounds(text, 0, text.length, ink)
                advance = paint.measureText(text)
                measuredSize = size
            }
            return ink
        }
    }
    internal data class Layout(
        val lines: List<List<Run>>,
        val size: Float,
        val bounds: List<RectF>,
        val wordGap: Float,
        val lineGap: Float,
    ) {
        val ascent: Float get() = bounds.minOf { it.top }
        val advance: Float get() = bounds.maxOf { it.bottom } - ascent + lineGap
        val height: Float get() = bounds.last().bottom - bounds.first().top + (lines.size - 1) * advance
    }

    fun draw(canvas: Canvas, width: Float, height: Float, padding: Float, wordGap: Float, lineGap: Float) {
        if (words.isEmpty() || width <= 1f || height <= 1f) return
        val layout = fit(width, height, wordGap, lineGap)
        layout.lines.forEachIndexed { lineIndex, line ->
            var x = padding - layout.bounds[lineIndex].left
            val baseline = padding - layout.bounds.first().top + lineIndex * layout.advance
            line.forEach { run ->
                run.paint.textSize = layout.size
                canvas.drawText(run.text, x, baseline, run.paint)
                x += run.advance + layout.wordGap
            }
        }
    }

    internal fun fit(width: Float, height: Float, wordGap: Float, lineGap: Float): Layout {
        require(words.isNotEmpty() && width > 1f && height > 1f)
        // Leave a pixel for raster rounding/antialiasing. Reuse paints while
        // probing sizes during this render; never keep an unbounded global cache.
        val maxWidth = width - 1f
        val maxHeight = height - 1f
        val paints = mutableMapOf<Pair<Int, Float>, Paint>()
        fun runs(fullness: Float, indices: List<Int> = words.indices.toList()) = indices.map { index ->
            Run(index, words[index], paints.getOrPut(index to fullness) { makePaint(index, fullness) })
        }
        var best: Layout? = null
        var bestFullness = 0f
        var bestScore = 0f
        // Compare readable, moderately condensed settings with natural proportions.
        // Shrinking the font alone can leave an entire line's worth of unused space.
        for (fullness in listOf(0f, -0.5f, -1f)) {
            val candidateRuns = runs(fullness)
            val layout = fitSize(maxWidth, maxHeight, wordGap, lineGap) { size, gap ->
                wrap(candidateRuns, size, maxWidth, gap)
            }
            val score = layout.size * (1f + fullness * 0.10f)
            if (score > bestScore) {
                best = layout
                bestFullness = fullness
                bestScore = score
            }
        }
        val chosen = checkNotNull(best)
        // Open up each line independently, including repeated occurrences of the
        // same word. Width changes use the font's outlines, never Canvas scaling.
        val expanded = chosen.lines.map { line ->
            var low = bestFullness
            var high = 1.5f
            var result = line
            repeat(6) {
                val fullness = (low + high) / 2f
                val candidate = runs(fullness, line.map { it.index })
                if (measure(candidate, chosen.size, chosen.wordGap).width() <= maxWidth) {
                    result = candidate
                    low = fullness
                } else high = fullness
            }
            result
        }
        // Changing axes can also alter ascent/descent; verify the final outlines
        // against both dimensions while preserving the chosen line breaks.
        val fitted = fitSize(maxWidth, maxHeight, wordGap, lineGap, chosen.size) { _, _ -> expanded }
        if (fitted.lines.size == 1) return fitted
        // Short counts often fit two large lines with room left below. Spend some
        // of that room on leading instead of making already-large letters bigger.
        val sparePerLine = ((maxHeight - fitted.height) / (fitted.lines.size - 1)).coerceAtLeast(0f)
        return fitted.copy(lineGap = minOf(fitted.lineGap + sparePerLine,
            maxOf(fitted.lineGap, fitted.size * 0.40f)))
    }

    private fun fitSize(
        width: Float,
        height: Float,
        wordGap: Float,
        lineGap: Float,
        sizeLimit: Float = height * 2f,
        linesAt: (size: Float, gap: Float) -> List<List<Run>>,
    ): Layout {
        fun layout(size: Float): Layout {
            // Spacing follows the common font size, not a follower-count cutoff.
            // Dense paragraphs keep their existing minimum gaps; sparse ones breathe.
            val fittedWordGap = maxOf(wordGap, minOf(size * 0.16f, wordGap * 2.5f))
            val fittedLineGap = maxOf(lineGap, minOf(size * 0.20f, lineGap * 3f))
            val lines = linesAt(size, fittedWordGap)
            return Layout(lines, size, lines.map { measure(it, size, fittedWordGap) }, fittedWordGap, fittedLineGap)
        }
        var low = 0.01f
        var high = sizeLimit
        repeat(16) {
            val size = (low + high) / 2f
            val candidate = layout(size)
            if (candidate.bounds.all { it.width() <= width } && candidate.height <= height) low = size
            else high = size
        }
        return layout(low)
    }

    private fun wrap(runs: List<Run>, size: Float, width: Float, gap: Float): List<List<Run>> {
        val lines = mutableListOf<List<Run>>()
        var current = mutableListOf<Run>()
        val bounds = RectF()
        val next = RectF()
        var x = 0f
        runs.forEach { run ->
            val ink = run.measure(size)
            next.set(bounds)
            next.union(x + ink.left, ink.top.toFloat(), x + ink.right, ink.bottom.toFloat())
            if (current.isNotEmpty() && next.width() > width) {
                lines += current
                current = mutableListOf()
                bounds.setEmpty()
                x = 0f
            }
            current += run
            bounds.union(x + ink.left, ink.top.toFloat(), x + ink.right, ink.bottom.toFloat())
            x += run.advance + gap
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun measure(runs: List<Run>, size: Float, gap: Float): RectF {
        val bounds = RectF()
        var x = 0f
        runs.forEach { run ->
            val ink = run.measure(size)
            bounds.union(x + ink.left, ink.top.toFloat(), x + ink.right, ink.bottom.toFloat())
            x += run.advance + gap
        }
        return bounds
    }
}
