package com.tjg.twidget.social

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import com.tjg.twidget.main.OnboardingActivity
import com.tjg.twidget.providers.FxTwitterClient
import com.tjg.twidget.ui.EdgeToEdgeActivity
import dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout
import android.widget.LinearLayout

class SocialOnboardingActivity : EdgeToEdgeActivity() {
    internal var step = Step.WELCOME
    internal var platform = SocialPlatform.X
    internal var catalog = SocialCatalog()
    internal var handle = ""
    internal var customName = ""
    internal var nameSource = ""
    internal var avatarSource = ""
    internal var editingProfile = ""
    internal var widgetAccountId = ""
    internal val selected = linkedSetOf<String>()
    internal var busy = false
    private var pendingLink = false
    private var generation = 0
    private var addMode = false
    internal var upgrade = false
        private set
    private var callback: Uri? = null
    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) { busy = false; render(); return@registerForActivityResult }
        val token = runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken }.getOrNull()
        if (token.isNullOrBlank()) failed() else connectToken(SocialPlatform.YOUTUBE, token, System.currentTimeMillis() + 55 * 60 * 1000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addMode = savedInstanceState?.getBoolean("flowAdd") ?: intent.getBooleanExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, false)
        upgrade = savedInstanceState?.getBoolean("flowUpgrade") ?: intent.getBooleanExtra(EXTRA_UPGRADE, false)
        step = savedInstanceState?.getString("step")?.let { Step.valueOf(it) } ?: if (addMode) Step.PLATFORMS else Step.WELCOME
        platform = savedInstanceState?.getString("platform")?.let(SocialPlatform::fromStorageId) ?: SocialPlatform.X
        pendingLink = savedInstanceState?.getBoolean("pendingLink") ?: false
        handle = savedInstanceState?.getString("handle").orEmpty()
        customName = savedInstanceState?.getString("customName").orEmpty()
        nameSource = savedInstanceState?.getString("nameSource").orEmpty()
        avatarSource = savedInstanceState?.getString("avatarSource").orEmpty()
        editingProfile = savedInstanceState?.getString("editingProfile").orEmpty()
        widgetAccountId = savedInstanceState?.getString("widgetAccountId").orEmpty()
        selected.addAll(savedInstanceState?.getStringArrayList("selected").orEmpty())
        callback = intent.data
        setContentView(R.layout.activity_social_onboarding)
        findViewById<AdaptiveCoordinatorLayout>(R.id.social_root).configureAdaptiveMargin(
            AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ADP_DEFAULT, setOf(findViewById<View>(R.id.social_content)))
        applyEdgeToEdgeInsets(findViewById(R.id.social_content)) { inset ->
            findViewById<View>(R.id.social_content).apply { setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + inset) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = back() })
        findViewById<AppCompatButton>(R.id.social_back).setOnClickListener { back() }
        findViewById<AppCompatButton>(R.id.social_next).setOnClickListener { next() }
        reload()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); this.intent.data = intent.data; callback = intent.data; reload() }
    override fun onDestroy() { generation++; super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("pendingLink", pendingLink)
        out.putBoolean("flowAdd", addMode); out.putBoolean("flowUpgrade", upgrade)
        out.putString("step", step.name); out.putString("platform", platform.storageId); out.putString("handle", handle)
        out.putString("customName", customName); out.putString("nameSource", nameSource); out.putString("avatarSource", avatarSource)
        out.putString("widgetAccountId", widgetAccountId)
        out.putString("editingProfile", editingProfile); out.putStringArrayList("selected", ArrayList(selected))
        super.onSaveInstanceState(out)
    }
    internal fun reload() = work({
        SocialRepository(applicationContext).use { it.synchronizeLegacyFrom(applicationContext) }
    }) {
        catalog = it
        val uri = callback; callback = null; intent.data = null
        if (uri != null) work({ SocialConnections.redeem(applicationContext, uri) }) { (provider, tokens) ->
            addMode = tokens.optBoolean("flowAdd"); upgrade = tokens.optBoolean("flowUpgrade")
            tokens.remove("flowAdd"); tokens.remove("flowUpgrade")
            connectToken(provider, tokens.getString("accessToken"), if (tokens.isNull("expiresAt")) null else tokens.getLong("expiresAt"), tokens)
        } else {
            if (pendingLink && step == Step.DISPLAY) {
                val preview = runCatching { SocialProfilePolicy.link(catalog, editingProfile, selected - editingProfile) }.getOrNull()
                if (preview != null) catalog = preview else { pendingLink = false; step = Step.LINK }
            }
            render()
        }
    }

    internal fun choose(value: SocialPlatform) { platform = value; handle = ""; step = Step.CONNECT; render() }
    internal fun connect() {
        if (busy) return
        currentFocus?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        when (platform) {
            SocialPlatform.X, SocialPlatform.BLUESKY -> {
                if (!platform.validPublicHandle(handle)) { failed(); return }
                val chosen = platform; val input = handle
                work({
                    val context = applicationContext
                    SocialRepository(context).use { repository ->
                        if (chosen == SocialPlatform.X) {
                            val stats = FxTwitterClient.fetchProfile(chosen.normalizeHandle(input))
                            require(stats.userName.equals(chosen.normalizeHandle(input), true))
                            TwidgetStore.addOnboardingAccount(context, stats.userName)
                            TwidgetStore.saveStats(context, stats)
                            val updated = repository.synchronizeLegacyFrom(context)
                            updated.accounts.first { it.id == LegacySocialMigration.accountId(input) }
                        } else {
                            val result = BlueskyProfileProvider.lookup(input)
                            check(result is SocialProfileResult.Success)
                            repository.connect(result)
                        }
                    }
                }) { connected(it) }
            }
            SocialPlatform.YOUTUBE -> {
                busy = true; render()
                Identity.getAuthorizationClient(this).authorize(AuthorizationRequest.builder()
                    .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/youtube.readonly"))).build())
                    .addOnSuccessListener(this) { result ->
                        if (result.hasResolution()) {
                            googleConsent.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
                        } else {
                            val token = result.accessToken
                            if (token.isNullOrBlank()) failed() else connectToken(SocialPlatform.YOUTUBE, token, System.currentTimeMillis() + 55 * 60 * 1000)
                        }
                    }.addOnFailureListener(this) { failed() }
            }
            else -> work({ SocialConnections.start(applicationContext, platform, addMode, upgrade) }) { uri ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { failed() }
            }
        }
    }

    private fun connectToken(provider: SocialPlatform, token: String, expiry: Long?, session: org.json.JSONObject? = null) = work({
        val result = AuthenticatedProfileProviders.fetch(provider, token)
        check(result is SocialProfileResult.Success)
        SocialRepository(applicationContext).use { repository ->
            // Persist credentials first, then publish the account; reconnects use its stable local ID.
            val existing = repository.catalog().accounts.firstOrNull { it.platform == provider && it.remoteId == result.account.remoteId }
            val id = existing?.id ?: result.account.id
            if (session != null) SocialConnections.saveSession(applicationContext, id, session) else SocialConnections.save(applicationContext, id, token, expiry)
            repository.connect(result)
        }
    }) { connected(it) }

    private fun connected(account: PlatformAccount) {
        step = Step.PLATFORMS
        work({ SocialRepository(applicationContext).use { it.catalog() } }) { updated ->
            catalog = updated
            catalog.profileFor(account.id)?.let { selected += it.id; editingProfile = it.id }
            render()
        }
    }

    internal fun render() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        findViewById<View>(R.id.social_settle).visibility = if (step == Step.DONE) View.VISIBLE else View.GONE
        supportFragmentManager.beginTransaction().replace(R.id.social_fragment, SocialOnboardingFragment()).commit()
        findViewById<AppCompatButton>(R.id.social_next).apply {
            text = getString(if (busy) R.string.social_working else when (step) {
                Step.WELCOME -> R.string.get_started
                Step.LINK -> R.string.social_yes
                Step.DONE -> R.string.social_continue
                Step.WIDGET -> R.string.social_add_widget
                Step.CONNECT -> if (platform in listOf(SocialPlatform.X, SocialPlatform.BLUESKY)) R.string.social_continue else R.string.social_sign_in
                else -> R.string.social_continue
            }, platform.label)
            isEnabled = !busy && (step != Step.PLATFORMS || catalog.accounts.isNotEmpty())
        }
        findViewById<AppCompatButton>(R.id.social_back).apply {
            text = getString(when (step) { Step.LINK -> R.string.social_no; Step.WIDGET -> R.string.social_skip; else -> R.string.back })
            isEnabled = !busy
            visibility = if (step in setOf(Step.LINK, Step.DISPLAY, Step.READY, Step.WIDGET)) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.social_buttons).visibility = if (step == Step.PLATFORMS && catalog.accounts.isEmpty()) View.GONE else View.VISIBLE
        val paired = findViewById<View>(R.id.social_back).visibility == View.VISIBLE
        findViewById<AppCompatButton>(R.id.social_next).layoutParams = LinearLayout.LayoutParams(
            if (paired) 0 else 268.dp(), 58.dp(), if (paired) 1f else 0f).apply { marginStart = if (paired) 6.dp() else 0 }
    }

    private fun next() {
        if (busy) return
        when (step) {
            Step.WELCOME -> { step = Step.PLATFORMS; render() }
            Step.CONNECT -> connect()
            Step.PLATFORMS -> {
                val differentPlatforms = catalog.accounts.map { it.platform }.distinct().size > 1
                if (selected.size < 2 && differentPlatforms) {
                    selected.clear()
                    val platforms = mutableSetOf<SocialPlatform>()
                    catalog.profiles.forEach { profile ->
                        val members = profile.accountIds.map { catalog.accountsById.getValue(it).platform }
                        if (members.none { it in platforms }) { selected += profile.id; platforms += members }
                    }
                }
                step = if (catalog.profiles.size > 1 && differentPlatforms) Step.LINK else Step.READY
                render()
            }
            Step.LINK -> link()
            Step.DISPLAY -> work({ SocialRepository(applicationContext).use { repository -> repository.edit {
                val linked = if (pendingLink) SocialProfilePolicy.link(it, editingProfile, selected - editingProfile) else it
                SocialProfilePolicy.display(linked, editingProfile, nameSource, avatarSource, customName)
            } } }) { catalog = it; pendingLink = false; step = Step.READY; render() }
            Step.READY -> { step = if (addMode || upgrade) Step.DONE else Step.WIDGET; render() }
            Step.WIDGET -> {
                val manager = android.appwidget.AppWidgetManager.getInstance(this)
                if (manager.isRequestPinAppWidgetSupported) {
                    SocialWidgetCache.preparePin(this, widgetAccountId)
                    manager.requestPinAppWidget(
                    android.content.ComponentName(this, com.tjg.twidget.TwidgetWidget::class.java), null, null)
                }
                step = Step.DONE; render()
            }
            Step.DONE -> work({
                SocialRepository(applicationContext).use { it.completeUpgradeIntroduction() }
                TwidgetStore.completeSocialOnboarding(applicationContext)
            }) {
                if (addMode) finish() else {
                    val dashboard = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    if (upgrade) {
                        val notice = Intent(this, com.tjg.twidget.notices.NoticeDetailActivity::class.java)
                            .putExtra(com.tjg.twidget.notices.NoticeDetailActivity.EXTRA_LATEST, true)
                        startActivities(arrayOf(dashboard, notice))
                    } else startActivity(dashboard)
                    finish()
                }
            }
        }
    }
    private fun link() {
        if (selected.size < 2) { Toast.makeText(this, R.string.social_link_error, Toast.LENGTH_LONG).show(); return }
        val target = catalog.defaultProfileId?.takeIf { it in selected } ?: selected.first()
        val source = selected - target
        if (runCatching { SocialProfilePolicy.link(catalog, target, source) }.isFailure) {
            Toast.makeText(this, R.string.social_link_error, Toast.LENGTH_LONG).show(); return
        }
        catalog = SocialProfilePolicy.link(catalog, target, source)
        editingProfile = target; pendingLink = true
        val profile = catalog.profiles.first { it.id == target }
        nameSource = profile.nameAccountId; avatarSource = profile.avatarAccountId; customName = profile.customDisplayName.orEmpty()
        step = Step.DISPLAY; render()
    }

    private fun back() {
        if (busy) return
        step = when (step) {
            Step.WELCOME -> { finish(); return }
            Step.PLATFORMS -> if (addMode) { finish(); return } else Step.WELCOME
            Step.CONNECT -> Step.PLATFORMS
            Step.LINK -> Step.READY
            Step.DISPLAY -> {
                pendingLink = false; step = Step.LINK; reload(); return
            }
            Step.READY -> Step.PLATFORMS
            Step.WIDGET -> Step.DONE
            Step.DONE -> Step.READY
        }
        render()
    }
    internal fun failed() { busy = false; Toast.makeText(this, R.string.social_connection_failed, Toast.LENGTH_LONG).show(); render() }
    private fun <T> work(block: () -> T, done: (T) -> Unit) {
        busy = true; render(); val current = ++generation
        AppExecutors.execute(onRejected = { runOnUiThread { if (generation == current && !isDestroyed) failed() } }) {
            val result = runCatching(block)
            runOnUiThread {
                if (generation != current || isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess(done).onFailure { failed() }
            }
        }
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    internal enum class Step { WELCOME, PLATFORMS, CONNECT, LINK, DISPLAY, READY, WIDGET, DONE }
    companion object { const val EXTRA_UPGRADE = "multiplatform_upgrade" }
}
