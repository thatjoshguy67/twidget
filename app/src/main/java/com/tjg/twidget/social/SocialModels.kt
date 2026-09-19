package com.tjg.twidget.social

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

enum class SocialPlatform(val storageId: String, val audienceMetric: SocialMetric) {
    X("x", SocialMetric.FOLLOWERS),
    INSTAGRAM("instagram", SocialMetric.FOLLOWERS),
    YOUTUBE("youtube", SocialMetric.SUBSCRIBERS),
    BLUESKY("bluesky", SocialMetric.FOLLOWERS),
    GITHUB("github", SocialMetric.FOLLOWERS);

    fun normalizeHandle(input: String): String = input.trim().removePrefix("@")
        .let { if (this == YOUTUBE) it else it.lowercase(Locale.ROOT) }

    fun validPublicHandle(input: String): Boolean {
        val handle = normalizeHandle(input)
        return when (this) {
            X -> handle.matches(Regex("[a-z0-9_]{1,15}"))
            INSTAGRAM -> handle.matches(Regex("[a-z0-9._]{1,30}"))
            GITHUB -> handle.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?")) && !handle.contains("--")
            BLUESKY -> handle.length <= 253 && handle.contains('.') &&
                handle.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } &&
                handle.substringAfterLast('.').firstOrNull()?.isLetter() == true
            // YouTube connections are resolved by OAuth/channel ID, not an ASCII-only handle validator.
            YOUTUBE -> false
        }
    }

    fun profileUrl(account: PlatformAccount): String {
        require(account.platform == this)
        val path = when (this) {
            X -> "https://x.com/${encode(account.handle)}"
            INSTAGRAM -> "https://www.instagram.com/${encode(account.handle)}/"
            GITHUB -> "https://github.com/${encode(account.handle)}"
            BLUESKY -> "https://bsky.app/profile/${encode(account.remoteId ?: account.handle)}"
            YOUTUBE -> "https://www.youtube.com/channel/${encode(requireNotNull(account.remoteId))}"
        }
        return URI(path).toASCIIString()
    }

    companion object {
        fun fromStorageId(id: String): SocialPlatform = entries.first { it.storageId == id }
        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
    }
}

enum class SocialMetric(val storageId: String) {
    FOLLOWERS("followers"), FOLLOWING("following"), POSTS("posts"),
    LIKES_GIVEN("likes_given"), SUBSCRIBERS("subscribers"), VIDEOS("videos"),
    VIEWS("views"), REPOSITORIES("repositories"), STARS("stars"), FORKS("forks");

    companion object {
        fun fromStorageId(id: String): SocialMetric = entries.first { it.storageId == id }
    }
}

enum class MetricPrecision { EXACT, ROUNDED }

/** Null is unavailable, never an observed zero. Provenance survives migration. */
data class MetricObservation(
    val accountId: String,
    val metric: SocialMetric,
    val value: Long?,
    val observedAt: Long,
    val source: String,
    val precision: MetricPrecision = MetricPrecision.EXACT,
    val estimated: Boolean = false,
    val imported: Boolean = false,
    val sharedImport: Boolean = false,
) {
    init {
        require(accountId.isNotBlank() && source.isNotBlank())
        require(value == null || value >= 0)
        require(observedAt > 0)
    }
}

data class PlatformAccount(
    val id: String,
    val platform: SocialPlatform,
    val remoteId: String?,
    val handle: String,
    val displayName: String,
    val avatarUrl: String = "",
) {
    init {
        require(id.isNotBlank() && handle.isNotBlank())
        require(remoteId == null || remoteId.isNotBlank())
        require(remoteId != null || platform == SocialPlatform.X) { "New provider accounts need a resolved remote ID" }
    }

    companion object {
        fun create(platform: SocialPlatform, remoteId: String, handle: String, name: String, avatar: String = "") =
            PlatformAccount(UUID.randomUUID().toString(), platform, remoteId, platform.normalizeHandle(handle), name, avatar)
    }
}

data class SocialProfile(
    val id: String,
    val accountIds: List<String>,
    val nameAccountId: String,
    val avatarAccountId: String,
    val customDisplayName: String? = null,
    val membershipVersion: Long = 1,
) {
    init {
        require(id.isNotBlank() && accountIds.isNotEmpty() && accountIds.distinct().size == accountIds.size)
        require(nameAccountId in accountIds && avatarAccountId in accountIds)
        require(customDisplayName == null || customDisplayName.isNotBlank())
        require(membershipVersion > 0)
    }

    val linked: Boolean get() = accountIds.size > 1

    fun displayName(accounts: Map<String, PlatformAccount>): String = customDisplayName
        ?: accounts.getValue(nameAccountId).let { it.displayName.ifBlank { it.handle } }

    fun avatarUrl(accounts: Map<String, PlatformAccount>): String = accounts.getValue(avatarAccountId).avatarUrl

    companion object {
        fun standalone(accountId: String, id: String = UUID.randomUUID().toString()) =
            SocialProfile(id, listOf(accountId), accountId, accountId)
    }
}

/** An explicit null binding means the account was removed and the widget needs configuration. */
data class SocialWidgetBinding(val widgetId: Int, val accountId: String?, val followsDefault: Boolean = false)

data class SocialCatalog(
    val accounts: List<PlatformAccount> = emptyList(),
    val profiles: List<SocialProfile> = emptyList(),
    val defaultProfileId: String? = null,
    val widgets: List<SocialWidgetBinding> = emptyList(),
) {
    val accountsById: Map<String, PlatformAccount> get() = accounts.associateBy { it.id }

    fun validate(): SocialCatalog = apply {
        require(accounts.map { it.id }.distinct().size == accounts.size)
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(widgets.map { it.widgetId }.distinct().size == widgets.size)
        val resolvedIdentities = accounts.filter { it.remoteId != null }.map { it.platform to it.remoteId }
        require(resolvedIdentities.distinct().size == resolvedIdentities.size) { "Duplicate remote account" }
        val memberships = profiles.flatMap { it.accountIds }
        require(memberships.size == accounts.size && memberships.toSet() == accounts.map { it.id }.toSet()) {
            "Every account must belong to exactly one profile"
        }
        val byId = accountsById
        profiles.forEach { profile ->
            require(profile.accountIds.map { byId.getValue(it).platform }.distinct().size == profile.accountIds.size) {
                "A linked profile can contain only one account per platform"
            }
        }
        require(defaultProfileId == null && profiles.isEmpty() || profiles.any { it.id == defaultProfileId })
        require(widgets.all { it.accountId == null || it.accountId in byId })
    }

    fun profileFor(accountId: String): SocialProfile? = profiles.firstOrNull { accountId in it.accountIds }
}
