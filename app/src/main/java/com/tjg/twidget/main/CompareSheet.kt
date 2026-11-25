package com.tjg.twidget.main

import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.FxTwitterClient
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.R as OneUiIconR

/** Read-only comparison: public profile and original-post analytics, with local history when identity matches. */
internal class CompareSheet(private val activity: MainActivity) {
    private data class Result(val profile: ProfileStats, val posts: ComparePostTotals?, val delta: Long?, val analyticsError: Boolean)
    private data class Slot(var handle: String, var loading: Boolean = false, var failed: Boolean = false, var result: Result? = null, var generation: Int = 0)
    private val tracked = (listOf(activity.selectedAccount) + TwidgetStore.accounts(activity)).filter { it.isNotBlank() }.distinctBy { it.lowercase(java.util.Locale.US) }
    private val slots = arrayOf(Slot(activity.selectedAccount), Slot(tracked.firstOrNull { !it.equals(activity.selectedAccount, true) }.orEmpty()))
    private val dialog = BottomSheetDialog(activity)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val locale get() = AppLocales.applicationLocale()
    private fun dp(value: Int) = activity.dp(value)
    private fun text(value: CharSequence, size: Float, bold: Boolean = false, secondary: Boolean = false): TextView = TextView(activity).apply {
        text = value; textSize = size; includeFontPadding = false
        typeface = TwidgetFonts.forApp(activity, if (bold) 700 else 400)
        setTextColor(activity.getColor(if (secondary) R.color.oneui_text_secondary else R.color.oneui_text_primary))
    }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun rounded(color: Int, radius: Int = 22) = GradientDrawable().apply { setColor(activity.getColor(color)); cornerRadius = dp(radius).toFloat() }
    private fun count(value: Long?) = value?.let { CompareLogic.compact(it, locale) } ?: activity.getString(R.string.compare_missing)
    private fun percent(value: Double?) = value?.let { AppLocales.percent(it, 1, locale) } ?: activity.getString(R.string.compare_missing)

    fun show() {
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.oneui_bg, 28)
            clipToOutline = true
        }
        val header = row().apply { setPadding(dp(20), dp(12), dp(16), dp(8)) }
        header.addView(text(activity.getString(R.string.compare_title), 24f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text(activity.getString(R.string.compare_done), 15f, true).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
            setTextColor(activity.getColor(R.color.compare_followers_color))
            setOnClickListener { dialog.dismiss() }
        })
        outer.addView(header)
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(20))
            addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(outer)
        outer.layoutParams = outer.layoutParams.apply { height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt() }
        dialog.setOnDismissListener { slots.forEach { it.generation++ } }
        dialog.setOnShowListener {
            dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.background = rounded(R.color.oneui_bg, 28)
                sheet.clipToOutline = true
                TwidgetFonts.observeWindow(sheet.rootView)
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        render()
        dialog.show()
        slots.indices.filter { slots[it].handle.isNotBlank() }.forEach(::load)
    }

    private fun load(index: Int) {
        val slot = slots[index]
        if (slot.handle.isBlank()) return
        val generation = ++slot.generation
        val handle = slot.handle
        slot.loading = true; slot.failed = false; slot.result = null
        render()
        fun finish(result: Result?) {
            activity.runOnUiThread {
                if (!dialog.isShowing || generation != slot.generation) return@runOnUiThread
                slot.loading = false; slot.failed = result == null; slot.result = result
                render()
            }
        }
        AppExecutors.execute(onRejected = { finish(null) }) {
            val result = runCatching {
                val trackedHandle = tracked.firstOrNull { it.equals(handle, true) }
                val snapshot = trackedHandle?.let { TwidgetStore.accountSnapshot(activity, it) }
                val profile = FxTwitterClient.fetchProfile(handle, snapshot?.stats?.userId.orEmpty())
                val analytics = if (profile.isPrivate == true) null else runCatching { AnalyticsClient.fetchPublic(profile.userName, profile.userId) }.getOrNull()
                val posts = analytics?.recentPosts?.map { post -> ComparePostCounts(
                    post.likes.takeIf { post.likesKnown }, post.reposts.takeIf { post.repostsKnown },
                    post.replies.takeIf { post.repliesKnown }, post.quotes.takeIf { post.quotesKnown },
                    post.views.takeIf { post.viewsKnown },
                ) }
                val history = if (profile.userId.isNotBlank() && snapshot != null && snapshot.stats.userId == profile.userId) snapshot.history
                    .filter { !it.estimated && it.followersKnown }.map { CompareHistoryPoint(it.timestamp, it.followers) } else emptyList()
                val delta = CompareLogic.weeklyDelta(profile.followersCount.takeIf { profile.followersKnown }, history, System.currentTimeMillis())
                Result(profile, posts?.let(CompareLogic::totals), delta, analytics == null)
            }.getOrNull()
            finish(result)
        }
    }

    private fun choose(index: Int) {
        val options = tracked.distinctBy { it.lowercase(java.util.Locale.US) }
        val labels = options.map { "@$it" } + activity.getString(R.string.compare_type_handle)
        AlertDialog.Builder(activity).setTitle(R.string.compare_choose).setItems(labels.toTypedArray()) { _, selected ->
            if (selected == options.size) enterHandle(index) else select(index, options[selected])
        }.setNegativeButton(R.string.compare_cancel, null).show()
    }
    private fun enterHandle(index: Int) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.compare_handle_hint)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(16))
        }
        val editor = AlertDialog.Builder(activity).setTitle(R.string.compare_type_handle).setView(input)
            .setNegativeButton(R.string.compare_cancel, null).setPositiveButton(R.string.compare_use_account, null).create()
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().trimStart('@')
                if (!value.matches(Regex("[A-Za-z0-9_]{1,15}"))) input.error = activity.getString(R.string.compare_invalid_handle)
                else { editor.dismiss(); select(index, value) }
            }
        }
        editor.show()
    }
    private fun select(index: Int, handle: String) { slots[index].handle = handle; load(index) }

    private fun render() {
        content.removeAllViews()
        val accounts = row().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        accounts.addView(accountColumn(0), LinearLayout.LayoutParams(0, -2, 1f))
        accounts.addView(text(activity.getString(R.string.compare_vs), 13f, true, secondary = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), dp(28), dp(6), 0)
        })
        accounts.addView(accountColumn(1), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(accounts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val left = slots[0].result
        val right = slots[1].result
        metricCard(R.string.compare_followers, OneUiIconR.drawable.ic_oui_samsung_account, R.color.compare_followers_color,
            left?.profile?.takeIf { it.followersKnown }?.followersCount, right?.profile?.takeIf { it.followersKnown }?.followersCount,
            left?.delta, right?.delta)
        metricCard(R.string.compare_engagement, OneUiIconR.drawable.ic_oui_equalizer_2, R.color.compare_engagement_color,
            left?.posts?.engagementRate, right?.posts?.engagementRate, isRate = true)
        metricCard(R.string.compare_likes, OneUiIconR.drawable.ic_oui_heart_outline, R.color.compare_likes_color, left?.posts?.likes, right?.posts?.likes)
        metricCard(R.string.compare_reposts, OneUiIconR.drawable.ic_oui_repeat, R.color.compare_reposts_color, left?.posts?.reposts, right?.posts?.reposts)
        metricCard(R.string.compare_views, OneUiIconR.drawable.ic_oui_eyes, R.color.compare_views_color, left?.posts?.views, right?.posts?.views)
        content.addView(text(activity.getString(R.string.compare_footnote), 12f, secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun accountColumn(index: Int): View {
        val slot = slots[index]
        val profile = slot.result?.profile
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val button = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(110); setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            contentDescription = activity.getString(R.string.compare_pick_description, slot.handle.ifBlank { activity.getString(R.string.compare_choose) })
            setOnClickListener { choose(index) }
        }
        val avatar = ImageView(activity)
        button.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))
        ProfileImageLoader.loadInto(activity, avatar, profile?.profileImage.orEmpty())
        button.addView(text(profile?.fullName?.ifBlank { slot.handle } ?: slot.handle.ifBlank { activity.getString(R.string.compare_choose) }, 15f, true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        val handle = row().apply { gravity = Gravity.CENTER }
        handle.addView(text(if (slot.handle.isBlank()) activity.getString(R.string.compare_choose) else "@${profile?.userName ?: slot.handle}", 12f, secondary = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        handle.addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_keyboard_arrow_right))
            imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.oneui_text_secondary))
            rotation = 90f
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        button.addView(handle, LinearLayout.LayoutParams(-1, -2))
        column.addView(button, LinearLayout.LayoutParams(-1, -2))
        val message = when { slot.loading -> R.string.compare_loading; slot.failed -> R.string.compare_load_error; slot.result?.analyticsError == true -> R.string.compare_analytics_error; else -> null }
        message?.let { column.addView(text(activity.getString(it), 11f, secondary = true).apply { gravity = Gravity.CENTER }) }
        if (slot.failed || slot.result?.analyticsError == true) column.addView(text(activity.getString(R.string.compare_retry), 12f, true).apply {
            setTextColor(activity.getColor(R.color.compare_followers_color)); minimumHeight = dp(40); gravity = Gravity.CENTER; setOnClickListener { load(index) }
        })
        return column
    }

    private fun metricCard(title: Int, icon: Int, color: Int, left: Number?, right: Number?, leftDelta: Long? = null, rightDelta: Long? = null, isRate: Boolean = false) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(R.color.oneui_card_bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val heading = row()
        heading.addView(ImageView(activity).apply {
            setImageDrawable(AppCompatResources.getDrawable(activity, icon))
            imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(color))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
        heading.addView(text(activity.getString(title), 14f, true).apply { setTextColor(activity.getColor(color)) })
        card.addView(heading)
        val emphasis = CompareLogic.emphasis(left?.toDouble(), right?.toDouble())
        val values = row()
        listOf(left, right).forEachIndexed { index, value ->
            val strong = when (emphasis) { CompareEmphasis.BOTH -> true; CompareEmphasis.LEFT -> index == 0; CompareEmphasis.RIGHT -> index == 1; CompareEmphasis.NEITHER -> false }
            val display = if (isRate) percent(value?.toDouble()) else count(value?.toLong())
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; if (index == 1) gravity = Gravity.END }
            column.addView(text(display, 22f, strong, secondary = !strong).apply { maxLines = 1 })
            val delta = if (index == 0) leftDelta else rightDelta
            if (delta != null) column.addView(text(activity.getString(R.string.compare_weekly_delta, CompareLogic.signed(delta, locale)), 12f).apply {
                setTextColor(activity.getColor(when (CompareLogic.tone(delta)) {
                    DeltaTone.POSITIVE -> R.color.compare_positive_color; DeltaTone.NEGATIVE -> R.color.compare_negative_color; DeltaTone.NEUTRAL -> R.color.oneui_text_secondary
                }))
            })
            column.contentDescription = activity.getString(R.string.compare_metric_description, activity.getString(title), slots[index].handle.ifBlank { activity.getString(R.string.compare_choose) }, display) +
                (delta?.let { ". " + activity.getString(R.string.compare_weekly_delta, CompareLogic.signed(it, locale)) } ?: "")
            values.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        card.addView(values, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
}
