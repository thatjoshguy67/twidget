package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.core.HttpTransport
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeVideo(val id: String, val title: String, val thumbnail: String, val publishedAt: Long, val views: Long)
data class YouTubeVideoSnapshot(val fetchedAt: Long, val videos: List<YouTubeVideo>, val complete: Boolean = true) {
    fun best(now: Long): YouTubeVideo? = videos.filter { it.publishedAt in (now - WEEK)..now }
        .maxWithOrNull(compareBy<YouTubeVideo> { it.views }.thenBy { it.publishedAt }.thenBy { it.id })
    companion object { const val WEEK = 7 * 86400000L }
}

/** Bounded uploads lookup uses the existing youtube.readonly grant; video failure cannot break channel refresh. */
internal object YouTubeVideos {
    fun fetch(channel: JSONObject, headers: Map<String, String>, now: Long = System.currentTimeMillis(),
        request: (String, Map<String, String>) -> HttpTransport.Response = { url, h -> HttpTransport.get(url, h) },
    ): YouTubeVideoSnapshot? = runCatching {
        val channelId = channel.getString("id")
        val playlist = channel.getJSONObject("contentDetails").getJSONObject("relatedPlaylists").getString("uploads")
        val videos = mutableListOf<YouTubeVideo>()
        var page = ""
        val seen = mutableSetOf<String>()
        repeat(4) {
            val response = request("https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=50&playlistId=${encode(playlist)}&pageToken=${encode(page)}", headers)
            check(response.code == 200)
            val body = JSONObject(response.body)
            val items = body.getJSONArray("items")
            val ids = mutableListOf<String>()
            var reachedOlderUploads = false
            for (i in 0 until items.length()) {
                val detail = items.getJSONObject(i).getJSONObject("contentDetails")
                val id = detail.getString("videoId")
                val published = runCatching { Instant.parse(detail.getString("videoPublishedAt")).toEpochMilli() }.getOrNull()
                if (published != null && published < now - YouTubeVideoSnapshot.WEEK) reachedOlderUploads = true
                if ((published == null || published >= now - YouTubeVideoSnapshot.WEEK) && seen.add(id)) ids += id
            }
            if (ids.isNotEmpty()) {
                val detail = request("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics,status&id=${encode(ids.joinToString(","))}", headers)
                check(detail.code == 200)
                videos += parse(JSONObject(detail.body), channelId, now)
            }
            page = body.optString("nextPageToken")
            if (page.isBlank() || reachedOlderUploads) return YouTubeVideoSnapshot(now, videos.distinctBy { it.id })
        }
        YouTubeVideoSnapshot(now, videos.distinctBy { it.id }, complete = false)
    }.getOrNull()

    internal fun parse(payload: JSONObject, channelId: String, now: Long): List<YouTubeVideo> {
        val items = payload.getJSONArray("items")
        return (0 until items.length()).mapNotNull { i -> runCatching {
            val item = items.getJSONObject(i)
            val snippet = item.getJSONObject("snippet")
            if (snippet.getString("channelId") != channelId || item.getJSONObject("status").optString("privacyStatus") != "public") return@mapNotNull null
            val date = Instant.parse(snippet.getString("publishedAt")).toEpochMilli()
            if (date !in (now - YouTubeVideoSnapshot.WEEK)..now || snippet.optString("liveBroadcastContent") in setOf("live", "upcoming")) return@mapNotNull null
            val views = item.getJSONObject("statistics").getString("viewCount").toLong().also { require(it >= 0) }
            val id = item.getString("id").also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) }
            val thumbs = snippet.optJSONObject("thumbnails")
            val thumbnail = (thumbs?.optJSONObject("high") ?: thumbs?.optJSONObject("medium") ?: thumbs?.optJSONObject("default"))?.optString("url").orEmpty()
            YouTubeVideo(id, snippet.getString("title"), thumbnail.takeIf { runCatching {
                java.net.URI(it).let { u -> u.scheme == "https" && u.host != null && u.userInfo == null }
            }.getOrDefault(false) }.orEmpty(), date, views)
        }.getOrNull() }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

internal object YouTubeVideoCache {
    private fun prefs(context: Context) = context.getSharedPreferences("youtube_videos", Context.MODE_PRIVATE)
    fun write(context: Context, id: String, snapshot: YouTubeVideoSnapshot) {
        val json = JSONObject().put("at", snapshot.fetchedAt).put("complete", snapshot.complete).put("videos", JSONArray(snapshot.videos.map {
            JSONObject().put("id", it.id).put("title", it.title).put("thumbnail", it.thumbnail).put("published", it.publishedAt).put("views", it.views)
        }))
        prefs(context).edit().putString(id, json.toString()).apply()
    }
    fun read(context: Context, id: String, now: Long = System.currentTimeMillis()): YouTubeVideoSnapshot? = runCatching {
        val json = JSONObject(prefs(context).getString(id, null) ?: return null)
        val at = json.getLong("at")
        if (now - at !in 0..86400000L) return null
        val videos = json.getJSONArray("videos")
        YouTubeVideoSnapshot(at, (0 until videos.length()).map { i -> videos.getJSONObject(i).let {
            YouTubeVideo(it.getString("id"), it.getString("title"), it.getString("thumbnail"), it.getLong("published"), it.getLong("views"))
        } }, json.getBoolean("complete"))
    }.getOrNull()
    fun clear(context: Context, id: String) { prefs(context).edit().remove(id).apply() }
}
