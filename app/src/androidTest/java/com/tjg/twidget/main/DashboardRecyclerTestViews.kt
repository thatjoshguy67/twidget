package com.tjg.twidget.main

import android.view.View
import android.view.ViewGroup

/** Header/footer are native list items too; tests identify cards by their stable IDs. */
internal fun ViewGroup.dashboardCardChildren(): List<View> = (0 until childCount).map(::getChildAt)
    .filter { DashboardCardType.fromId(it.tag as? String ?: "") != null }
