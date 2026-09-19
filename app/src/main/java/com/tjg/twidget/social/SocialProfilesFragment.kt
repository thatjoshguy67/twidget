package com.tjg.twidget.social

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.BufferOAuth
import com.tjg.twidget.settings.SettingsScheduleActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.TwidgetWidget

class SocialProfilesFragment : InsetPreferenceFragment() {
    private var catalog = SocialCatalog()
    private var generation = 0
    private var editing = ""
    private var busy = false
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        editing = savedInstanceState?.getString("editing").orEmpty()
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("editing", editing); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); reload() }
    override fun onDestroy() { generation++; super.onDestroy() }
    private fun reload() = work { SocialRepository(it).use { repository -> repository.synchronizeLegacyFrom(it) } }
    private fun work(action: (android.content.Context) -> SocialCatalog) {
        if (!isAdded) return
        val app = requireContext().applicationContext; val current = ++generation
        busy = true; preferenceScreen?.isEnabled = false
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        AppExecutors.execute(onRejected = { ui.post { if (isAdded && generation == current) showError() } }) {
            val result = runCatching { action(app) }
            ui.post {
                if (!isAdded || generation != current) return@post
                busy = false
                result.onSuccess { catalog = it; render() }.onFailure { showError() }
            }
        }
    }
    private fun showError() { busy = false; preferenceScreen?.isEnabled = true; Toast.makeText(context, R.string.social_load_failed, Toast.LENGTH_LONG).show() }
    private fun edit(transform: (SocialCatalog) -> SocialCatalog) = work { app -> SocialRepository(app).use { it.edit(transform) } }
    private fun render() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        val profile = catalog.profiles.firstOrNull { it.id == editing }
        if (profile != null) {
            screen.row(getString(R.string.back)) { editing = ""; render() }
            screen.addDisplayChoices(catalog, profile, profile.nameAccountId, profile.avatarAccountId, profile.customDisplayName.orEmpty(),
                nameChanged = { id -> edit { SocialProfilePolicy.display(it, profile.id, id, profile.avatarAccountId, profile.customDisplayName) } },
                avatarChanged = { id -> edit { SocialProfilePolicy.display(it, profile.id, profile.nameAccountId, id, profile.customDisplayName) } },
                customChanged = { name -> edit { SocialProfilePolicy.display(it, profile.id, profile.nameAccountId, profile.avatarAccountId, name) } })
            screen.row(getString(R.string.social_make_default)) { edit { it.copy(defaultProfileId = profile.id) } }
            profile.accountIds.map(catalog.accountsById::getValue).forEach { account ->
                screen.addPreference(PreferenceCategory(requireContext()).apply { title = "${account.platform.label} · @${account.handle}" })
                if (profile.linked) screen.row(getString(R.string.social_unlink)) { edit { SocialProfilePolicy.unlink(it, account.id) } }
                if (account.platform !in setOf(SocialPlatform.X, SocialPlatform.BLUESKY)) screen.row(getString(R.string.social_reconnect, account.platform.label)) {
                    startActivity(Intent(context, SocialOnboardingActivity::class.java)
                        .putExtra(com.tjg.twidget.main.OnboardingActivity.EXTRA_ADD_ACCOUNT, true))
                }
                screen.row(getString(R.string.social_remove)) { confirmRemove(account) }
            }
        } else {
            catalog.profiles.sortedBy { it.id != catalog.defaultProfileId }.forEach { p ->
                screen.addPreference(PreferenceCategory(requireContext()).apply { title = getString(if (p.id == catalog.defaultProfileId) R.string.social_default else R.string.social_other_profiles) })
                screen.row(p.displayName(catalog.accountsById), getString(R.string.social_edit_display)) { editing = p.id; render() }
                p.accountIds.map(catalog.accountsById::getValue).forEach { account ->
                    screen.addPreference(Preference(requireContext()).apply {
                        title = account.platform.label; summary = "@${account.handle}"; icon = account.platform.icon(context); isPersistent = false
                        setOnPreferenceClickListener { editing = p.id; render(); true }
                    })
                }
            }
            screen.row(getString(R.string.social_link_action)) { linkDialog() }
            screen.row(getString(R.string.add_account)) { requireActivity().startAddAccountActivity() }
            screen.addPreference(PreferenceCategory(requireContext()).apply { title = getString(R.string.social_optional) })
            screen.row("Buffer") { requireActivity().startSettingsSubActivity(Intent(context, SettingsScheduleActivity::class.java)) }
            catalog.accounts.filter { it.platform == SocialPlatform.X }.forEach { account ->
                screen.row(getString(R.string.import_x_analytics), "@${account.handle}") {
                    startActivity(Intent(context, AnalyticsImportActivity::class.java).putExtra(AnalyticsImportActivity.EXTRA_USERNAME, account.handle))
                }
            }
        }
        screen.addBottomInset(); preferenceScreen = screen
    }
    private fun linkDialog() {
        val choices = catalog.profiles
        val checked = BooleanArray(choices.size)
        val dialog = AlertDialog.Builder(requireContext()).setTitle(R.string.social_link_title)
            .setMultiChoiceItems(choices.map { p -> p.displayName(catalog.accountsById) + " · " + p.accountIds.joinToString { catalog.accountsById.getValue(it).platform.label } }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.social_link_selected, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = choices.filterIndexed { index, _ -> checked[index] }.map { it.id }
                if (selected.size < 2 || runCatching { SocialProfilePolicy.link(catalog, selected.first(), selected.drop(1).toSet()) }.isFailure) {
                    Toast.makeText(context, R.string.social_link_error, Toast.LENGTH_LONG).show()
                } else { dialog.dismiss(); editing = selected.first(); edit { SocialProfilePolicy.link(it, selected.first(), selected.drop(1).toSet()) } }
            }
        }
        dialog.show()
    }
    private fun confirmRemove(account: PlatformAccount) {
        AlertDialog.Builder(requireContext()).setTitle(R.string.social_remove).setMessage(R.string.social_remove_message)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.social_remove) { _, _ -> work { app ->
                if (account.platform == SocialPlatform.X) TwidgetStore.removeAccount(app, account.handle)
                SocialRepository(app).use { repository ->
                    val result = repository.edit { SocialProfilePolicy.remove(it, account.id) }
                    if (account.platform !in setOf(SocialPlatform.X, SocialPlatform.BLUESKY)) SocialConnections.disconnect(app, account.id)
                    TwidgetWidget.updateAll(app)
                    result
                }
            } }.show()
    }
    private fun PreferenceGroup.row(title: String, summary: String? = null, action: () -> Unit) = addPreference(Preference(context).apply {
        this.title = title; this.summary = summary; isPersistent = false
        setOnPreferenceClickListener { if (!busy) action(); true }
    })
}
