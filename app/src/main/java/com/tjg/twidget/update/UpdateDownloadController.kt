package com.tjg.twidget.update

/** One process-local update transfer can be paused, resumed, or cancelled from its notification. */
object UpdateDownloadController {
    private val lock = Object()
    private var active = false
    private var paused = false
    private var stopped = false

    /** Starts a transfer unless another update download is already active. */
    fun tryBegin(): Boolean = synchronized(lock) {
        if (active) return@synchronized false
        active = true
        paused = false
        stopped = false
        true
    }

    fun finish() = synchronized(lock) {
        active = false
        paused = false
        stopped = false
        lock.notifyAll()
    }

    fun pause() = synchronized(lock) {
        if (active) paused = true
    }

    fun resume() = synchronized(lock) {
        if (!active) return@synchronized
        paused = false
        lock.notifyAll()
    }

    fun stop() = synchronized(lock) {
        if (!active) return@synchronized
        stopped = true
        paused = false
        lock.notifyAll()
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun isPaused(): Boolean = synchronized(lock) { paused }

    /** Blocks the transfer while paused and returns false after Stop was selected. */
    fun awaitPermissionToContinue(): Boolean = synchronized(lock) {
        while (active && paused && !stopped) lock.wait()
        active && !stopped
    }
}
