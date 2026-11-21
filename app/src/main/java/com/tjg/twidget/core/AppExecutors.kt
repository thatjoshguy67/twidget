package com.tjg.twidget.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Shared, bounded executor for short-lived disk and network work. */
object AppExecutors {
    private val threadNumber = AtomicInteger()
    private val ioExecutor = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
        ThreadFactory { task ->
            Thread(task, "twidget-io-${threadNumber.incrementAndGet()}").apply {
                isDaemon = false
                priority = Thread.NORM_PRIORITY - 1
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private val imageExecutor = ThreadPoolExecutor(
        4, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        ThreadFactory { task -> Thread(task, "twidget-image-${threadNumber.incrementAndGet()}").apply {
            priority = Thread.NORM_PRIORITY - 1
        } }, ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    fun executeImage(onRejected: () -> Unit = {}, task: () -> Unit): Boolean = try {
        imageExecutor.execute(Runnable(task))
        true
    } catch (_: RejectedExecutionException) {
        onRejected()
        false
    }

    /** Owner can cancel a queued/running task; transports cooperate with interruption. */
    fun submit(task: () -> Unit): java.util.concurrent.Future<*>? = try {
        ioExecutor.submit(Runnable(task))
    } catch (_: RejectedExecutionException) { null }

    fun cancel(task: java.util.concurrent.Future<*>?) {
        task?.cancel(true)
        if (task is Runnable) ioExecutor.remove(task)
    }

    /** Returns false instead of growing threads or queues when fully saturated. */
    fun execute(onRejected: () -> Unit = {}, task: () -> Unit): Boolean = try {
        ioExecutor.execute(Runnable(task))
        true
    } catch (_: RejectedExecutionException) {
        onRejected()
        false
    }
}
