package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.widget.FollowerHeroLayout
import com.tjg.twidget.widget.WidgetArtworkRenderer
import com.tjg.twidget.widget.WidgetStyle

/** Live vector text using exactly the home-screen widget's wrapping and font-axis fitting. */
internal class FollowerCountCardView(context: Context) : View(context) {
    private var hero: FollowerHeroLayout? = null
    private var fitted: FollowerHeroLayout.Layout? = null
    private var fittedWidth = 0
    private var fittedHeight = 0

    fun bind(stats: ProfileStats, delta: Long) {
        val family = when (AppAppearance.font(context)) {
            AppAppearance.Font.GOOGLE_SANS_FLEX -> TwidgetStore.FONT_GOOGLE_SANS_FLEX
            AppAppearance.Font.SYSTEM -> TwidgetStore.FONT_SYSTEM
            AppAppearance.Font.DEFAULT -> TwidgetStore.FONT_ONE_UI_SANS
        }
        val settings = TwidgetWidgetSettings(tintAlpha = 255, tintColor = 0, logo = TwidgetStore.LOGO_X,
            tapAction = TwidgetStore.TAP_APP, accountUsername = stats.userName, colorMode = TwidgetStore.COLOR_MODE_SYSTEM,
            fontFamily = family, language = AppLocales.applicationLocale().toLanguageTag(), style = WidgetStyle.MATERIAL)
        val (words, countWordCount) = WidgetArtworkRenderer.followerHeroWords(context, settings, stats, delta)
        hero = WidgetArtworkRenderer.followerHero(context, settings, words, countWordCount,
            context.getColor(R.color.oneui_text_primary), context.getColor(R.color.oneui_text_secondary),
            context.getColor(if (delta < 0) R.color.metric_red else R.color.dashboard_count_editorial_delta))
        contentDescription = words.joinToString(" ")
        fitted = null
        invalidate()
    }

    internal fun fittedLayout(): FollowerHeroLayout.Layout? {
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 1 || availableHeight <= 1) return null
        if (fitted == null || fittedWidth != availableWidth || fittedHeight != availableHeight) {
            fittedWidth = availableWidth
            fittedHeight = availableHeight
            fitted = hero?.fit(availableWidth.toFloat(), availableHeight.toFloat(),
                WidgetArtworkRenderer.wordSpacing(context, availableWidth.toFloat()), 4 * resources.displayMetrics.density)
        }
        return fitted
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = fittedLayout() ?: return
        val save = canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        hero?.draw(canvas, layout, 0f)
        canvas.restoreToCount(save)
    }
}
