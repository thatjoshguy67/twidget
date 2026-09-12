package com.tjg.twidget.main

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object MilestoneGoalStore {
    private const val PREFS = "twidget_account_goals"

    fun read(context: Context, username: String): AccountGoalSettings =
        readAll(context, username).firstOrNull() ?: AccountGoalSettings()

    fun readAll(context: Context, username: String): List<AccountGoalSettings> {
        val key = accountKey(username)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("${key}_goals", null)?.let { stored ->
            return runCatching { decodeGoals(stored) }.getOrDefault(emptyList())
        }
        if (!prefs.getBoolean("${key}_configured", false)) {
            // Preserve the follower target created by PR #12's earlier dialog.
            val legacy = TwidgetStore.milestoneSettings(context, username)
            val legacyTarget = legacy.target
            if (legacyTarget != null) {
                return listOf(AccountGoalSettings(
                    configured = true,
                    metric = MilestoneMetric.FOLLOWERS,
                    target = legacyTarget.toDouble(),
                    autoAdjust = false,
                ))
            }
            return emptyList()
        }
        return listOf(AccountGoalSettings(
            configured = true,
            metric = MilestoneMetric.fromStorageId(prefs.getString("${key}_metric", null)),
            target = prefs.getString("${key}_target", null)?.toDoubleOrNull() ?: 0.0,
            autoAdjust = prefs.getBoolean("${key}_auto", true),
        )).let(::normalize)
    }

    fun save(context: Context, username: String, settings: AccountGoalSettings) {
        saveAll(context, username, listOf(settings))
    }

    fun saveAll(context: Context, username: String, settings: Collection<AccountGoalSettings>) {
        val key = accountKey(username)
        val goals = normalize(settings)
        val primary = goals.firstOrNull()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val autoAdjust = primary?.autoAdjust
            ?: prefs.getBoolean("${key}_auto_all", true)
        prefs.edit()
            .putString("${key}_goals", encodeGoals(goals))
            .putBoolean("${key}_configured", primary != null)
            .putString("${key}_metric", primary?.metric?.storageId)
            .putString("${key}_target", primary?.target?.toString())
            .putBoolean("${key}_auto", autoAdjust)
            .putBoolean("${key}_auto_all", autoAdjust)
            .apply()

        // Keep the old setting in sync so existing milestone consumers and the
        // new widget implementation receive the follower goal immediately.
        val followerGoal = goals.firstOrNull { it.metric == MilestoneMetric.FOLLOWERS }
        TwidgetStore.saveMilestoneSettings(
            context,
            username,
            MilestoneSettings(
                target = followerGoal?.target?.toLong()?.takeIf { it > 0L },
                labelRaw = followerGoal?.target?.toLong()?.toString().orEmpty(),
                showPercent = true,
            ),
        )
    }

    fun autoAdjust(context: Context, username: String): Boolean {
        val key = accountKey(username)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains("${key}_auto_all")) {
            prefs.getBoolean("${key}_auto_all", true)
        } else {
            readAll(context, username).firstOrNull()?.autoAdjust ?: true
        }
    }

    fun setAutoAdjust(context: Context, username: String, enabled: Boolean) {
        val key = accountKey(username)
        val goals = readAll(context, username)
        if (goals.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("${key}_auto_all", enabled)
                .putBoolean("${key}_auto", enabled)
                .apply()
        } else {
            saveAll(context, username, goals.map { it.copy(autoAdjust = enabled) })
        }
    }

    internal fun normalize(settings: Collection<AccountGoalSettings>): List<AccountGoalSettings> {
        val byMetric = settings.asSequence()
            .filter { it.configured && it.target.isFinite() && it.target > 0.0 }
            .associateBy(AccountGoalSettings::metric)
        return MilestoneMetric.entries.mapNotNull(byMetric::get)
    }

    private fun encodeGoals(goals: List<AccountGoalSettings>): String = JSONArray().apply {
        goals.forEach { goal ->
            put(JSONObject().apply {
                put("metric", goal.metric.storageId)
                put("target", goal.target)
                put("autoAdjust", goal.autoAdjust)
            })
        }
    }.toString()

    private fun decodeGoals(raw: String): List<AccountGoalSettings> {
        val json = JSONArray(raw)
        return normalize(buildList {
            for (index in 0 until json.length()) {
                val goal = json.optJSONObject(index) ?: continue
                add(AccountGoalSettings(
                    configured = true,
                    metric = MilestoneMetric.fromStorageId(goal.optString("metric")),
                    target = goal.optDouble("target", 0.0),
                    autoAdjust = goal.optBoolean("autoAdjust", true),
                ))
            }
        })
    }

    private fun accountKey(username: String): String =
        username.trim().trimStart('@').lowercase(Locale.US).ifBlank { "default" }
}
