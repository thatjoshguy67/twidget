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
import com.tjg.twidget.settings.SettingsScheduleActivity
import com.tjg.twidget.ui.ProfileImageLoader
import dev.oneuiproject.oneui.preference.LayoutPreference
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.R as OneUiIconR
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
        val main = catalog.profiles.firstOrNull { it.id == catalog.defaultProfileId }
        if (main != null) {
            screen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(R.string.social_default) })
            screen.profileHeader(main, expanded = true)
            screen.accountRows(main)
            val members = main.accountIds.map(catalog.accountsById::getValue)
            if (members.any { it.platform in setOf(SocialPlatform.X, SocialPlatform.BLUESKY) }) {
                screen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(R.string.social_optional) })
                screen.row("Buffer") { requireActivity().startSettingsSubActivity(Intent(context, SettingsScheduleActivity::class.java)) }
                members.filter { it.platform == SocialPlatform.X }.forEach { account ->
                    screen.row(getString(R.string.import_x_analytics), "@${account.handle}") {
                        startActivity(Intent(context, AnalyticsImportActivity::class.java).putExtra(AnalyticsImportActivity.EXTRA_USERNAME, account.handle))
                    }
                }
            }
        }
        val others = catalog.profiles.filter { it.id != catalog.defaultProfileId }
        if (others.isNotEmpty()) {
            screen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(R.string.social_other_profiles) })
            others.forEach { profile ->
                screen.profileHeader(profile, expanded = editing == profile.id)
                if (editing == profile.id) {
                    screen.accountRows(profile)
                    screen.row(getString(R.string.social_make_default)) {
                        editing = ""; edit { it.copy(defaultProfileId = profile.id) }
                    }
                }
            }
        }
        screen.addPreference(PreferenceCategory(requireContext()))
        screen.row(getString(R.string.social_add_profiles)) { requireActivity().startAddAccountActivity() }
        if (catalog.profiles.size > 1) screen.row(getString(R.string.social_link_action)) { linkDialog() }
        screen.addBottomInset(); preferenceScreen = screen
    }

    private fun PreferenceGroup.profileHeader(profile: SocialProfile, expanded: Boolean) {
        val row = CardItemView(requireContext()).apply {
            title = profile.displayName(catalog.accountsById)
            summary = if (profile.linked) getString(R.string.social_linked_profile) else {
                val account = catalog.accountsById.getValue(profile.accountIds.first())
                "${account.platform.label} · @${account.handle}"
            }
            iconSize = (40 * resources.displayMetrics.density).toInt()
            icon = context.getDrawable(R.drawable.avatar_twidget)
            ProfileImageLoader.loadInto(context, getIconImageView(), profile.avatarUrl(catalog.accountsById))
            getEndImageView().apply {
                setImageResource(OneUiIconR.drawable.ic_oui_edit_outline)
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.oneui_text_primary))
                contentDescription = getString(R.string.social_edit_display)
                setOnClickListener { editDisplay(profile) }
            }
            setOnClickListener {
                if (profile.id == catalog.defaultProfileId) editDisplay(profile)
                else { editing = if (expanded) "" else profile.id; render() }
            }
        }
        addPreference(LayoutPreference(requireContext(), row).apply {
            key = "profile:${profile.id}"
            setAllowDividerAbove(true); setAllowDividerBelow(true)
        })
    }

    private fun editDisplay(profile: SocialProfile) {
        startActivity(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(SocialOnboardingActivity.EXTRA_EDIT_PROFILE, profile.id))
    }

    private fun PreferenceGroup.accountRows(profile: SocialProfile) {
        profile.accountIds.map(catalog.accountsById::getValue).forEach { account ->
            addPreference(Preference(requireContext()).apply {
                key = "account:${account.id}"
                title = account.platform.label; summary = "@${account.handle}"
                icon = account.platform.icon(context); isPersistent = false
                setOnPreferenceClickListener { if (!busy) accountMenu(profile, account); true }
            })
        }
        if (catalog.profiles.any { candidate -> candidate.id != profile.id &&
                runCatching { SocialProfilePolicy.link(catalog, profile.id, setOf(candidate.id)) }.isSuccess }) {
            row(getString(R.string.social_add_linked)) { linkDialog(profile.id) }
        }
    }

    private fun accountMenu(profile: SocialProfile, account: PlatformAccount) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (profile.linked) actions += getString(R.string.social_unlink) to {
            edit { SocialProfilePolicy.unlink(it, account.id) }
        }
        if (account.platform !in setOf(SocialPlatform.X, SocialPlatform.BLUESKY)) {
            actions += getString(R.string.social_reconnect, account.platform.label) to {
                startActivity(Intent(context, SocialOnboardingActivity::class.java)
                    .putExtra(com.tjg.twidget.main.OnboardingActivity.EXTRA_ADD_ACCOUNT, true)
                    .putExtra(SocialOnboardingActivity.EXTRA_CONNECT_PLATFORM, account.platform.storageId))
            }
        }
        actions += getString(R.string.social_remove) to { confirmRemove(account) }
        AlertDialog.Builder(requireContext()).setTitle("${account.platform.label} · @${account.handle}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .show()
    }
    private fun linkDialog(targetId: String? = null) {
        val choices = catalog.profiles.filter { profile -> targetId == null ||
            (profile.id != targetId && runCatching { SocialProfilePolicy.link(catalog, targetId, setOf(profile.id)) }.isSuccess) }
        val checked = BooleanArray(choices.size)
        val dialog = AlertDialog.Builder(requireContext()).setTitle(R.string.social_link_title)
            .setMultiChoiceItems(choices.map { p -> p.displayName(catalog.accountsById) + " · " + p.accountIds.joinToString { catalog.accountsById.getValue(it).platform.label } }.toTypedArray(), checked) { _, i, value -> checked[i] = value }
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.social_link_selected, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = listOfNotNull(targetId) + choices.filterIndexed { index, _ -> checked[index] }.map { it.id }
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
