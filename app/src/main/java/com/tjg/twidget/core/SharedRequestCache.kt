package com.tjg.twidget.core

import java.util.concurrent.FutureTask

/** Bounded successful-result cache. Overlapping callers perform one request on the winning caller. */
internal class SharedRequestCache<T>(private val ttlMillis: Long, private val capacity: Int = 8) {
    private data class Entry<T>(val task: FutureTask<T>, var completedAt: Long = 0L)
    private val entries = LinkedHashMap<String, Entry<T>>(capacity, 0.75f, true)

    fun invalidateCompleted(matches: (String) -> Boolean) {
        synchronized(entries) { entries.entries.removeAll { matches(it.key) && it.value.task.isDone } }
    }

    fun get(key: String, fetch: () -> T): T {
        val entry = synchronized(entries) {
            val now = System.currentTimeMillis()
            entries.entries.removeAll { (_, value) -> value.task.isDone && value.completedAt > 0L && now - value.completedAt > ttlMillis }
            entries[key] ?: Entry(FutureTask(fetch)).also {
                // Active requests remain shareable; evict only completed results.
                while (entries.size >= capacity) {
                    val oldest = entries.entries.firstOrNull { it.value.task.isDone } ?: break
                    entries.remove(oldest.key)
                }
                if (entries.size < capacity) entries[key] = it
            }
        }
        return try {
            entry.task.run()
            entry.task.get().also { synchronized(entries) {
                if (entry.completedAt == 0L) entry.completedAt = System.currentTimeMillis()
            } }
        } catch (error: java.util.concurrent.ExecutionException) {
            synchronized(entries) { if (entries[key] === entry) entries.remove(key) }
            throw error.cause ?: error
        }
    }
}
