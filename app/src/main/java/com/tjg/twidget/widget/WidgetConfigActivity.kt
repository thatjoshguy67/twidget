package com.tjg.twidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.VerifiedBadge
import dev.oneuiproject.oneui.design.R as OneUiR
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.RadioItemViewGroup

class WidgetConfigActivity : EdgeToEdgeActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var tintAlpha = 205
    private var tintColor = 0x00FFFFFF
    private var logo = TwidgetStore.LOGO_X
    private var tapAction = TwidgetStore.TAP_REFRESH
    private var accountUsername = ""
    private var colorMode = TwidgetStore.COLOR_MODE_SYSTEM
    private var widgetStyle = WidgetStyle.ONE_UI
    private var fontFamily = TwidgetStore.FONT_ONE_UI_SANS
    private var showDelta = true
    private var language = "DEFAULT"
    private var currentLevel = 2
    private var isLockWidget = false
    private var isLockWide = false
    private var isBriefWidget = false
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
        isBriefWidget = providerClass == TwidgetBriefWidget::class.java.name
        if (providerClass.startsWith("com.tjg.twidget.LockScreenFollower")) {
            isLockWidget = true
            isLockWide = providerClass == com.tjg.twidget.LockScreenFollowerWideWidget::class.java.name
        }
        setContentView(R.layout.activity_widget_config)
        WidgetConfigChrome.install(findViewById(R.id.widget_config_root))
        val buttonBar = findViewById<View>(R.id.config_button_bar)
        val baseButtonMargin = (16 * resources.displayMetrics.density).toInt()
        applyEdgeToEdgeInsets(findViewById(R.id.widget_config_root)) { navigationBarInset ->
            buttonBar.updateBottomMarginForNavigationBar(baseButtonMargin, navigationBarInset)
        }
        if (isLockWidget) {
            // Lock screen artwork is monotone white — opacity, tint, font, and
            // tap action don't apply there; account and logo do.
            listOf(R.id.widget_style_row, R.id.opacity_block, R.id.tint_row, R.id.font_row, R.id.tap_separator, R.id.tap_action_card)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            findViewById<CardItemView>(R.id.logo_row).showTopDivider = false
        } else if (isBriefWidget) {
            // Brief chooses its account, copy, icon and tap destination from the
            // current dynamic card. Its glass appearance and font remain user
            // configurable.
            listOf(
                R.id.account_separator,
                R.id.account_group,
                R.id.logo_row,
                R.id.delta_row,
                R.id.tap_separator,
                R.id.tap_action_card,
            ).forEach { findViewById<View>(it).visibility = View.GONE }
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
        widgetStyle = settings.style
        fontFamily = settings.fontFamily
        showDelta = settings.showDelta
        language = settings.language
        if (isBriefWidget) accountUsername = ""
        bindControls()
        if (!isBriefWidget) buildAccountRows()
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    private fun bindControls() {
        WidgetOpacityControl.bind(findViewById(R.id.opacity_block), tintAlpha) { alpha ->
            tintAlpha = alpha
            currentLevel = closestOpacityLevel(alpha)
            render()
        }
        findViewById<CardItemView>(R.id.widget_style_row).setOnClickListener { anchor ->
            val styles = WidgetStyle.entries
            showDropDown(anchor, styles.map { styleLabel(it) }, styles.indexOf(widgetStyle)) { index ->
                val previous = widgetStyle
                widgetStyle = styles[index]
                if (fontFamily == previous.defaultFont) fontFamily = widgetStyle.defaultFont
                render()
            }
        }
        findViewById<CardItemView>(R.id.tint_row).setOnClickListener { pickColorMode(it) }
        findViewById<CardItemView>(R.id.logo_row).setOnClickListener { pickLogo(it) }
        findViewById<CardItemView>(R.id.font_row).setOnClickListener { pickFont(it) }
        findViewById<CardItemView>(R.id.language_row)?.setOnClickListener { pickLanguage(it) }
        findViewById<SwitchCompat>(R.id.delta_switch).isChecked = showDelta
        findViewById<View>(R.id.delta_row).setOnClickListener {
            showDelta = !showDelta
            findViewById<SwitchCompat>(R.id.delta_switch).isChecked = showDelta
            render()
        }
        findViewById<View>(R.id.btn_cancel).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveAndFinish() }
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
                    com.tjg.twidget.ui.TwidgetFonts.setRole(this, com.tjg.twidget.ui.TwidgetFonts.Role.LABEL)
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.account_handle, username.trimStart('@'))
                    setTextColor(context.getColor(R.color.oneui_text_secondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                    com.tjg.twidget.ui.TwidgetFonts.setRole(this, com.tjg.twidget.ui.TwidgetFonts.Role.SUMMARY)
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

    private fun styleLabel(style: WidgetStyle) = getString(
        if (style == WidgetStyle.MATERIAL) R.string.widget_style_material else R.string.widget_style_one_ui,
    )

    private fun render() {
        findViewById<CardItemView>(R.id.widget_style_row).summary = styleLabel(widgetStyle)
        findViewById<View>(R.id.opacity_block).visibility =
            if (isLockWidget || widgetStyle == WidgetStyle.MATERIAL) View.GONE else View.VISIBLE
        findViewById<View>(R.id.opacity_separator).visibility = findViewById<View>(R.id.opacity_block).visibility
        findViewById<CardItemView>(R.id.tint_row).summary = colorModeLabel(colorMode)
        findViewById<CardItemView>(R.id.font_row).summary = fontLabel(fontFamily)
        findViewById<CardItemView>(R.id.language_row)?.summary = languageLabel(language)
        findViewById<CardItemView>(R.id.logo_row).apply {
            summary = when (logo) {
                TwidgetStore.LOGO_TWITTER -> getString(R.string.widget_logo_twitter)
                else -> getString(R.string.widget_logo_x)
            }
            findViewById<ImageView>(OneUiR.id.end_view)?.apply {
                setImageResource(
                    when (logo) {
                        TwidgetStore.LOGO_TWITTER -> R.drawable.ic_logo_twitter
                        else -> R.drawable.ic_logo_x
                    },
                )
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }
        }

        val preview = findViewById<FrameLayout>(R.id.preview_widget)
        preview.removeAllViews()
        preview.setPadding(0, 0, 0, 0)

        val selectedAccount = accountUsername.ifBlank { TwidgetStore.settings(this).username }
        val previewSettings = TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, selectedAccount, colorMode, fontFamily, showDelta, language, widgetStyle)

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

        if (isBriefWidget) {
            val spec = homePreviewSpec()
            val widthDp = spec.widthDp
            val heightDp = spec.heightDp
            val darkPreview = isDarkPreview()
            preview.background = GradientDrawable().apply {
                cornerRadius = resources.displayMetrics.density * spec.cornerRadiusDp * previewScale(widthDp)
                setColor(WidgetColors.resolve(this@WidgetConfigActivity, previewSettings, darkPreview).background)
            }
            preview.layoutParams = preview.layoutParams.apply {
                width = (dp(widthDp) * previewScale(widthDp)).toInt()
                height = (dp(heightDp) * previewScale(widthDp)).toInt()
            }
            preview.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(
                    BriefWidgetArtworkRenderer.render(
                        context = com.tjg.twidget.core.AppLocales.wrap(this@WidgetConfigActivity, language),
                        widthPx = dp(widthDp),
                        heightPx = dp(heightDp),
                        account = selectedAccount,
                        snapshot = BriefStore.read(this@WidgetConfigActivity, selectedAccount),
                        dark = darkPreview,
                        fontFamily = fontFamily,
                        style = widgetStyle,
                    ),
                )
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            return
        }

        val stats = TwidgetStore.currentStats(this, selectedAccount)
        val previewSpec = homePreviewSpec()
        // Match the live widget's resolved color mode. The old tint-color
        // shortcut treated System/Dark as a light card whenever the stored
        // tint happened to be white, leaving white artwork with no contrast.
        val darkPreview = isDarkPreview()
        preview.background = GradientDrawable().apply {
            cornerRadius = resources.displayMetrics.density * previewSpec.cornerRadiusDp * previewScale(previewSpec.widthDp)
            setColor(WidgetColors.resolve(this@WidgetConfigActivity, previewSettings, darkPreview).background)
        }
        preview.layoutParams = preview.layoutParams.apply {
            width = (dp(previewSpec.widthDp) * previewScale(previewSpec.widthDp)).toInt()
            height = (dp(previewSpec.heightDp) * previewScale(previewSpec.widthDp)).toInt()
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
                    dark = darkPreview,
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
        val values = arrayOf(
            TwidgetStore.FONT_SYSTEM,
            TwidgetStore.FONT_ONE_UI_SANS,
            TwidgetStore.FONT_GOOGLE_SANS_FLEX,
        )
        showDropDown(anchor, values.map { fontLabel(it) }, values.indexOf(fontFamily).coerceAtLeast(0)) { which ->
            fontFamily = values[which]
            render()
        }
    }

    private fun pickLogo(anchor: View) {
        val values = arrayOf(TwidgetStore.LOGO_X, TwidgetStore.LOGO_TWITTER)
        val labels = values.map {
            when (it) {
                TwidgetStore.LOGO_TWITTER -> getString(R.string.widget_logo_twitter)
                else -> getString(R.string.widget_logo_x)
            }
        }
        showDropDown(anchor, labels, values.indexOf(logo).coerceAtLeast(0)) { which ->
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
        TwidgetStore.saveWidgetSettings(this, appWidgetId, TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, accountUsername, colorMode, fontFamily, showDelta, language, widgetStyle))
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
                if (isBriefWidget) {
                    TwidgetBriefWidget.updateWidget(this, manager, appWidgetId)
                } else {
                    TwidgetWidget.updateWidget(this, manager, appWidgetId)
                }
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

    private fun isDarkPreview(): Boolean = widgetUsesDarkTheme(colorMode)
    private fun homePreviewSpec(): HomePreviewSpec {
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val oneUi = com.tjg.twidget.ui.TwidgetFonts.hasSystemOneUiSans
        val width = options.getInt(if (!oneUi && landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (isBriefWidget) 352 else 162).coerceAtLeast(100)
        val height = options.getInt(if (!oneUi && !landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 176).coerceAtLeast(56)
        val mode = if (oneUi && !options.isEmpty) TwidgetWidget.layoutMode(options)
            else TwidgetWidget.layoutModeForAosp(width, height)
        return HomePreviewSpec(mode, width, height,
            if (widgetStyle == WidgetStyle.MATERIAL || height > 110) 26f else height / 2f)
    }

    private fun previewScale(widthDp: Int): Float = minOf(1f,
        (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 64f) / widthDp)

    private fun colorModeLabel(mode: String): String = when (mode) {
        TwidgetStore.COLOR_MODE_DARK -> getString(R.string.widget_tint_dark)
        TwidgetStore.COLOR_MODE_SYSTEM -> getString(R.string.widget_tint_system)
        else -> getString(R.string.widget_tint_light)
    }
    private fun fontLabel(font: String): String = when (font) {
        TwidgetStore.FONT_SYSTEM -> getString(R.string.widget_font_system)
        TwidgetStore.FONT_GOOGLE_SANS_FLEX -> getString(R.string.widget_font_google)
        else -> getString(R.string.widget_font_one_ui)
    }

    private fun closestOpacityLevel(alpha: Int): Int = WidgetOpacityControl.closestLevel(alpha)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LOCKSCREEN_WIDGET = "com.tjg.twidget.extra.LOCKSCREEN_WIDGET"
        const val EXTRA_LOCKSCREEN_WIDE = "com.tjg.twidget.extra.LOCKSCREEN_WIDE"
        private val OPACITY_PRESETS = WidgetOpacityControl.presets
    }

    private data class HomePreviewSpec(
        val mode: Int,
        val widthDp: Int,
        val heightDp: Int,
        val cornerRadiusDp: Float,
    )
    private fun pickLanguage(anchor: View) {
        val values = arrayOf("DEFAULT", "de", "en")
        val labels = listOf(
            getString(R.string.widget_language_default),
            getString(R.string.widget_language_de),
            getString(R.string.widget_language_en)
        )
        showDropDown(anchor, labels, values.indexOf(language).coerceAtLeast(0)) { which ->
            language = values[which]
            render()
        }
    }

    private fun languageLabel(lang: String): String = when (lang) {
        "de" -> getString(R.string.widget_language_de)
        "en" -> getString(R.string.widget_language_en)
        else -> getString(R.string.widget_language_default)
    }
}
