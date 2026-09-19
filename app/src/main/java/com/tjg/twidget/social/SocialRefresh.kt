package com.tjg.twidget.social

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.RettiwtClient

object SocialRefresh {
    /** Call on IO; each account fails independently and keeps its last known observations. */
    fun refresh(context: Context, accountIds: Set<String>? = null, includeX: Boolean = true): Map<String, SocialProviderError?> =
        SocialRepository(context).use { repository ->
            repository.synchronizeLegacyFrom(context)
            repository.catalog().accounts.filter { (accountIds == null || it.id in accountIds) && (includeX || it.platform != SocialPlatform.X) }
                .associate { account -> account.id to runCatching {
                    if (account.platform == SocialPlatform.X) {
                        TwidgetStore.saveStats(context, RettiwtClient.refresh(context, account.handle))
                        repository.synchronizeLegacyFrom(context)
                        null
                    } else {
                        val tokenWasMissing = account.platform == SocialPlatform.YOUTUBE && SocialConnections.token(context, account.id).isBlank()
                        var refreshedToken = ""
                        val result = if (account.platform == SocialPlatform.BLUESKY) BlueskyProfileProvider.refresh(account) else {
                            val token = SocialConnections.refreshableToken(context, account)
                            refreshedToken = token
                            if (token.isBlank()) SocialProfileResult.Failure(SocialProviderError.REAUTHORIZATION_REQUIRED)
                            else AuthenticatedProfileProviders.fetch(account.platform, token, account)
                        }
                        when (result) {
                            is SocialProfileResult.Success -> {
                                val applied = repository.applyRefresh(result)
                                if (applied && tokenWasMissing && account.platform == SocialPlatform.YOUTUBE && refreshedToken.isNotBlank())
                                    SocialConnections.save(context, account.id, refreshedToken, System.currentTimeMillis() + 55 * 60 * 1000L)
                                null
                            }
                            is SocialProfileResult.Failure -> result.reason
                        }
                    }
                }.getOrDefault(SocialProviderError.UNAVAILABLE) }
        }
}
