package com.tjg.twidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefEditorialSummary
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts

/** One Brief provider, built on the same RemoteViews surface path as Followers. */
class TwidgetBriefWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        BriefSettingsStore.setEnabled(context, true)
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        updateWidget(context, manager, id)
    }

    companion object {
        private const val BITMAP_BUDGET = 8_000_000L

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TwidgetBriefWidget::class.java))
                .forEach { updateWidget(context, manager, it) }
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val account = TwidgetStore.settings(context).username
            val snapshot = BriefStore.read(context, account)
                ?: if (account.isNotBlank()) BriefEngine.rebuild(context, account) else null
            val options = manager.getAppWidgetOptions(id)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 352)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 175)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val width = if (!TwidgetFonts.hasSystemOneUiSans && landscape) {
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
            } else minWidth
            val height = if (!TwidgetFonts.hasSystemOneUiSans && !landscape) {
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
            } else minHeight

            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !TwidgetFonts.hasSystemOneUiSans) {
                responsiveViews(context, id, options, width, height, account, snapshot)
            } else {
                createViews(context, id, width, height, account, snapshot)
            }
            manager.updateAppWidget(id, views)
            warmAvatars(context, manager, id, account)
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun responsiveViews(
            context: Context,
            id: Int,
            options: Bundle,
            currentWidth: Int,
            currentHeight: Int,
            account: String,
            snapshot: com.tjg.twidget.brief.BriefSnapshot?,
        ): RemoteViews {
            val views = linkedMapOf<SizeF, RemoteViews>()
            var bytes = 0L
            fun add(key: SizeF, width: Int, height: Int) {
                val cost = dp(context, width).toLong() * dp(context, height).toLong() * 4L
                if (bytes + cost > BITMAP_BUDGET || views.containsKey(key)) return
                views[key] = createViews(context, id, width, height, account, snapshot)
                bytes += cost
            }
            widgetSizes(options)
                .sortedBy { kotlin.math.abs(it.width - currentWidth) + kotlin.math.abs(it.height - currentHeight) }
                .forEach { size -> add(size, size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)) }
            add(SizeF(110f, 40f), 162, 76)
            add(SizeF(231f, 40f), 352, 76)
            add(SizeF(110f, 111f), 162, 176)
            add(SizeF(231f, 111f), 352, 175)
            return RemoteViews(views)
        }

        private fun createViews(
            context: Context,
            id: Int,
            width: Int,
            height: Int,
            account: String,
            snapshot: com.tjg.twidget.brief.BriefSnapshot?,
        ): RemoteViews {
            val oneRow = height <= 110
            val summary = snapshot?.let(BriefEditorialSummary::from)
            val settings = TwidgetStore.widgetSettings(context, id)
            val dark = isDark(context, settings.colorMode)
            val base = if (dark) 16 else 255
            val backgroundColor = Color.argb(settings.tintAlpha, base, base, base)
            return RemoteViews(
                context.packageName,
                if (oneRow) R.layout.widget_brief_pill else R.layout.widget_brief_card,
            ).apply {
                // Keep this identical to Followers: tint the existing rounded
                // drawable because One UI owns the blur behind that surface.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setColorStateList(
                        android.R.id.background,
                        "setBackgroundTintList",
                        ColorStateList.valueOf(backgroundColor),
                    )
                } else {
                    setInt(android.R.id.background, "setBackgroundColor", backgroundColor)
                }
                setImageViewBitmap(
                    R.id.brief_widget_artwork,
                    BriefWidgetArtworkRenderer.render(
                        context = context,
                        widthPx = dp(context, width),
                        heightPx = dp(context, height),
                        account = account,
                        snapshot = snapshot,
                        dark = dark,
                        fontFamily = settings.fontFamily,
                    ),
                )
                setContentDescription(
                    android.R.id.background,
                    listOfNotNull(summary?.title, summary?.body).joinToString(". ")
                        .ifBlank { context.getString(R.string.brief_widget_empty_title) },
                )
                if (account.isNotBlank()) {
                    setOnClickPendingIntent(
                        android.R.id.background,
                        PendingIntent.getActivity(
                            context,
                            9321 + id,
                            TwidgetBriefActivity.intent(context, account),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        }

        private fun warmAvatars(context: Context, manager: AppWidgetManager, id: Int, account: String) {
            val urls = listOf(
                TopFollowersStore.read(context, account).top.firstOrNull()?.avatarUrl.orEmpty(),
            ).filter(String::isNotBlank).distinct()
            val missing = urls.filter { ProfileImageLoader.cachedBitmap(context, it) == null }
            if (missing.isEmpty()) return
            AppExecutors.execute {
                var changed = false
                missing.forEach { changed = ProfileImageLoader.downloadToCache(context, it) != null || changed }
                if (changed) updateWidget(context, manager, id)
            }
        }

        @Suppress("DEPRECATION")
        private fun widgetSizes(options: Bundle): List<SizeF> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java).orEmpty()
            } else options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        private fun isDark(context: Context, colorMode: String): Boolean =
            when (colorMode) {
                TwidgetStore.COLOR_MODE_DARK -> true
                TwidgetStore.COLOR_MODE_LIGHT -> false
                else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
    }
}
