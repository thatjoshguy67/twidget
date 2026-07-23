package com.tjg.twidget.followers

import android.content.Context
import java.io.File
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
