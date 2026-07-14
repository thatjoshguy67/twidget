package com.tjg.twidget

/**
 * Drawer-free shell retained for Recently deleted and existing notification/deep-link intents.
 * The normal scheduling queue is hosted in-place by [MainActivity].
 */
class ScheduleActivity : ScheduleQueueHostActivity() {
    companion object {
        const val EXTRA_USERNAME = ScheduleQueueHostActivity.EXTRA_USERNAME
        const val EXTRA_OPEN_TRASH = ScheduleQueueHostActivity.EXTRA_OPEN_TRASH
    }
}
