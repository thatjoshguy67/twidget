package com.tjg.twidget.social

import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import java.util.UUID

data class LegacySocialAccount(val handle: String, val stats: ProfileStats?, val history: List<HistorySample>)
data class LegacySocialSnapshot(
    val accounts: List<LegacySocialAccount>,
    val defaultHandle: String,
    val widgetHandles: Map<Int, String>,
    val onboarded: Boolean,
)

/** Pure conversion: never imports demo values, credentials or interpolated UI history. */
object LegacySocialMigration {
    const val SOURCE = "legacy_x"

    fun accountId(handle: String): String = "legacy-x-" + UUID.nameUUIDFromBytes(
        SocialPlatform.X.normalizeHandle(handle).toByteArray(Charsets.UTF_8),
    )

    fun profileId(handle: String): String = "profile-${accountId(handle)}"

    fun catalog(snapshot: LegacySocialSnapshot): SocialCatalog {
        val unique = snapshot.accounts.filter { it.handle.isNotBlank() }
            .distinctBy { SocialPlatform.X.normalizeHandle(it.handle) }
        val accounts = unique.map { legacy ->
            val handle = SocialPlatform.X.normalizeHandle(legacy.handle)
            PlatformAccount(accountId(handle), SocialPlatform.X, null, handle,
                legacy.stats?.fullName.orEmpty(), legacy.stats?.profileImage.orEmpty())
        }
        val profiles = accounts.map { SocialProfile.standalone(it.id, profileId(it.handle)) }
        val defaultId = accounts.firstOrNull {
            it.handle == SocialPlatform.X.normalizeHandle(snapshot.defaultHandle)
        }?.let { profileId(it.handle) } ?: profiles.firstOrNull()?.id
        val widgets = snapshot.widgetHandles.map { (id, handle) ->
            val resolved = handle.ifBlank { snapshot.defaultHandle }
            SocialWidgetBinding(id, accountId(resolved).takeIf { candidate -> accounts.any { it.id == candidate } },
                followsDefault = handle.isBlank())
        }
        return SocialCatalog(accounts, profiles, defaultId, widgets).validate()
    }

    fun observations(account: LegacySocialAccount): List<MetricObservation> {
        val id = accountId(account.handle)
        fun values(at: Long, followers: Long, following: Long, posts: Long, likes: Long,
            followersKnown: Boolean, followingKnown: Boolean, postsKnown: Boolean, likesKnown: Boolean,
            estimated: Boolean = false, imported: Boolean = false, sharedImport: Boolean = false): List<MetricObservation> {
            if (at <= 0) return emptyList()
            return listOf(
                SocialMetric.FOLLOWERS to followers.takeIf { followersKnown },
                SocialMetric.FOLLOWING to following.takeIf { followingKnown },
                SocialMetric.POSTS to posts.takeIf { postsKnown },
                SocialMetric.LIKES_GIVEN to likes.takeIf { likesKnown },
            ).map { (metric, value) -> MetricObservation(id, metric, value?.takeIf { it >= 0 }, at, SOURCE,
                estimated = estimated, imported = imported, sharedImport = sharedImport) }
        }
        val history = account.history.flatMap { sample -> values(sample.timestamp, sample.followers,
            sample.following, sample.posts, sample.likes, sample.followersKnown, sample.followingKnown,
            sample.postsKnown, sample.likesKnown, sample.estimated, sample.imported, sample.sharedImport) }
        val current = account.stats?.let { stats -> values(stats.syncedAt, stats.followersCount,
            stats.followingsCount, stats.statusesCount, stats.likeCount, stats.followersKnown,
            stats.followingKnown, stats.postsKnown, stats.likesKnown) }.orEmpty()
        // A current observation wins on an identical metric/timestamp; estimates never replace real data.
        return (history + current).groupBy { it.metric to it.observedAt }.values.map { sameTime ->
            sameTime.lastOrNull { !it.estimated } ?: sameTime.last()
        }
    }
}
