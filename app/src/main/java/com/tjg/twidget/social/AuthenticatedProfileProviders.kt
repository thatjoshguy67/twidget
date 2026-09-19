package com.tjg.twidget.social

import com.tjg.twidget.core.HttpTransport
import org.json.JSONObject
import java.util.UUID

/** Provider counts retain unavailable values; totals are never inferred from an incomplete page. */
object AuthenticatedProfileProviders {
    fun fetch(platform: SocialPlatform, token: String, existing: PlatformAccount? = null): SocialProfileResult = runCatching {
        require(token.isNotBlank())
        val headers = mapOf("Authorization" to "Bearer $token")
        val response = when (platform) {
            SocialPlatform.GITHUB -> HttpTransport.get(if (existing == null) "https://api.github.com/user" else
                "https://api.github.com/user/${existing.remoteId}", headers + mapOf("X-GitHub-Api-Version" to "2022-11-28"), userAgent = "Twidget")
            SocialPlatform.YOUTUBE -> HttpTransport.get("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&mine=true", headers)
            SocialPlatform.INSTAGRAM -> HttpTransport.get("https://graph.instagram.com/me?fields=user_id,username,name,profile_picture_url,followers_count,follows_count,media_count", headers)
            else -> error("Unsupported authenticated provider")
        }
        if (response.code == 401 || response.code == 403) return SocialProfileResult.Failure(SocialProviderError.REAUTHORIZATION_REQUIRED)
        if (response.code == 429) return SocialProfileResult.Failure(SocialProviderError.RATE_LIMITED)
        if (response.code !in 200..299) return SocialProfileResult.Failure(SocialProviderError.UNAVAILABLE)
        val payload = JSONObject(response.body)
        if (platform == SocialPlatform.GITHUB) payload.put("twidget_repository_totals", githubRepositoryTotals(payload, headers))
        parse(platform, payload, existing)
    }.getOrElse { SocialProfileResult.Failure(SocialProviderError.UNAVAILABLE) }

    internal fun parse(platform: SocialPlatform, payload: JSONObject, existing: PlatformAccount? = null,
        now: Long = System.currentTimeMillis()): SocialProfileResult = runCatching {
        val data = if (platform == SocialPlatform.YOUTUBE) {
            val items = payload.getJSONArray("items")
            if (items.length() != 1) return SocialProfileResult.Failure(SocialProviderError.NOT_FOUND)
            items.getJSONObject(0)
        } else payload
        val remoteId = when (platform) {
            SocialPlatform.INSTAGRAM -> data.get("user_id").toString()
            else -> data.get("id").toString()
        }
        require(remoteId.isNotBlank() && remoteId != "null")
        require(existing == null || existing.platform == platform && existing.remoteId == remoteId)
        val snippet = data.optJSONObject("snippet") ?: data
        val handle = when (platform) {
            SocialPlatform.GITHUB -> data.getString("login")
            SocialPlatform.INSTAGRAM -> data.getString("username")
            else -> snippet.optString("customUrl").ifBlank { remoteId }
        }
        val name = snippet.optString(if (platform == SocialPlatform.YOUTUBE) "title" else "name").takeUnless { it == "null" }.orEmpty().ifBlank { handle }
        val avatar = when (platform) {
            SocialPlatform.GITHUB -> data.optString("avatar_url")
            SocialPlatform.INSTAGRAM -> data.optString("profile_picture_url")
            else -> snippet.optJSONObject("thumbnails")?.optJSONObject("default")?.optString("url").orEmpty()
        }.takeIf { runCatching { java.net.URI(it).let { uri -> uri.scheme == "https" && uri.host != null && uri.userInfo == null } }.getOrDefault(false) }.orEmpty()
        val account = PlatformAccount(existing?.id ?: UUID.randomUUID().toString(), platform, remoteId, platform.normalizeHandle(handle), name, avatar)
        val fields = when (platform) {
            SocialPlatform.GITHUB -> listOf(SocialMetric.FOLLOWERS to "followers", SocialMetric.FOLLOWING to "following", SocialMetric.REPOSITORIES to "public_repos", SocialMetric.STARS to "stars", SocialMetric.FORKS to "forks")
            SocialPlatform.INSTAGRAM -> listOf(SocialMetric.FOLLOWERS to "followers_count", SocialMetric.FOLLOWING to "follows_count", SocialMetric.POSTS to "media_count")
            SocialPlatform.YOUTUBE -> listOf(SocialMetric.SUBSCRIBERS to "subscriberCount", SocialMetric.VIDEOS to "videoCount", SocialMetric.VIEWS to "viewCount")
            else -> error("Unsupported provider")
        }
        val counts = data.optJSONObject("statistics") ?: data
        SocialProfileResult.Success(account, fields.map { (metric, field) ->
            val metricCounts = if (platform == SocialPlatform.GITHUB && metric in setOf(SocialMetric.STARS, SocialMetric.FORKS)) data.optJSONObject("twidget_repository_totals") ?: JSONObject() else counts
            val value = if (metric == SocialMetric.SUBSCRIBERS && counts.optBoolean("hiddenSubscriberCount")) null else count(metricCounts.opt(field))
            MetricObservation(account.id, metric, value, now, "${platform.storageId}_official",
                if (metric == SocialMetric.SUBSCRIBERS) MetricPrecision.ROUNDED else MetricPrecision.EXACT)
        })
    }.getOrElse { SocialProfileResult.Failure(SocialProviderError.INVALID_RESPONSE) }

    private fun githubRepositoryTotals(profile: JSONObject, headers: Map<String, String>): JSONObject = runCatching {
        val expected = requireNotNull(count(profile.opt("public_repos")))
        require(expected <= 1000) // Large accounts remain unavailable rather than displaying a partial total.
        val handle = profile.getString("login")
        require(SocialPlatform.GITHUB.validPublicHandle(handle))
        val ids = mutableSetOf<Long>(); var stars = 0L; var forks = 0L
        for (page in 1..10) {
            val response = HttpTransport.get("https://api.github.com/users/$handle/repos?type=owner&per_page=100&page=$page",
                headers + mapOf("X-GitHub-Api-Version" to "2022-11-28"), userAgent = "Twidget")
            check(response.code == 200)
            val repos = org.json.JSONArray(response.body)
            for (index in 0 until repos.length()) {
                val repo = repos.getJSONObject(index)
                require(!repo.optBoolean("private"))
                require(ids.add(repo.getLong("id")))
                stars = Math.addExact(stars, requireNotNull(count(repo.opt("stargazers_count"))))
                forks = Math.addExact(forks, requireNotNull(count(repo.opt("forks_count"))))
            }
            if (repos.length() < 100 || ids.size.toLong() == expected) break
        }
        require(ids.size.toLong() == expected)
        JSONObject().put("stars", stars).put("forks", forks)
    }.getOrElse { JSONObject() }

    private fun count(raw: Any?): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is String -> raw.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
        else -> null
    }?.takeIf { it >= 0 }
}
