package com.tjg.twidget.brief

/** Auto always finishes its local attempt before considering the selected cloud provider. */
internal object BriefProviderRouting {
    suspend fun auto(
        local: suspend () -> BriefSnapshot?,
        cloud: suspend (BriefSnapshot?) -> BriefSnapshot?,
    ): BriefSnapshot? {
        val localResult = local()
        if (localResult?.hasGeneratedNarrative == true) return localResult
        val cloudResult = cloud(localResult)
        return if (cloudResult != null && localResult != null) BriefIncrementalPolicy.merge(localResult, cloudResult)
            else cloudResult ?: localResult
    }
}
