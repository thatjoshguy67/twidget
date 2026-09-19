package com.tjg.twidget.followers

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.social.SocialPlatform
import com.tjg.twidget.social.icon
import com.tjg.twidget.social.label
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import com.tjg.twidget.settings.SettingsCategoryActivity
import com.tjg.twidget.settings.SettingsPage
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR

/** Shows completed bridge rankings and the shared-history entry point. */
internal class TopFollowersCardBinder(
    private val activity: MainActivity,
    private val requestNotificationPermission: () -> Unit,
) {
    fun create(account: String): View {
        val state = TopFollowersStore.read(activity, account)
        return when {
            state.complete && state.top.isNotEmpty() -> resultsCard(account, state)
            else -> notScannedCard(account, state)
        }
    }

    private fun notScannedCard(account: String, state: TopFollowersState): View =
        FrameLayout(activity).apply {
            minimumHeight = dp(233)
            background = rounded(cardColor, 28f)
            clipToOutline = true

            repeat(3) { index ->
                addView(View(activity).apply { background = rounded(skeletonColor, 12f) }, matchFrameParams(45).apply {
                    leftMargin = dp(23)
                    rightMargin = dp(23)
                    topMargin = dp(110 + index * 54)
                })
            }
            addView(label(activity.getString(R.string.top_followers_question), 14f, primaryColor, 700).apply {
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(SocialPlatform.X.icon(activity)?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
                compoundDrawablePadding = dp(8)
            }, matchFrameParams(19).apply { topMargin = dp(20) })
            addView(label(activity.getString(R.string.top_followers_hero), 48f, accentColor, 700).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                letterSpacing = -0.02f
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 34, 48, 1, TypedValue.COMPLEX_UNIT_SP,
                )
            }, matchFrameParams(64).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = dp(40)
            })
            addView(Button(activity).apply {
                val shareHistory = TwidgetStore.settings(activity).shareHistory
                text = activity.getString(when {
                    shareHistory -> R.string.top_followers_find_with_bridge
                    else -> R.string.top_followers_enable_shared_history
                })
                isAllCaps = false
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = weightedTypeface(700)
                stateListAnimator = null
                elevation = dp(8).toFloat()
                background = rounded(accentColor, 28f)
                setOnClickListener {
                    when {
                        shareHistory -> requestBridgeScan(account)
                        else -> openSharedHistorySettings()
                    }
                }
                contentDescription = if (state.error.isBlank()) text else "${text}. ${state.error}"
            }, frameParams(206, 60).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(151)
            })
        }

    private fun resultsCard(account: String, state: TopFollowersState): View {
        TopFollowersArchiveStore.seedFromTop(activity, account, state.top)
        val canBrowse = state.complete && state.top.isNotEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(384)
            background = rounded(cardColor, 28f)
            clipToOutline = true
            addView(header(activity.getString(R.string.top_followers_results_title), account, openBrowserEnabled = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            state.top.take(5).forEachIndexed { index, follower ->
                addView(resultRow(index + 1, follower, index < 4),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)))
            }
            if (canBrowse) {
                addView(label(activity.getString(R.string.top_followers_view_all), 14f, accentColor, 700).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openBrowse(account) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun header(
        title: String,
        account: String,
        openBrowserEnabled: Boolean,
    ): View {
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), 0)
            if (openBrowserEnabled) {
                isClickable = true
                isFocusable = true
                foreground = RippleDrawable(
                    ColorStateList.valueOf(rippleColor),
                    null,
                    ColorDrawable(Color.WHITE),
                )
                contentDescription = "$title. ${activity.getString(R.string.top_followers_view_all)}"
                setOnClickListener { openBrowse(account) }
            }
            addView(ImageView(activity).apply {
                setImageDrawable(SocialPlatform.X.icon(activity)); contentDescription = SocialPlatform.X.label
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(label(title, 13f, secondaryColor, 700).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_keyboard_arrow_right))
                imageTintList = ColorStateList.valueOf(secondaryColor)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    private fun resultRow(rank: Int, follower: TopFollower, divider: Boolean): View = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = true
        // One bounded target owns the complete row, including its avatar and
        // follower count. The mask gives every row the same rounded grey
        // pressed surface instead of separate, inconsistent child targets.
        foreground = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            null,
            rounded(cardColor, 24f),
        )
        contentDescription = activity.getString(
            R.string.top_follower_accessibility,
            rank,
            follower.name,
            follower.username,
            TwidgetStore.compactNumber(follower.followers),
        )
        setOnClickListener { openXProfile(follower.username) }
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(label(rank.toString(), 24f, primaryColor, 200).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(24), dp(40)))
            addView(ImageView(activity).apply {
                contentDescription = null
                ProfileImageLoader.loadInto(activity, this, follower.avatarUrl)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(follower.name, 16f, primaryColor, 700).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(23)))
                addView(label("@${follower.username}", 12f, secondaryColor, 400).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(17)))
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
            addView(communityIcon(primaryColor), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label(TwidgetStore.compactNumber(follower.followers), 12f, secondaryColor, 400).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)).apply { marginStart = dp(4) })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (divider) addDivider()
    }

    private fun FrameLayout.addDivider() {
        addView(View(activity).apply { setBackgroundColor(dividerColor) }, matchFrameParams(1).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            gravity = Gravity.BOTTOM
        })
    }

    private fun requestBridgeScan(account: String) {
        if (!TwidgetStore.settings(activity).shareHistory) return
        requestNotificationPermission()
        TopFollowersBridgeSyncWorker.enqueueScanRequest(activity, account)
        Toast.makeText(activity, R.string.top_followers_bridge_scan_requested, Toast.LENGTH_LONG).show()
    }

    private fun openSharedHistorySettings() {
        Toast.makeText(activity, R.string.top_followers_setup, Toast.LENGTH_LONG).show()
        activity.startActivity(SettingsCategoryActivity.intent(activity, SettingsPage.DATA))
    }

    private fun openXProfile(username: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(username)}")))
    }

    private fun openBrowse(account: String) {
        activity.startRightSidePopOverActivity(
            Intent(activity, TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, account),
        )
    }

    private fun label(textValue: String, size: Float, color: Int, weight: Int) = TextView(activity).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = weightedTypeface(weight)
    }

    private fun communityIcon(tint: Int) = ImageView(activity).apply {
        setImageDrawable(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_community))
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = null
    }

    private fun weightedTypeface(weight: Int): Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(Typeface.create("sec", Typeface.NORMAL), weight, false)
    } else {
        Typeface.create("sec", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun frameParams(widthDp: Int, heightDp: Int) = FrameLayout.LayoutParams(dp(widthDp), dp(heightDp))
    private fun matchFrameParams(heightDp: Int) =
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    private val cardColor get() = activity.getColor(R.color.oneui_card_bg)
    private val primaryColor get() = activity.getColor(R.color.oneui_text_primary)
    private val secondaryColor get() = activity.getColor(R.color.oneui_text_secondary)
    private val accentColor get() = activity.getColor(R.color.oneui_accent)
    private val skeletonColor get() = activity.getColor(R.color.top_followers_skeleton)
    private val dividerColor get() = activity.getColor(R.color.oneui_divider)
    private val rippleColor get() = (primaryColor and 0x00FFFFFF) or 0x24000000
}
