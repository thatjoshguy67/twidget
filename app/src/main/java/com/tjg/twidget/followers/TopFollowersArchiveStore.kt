package com.tjg.twidget.followers

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object TopFollowersArchiveStore {
    private const val DIR = "top_followers"

    @Synchronized
    fun seedFromTop(context: Context, username: String, top: List<TopFollower>) {
        if (top.isEmpty() || count(context, username) > 0) return
        append(context, username, top.mapIndexed { index, follower ->
            follower.copy(scanIndex = index)
        }, pageNumber = 1)
    }

    @Synchronized
    fun clear(context: Context, username: String) {
        archiveFile(context, username).delete()
    }

    @Synchronized
    fun append(context: Context, username: String, users: List<TopFollower>, pageNumber: Int) {
        if (users.isEmpty()) return
        val file = archiveFile(context, username)
        file.parentFile?.mkdirs()
        val existing = readAll(context, username)
        val seen = existing.map { it.id.ifBlank { it.username.lowercase(Locale.US) } }.toMutableSet()
        var nextIndex = existing.maxOfOrNull { it.scanIndex }?.plus(1) ?: 0
        val appended = buildList {
            users.forEach { user ->
                val dedupeKey = user.id.ifBlank { user.username.lowercase(Locale.US) }
                if (!seen.add(dedupeKey)) return@forEach
                add(
                    user.copy(
                        scanIndex = nextIndex,
                    ).also { nextIndex++ },
                )
            }
        }
        if (appended.isEmpty()) return
        file.appendText(
            buildString {
                appended.forEach { follower ->
                    append(followerToJson(follower, pageNumber).toString())
                    append('\n')
                }
            },
        )
    }

    @Synchronized
    fun beginReplacement(context: Context, username: String) {
        replacementFile(context, username).apply {
            parentFile?.mkdirs()
            delete()
        }
    }

    @Synchronized
    fun appendReplacement(context: Context, username: String, users: List<TopFollower>, pageNumber: Int) {
        if (users.isEmpty()) return
        replacementFile(context, username).appendText(buildString {
            users.forEach { follower ->
                append(followerToJson(follower, pageNumber).toString())
                append('\n')
            }
        })
    }

    @Synchronized
    fun commitReplacement(context: Context, username: String) {
        val pending = replacementFile(context, username)
        if (!pending.isFile) return
        val target = archiveFile(context, username).toPath()
        try {
            Files.move(
                pending.toPath(),
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun abortReplacement(context: Context, username: String) {
        replacementFile(context, username).delete()
    }

    @Synchronized
    fun readAll(context: Context, username: String): List<TopFollower> {
        val file = archiveFile(context, username)
        if (!file.isFile) return emptyList()
        return file.readLines()
            .mapNotNull { line ->
                runCatching { followerFromJson(JSONObject(line)) }.getOrNull()
            }
    }

    @Synchronized
    fun count(context: Context, username: String): Int = readAll(context, username).size

    private fun archiveFile(context: Context, username: String): File {
        val clean = username.trim().trimStart('@').lowercase(Locale.US)
        return File(context.filesDir, "$DIR/$clean.jsonl")
    }

    private fun replacementFile(context: Context, username: String): File =
        File(archiveFile(context, username).parentFile, "${archiveFile(context, username).name}.pending")

    private fun followerToJson(follower: TopFollower, pageNumber: Int): JSONObject =
        JSONObject().apply {
            put("id", follower.id)
            put("username", follower.username)
            put("name", follower.name)
            put("followers", follower.followers)
            put("verified", follower.verified)
            put("avatar", follower.avatarUrl)
            put("scanIndex", follower.scanIndex)
            put("page", pageNumber)
            follower.mutual?.let { put("mutual", it) }
        }

    private fun followerFromJson(json: JSONObject): TopFollower = TopFollower(
        id = json.optString("id"),
        username = json.optString("username"),
        name = json.optString("name"),
        followers = json.optLong("followers"),
        verified = json.optBoolean("verified"),
        avatarUrl = json.optString("avatar"),
        scanIndex = json.optInt("scanIndex"),
        mutual = json.optNullableBoolean("mutual"),
    )
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

enum class TopFollowersFilter {
    ALL,
    ALPHABETICAL,
    RECENT,
    VERIFIED,
    MUTUAL,
}

object TopFollowersFilterPolicy {
    fun apply(followers: List<TopFollower>, filter: TopFollowersFilter): List<TopFollower> {
        val filtered = when (filter) {
            TopFollowersFilter.VERIFIED -> followers.filter { it.verified }
            TopFollowersFilter.MUTUAL -> followers.filter { it.mutual == true }
            else -> followers
        }
        return when (filter) {
            TopFollowersFilter.ALPHABETICAL -> filtered.sortedWith(
                compareBy<TopFollower> { it.name.lowercase(Locale.US) }
                    .thenBy { it.username.lowercase(Locale.US) },
            )
            TopFollowersFilter.RECENT -> filtered.sortedByDescending { it.scanIndex }
            else -> filtered.sortedBy { it.scanIndex }
        }
    }

    fun mutualFilterAvailable(followers: List<TopFollower>): Boolean =
        followers.any { it.mutual != null }
}

/**
 * Produces the single canonical order used by the followers browser.
 *
 * Search results retain their rank from the complete list so typing a query
 * never makes (for example) the account ranked 42nd appear to be number one.
 */
data class RankedTopFollower(
    val rank: Int,
    val follower: TopFollower,
)

object TopFollowersBrowserPolicy {
    fun apply(followers: List<TopFollower>, query: String = ""): List<RankedTopFollower> {
        val normalizedQuery = query.trim().lowercase(Locale.US).trimStart('@')
        return followers
            .distinctBy(::identityKey)
            .sortedWith(
                compareByDescending<TopFollower> { it.followers }
                    .thenBy { it.name.lowercase(Locale.US) }
                    .thenBy { it.username.lowercase(Locale.US) }
                    .thenBy { it.id.lowercase(Locale.US) }
                    .thenBy { it.scanIndex },
            )
            .mapIndexed { index, follower -> RankedTopFollower(index + 1, follower) }
            .filter { ranked ->
                normalizedQuery.isEmpty() ||
                    ranked.follower.name.lowercase(Locale.US).contains(normalizedQuery) ||
                    ranked.follower.username.lowercase(Locale.US).contains(normalizedQuery)
            }
    }

    private fun identityKey(follower: TopFollower): String =
        (
            follower.id.trim().takeIf(String::isNotEmpty)
                ?: follower.username.trim().trimStart('@')
            ).lowercase(Locale.US)
}

/**
 * Keeps RecyclerView prefetch from becoming image prefetch. Text can be bound
 * off-screen, while avatar requests start only after the row is attached.
 */
object TopFollowerAvatarLoadPolicy {
    fun shouldLoad(
        rowAttached: Boolean,
        boundIdentity: String?,
        requestedIdentity: String?,
    ): Boolean =
        rowAttached &&
            !boundIdentity.isNullOrBlank() &&
            boundIdentity != requestedIdentity
}
