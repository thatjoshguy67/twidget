package com.tjg.twidget.update

/** One process-local update transfer can be paused, resumed, or cancelled from its notification. */
object UpdateDownloadController {
    private val lock = Object()
    private var paused = false
    private var stopped = false

    fun begin() = synchronized(lock) {
        paused = false
        stopped = false
    }

    fun pause() = synchronized(lock) { paused = true }

    fun resume() = synchronized(lock) {
        paused = false
        lock.notifyAll()
    }

    fun stop() = synchronized(lock) {
        stopped = true
        paused = false
        lock.notifyAll()
    }

    fun isPaused(): Boolean = synchronized(lock) { paused }

    /** Blocks the transfer while paused and returns false after Stop was selected. */
    fun awaitPermissionToContinue(): Boolean = synchronized(lock) {
        while (paused && !stopped) lock.wait()
        !stopped
    }
}
