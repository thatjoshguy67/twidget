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
            minimumHeight = 0
            background = rounded(cardColor, 28f)
            clipToOutline = true

            repeat(3) { index ->
                addView(View(activity).apply { background = rounded(skeletonColor, 12f) }, matchFrameParams(58).apply {
                    leftMargin = dp(48)
                    rightMargin = dp(47)
                    topMargin = dp(44 + index * 67)
                })
            }
            addView(label(activity.getString(R.string.top_followers_question), 14f, primaryColor, 700).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(label(activity.getString(R.string.top_followers_hero), 48f, accentColor, 700).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                letterSpacing = -0.02f
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 32, 48, 1, TypedValue.COMPLEX_UNIT_SP,
                )
            }, matchFrameParams(64).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                topMargin = dp(38)
            })
            val action = com.tjg.twidget.ui.DashboardShadowLayout(activity, 28f, 9.5f, 4f).apply {
                minimumWidth = dp(206)
                addView(Button(activity).apply {
                val shareHistory = TwidgetStore.settings(activity).shareHistory
                text = activity.getString(when {
                    shareHistory -> R.string.top_followers_find_with_bridge
                    else -> R.string.top_followers_enable_shared_history
                })
                isAllCaps = false
                textSize = 16f
                minWidth = 0
                minHeight = 0
                includeFontPadding = false
                setTextColor(Color.WHITE)
                typeface = weightedTypeface(700)
                setPadding(dp(24), dp(12), dp(24), dp(12))
                stateListAnimator = null
                elevation = 0f
                background = rounded(accentColor, 28f)
                setOnClickListener {
                    when {
                        shareHistory -> requestBridgeScan(account)
                        else -> openSharedHistorySettings()
                    }
                }
                contentDescription = if (state.error.isBlank()) text else "${text}. ${state.error}"
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            addView(action, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(60)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(140)
            })
        }

    private fun resultsCard(account: String, state: TopFollowersState): View {
        TopFollowersArchiveStore.seedFromTop(activity, account, state.top)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = 0
            background = rounded(cardColor, 28f)
            clipToOutline = true
            setPadding(0, 0, 0, dp(12))
            addView(header(activity.getString(R.string.top_followers_results_title), account, openBrowserEnabled = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            state.top.take(3).forEachIndexed { index, follower ->
                addView(resultRow(index + 1, follower, index < 2),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
            setPadding(dp(20), dp(12), dp(20), dp(8))
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
            addView(label(title, 14f, secondaryColor, 700).apply {
                com.tjg.twidget.ui.TwidgetFonts.dashboardHeading(this)
                gravity = Gravity.CENTER_VERTICAL or if (openBrowserEnabled) Gravity.START else Gravity.CENTER_HORIZONTAL
            },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(activity).apply {
                visibility = if (openBrowserEnabled) View.VISIBLE else View.GONE
                setImageDrawable(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_keyboard_arrow_right))
                imageTintList = ColorStateList.valueOf(secondaryColor)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
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
            addView(label(rank.toString(), 20f, primaryColor, 200).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ImageView(activity).apply {
                contentDescription = null
                ProfileImageLoader.loadInto(activity, this, follower.avatarUrl)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
            addView(com.tjg.twidget.ui.DashboardFollowerIdentityLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(follower.name, 16f, primaryColor, 700).apply {
                    gravity = Gravity.START
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
                addView(label("@${follower.username}", 12f, secondaryColor, 400).apply {
                    gravity = Gravity.START
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    textDirection = View.TEXT_DIRECTION_LTR
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10); marginEnd = dp(10)
            })
            addView(communityIcon(primaryColor), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label(TwidgetStore.compactNumber(follower.followers), 12f, secondaryColor, 400).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
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

    private fun weightedTypeface(weight: Int): Typeface = com.tjg.twidget.ui.TwidgetFonts.forApp(activity, weight)

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun frameParams(widthDp: Int, heightDp: Int) = FrameLayout.LayoutParams(dp(widthDp), dp(heightDp))
    private fun matchFrameParams(heightDp: Int) =
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    private val cardColor get() = activity.getColor(R.color.dashboard_stat_surface)
    private val primaryColor get() = activity.getColor(R.color.oneui_text_primary)
    private val secondaryColor get() = activity.getColor(R.color.oneui_text_secondary)
    private val accentColor get() = activity.getColor(R.color.oneui_accent)
    private val skeletonColor get() = activity.getColor(R.color.top_followers_skeleton)
    private val dividerColor get() = activity.getColor(R.color.oneui_divider)
    private val rippleColor get() = (primaryColor and 0x00FFFFFF) or 0x24000000
}
