package com.tjg.twidget.social

/** Pure identity and membership operations; the repository commits each result atomically. */
object SocialProfilePolicy {
    fun add(catalog: SocialCatalog, account: PlatformAccount): SocialCatalog {
        catalog.validate()
        val existing = catalog.accounts.firstOrNull {
            it.platform == account.platform && account.remoteId != null && it.remoteId == account.remoteId
        }
        if (existing != null) {
            // A handle/name change must not replace a stable local ID or its widget bindings.
            return catalog.copy(accounts = catalog.accounts.map {
                if (it.id == existing.id) account.copy(id = existing.id) else it
            }).validate()
        }
        require(catalog.accounts.none { it.id == account.id })
        val profile = SocialProfile.standalone(account.id)
        return catalog.copy(
            accounts = catalog.accounts + account,
            profiles = catalog.profiles + profile,
            defaultProfileId = catalog.defaultProfileId ?: profile.id,
        ).validate()
    }

    fun link(catalog: SocialCatalog, targetProfileId: String, sourceProfileIds: Set<String>): SocialCatalog {
        catalog.validate()
        require(sourceProfileIds.isNotEmpty() && targetProfileId !in sourceProfileIds)
        val target = catalog.profiles.first { it.id == targetProfileId }
        val sources = catalog.profiles.filter { it.id in sourceProfileIds }
        require(sources.size == sourceProfileIds.size)
        val linked = target.copy(
            accountIds = target.accountIds + sources.flatMap { it.accountIds },
            membershipVersion = Math.addExact(target.membershipVersion, 1),
        )
        return catalog.copy(
            profiles = catalog.profiles.filterNot { it.id in sourceProfileIds }
                .map { if (it.id == targetProfileId) linked else it },
            defaultProfileId = if (catalog.defaultProfileId in sourceProfileIds) targetProfileId else catalog.defaultProfileId,
        ).validate()
    }

    fun unlink(catalog: SocialCatalog, accountId: String): SocialCatalog {
        catalog.validate()
        val profile = requireNotNull(catalog.profileFor(accountId))
        if (!profile.linked) return catalog
        val remaining = withoutMember(profile, accountId)
        return catalog.copy(profiles = catalog.profiles.map {
            if (it.id == profile.id) remaining else it
        } + SocialProfile.standalone(accountId)).validate()
    }

    fun remove(catalog: SocialCatalog, accountId: String): SocialCatalog {
        catalog.validate()
        val profile = catalog.profileFor(accountId) ?: return catalog
        val profiles = if (profile.linked) catalog.profiles.map {
            if (it.id == profile.id) withoutMember(it, accountId) else it
        } else catalog.profiles.filterNot { it.id == profile.id }
        return catalog.copy(
            accounts = catalog.accounts.filterNot { it.id == accountId },
            profiles = profiles,
            defaultProfileId = catalog.defaultProfileId.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id,
            widgets = catalog.widgets.map {
                if (it.accountId == accountId) it.copy(accountId = null, followsDefault = false) else it
            },
        ).validate()
    }

    fun display(catalog: SocialCatalog, profileId: String, nameAccountId: String, avatarAccountId: String,
        customName: String? = null): SocialCatalog {
        require(catalog.profiles.any { it.id == profileId })
        return catalog.copy(profiles = catalog.profiles.map {
            if (it.id == profileId) it.copy(nameAccountId = nameAccountId, avatarAccountId = avatarAccountId,
                customDisplayName = customName?.trim()?.takeIf(String::isNotBlank)) else it
        }).validate()
    }

    private fun withoutMember(profile: SocialProfile, accountId: String): SocialProfile {
        val members = profile.accountIds - accountId
        return profile.copy(
            accountIds = members,
            nameAccountId = profile.nameAccountId.takeIf { it in members } ?: members.first(),
            avatarAccountId = profile.avatarAccountId.takeIf { it in members } ?: members.first(),
            membershipVersion = Math.addExact(profile.membershipVersion, 1),
        )
    }
}
