package com.tjg.twidget.widget

import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.PagerAdapter
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
import dev.oneuiproject.oneui.widget.SwitchItemView
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
    private var tapAction = TwidgetStore.TAP_APP
    private var accountUsername = ""
    private var colorMode = TwidgetStore.COLOR_MODE_SYSTEM
    private var widgetStyle = WidgetStyle.ONE_UI
    private var fontFamily = TwidgetStore.FONT_ONE_UI_SANS
    private var showDelta = true
    private var containedFooter = false
    private var language = "DEFAULT"
    private var currentLevel = 2
    private var isLockWidget = false
    private var isLockWide = false
    private var isBriefWidget = false
    private var isColorOsBlurProbe = false
    private var widgetKind = WidgetKind.FOLLOWERS
    private var editingDefaults = false
    private val previewPages = mutableListOf<FrameLayout>()
    private val accountRadios = mutableListOf<Pair<String, RadioButton>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasEntered = savedInstanceState != null
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        isLockWidget = intent?.getBooleanExtra(EXTRA_LOCKSCREEN_WIDGET, false) == true
        isLockWide = intent?.getBooleanExtra(EXTRA_LOCKSCREEN_WIDE, false) == true
        val providerClass = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
        isColorOsBlurProbe = com.tjg.twidget.BuildConfig.DEBUG &&
            providerClass == com.tjg.twidget.widget.coloros.ColorOsWidgetBlurProbe.PROVIDER
        val requestedKind = intent?.getStringExtra(EXTRA_DEFAULT_KIND)
        editingDefaults = requestedKind != null || appWidgetId <= 0
        widgetKind = WidgetKind.entries.firstOrNull { it.name == requestedKind }
            ?: if (isLockWidget) WidgetKind.LOCK_SCREEN else WidgetKind.fromProvider(providerClass)
        isBriefWidget = widgetKind == WidgetKind.BRIEF
        isLockWidget = widgetKind == WidgetKind.LOCK_SCREEN
        if (providerClass.startsWith("com.tjg.twidget.LockScreenFollower")) {
            isLockWidget = true
            isLockWide = providerClass == com.tjg.twidget.LockScreenFollowerWideWidget::class.java.name
        }
        setContentView(R.layout.activity_widget_config)
        WidgetConfigChrome.install(findViewById(R.id.widget_config_root))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                backGestureActive = true
                findViewById<WallpaperPreviewLayout>(R.id.preview_container).setBackProgress(backEvent.progress)
            }

            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                findViewById<WallpaperPreviewLayout>(R.id.preview_container).setBackProgress(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                backGestureActive = false
                revealWallpaperPreview()
            }

            override fun handleOnBackPressed() {
                backGestureActive = false
                setResult(RESULT_CANCELED)
                finish()
            }
        })
        val buttonBar = findViewById<View>(R.id.config_button_bar)
        val baseButtonMargin = (16 * resources.displayMetrics.density).toInt()
        applyEdgeToEdgeInsets(findViewById(R.id.widget_config_root)) { navigationBarInset ->
            buttonBar.updateBottomMarginForNavigationBar(baseButtonMargin, navigationBarInset)
        }
        if (isLockWidget) {
            // Lock screen artwork is monotone white — opacity, tint, font, and
            // tap action don't apply there; account and logo do.
            listOf(R.id.opacity_block, R.id.tint_row, R.id.font_row, R.id.tap_separator, R.id.tap_action_card)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            findViewById<View>(R.id.preview_container).layoutParams =
                findViewById<View>(R.id.preview_container).layoutParams.apply { height = dp(220) }
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
        val settings = TwidgetStore.widgetSettings(this, appWidgetId, widgetKind)
        tintAlpha = settings.tintAlpha
        currentLevel = closestOpacityLevel(tintAlpha)
        tintAlpha = OPACITY_PRESETS[currentLevel]
        tintColor = settings.tintColor
        logo = if (getSharedPreferences(TwidgetStore.PREFS, MODE_PRIVATE).contains("widget_logo_$appWidgetId"))
            settings.logo else TwidgetStore.widgetSettings(this, kind = widgetKind).logo
        tapAction = settings.tapAction
        accountUsername = settings.accountUsername
        colorMode = settings.colorMode
        widgetStyle = if (com.tjg.twidget.BuildConfig.DEBUG &&
            providerClass == com.tjg.twidget.widget.coloros.ColorOsWidgetBlurProbe.PROVIDER &&
            !getSharedPreferences(TwidgetStore.PREFS, MODE_PRIVATE).contains("widget_style_$appWidgetId"))
            WidgetStyle.COLOR_OS else settings.style
        fontFamily = settings.fontFamily
        showDelta = settings.showDelta
        containedFooter = savedInstanceState?.getBoolean("contained_footer", settings.containedFooter) ?: settings.containedFooter
        language = settings.language
        if (isBriefWidget) accountUsername = ""
        savedInstanceState?.let {
            tintAlpha = it.getInt("tint_alpha", tintAlpha)
            currentLevel = closestOpacityLevel(tintAlpha)
            tintColor = it.getInt("tint_color", tintColor)
            logo = it.getString("logo", logo)
            tapAction = it.getString("tap_action", tapAction)
            accountUsername = it.getString("account", accountUsername)
            colorMode = it.getString("color_mode", colorMode)
            widgetStyle = WidgetStyle.resolve(it.getString("style", widgetStyle.storedValue))
            fontFamily = it.getString("font", fontFamily)
            showDelta = it.getBoolean("show_delta", showDelta)
            language = it.getString("language", language)
        }
        installStylePager()
        bindControls()
        if (!isBriefWidget) buildAccountRows()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("contained_footer", containedFooter)
        outState.putInt("tint_alpha", tintAlpha)
        outState.putInt("tint_color", tintColor)
        outState.putString("logo", logo)
        outState.putString("tap_action", tapAction)
        outState.putString("account", accountUsername)
        outState.putString("color_mode", colorMode)
        outState.putString("style", widgetStyle.storedValue)
        outState.putString("font", fontFamily)
        outState.putBoolean("show_delta", showDelta)
        outState.putString("language", language)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        setResult(RESULT_CANCELED)
        finish()
        return true
    }

    private var hasEntered = false
    private var exitRequested = false
    private var backGestureActive = false

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        hasEntered = true
        revealWallpaperPreview()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && hasEntered) revealWallpaperPreview()
    }

    private fun revealWallpaperPreview() {
        if (exitRequested || backGestureActive) return
        val preview = findViewById<WallpaperPreviewLayout>(R.id.preview_container) ?: return
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        preview.fadeInWallpaper()
    }

    private fun hideWallpaperPreview() {
        findViewById<WallpaperPreviewLayout>(R.id.preview_container)?.showWallpaper = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        val background = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.roundedCornerColor, background, true)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.data))
    }

    override fun onPause() {
        if (!exitRequested) {
            findViewById<WallpaperPreviewLayout>(R.id.preview_container)?.fadeOutWallpaper {
                if (!hasWindowFocus()) hideWallpaperPreview()
            }
        }
        super.onPause()
    }

    override fun finish() {
        if (exitRequested) return
        exitRequested = true
        val preview = findViewById<WallpaperPreviewLayout>(R.id.preview_container)
        if (preview == null) {
            super.finish()
            return
        }
        preview.fadeOutWallpaper {
            hideWallpaperPreview()
            super.finish()
        }
    }

    private fun installStylePager() {
        val pager = findViewById<ViewPager>(R.id.widget_style_pager)
        val styles = if (isLockWidget) listOf(WidgetStyle.ONE_UI) else WidgetStyle.entries
        previewPages.clear()
        styles.forEachIndexed { index, _ ->
            previewPages.add(FrameLayout(this).apply {
                addView(FrameLayout(context).apply {
                    if (index == 0) id = R.id.preview_widget
                }, FrameLayout.LayoutParams(dp(162), dp(176), Gravity.CENTER))
            })
        }
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = previewPages.size
            override fun isViewFromObject(view: View, item: Any) = view === item
            override fun instantiateItem(container: ViewGroup, position: Int): Any =
                previewPages[position].also { container.addView(it) }
            override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                container.removeView(item as View)
            }
            override fun getPageTitle(position: Int) = styleLabel(styles[position])
        }
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(styles.indexOf(widgetStyle).coerceAtLeast(0), false)
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                val previous = widgetStyle
                widgetStyle = styles[position]
                if (fontFamily == previous.defaultFont) fontFamily = widgetStyle.defaultFont
                render()
            }
        })
        findViewById<View>(R.id.widget_style_indicator).apply {
            visibility = if (isLockWidget) View.GONE else View.VISIBLE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { pager.setCurrentItem((pager.currentItem + 1) % styles.size, true) }
        }
    }

    private fun bindControls() {
        WidgetOpacityControl.bind(findViewById(R.id.opacity_block), tintAlpha) { alpha ->
            tintAlpha = alpha
            currentLevel = closestOpacityLevel(alpha)
            render()
        }
        findViewById<CardItemView>(R.id.tint_row).setOnClickListener { pickColorMode(it) }
        findViewById<CardItemView>(R.id.logo_row).setOnClickListener { pickLogo(it) }
        findViewById<CardItemView>(R.id.font_row).setOnClickListener { pickFont(it) }
        findViewById<CardItemView>(R.id.language_row)?.setOnClickListener { pickLanguage(it) }
        findViewById<SwitchItemView>(R.id.contained_footer_row).apply {
            isChecked = containedFooter
            onCheckedChangedListener = { _, checked ->
                if (containedFooter != checked) {
                    containedFooter = checked
                    render()
                }
            }
        }
        findViewById<SwitchItemView>(R.id.delta_row).apply {
            isChecked = showDelta
            onCheckedChangedListener = { _, checked ->
                if (showDelta != checked) {
                    showDelta = checked
                    render()
                }
            }
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
            minimumHeight = resources.getDimensionPixelSize(R.dimen.settings_account_min_height)
            val horizontalPadding = resources.getDimensionPixelSize(R.dimen.widget_settings_content_inset)
            val verticalPadding = resources.getDimensionPixelSize(OneUiR.dimen.oui_des_widget_item_vertical_padding)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
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
        when (style) {
            WidgetStyle.MATERIAL -> R.string.widget_style_material
            WidgetStyle.COLOR_OS -> R.string.widget_style_color_os
            WidgetStyle.ONE_UI -> R.string.widget_style_one_ui
        },
    )

    private fun render() {
        val previewMode = if (!isLockWidget && !isBriefWidget) homePreviewSpec().mode else -1
        findViewById<View>(R.id.contained_footer_row).visibility = if (
            widgetStyle == WidgetStyle.MATERIAL && !isLockWidget && !isBriefWidget &&
            previewMode != TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 && previewMode != TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP
        ) View.VISIBLE else View.GONE
        findViewById<View>(R.id.contained_footer_summary).visibility = findViewById<View>(R.id.contained_footer_row).visibility
        findViewById<SwitchItemView>(R.id.contained_footer_row).isChecked = containedFooter
        findViewById<View>(R.id.opacity_block).visibility =
            if (isLockWidget || widgetStyle == WidgetStyle.MATERIAL) View.GONE else View.VISIBLE
        findViewById<View>(R.id.font_row).visibility =
            if (isColorOsBlurProbe && widgetStyle == WidgetStyle.COLOR_OS) View.GONE else View.VISIBLE
        findViewById<CardItemView>(R.id.font_row).showTopDivider = findViewById<View>(R.id.opacity_block).visibility == View.VISIBLE
        findViewById<CardItemView>(R.id.language_row).showTopDivider = !isLockWidget
        findViewById<CardItemView>(R.id.logo_row).showTopDivider = true
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

        findViewById<TextView>(R.id.widget_style_indicator).apply {
            text = "${styleLabel(widgetStyle)}\n${(if (isLockWidget) listOf(WidgetStyle.ONE_UI) else WidgetStyle.entries).joinToString("  ") { if (it == widgetStyle) "●" else "○" }}"
            contentDescription = "${getString(R.string.widget_style)}: ${styleLabel(widgetStyle)}"
        }
        previewPages.forEachIndexed { index, page ->
            renderPreview(page.getChildAt(0) as FrameLayout,
                if (isLockWidget) widgetStyle else WidgetStyle.entries[index])
        }
    }

    private fun renderPreview(preview: FrameLayout, style: WidgetStyle) {
        preview.removeAllViews()
        preview.setPadding(0, 0, 0, 0)

        val selectedAccount = accountUsername.ifBlank { TwidgetStore.settings(this).username }
        val previewSettings = TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, selectedAccount, colorMode, if (fontFamily == widgetStyle.defaultFont) style.defaultFont else fontFamily, showDelta, language, style, containedFooter)

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
            val spec = homePreviewSpec(style)
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
                        fontFamily = previewSettings.fontFamily,
                        style = style,
                    ),
                )
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            return
        }

        if (isColorOsBlurProbe && style == WidgetStyle.COLOR_OS) {
            val spec = homePreviewSpec(style)
            val scale = previewScale(spec.widthDp)
            preview.background = null
            preview.layoutParams = preview.layoutParams.apply {
                width = (dp(spec.widthDp) * scale).toInt()
                height = (dp(spec.heightDp) * scale).toInt()
            }
            val native = ColorOsNativeFollowerViews.create(this, spec.widthDp, spec.heightDp, previewSettings,
                TwidgetStore.currentStats(this, selectedAccount), TwidgetStore.followersDelta(this, selectedAccount), null)
                .apply(this, preview)
            native.scaleX = scale
            native.scaleY = scale
            preview.addView(native, FrameLayout.LayoutParams(dp(spec.widthDp), dp(spec.heightDp), Gravity.CENTER))
            return
        }

        val stats = TwidgetStore.currentStats(this, selectedAccount)
        val previewSpec = homePreviewSpec(style)
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
        TwidgetStore.saveWidgetSettings(this, if (editingDefaults) 0 else appWidgetId,
            TwidgetWidgetSettings(tintAlpha, tintColor, logo, tapAction, accountUsername, colorMode, fontFamily, showDelta, language, widgetStyle, containedFooter), widgetKind)
        if (editingDefaults) {
            WidgetPreviews.publish(this)
            when (widgetKind) {
                WidgetKind.FOLLOWERS -> TwidgetWidget.updateAll(this)
                WidgetKind.BRIEF -> TwidgetBriefWidget.updateAll(this)
                WidgetKind.LOCK_SCREEN -> {
                    LockScreenFollowerViews.updateAll(this)
                    LockScreenFollowerServiceBoxReceiver.refresh(this)
                }
            }
            setResult(RESULT_OK)
            finish()
            return
        }
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
    private fun homePreviewSpec(style: WidgetStyle = widgetStyle): HomePreviewSpec {
        val options = if (appWidgetId > 0) AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId) else Bundle()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val oneUi = com.tjg.twidget.ui.TwidgetFonts.hasSystemOneUiSans && !(isColorOsBlurProbe && style == WidgetStyle.COLOR_OS)
        val width = options.getInt(if (!oneUi && landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (isBriefWidget || editingDefaults) 352 else 162).coerceAtLeast(100)
        val height = options.getInt(if (!oneUi && !landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 176).coerceAtLeast(if (isColorOsBlurProbe && style == WidgetStyle.COLOR_OS) 1 else 56)
        val mode = if (oneUi && !options.isEmpty) TwidgetWidget.layoutMode(options)
            else TwidgetWidget.layoutModeForAosp(width, height)
        return HomePreviewSpec(mode, width, height,
            if (style == WidgetStyle.COLOR_OS) 24f else if (style == WidgetStyle.MATERIAL || height > 110) 26f else height / 2f)
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
        const val EXTRA_DEFAULT_KIND = "com.tjg.twidget.extra.DEFAULT_WIDGET_KIND"
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
        val values = arrayOf("DEFAULT", "ca", "de", "en", "es", "fr")
        val labels = listOf(
            getString(R.string.widget_language_default),
            getString(R.string.widget_language_ca),
            getString(R.string.widget_language_de),
            getString(R.string.widget_language_en),
            getString(R.string.widget_language_es),
            getString(R.string.widget_language_fr)
        )
        showDropDown(anchor, labels, values.indexOf(language).coerceAtLeast(0)) { which ->
            language = values[which]
            render()
        }
    }

    private fun languageLabel(lang: String): String = when (lang) {
        "ca" -> getString(R.string.widget_language_ca)
        "de" -> getString(R.string.widget_language_de)
        "en" -> getString(R.string.widget_language_en)
        "es" -> getString(R.string.widget_language_es)
        "fr" -> getString(R.string.widget_language_fr)
        else -> getString(R.string.widget_language_default)
    }
}
