package com.tjg.twidget.social

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import android.text.TextWatcher
import android.text.Editable
import androidx.fragment.app.Fragment
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader

/** The onboarding is a sequence of full-page compositions, sharing the original gradient shell. */
class SocialOnboardingFragment : Fragment() {
    private val host get() = requireActivity() as SocialOnboardingActivity
    private val ctx get() = requireContext()
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun AppCompatEditText.doAfterTextChanged(action: (Editable?) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action(s)
    })
    private fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int = 16, bold: Boolean = false, centered: Boolean = false) = AppCompatTextView(ctx).apply {
        text = value; textSize = size.toFloat(); includeFontPadding = false
        typeface = Typeface.create("sec", if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = if (centered) Gravity.CENTER else Gravity.START
        setTextColor(ctx.getColor(R.color.oneui_text_primary))
    }
    private fun LinearLayout.put(view: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height < 0) height else height.dp()).apply { topMargin = top.dp() })
    }
    private fun LinearLayout.space(height: Int) = put(Space(ctx), height)
    private fun LinearLayout.flex() = addView(Space(ctx), LinearLayout.LayoutParams(1, 0, 1f))
    private fun logo(platform: SocialPlatform, size: Int = 24) = ImageView(ctx).apply {
        setImageDrawable(platform.icon(ctx)); layoutParams = LinearLayout.LayoutParams(size.dp(), size.dp())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun avatar(account: PlatformAccount?, size: Int = 64) = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(size.dp(), size.dp())
        contentDescription = account?.displayName
        ProfileImageLoader.loadInto(ctx, this, account?.avatarUrl.orEmpty())
    }
    private fun LinearLayout.heading(title: String, subtitle: String? = null, platform: SocialPlatform? = null) {
        val compact = host.step == SocialOnboardingActivity.Step.DISPLAY && resources.configuration.screenHeightDp < 800
        space(if (compact) 24 else if (platform != null) 40 else 64)
        if (platform != null) { addView(logo(platform, 64), LinearLayout.LayoutParams(64.dp(), 64.dp()).apply { gravity = Gravity.CENTER_HORIZONTAL }); space(20) }
        put(text(title, 32, true, true))
        subtitle?.let { put(text(it, 16, centered = true).apply { alpha = .7f }, top = 14) }
        space(if (compact) 24 else 38)
    }
    private fun row(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 60.dp(); setPadding(20.dp(), 12.dp(), 20.dp(), 12.dp())
        setBackgroundResource(R.drawable.onboarding_glass_button_bg)
    }
    private fun LinearLayout.rowText(title: String, subtitle: String? = null) {
        addView(column().apply {
            put(text(title, 18))
            subtitle?.let { put(text(it, 14).apply { alpha = .65f }, top = 4) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16.dp(); marginEnd = 8.dp() })
    }
    private fun link(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val body = column().apply { setPadding(20.dp(), 12.dp(), 20.dp(), 20.dp()); minimumHeight = 560.dp() }
        when (host.step) {
            SocialOnboardingActivity.Step.WELCOME -> with(body) {
                addView(ImageView(ctx).apply { setImageResource(R.drawable.onboarding_twidget_mark); contentDescription = null },
                    LinearLayout.LayoutParams(224.dp(), 224.dp()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                put(text(getString(if (host.upgrade) R.string.social_upgrade else R.string.social_welcome), 32, true, true))
                flex()
                put(text(getString(R.string.social_welcome_terms), 14, centered = true).apply {
                    setPadding(12.dp(), 20.dp(), 12.dp(), 12.dp())
                    val value = text.toString()
                    val label = getString(R.string.social_privacy_link)
                    val start = value.indexOf(label)
                    if (start >= 0) {
                        text = android.text.SpannableString(value).apply {
                            setSpan(android.text.style.URLSpan(getString(R.string.link_privacy_policy)), start, start + label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        movementMethod = android.text.method.LinkMovementMethod.getInstance()
                    }
                })
            }
            SocialOnboardingActivity.Step.PLATFORMS -> with(body) {
                heading(getString(if (host.catalog.accounts.isEmpty()) R.string.social_choose_platform else R.string.social_more_platforms))
                flex()
                SocialPlatform.entries.forEach { platform ->
                    val accounts = host.catalog.accounts.filter { it.platform == platform }
                    put(row().apply {
                        tag = platform.storageId
                        addView(logo(platform))
                        rowText(platform.label, accounts.joinToString { "@${it.handle}" }.takeIf { it.isNotBlank() })
                        if (accounts.isNotEmpty()) addView(AppCompatCheckBox(ctx).apply {
                            isChecked = true; isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(32.dp(), 32.dp()))
                        isEnabled = !host.busy; setOnClickListener { host.choose(platform) }
                    }, top = 10)
                }
                space(30)
            }
            SocialOnboardingActivity.Step.CONNECT -> with(body) {
                val public = host.platform in setOf(SocialPlatform.X, SocialPlatform.BLUESKY)
                heading(getString(if (public) R.string.social_enter_handle else R.string.social_sign_in_title, host.platform.label), platform = host.platform)
                if (public) {
                    put(AppCompatEditText(ctx).apply {
                        tag = "social_handle"; setSingleLine(); hint = "@"; textSize = 22f
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        imeOptions = EditorInfo.IME_ACTION_DONE
                        setBackgroundResource(R.drawable.onboarding_input_bg)
                        setPadding(22.dp(), 4.dp(), 22.dp(), 4.dp())
                        setText(host.handle); setSelection(text?.length ?: 0); isEnabled = !host.busy
                        doAfterTextChanged { host.handle = it.toString().trim() }
                        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { host.connect(); true } else false }
                    }, 60)
                    if (host.platform == SocialPlatform.X) put(LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL; setPadding(0, 12.dp(), 8.dp(), 0)
                        val check = AppCompatCheckBox(ctx).apply {
                            contentDescription = getString(R.string.share_history)
                            isChecked = TwidgetStore.settings(ctx).shareHistory
                            setOnCheckedChangeListener { _, value ->
                                val app = ctx.applicationContext
                                com.tjg.twidget.core.AppExecutors.execute { TwidgetStore.saveSettings(app, TwidgetStore.settings(app).copy(shareHistory = value)) }
                            }
                        }
                        addView(check, LinearLayout.LayoutParams(40.dp(), 48.dp()))
                        rowText(getString(R.string.share_history), getString(R.string.share_history_summary))
                        setOnClickListener { check.toggle() }
                    }, top = 8)
                } else {
                    put(text(getString(R.string.social_session_secure), 18), top = 12)
                    put(text(getString(when (host.platform) {
                        SocialPlatform.YOUTUBE -> R.string.social_youtube_privacy
                        SocialPlatform.INSTAGRAM -> R.string.social_instagram_privacy
                        else -> R.string.social_github_privacy
                    }), 14).apply { alpha = .7f }, top = 6)
                    if (host.platform == SocialPlatform.INSTAGRAM) put(text(getString(R.string.social_instagram_eligibility), 14), top = 16)
                    put(text(getString(R.string.social_platform_terms), 18).apply {
                        setPadding(0, 12.dp(), 0, 12.dp())
                        setOnClickListener { link(when(host.platform) {
                            SocialPlatform.INSTAGRAM -> "https://help.instagram.com/581066165581870"
                            SocialPlatform.YOUTUBE -> "https://www.youtube.com/t/terms"
                            else -> "https://docs.github.com/en/site-policy/github-terms/github-terms-of-service"
                        }) }
                    }, top = 20)
                }
                flex()
            }
            SocialOnboardingActivity.Step.LINK -> with(body) {
                heading(getString(R.string.social_link_title), getString(R.string.social_link_summary))
                flex()
                host.catalog.profiles.forEach { profile ->
                    val account = host.catalog.accountsById[profile.avatarAccountId]
                    put(row().apply {
                        addView(avatar(account, 54))
                        rowText(profile.displayName(host.catalog.accountsById), profile.accountIds.joinToString { "@${host.catalog.accountsById.getValue(it).handle}" })
                        account?.let { addView(logo(it.platform, 24)) }
                        val check = AppCompatCheckBox(ctx).apply {
                            contentDescription = profile.displayName(host.catalog.accountsById)
                            isChecked = profile.id in host.selected
                            setOnCheckedChangeListener { _, value -> if (value) host.selected += profile.id else host.selected -= profile.id }
                        }
                        addView(check, LinearLayout.LayoutParams(40.dp(), 48.dp()))
                        setOnClickListener { check.toggle() }
                    }, top = 10)
                }
                flex()
            }
            SocialOnboardingActivity.Step.DISPLAY -> with(body) {
                heading(getString(R.string.social_display_title), getString(R.string.social_display_summary))
                val profile = host.catalog.profiles.firstOrNull { it.id == host.editingProfile }
                val members = profile?.accountIds?.map { host.catalog.accountsById.getValue(it) }.orEmpty()
                flex()
                put(text(getString(R.string.social_avatar_source), 14, true).apply { alpha = .65f }, top = 12)
                val choices = row().apply { setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp()) }
                val avatarViews = mutableMapOf<String, View>()
                members.forEach { account ->
                    val choice = column().apply {
                        gravity = Gravity.CENTER; setPadding(4.dp(), 10.dp(), 4.dp(), 10.dp())
                        addView(avatar(account, 56)); space(10); addView(logo(account.platform, 24))
                        tag = "avatar:${account.id}"
                        contentDescription = getString(R.string.social_avatar_choice, account.platform.label)
                        isSelected = host.avatarSource == account.id
                        if (isSelected) setBackgroundResource(R.drawable.onboarding_glass_button_bg)
                        setOnClickListener {
                            host.avatarSource = account.id
                            avatarViews.forEach { (id, view) ->
                                view.isSelected = id == account.id
                                if (view.isSelected) view.setBackgroundResource(R.drawable.onboarding_glass_button_bg) else view.background = null
                            }
                        }
                    }
                    avatarViews[account.id] = choice
                    choices.addView(choice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                put(choices, top = 10)
                put(text(getString(R.string.social_name_source), 14, true).apply { alpha = .65f }, top = 16)
                val custom = text(host.customName.ifBlank { getString(R.string.social_custom_name) }, 14).apply {
                    setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                    setOnClickListener {
                        val input = AppCompatEditText(ctx).apply {
                            setSingleLine(); setText(host.customName)
                            filters = arrayOf(android.text.InputFilter.LengthFilter(100))
                        }
                        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle(R.string.social_custom_name).setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                host.customName = input.text.toString().trim()
                                text = host.customName.ifBlank { getString(R.string.social_custom_name) }
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                val radios = mutableMapOf<String, AppCompatRadioButton>()
                members.forEach { account ->
                    val radio = AppCompatRadioButton(ctx).apply {
                        isChecked = host.nameSource == account.id; isClickable = false; isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    radios[account.id] = radio
                    put(row().apply {
                        tag = "name:${account.id}"
                        addView(logo(account.platform, 24)); rowText(account.displayName.ifBlank { account.handle })
                        addView(radio, LinearLayout.LayoutParams(32.dp(), 32.dp()))
                        setOnClickListener {
                            host.nameSource = account.id; host.customName = ""; custom.text = getString(R.string.social_custom_name)
                            radios.forEach { (id, button) -> button.isChecked = id == account.id }
                        }
                    }, top = 10)
                }
                put(custom, top = 8)
            }
            SocialOnboardingActivity.Step.READY -> with(body) {
                val profile = host.catalog.profiles.firstOrNull { it.id == host.editingProfile }
                    ?: host.catalog.profiles.firstOrNull { it.id == host.catalog.defaultProfileId }
                space(28)
                addView(avatar(host.catalog.accountsById[profile?.avatarAccountId], 70), LinearLayout.LayoutParams(70.dp(), 70.dp()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                put(text(getString(R.string.social_hello, profile?.displayName(host.catalog.accountsById).orEmpty()), 32, true, true), top = 20)
                put(text(getString(R.string.social_ready), 16, centered = true).apply { alpha = .7f }, top = 14)
                val members = profile?.accountIds?.mapNotNull { host.catalog.accountsById[it] }.orEmpty()
                val group = column().apply { setBackgroundResource(R.drawable.onboarding_glass_button_bg) }
                fun option(icon: Int, title: String, subtitle: String, action: () -> Unit) {
                    group.put(row().apply {
                        background = null
                        addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.oneui_text_primary)) }, LinearLayout.LayoutParams(24.dp(), 24.dp()))
                        rowText(title, subtitle)
                        setOnClickListener { action() }
                    })
                }
                if (members.any { it.platform == SocialPlatform.X || it.platform == SocialPlatform.BLUESKY }) {
                    option(R.drawable.ic_buffer, getString(R.string.social_link_buffer), getString(R.string.social_buffer_summary)) {
                        startActivity(Intent(ctx, com.tjg.twidget.settings.SettingsScheduleActivity::class.java))
                    }
                }
                members.filter { it.platform == SocialPlatform.X }.forEach { account ->
                    group.put(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.oneui_text_secondary)); alpha = .12f }, 1)
                    option(R.drawable.ic_import_analytics, getString(R.string.import_x_analytics), "@${account.handle}") {
                        startActivity(Intent(ctx, com.tjg.twidget.analytics.AnalyticsImportActivity::class.java).putExtra(com.tjg.twidget.analytics.AnalyticsImportActivity.EXTRA_USERNAME, account.handle))
                    }
                }
                if (group.childCount > 0) {
                    put(text(getString(R.string.social_optional), 14, true).apply { alpha = .65f }, top = 64)
                    put(group, top = 10)
                }
                flex()
            }
            SocialOnboardingActivity.Step.WIDGET -> with(body) {
                heading(getString(R.string.social_add_widget))
                flex()
                val profile = host.catalog.profiles.firstOrNull { it.id == host.editingProfile }
                    ?: host.catalog.profiles.firstOrNull { it.id == host.catalog.defaultProfileId }
                val accounts = profile?.accountIds?.map { host.catalog.accountsById.getValue(it) }.orEmpty()
                if (accounts.none { it.id == host.widgetAccountId }) host.widgetAccountId = accounts.firstOrNull()?.id.orEmpty()
                val settings = TwidgetStore.widgetSettings(ctx).copy(socialAccountId = host.widgetAccountId)
                put(ImageView(ctx).apply {
                    adjustViewBounds = true; contentDescription = getString(R.string.social_add_widget)
                    setImageBitmap(com.tjg.twidget.widget.WidgetArtworkRenderer.render(ctx, 700, 350,
                        SocialWidgetCache.stats(ctx, settings), settings, com.tjg.twidget.widget.TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP,
                        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES,
                        SocialWidgetCache.delta(ctx, settings), true))
                })
                val group = column().apply { setBackgroundResource(R.drawable.onboarding_glass_button_bg) }
                accounts.forEachIndexed { index, account ->
                    if (index > 0) group.put(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.oneui_text_secondary)); alpha = .12f }, 1)
                    group.put(row().apply {
                        background = null; addView(logo(account.platform, 24)); rowText(account.platform.label, "@${account.handle}")
                        addView(AppCompatRadioButton(ctx).apply {
                            isChecked = host.widgetAccountId == account.id; isClickable = false; isFocusable = false
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(32.dp(), 32.dp()))
                        setOnClickListener { host.widgetAccountId = account.id; host.render() }
                    })
                }
                put(group, top = 24)
                flex()
            }
            SocialOnboardingActivity.Step.DONE -> with(body) {
                heading(getString(R.string.onboarding_done_title))
                flex()
            }
        }
        if (host.busy) {
            fun disable(view: View) {
                view.isEnabled = false
                if (view is ViewGroup) (0 until view.childCount).forEach { disable(view.getChildAt(it)) }
            }
            disable(body)
        }
        return ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false; addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }
}
