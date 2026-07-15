package com.tjg.twidget.providers

import android.content.Context
import com.tjg.twidget.bridge.BridgeLog
import com.tjg.twidget.bridge.HistoryPool
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.MetricProvenance
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RettiwtClient {
    fun refresh(context: Context, accountUsername: String = TwidgetStore.settings(context).username): ProfileStats {
        val settings = TwidgetStore.settings(context)
        val username = accountUsername.trim().trimStart('@')
        if (username.isBlank()) {
            throw IllegalStateException("Username is required before syncing")
        }
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        var lastError: Exception? = null
        val useXApi = settings.dataSource == TwidgetStore.DATA_SOURCE_X_API &&
            XApiClient.hasCredentials(settings)
        val useTwitterApis = settings.dataSource == TwidgetStore.DATA_SOURCE_TWITTERAPIS &&
            TwitterApisClient.hasCredentials(context)

        if (useTwitterApis) {
            try {
                return finalize(withAvailableLikes(context, username, TwitterApisClient.fetchProfile(context, username)), username)
            } catch (error: Exception) {
                lastError = error
            }
        }

        // X API source calls api.x.com directly from the device; the bridge is
        // only a fallback. Other sources go through the bridge first and fall
        // back to the X API when the user has configured credentials.
        if (useXApi) {
            try {
                return finalize(withAvailableLikes(context, username, XApiClient.fetchProfile(context, username)), username)
            } catch (error: Exception) {
                lastError = error
            }
        }

        // FxTwitter source hits the public api.fxtwitter.com directly from the
        // device. Sharing history is the bridge opt-in: with it enabled the
        // bridge serves the shared history pool and stays available as a
        // fallback; with it off the app talks to FxTwitter only.
        if (settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER) {
            try {
                val stats = finalize(FxTwitterClient.fetchProfile(username), username)
                return HistoryPool.enrich(context, stats, settings, endpoint)
            } catch (error: Exception) {
                if (!settings.shareHistory) throw error
                lastError = error
            }
        }

        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val candidates = listOf(
            "${endpoint.url}/user/$encoded",
            "${endpoint.url}/users/$encoded",
            "${endpoint.url}/details/$encoded",
            "${endpoint.url}?username=$encoded",
        )
        for (candidate in candidates) {
            try {
                return enrichStatusFields(
                    context,
                    finalize(parseUser(read(context, candidate, endpoint.token)), username),
                    settings,
                    endpoint,
                )
            } catch (error: Exception) {
                lastError = error
                // Compatibility aliases are only useful when an older
                // self-hosted bridge says the route does not exist. Retrying
                // the same failing upstream four times can otherwise stall a
                // refresh for roughly 40 seconds.
                if (error !is HttpTransport.HttpException || error.code !in setOf(404, 405)) break
            }
        }

        if (!useXApi && !useTwitterApis && XApiClient.hasCredentials(settings)) {
            try {
                return finalize(withAvailableLikes(context, username, XApiClient.fetchProfile(context, username)), username)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Unable to fetch Rettiwt profile")
    }

    // Rettiwt doesn't report protected/verified status reliably. Fill unknown
    // status fields from an official bridge endpoint when available, or from
    // local X API credentials as a final source.
    private fun enrichStatusFields(
        context: Context,
        parsed: ProfileStats,
        settings: TwidgetSettings,
        endpoint: BridgeEndpoint,
    ): ProfileStats {
        if (parsed.isPrivate != null && parsed.isVerified != null) return parsed
        bridgeOfficialStatus(context, parsed.userName, endpoint)?.let { official ->
            return parsed.copy(
                isVerified = parsed.isVerified ?: official.isVerified,
                isPrivate = parsed.isPrivate ?: official.isPrivate,
            )
        }
        runCatching { FxTwitterClient.fetchProfile(parsed.userName) }
            .getOrNull()
            ?.takeIf { it.isVerified != null || it.isPrivate != null }
            ?.let { fx ->
                return parsed.copy(
                    isVerified = parsed.isVerified ?: fx.isVerified,
                    isPrivate = parsed.isPrivate ?: fx.isPrivate,
                )
            }
        if (!XApiClient.hasCredentials(settings)) return parsed
        return runCatching {
            val official = XApiClient.fetchProfile(context, parsed.userName)
            parsed.copy(
                isVerified = parsed.isVerified ?: official.isVerified,
                isPrivate = parsed.isPrivate ?: official.isPrivate,
            )
        }.getOrDefault(parsed)
    }

    private fun bridgeOfficialStatus(context: Context, username: String, endpoint: BridgeEndpoint): ProfileStats? {
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        return runCatching {
            parseUser(read(context, "${endpoint.url}/official/user/$encoded", endpoint.token))
        }.getOrNull()?.takeIf { it.isPrivate != null || it.isVerified != null }
    }

    private fun finalize(parsed: ProfileStats, requestedUsername: String): ProfileStats {
        val username = parsed.userName.ifBlank { requestedUsername }
        return parsed.copy(
            fullName = parsed.fullName.ifBlank { requestedUsername },
            userName = username,
            profileImage = highResolutionProfileImageUrl(parsed.profileImage).ifBlank { fallbackAvatarUrl(username) },
            syncedAt = System.currentTimeMillis(),
        )
    }

    // X user public_metrics has no profile-wide likes count. Use FxTwitter for
    // that one field when possible, otherwise retain the last observed value
    // instead of recording a fabricated drop to zero.
    private fun withAvailableLikes(context: Context, username: String, official: ProfileStats): ProfileStats {
        val live = runCatching { FxTwitterClient.fetchProfile(username) }.getOrNull()
        val previous = TwidgetStore.currentStats(context, username)
        val likes = MetricProvenance.preferLiveThenPrevious(
            liveValue = live?.likeCount,
            liveKnown = live?.likesKnown == true,
            previousValue = previous.likeCount,
            previousKnown = previous.likesKnown,
        )
        return official.copy(likeCount = likes.value, likesKnown = likes.known)
    }

    private fun read(context: Context?, url: String, apiKey: String): String {
        val startedAt = System.currentTimeMillis()
        val headers = buildMap {
            if (apiKey.isNotBlank()) {
                put("X-Rettiwt-Api-Key", apiKey)
                put("Authorization", "Bearer $apiKey")
            }
        }
        try {
            val response = HttpTransport.get(url, headers)
            BridgeLog.record(context, "GET", url, response.code, response.body, System.currentTimeMillis() - startedAt)
            return HttpTransport.requireSuccess(response, "Bridge")
        } catch (error: Exception) {
            BridgeLog.record(context, "GET", url, null, null, System.currentTimeMillis() - startedAt, error = error.message)
            throw error
        }
    }

    private fun parseUser(body: String): ProfileStats = NetworkResponseParsers.parseBridgeProfile(body)

    private fun highResolutionProfileImageUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun fallbackAvatarUrl(username: String): String =
        "https://unavatar.io/twitter/${URLEncoder.encode(username.trimStart('@'), StandardCharsets.UTF_8.name())}"
}
