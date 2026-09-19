package com.tjg.twidget.social

data class AudienceTotal(
    val profileId: String,
    val membershipVersion: Long,
    val accountIds: Set<String>,
    val value: Long?,
    val availableSubtotal: Long,
    val missingAccountIds: Set<String>,
    val approximate: Boolean,
    val oldestObservationAt: Long?,
)

object AudienceAggregation {
    /** No interpolation or stale/future samples are admitted into a numeric total. */
    fun total(profile: SocialProfile, accounts: Map<String, PlatformAccount>, observations: List<MetricObservation>,
        at: Long, maxAgeMillis: Long): AudienceTotal {
        require(at > 0 && maxAgeMillis >= 0)
        val missing = linkedSetOf<String>()
        val samples = profile.accountIds.mapNotNull { id ->
            val account = accounts.getValue(id)
            val sample = observations.filter {
                it.accountId == id && it.metric == account.platform.audienceMetric &&
                    !it.estimated && it.observedAt <= at
            }.maxByOrNull { it.observedAt }
            // A newer unavailable observation supersedes an older valid value.
            sample?.takeIf { it.value != null && at - it.observedAt <= maxAgeMillis }
                ?: run { missing += id; null }
        }
        val subtotal = samples.fold(0L) { sum, sample -> Math.addExact(sum, requireNotNull(sample.value)) }
        return AudienceTotal(profile.id, profile.membershipVersion, profile.accountIds.toSet(),
            subtotal.takeIf { missing.isEmpty() }, subtotal, missing,
            samples.any { it.precision == MetricPrecision.ROUNDED }, samples.minOfOrNull { it.observedAt })
    }

    /** Membership changes are not growth; partial totals do not produce a delta. */
    fun delta(before: AudienceTotal, after: AudienceTotal): Long? {
        if (before.profileId != after.profileId || before.membershipVersion != after.membershipVersion ||
            before.accountIds != after.accountIds || before.value == null || after.value == null) return null
        return Math.subtractExact(after.value, before.value)
    }
}
