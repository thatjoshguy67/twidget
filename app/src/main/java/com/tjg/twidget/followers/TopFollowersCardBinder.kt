package com.tjg.twidget.followers

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import com.tjg.twidget.settings.SettingsAdvancedActivity
import com.tjg.twidget.ui.OneUiSpinner
import com.tjg.twidget.ui.ProfileImageLoader
import dev.oneuiproject.oneui.R as OneUiIconR

/** Renders the four Figma states for the dashboard's Top Followers card. */
internal class TopFollowersCardBinder(
    private val activity: MainActivity,
    private val onStateChanged: () -> Unit,
    private val requestNotificationPermission: () -> Unit,
) {
    fun create(account: String): View {
        val state = TopFollowersStore.read(activity, account)
        return when {
            state.scanning -> scanningCard(account, state)
            state.complete && state.top.isNotEmpty() -> resultsCard(account, state)
            else -> notScannedCard(account, state)
        }
    }

    private fun notScannedCard(account: String, state: TopFollowersState): View =
        FrameLayout(activity).apply {
            minimumHeight = dp(233)
            background = rounded(Color.WHITE, 28f)
            clipToOutline = true

            repeat(3) { index ->
                addView(View(activity).apply { background = rounded(SKELETON, 12f) }, matchFrameParams(45).apply {
                    leftMargin = dp(23)
                    rightMargin = dp(23)
                    topMargin = dp(110 + index * 54)
                })
            }
            addView(label(activity.getString(R.string.top_followers_question), 14f, Color.BLACK, 700).apply {
                gravity = Gravity.CENTER
            }, matchFrameParams(19).apply { topMargin = dp(20) })
            addView(label(activity.getString(R.string.top_followers_hero), 48f, ACCENT, 700).apply {
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
                text = activity.getString(R.string.top_followers_start_scan)
                isAllCaps = false
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = weightedTypeface(700)
                stateListAnimator = null
                elevation = dp(8).toFloat()
                background = rounded(ACCENT, 28f)
                setOnClickListener { showStartDialog(account) }
                contentDescription = if (state.error.isBlank()) text else "${text}. ${state.error}"
            }, frameParams(206, 60).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(151)
            })
        }

    private fun scanningCard(account: String, state: TopFollowersState): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(424)
            background = rounded(Color.WHITE, 28f)
            clipToOutline = true
            addView(header(activity.getString(R.string.top_followers_scanning_title), account, refreshEnabled = false),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            repeat(5) { index ->
                addView(scanningRow(index + 1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
            }
        }.also { card ->
            val total = TwidgetStore.currentStats(activity, account).let {
                it.followersCount.takeIf { count -> it.followersKnown && count > 0 }
            }
            card.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 10_000
                isIndeterminate = total == null
                progress = total?.let { ((state.scanned.toLong().coerceAtMost(it) * max) / it).toInt() } ?: 0
                progressTintList = ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))
            // Keep the total Figma height: the progress overlays the first four header pixels.
            (card.getChildAt(1).layoutParams as LinearLayout.LayoutParams).height = dp(40)
        }

    private fun resultsCard(account: String, state: TopFollowersState): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(384)
            background = rounded(Color.WHITE, 28f)
            clipToOutline = true
            addView(header(activity.getString(R.string.top_followers_results_title), account, refreshEnabled = true),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            state.top.take(5).forEachIndexed { index, follower ->
                addView(resultRow(index + 1, follower, index < 4),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)))
            }
        }

    private fun header(title: String, account: String, refreshEnabled: Boolean): View =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(12), 0)
            addView(label(title, 13f, SECONDARY, 700).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(ImageView(activity).apply {
                setImageDrawable(AppCompatResources.getDrawable(activity, OneUiIconR.drawable.ic_oui_refresh))
                imageTintList = ColorStateList.valueOf(Color.BLACK)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isEnabled = refreshEnabled
                alpha = if (refreshEnabled) 1f else 0.7f
                isClickable = refreshEnabled
                isFocusable = refreshEnabled
                if (refreshEnabled) {
                    background = RippleDrawable(
                        ColorStateList.valueOf(RIPPLE),
                        null,
                        rounded(Color.WHITE, 20f),
                    )
                }
                contentDescription = activity.getString(R.string.top_followers_refresh)
                if (refreshEnabled) setOnClickListener { showStartDialog(account) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

    private fun scanningRow(rank: Int): View = FrameLayout(activity).apply {
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(label(rank.toString(), 24f, Color.BLACK, 200).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(24), dp(40)))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.oneui_spinner)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                OneUiSpinner.loop(this)
                contentDescription = activity.getString(R.string.top_followers_scanning_title)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(activity).apply { background = rounded(SKELETON, 12f) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)))
                addView(LinearLayout(activity).apply {
                    addView(View(activity).apply { background = rounded(SKELETON, 12f) },
                        LinearLayout.LayoutParams(0, dp(25), 0.78f))
                    addView(View(activity), LinearLayout.LayoutParams(0, dp(25), 0.22f))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(25)).apply { topMargin = dp(5) })
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(10) })
            addView(communityIcon(Color.BLACK), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) })
            addView(View(activity).apply { background = rounded(SKELETON, 12f) },
                LinearLayout.LayoutParams(dp(53), dp(25)).apply { marginStart = dp(4) })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addDivider()
    }

    private fun resultRow(rank: Int, follower: TopFollower, divider: Boolean): View = FrameLayout(activity).apply {
        isClickable = true
        isFocusable = true
        // One bounded target owns the complete row, including its avatar and
        // follower count. The mask gives every row the same rounded grey
        // pressed surface instead of separate, inconsistent child targets.
        foreground = RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            null,
            rounded(Color.WHITE, 24f),
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
            addView(label(rank.toString(), 24f, Color.BLACK, 200).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(24), dp(40)))
            addView(ImageView(activity).apply {
                contentDescription = null
                ProfileImageLoader.loadInto(activity, this, follower.avatarUrl)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(follower.name, 16f, Color.BLACK, 700).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(23)))
                addView(label("@${follower.username}", 12f, SECONDARY, 400).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(17)))
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
            addView(communityIcon(Color.BLACK), LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(label(TwidgetStore.compactNumber(follower.followers), 12f, SECONDARY, 400).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(24)).apply { marginStart = dp(4) })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (divider) addDivider()
    }

    private fun FrameLayout.addDivider() {
        addView(View(activity).apply { setBackgroundColor(DIVIDER) }, matchFrameParams(1).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            gravity = Gravity.BOTTOM
        })
    }

    private fun showStartDialog(account: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.top_followers_scan_title)
            .setMessage(R.string.top_followers_scan_message)
            // AppCompat lays buttons out neutral, negative, positive in LTR.
            .setNeutralButton(R.string.cancel, null)
            .setNegativeButton(R.string.top_followers_add_api_key) { _, _ -> openApiKeySettings() }
            .setPositiveButton(R.string.top_followers_start) { _, _ -> startScan(account) }
            .show()
    }

    private fun startScan(account: String) {
        if (SecureCredentialStore.read(activity, SecureCredentialStore.TWITTERAPIS_API_KEY).isBlank()) {
            Toast.makeText(activity, R.string.top_followers_api_key_required, Toast.LENGTH_LONG).show()
            openApiKeySettings()
            return
        }
        when (TopFollowersScanWorker.enqueue(activity, account, restart = true)) {
            TopFollowersScanStart.STARTED -> {
                requestNotificationPermission()
                onStateChanged()
            }
            TopFollowersScanStart.ALREADY_SCANNED_TODAY ->
                Toast.makeText(activity, R.string.top_followers_daily_limit, Toast.LENGTH_LONG).show()
        }
    }

    private fun openApiKeySettings() {
        activity.startActivity(Intent(activity, SettingsAdvancedActivity::class.java))
    }

    private fun openXProfile(username: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(username)}")))
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

    private companion object {
        private const val ACCENT = 0xFF387AFF.toInt()
        private const val SECONDARY = 0xFF848487.toInt()
        private const val SKELETON = 0xFFF3F3F5.toInt()
        private const val DIVIDER = 0xFFEAEAEA.toInt()
        private const val RIPPLE = 0x24000000
    }
}
