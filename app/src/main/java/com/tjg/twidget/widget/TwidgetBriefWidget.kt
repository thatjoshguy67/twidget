package com.tjg.twidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
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
import com.tjg.twidget.brief.BriefStrings
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
            val variants = widgetArtworkVariants(TwidgetStore.widgetSettings(context, id))
            fun add(key: SizeF, width: Int, height: Int) {
                val cost = dp(context, width).toLong() * dp(context, height).toLong() * 4L * variants
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

        internal fun createViews(
            context: Context,
            id: Int,
            width: Int,
            height: Int,
            account: String,
            snapshot: com.tjg.twidget.brief.BriefSnapshot?,
        ): RemoteViews {
            val oneRow = height <= 110
            val settings = TwidgetStore.widgetSettings(context, id)
            val localizedContext = com.tjg.twidget.core.AppLocales.wrap(context, settings.language)
            val strings = BriefStrings.from(context, settings.language)
            val summary = snapshot?.let { BriefEditorialSummary.from(it, strings) }
            val dark = isDark(context, settings.colorMode)
            val backgroundColor = WidgetColors.resolve(context, settings, dark).background
            return RemoteViews(
                context.packageName,
                if (oneRow) R.layout.widget_brief_pill else R.layout.widget_brief_card,
            ).apply {
                setInt(android.R.id.background, "setBackgroundResource",
                    if (settings.style == WidgetStyle.MATERIAL) R.drawable.widget_material_surface
                    else if (oneRow) R.drawable.widget_brief_pill_surface else R.drawable.widget_brief_card_surface)
                // Keep this identical to Followers: tint the existing rounded
                // drawable because One UI owns the blur behind that surface.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setWidgetBackgroundTint(context, settings)
                } else {
                    setInt(android.R.id.background, "setBackgroundColor", Color.TRANSPARENT)
                }
                setWidgetArtwork(R.id.brief_widget_artwork, settings) { artworkDark ->
                    BriefWidgetArtworkRenderer.render(
                        context = localizedContext,
                        strings = strings,
                        widthPx = dp(context, width),
                        heightPx = dp(context, height),
                        account = account,
                        snapshot = snapshot,
                        dark = artworkDark,
                        fontFamily = settings.fontFamily,
                        style = settings.style,
                        background = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) backgroundColor else null,
                    )
                }
                setContentDescription(
                    android.R.id.background,
                    listOfNotNull(summary?.title, summary?.body).joinToString(". ")
                        .ifBlank { localizedContext.getString(R.string.brief_widget_empty_title) },
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
                TwidgetStore.currentStats(context, account).profileImage,
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

        private fun isDark(context: Context, colorMode: String): Boolean = widgetUsesDarkTheme(colorMode)
    }
}
