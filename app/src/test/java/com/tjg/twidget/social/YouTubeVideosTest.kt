package com.tjg.twidget.social

import com.tjg.twidget.core.HttpTransport
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeVideosTest {
    private val now = Instant.parse("2026-09-19T12:00:00Z").toEpochMilli()
    private fun item(id: String, views: String = "100", channel: String = "channel", privacy: String = "public", date: String = "2026-09-18T12:00:00Z") =
        JSONObject("""{"id":"$id","snippet":{"channelId":"$channel","title":"Video $id","publishedAt":"$date","thumbnails":{"high":{"url":"https://i.ytimg.com/a.jpg"}}},"statistics":{"viewCount":"$views"},"status":{"privacyStatus":"$privacy"}}""")
    @Test fun onlyPublicRecentVideosFromAuthorizedChannelWithKnownViewsQualify() {
        val payload = JSONObject().put("items", org.json.JSONArray(listOf(item("a"), item("b", "500"), item("private", privacy="private"),
            item("other", channel="other"), item("old", date="2026-08-01T00:00:00Z"), item("future", date="2027-01-01T00:00:00Z"), item("missing", views="unknown"))))
        val parsed = YouTubeVideos.parse(payload, "channel", now)
        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals("b", YouTubeVideoSnapshot(now, parsed).best(now)?.id)
        assertNull(YouTubeVideoSnapshot(now, parsed).best(now + YouTubeVideoSnapshot.WEEK))
    }
    @Test fun paginationBatchesStatisticsAndStopsAtOlderUploads() {
        val urls = mutableListOf<String>()
        val channel = JSONObject("""{"id":"channel","contentDetails":{"relatedPlaylists":{"uploads":"uploads"}}}""")
        val result = YouTubeVideos.fetch(channel, emptyMap(), now) { url, _ ->
            urls += url
            HttpTransport.Response(200, when {
                url.contains("/videos?") -> JSONObject().put("items", org.json.JSONArray(listOf(item(if (url.contains("id=b")) "b" else "a")))).toString()
                url.contains("pageToken=next") -> """{"items":[{"contentDetails":{"videoId":"b","videoPublishedAt":"2026-09-18T00:00:00Z"}},{"contentDetails":{"videoId":"old","videoPublishedAt":"2025-01-01T00:00:00Z"}}],"nextPageToken":"older"}"""
                else -> """{"items":[{"contentDetails":{"videoId":"a","videoPublishedAt":"2026-09-19T00:00:00Z"}}],"nextPageToken":"next"}"""
            })
        }
        assertEquals(4, urls.size)
        assertEquals(2, result!!.videos.size)
        assertTrue(result.complete)
    }
    @Test fun errorsAreUnavailableNotAnEmptySuccessfulWeek() {
        assertNull(YouTubeVideos.fetch(JSONObject("""{"id":"channel","contentDetails":{"relatedPlaylists":{"uploads":"uploads"}}}"""), emptyMap(), now) { _, _ -> HttpTransport.Response(403, "") })
    }
}
