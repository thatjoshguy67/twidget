package com.tjg.twidget

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.ViewGroup
import android.widget.ListPopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SeslSeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import dev.oneuiproject.oneui.design.R as OneUiR
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.RadioItemViewGroup

class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var tintAlpha = 205
    private var tintColor = 0x00FFFFFF
    private var logo = TwidgetStore.LOGO_X
    private var tapAction = TwidgetStore.TAP_REFRESH
    private var accountUsername = ""
    private var colorMode = TwidgetStore.COLOR_MODE_SYSTEM
    private var fontFamily = TwidgetStore.FONT_ONE_UI_SANS
    private var showDelta = true
    private var currentLevel = 2
    private var isLockWidget = false
    private var isLockWide = false
    private val accountRadios = mutableListOf<Pair<String, RadioButton>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        isLockWidget = intent?.getBooleanExtra(EXTRA_LOCKSCREEN_WIDGET, false) == true
        isLockWide = intent?.getBooleanExtra(EXTRA_LOCKSCREEN_WIDE, false) == true
        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        if (providerClass.startsWith("com.tjg.twidget.LockScreenFollower")) {
            isLockWidget = true
            isLockWide = providerClass == LockScreenFollowerWideWidget::class.java.name
        }
        setContentView(R.layout.activity_widget_config)
        if (isLockWidget) {
            // Lock screen artwork is monotone white — opacity, tint, font, and
            // tap action don't apply there; account and logo do.
            listOf(R.id.opacity_block, R.id.tint_row, R.id.font_row, R.id.tap_separator, R.id.tap_action_card)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            findViewById<CardItemView>(R.id.logo_row).showTopDivider = false
        }

        val settings = TwidgetStore.widgetSettings(this, appWidgetId)
        tintAlpha = settings.tintAlpha
        currentLevel = closestOpacityLevel(tintAlpha)
        tintAlpha = OPACITY_PRESETS[currentLevel]
        tintColor = settings.tintColor
        logo = settings.logo
        tapAction = settings.tapAction
        accountUsername = settings.accountUsername
        colorMode = settings.colorMode
        fontFamily = settings.fontFamily
        showDelta = settings.showDelta
        bindControls()
        buildAccountRows()
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    private fun bindControls() {
        findViewById<SeslSeekBar>(R.id.opacity_slider).apply {
            alpha = 0f
            progressDrawable?.alpha = 0
            progress = currentLevel
            updateSliderVisuals()
            setOnSeekBarChangeListener(object : SeslSeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeslSeekBar, progress: Int, fromUser: Boolean) {
                    currentLevel = progress.coerceIn(0, OPACITY_PRESETS.lastIndex)
                    tintAlpha = OPACITY_PRESETS[currentLevel]
                    updateSliderVisuals()
                    render()
                }
                override fun onStartTrackingTouch(seekBar: SeslSeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeslSeekBar) = Unit
            })
        }
        findViewById<CardItemView>(R.id.tint_row).setOnClickListener { pickColorMode(it) }
        findViewById<CardItemView>(R.id.logo_row).setOnClickListener { pickLogo(it) }
        findViewById<CardItemView>(R.id.font_row).setOnClickListener { pickFont(it) }
        findViewById<SwitchCompat>(R.id.delta_switch).isChecked = showDelta
        findViewById<View>(R.id.delta_row).setOnClickListener {
            showDelta = !showDelta
            findViewById<SwitchCompat>(R.id.delta_switch).isChecked = showDelta
            render()
        }
        findViewById<TextView>(R.id.btn_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveAndFinish() }
        findViewById<RadioItemViewGroup>(R.id.tap_action_group).apply {
            check(tapActionRowId(tapAction))
            setOnCheckedChangeListener(object : RadioItemViewGroup.OnCheckedChangeListener {
                override fun onCheckedChanged(group: RadioItemViewGroup?, checkedId: Int) {
                    tapAction = when (checkedId) {
                        R.id.tap_profile_row -> TwidgetStore.TAP_PROFILE
                        R.id.tap_app_row -> TwidgetStore.TAP_APP
                        else -> TwidgetStore.TAP_REFRESH
                    }
                }
            })
        }
    }

    private fun tapActionRowId(action: String): Int = when (action) {
        TwidgetStore.TAP_PROFILE -> R.id.tap_profile_row
        TwidgetStore.TAP_APP -> R.id.tap_app_row
        else -> R.id.tap_refresh_row
    }

    private fun buildAccountRows() {
        val group = findViewById<LinearLayout>(R.id.account_group)
        group.removeAllViews()
        accountRadios.clear()
        val defaultAccount = TwidgetStore.settings(this).username
        val accounts = TwidgetStore.accounts(this)
            .ifEmpty { listOf(defaultAccount) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        accounts.forEachIndexed { index, username ->
            if (index > 0) group.addView(divider())
            group.addView(accountRow(username, defaultAccount))
        }
        updateAccountChecks()
    }

    private fun accountRow(username: String, defaultAccount: String): View {
        val stats = TwidgetStore.currentStats(this, username)
        val radio = RadioButton(this).apply {
            isClickable = false
            isFocusable = false
        }
        accountRadios.add(username to radio)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(74)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveSelectableItemBackground())
            setOnClickListener {
                accountUsername = if (username.equals(defaultAccount, ignoreCase = true)) "" else username
                updateAccountChecks()
                render()
            }

            addView(radio, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(16)
            })

            addView(ImageView(context).apply {
                setBackgroundResource(R.drawable.avatar_twidget)
                ProfileImageLoader.loadInto(context, this, stats.profileImage)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = VerifiedBadge.decorate(context, stats.fullName.ifBlank { username }, stats.isVerified, stats.isPrivate, dp(17))
                    setTextColor(context.getColor(R.color.oneui_text_primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = "@${username.trimStart('@')}"
                    setTextColor(context.getColor(R.color.oneui_text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            })
        }
    }

    private fun updateAccountChecks() {
        val selected = accountUsername.ifBlank { TwidgetStore.settings(this).username }
        accountRadios.forEach { (username, radio) ->
            radio.isChecked = username.equals(selected, ignoreCase = true)
        }
    }

    private fun divider(): View =
        View(this).apply {
            setBackgroundColor(getColor(R.color.oneui_divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        }

    private fun render() {
        findViewById<CardItemView>(R.id.tint_row).summary = colorModeLabel(colorMode)
        findViewById<CardItemView>(R.id.font_row).summary = fontLabel(fontFamily)
        findViewById<CardItemView>(R.id.logo_row).apply {
            summary = if (logo == TwidgetStore.LOGO_TWITTER) getString(R.string.widget_logo_twitter) else getString(R.string.widget_logo_x)
            findViewById<ImageView>(OneUiR.id.end_view)?.apply {
                setImageResource(if (logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x)
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }
        }

        val preview = findViewById<FrameLayout>(R.id.preview_widget)
        preview.removeAllViews()
        preview.setPadding(0, 0, 0, 0)

        val selectedAccount = accountUsername.ifBlank { TwidgetStore.settings(this).username }
        val previewSettings = TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, selectedAccount, colorMode, fontFamily, showDelta)

        if (isLockWidget) {
            preview.background = null
            preview.addView(ImageView(this).apply {
                setBackgroundResource(R.drawable.config_lock_widget_shape)
                setPadding(dp(if (isLockWide) 8 else 6), dp(6), dp(if (isLockWide) 8 else 6), dp(6))
                setImageBitmap(LockScreenFollowerViews.previewArt(this@WidgetConfigActivity, isLockWide, previewSettings))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            return
        }

        val stats = TwidgetStore.currentStats(this, selectedAccount)
        val previewSpec = homePreviewSpec()
        preview.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * previewSpec.cornerRadiusDp
            setColor(Color.argb(tintAlpha, tintComponent(16), tintComponent(16), tintComponent(16)))
        }
        preview.layoutParams = preview.layoutParams.apply {
            width = dp(previewSpec.widthDp)
            height = dp(previewSpec.heightDp)
        }
        preview.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(
                WidgetArtworkRenderer.render(
                    context = this@WidgetConfigActivity,
                    widthPx = dp(previewSpec.widthDp),
                    heightPx = dp(previewSpec.heightDp),
                    stats = stats,
                    settings = previewSettings,
                    mode = previewSpec.mode,
                    dark = isDarkPreview(),
                    delta = TwidgetStore.followersDelta(this@WidgetConfigActivity, selectedAccount),
                )
            )
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun pickColorMode(anchor: View) {
        val values = arrayOf(TwidgetStore.COLOR_MODE_SYSTEM, TwidgetStore.COLOR_MODE_LIGHT, TwidgetStore.COLOR_MODE_DARK)
        showDropDown(anchor, values.map { colorModeLabel(it) }, values.indexOf(colorMode).coerceAtLeast(0)) { which ->
            colorMode = values[which]
            tintColor = if (colorMode == TwidgetStore.COLOR_MODE_DARK) 0x00000000 else 0x00FFFFFF
            render()
        }
    }

    private fun pickFont(anchor: View) {
        val values = arrayOf(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX)
        showDropDown(anchor, values.map { fontLabel(it) }, values.indexOf(fontFamily).coerceAtLeast(0)) { which ->
            fontFamily = values[which]
            render()
        }
    }

    private fun pickLogo(anchor: View) {
        val values = arrayOf(TwidgetStore.LOGO_X, TwidgetStore.LOGO_TWITTER)
        showDropDown(anchor, listOf(getString(R.string.widget_logo_x), getString(R.string.widget_logo_twitter)), values.indexOf(logo).coerceAtLeast(0)) { which ->
            logo = values[which]
            render()
        }
    }

    private fun showDropDown(anchor: View, labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
        val accent = getColor(R.color.oneui_accent)
        val normal = getColor(R.color.oneui_text_primary)
        val adapter = object : ArrayAdapter<String>(this, R.layout.dropdown_item_checked, R.id.dropdown_label, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val selected = position == selectedIndex
                view.findViewById<TextView>(R.id.dropdown_label).apply {
                    setTextColor(if (selected) accent else normal)
                    typeface = Typeface.create("sec", if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
                view.findViewById<ImageView>(R.id.dropdown_check).apply {
                    imageTintList = android.content.res.ColorStateList.valueOf(accent)
                    visibility = if (selected) View.VISIBLE else View.INVISIBLE
                }
                return view
            }
        }
        val popup = ListPopupWindow(this).apply {
            setAdapter(adapter)
            this.anchorView = anchor
            width = dp(220)
            height = ListPopupWindow.WRAP_CONTENT
            isModal = true
            setSelection(selectedIndex)
            horizontalOffset = dp(18)
            verticalOffset = -dp(8)
        }
        popup.setOnItemClickListener { _: AdapterView<*>, _: View, position: Int, _: Long ->
            onSelected(position)
            popup.dismiss()
        }
        popup.show()
    }

    private fun saveAndFinish() {
        tintAlpha = OPACITY_PRESETS[currentLevel]
        TwidgetStore.saveWidgetSettings(this, appWidgetId, TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, accountUsername, colorMode, fontFamily, showDelta))
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val manager = AppWidgetManager.getInstance(this)
            if (isLockWidget) {
                LockScreenFollowerViews.update(
                    this,
                    manager,
                    intArrayOf(appWidgetId),
                    if (isLockWide) R.layout.lockscreen_message_2x1 else R.layout.lockscreen_message_1x1,
                )
            } else {
                TwidgetWidget.updateWidget(this, manager, appWidgetId)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        } else if (isLockWidget) {
            LockScreenFollowerViews.updateAll(this)
            LockScreenFollowerServiceBoxReceiver.refresh(this)
        }
        finish()
    }

    private fun resolveSelectableItemBackground(): Int {
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun tintComponent(component: Int): Int =
        if (isDarkTint()) component else 255

    private fun isDarkTint(): Boolean = Color.red(tintColor) < 128
    private fun isDarkPreview(): Boolean =
        colorMode == TwidgetStore.COLOR_MODE_DARK ||
            (colorMode == TwidgetStore.COLOR_MODE_SYSTEM &&
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) ||
            isDarkTint()
    private fun homePreviewSpec(): HomePreviewSpec {
        val fallback = HomePreviewSpec(TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE, 176, 176, 24f)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return fallback
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
        val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, fallback.widthDp) ?: fallback.widthDp
        val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, fallback.heightDp) ?: fallback.heightDp
        val mode = if (options == null) fallback.mode else TwidgetWidget.layoutMode(options)
        val rows = options?.getInt("semAppWidgetRowSpan", 0) ?: 0
        val (widthDp, heightDp) = when (mode) {
            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 -> 226 to 98
            TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP -> 300 to 62
            TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE -> 176 to 176
            else -> {
                val width = 300
                val height = if (rows >= 3) 176 else (width * minHeight / minWidth.coerceAtLeast(1)).coerceIn(124, 142)
                width to height
            }
        }
        val cornerRadius = if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 || mode == TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP) {
            heightDp / 2f
        } else {
            24f
        }
        return HomePreviewSpec(
            mode = mode,
            widthDp = widthDp,
            heightDp = heightDp,
            cornerRadiusDp = cornerRadius,
        )
    }

    private fun colorModeLabel(mode: String): String = when (mode) {
        TwidgetStore.COLOR_MODE_DARK -> getString(R.string.widget_tint_dark)
        TwidgetStore.COLOR_MODE_SYSTEM -> getString(R.string.widget_tint_system)
        else -> getString(R.string.widget_tint_light)
    }
    private fun fontLabel(font: String): String = when (font) {
        TwidgetStore.FONT_GOOGLE_SANS_FLEX -> getString(R.string.widget_font_google)
        else -> getString(R.string.widget_font_one_ui)
    }

    private fun updateSliderVisuals() {
        val tickIds = listOf(R.id.opacity_tick_0, R.id.opacity_tick_1, R.id.opacity_tick_2, R.id.opacity_tick_3)
        tickIds.forEachIndexed { index, id ->
            findViewById<View>(id).alpha = if (index == currentLevel) 0f else 1f
        }
        val thumb = findViewById<View>(R.id.opacity_thumb_visual)
        thumb.post {
            val selectedTick = findViewById<View>(tickIds[currentLevel.coerceIn(0, tickIds.lastIndex)])
            thumb.translationX = selectedTick.x + selectedTick.width / 2f - thumb.width / 2f
        }
    }

    private fun closestOpacityLevel(alpha: Int): Int =
        OPACITY_PRESETS.indices.minBy { index -> kotlin.math.abs(OPACITY_PRESETS[index] - alpha) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LOCKSCREEN_WIDGET = "com.tjg.twidget.extra.LOCKSCREEN_WIDGET"
        const val EXTRA_LOCKSCREEN_WIDE = "com.tjg.twidget.extra.LOCKSCREEN_WIDE"
        private val OPACITY_PRESETS = intArrayOf(38, 102, 178, 240)
    }

    private data class HomePreviewSpec(
        val mode: Int,
        val widthDp: Int,
        val heightDp: Int,
        val cornerRadiusDp: Float,
    )
}
