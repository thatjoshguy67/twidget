package com.tjg.twidget.schedule

/** Account matching for retained composer archives; no scheduling or network behavior. */
internal fun normalizeLegacyScheduleAccount(value: String): String =
    value.trim().trimStart('@').lowercase(java.util.Locale.US)
