package com.tjg.twidget.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRequestCacheTest {
    @Test fun overlappingConsumersShareRequestAndSuccessfulResult() {
        val cache = SharedRequestCache<Int>(60_000)
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Int> { cache.get("a") { calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); 42 } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            cache.invalidateCompleted { true } // Active work must stay shared during explicit refresh.
            val second = pool.submit<Int> { cache.get("a") { calls.incrementAndGet(); 99 } }
            release.countDown()
            assertEquals(42, first.get(5, TimeUnit.SECONDS))
            assertEquals(42, second.get(5, TimeUnit.SECONDS))
            assertEquals(42, cache.get("a") { 100 })
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun failuresAreRetriedAndCompletedResultsAreEvicted() {
        val cache = SharedRequestCache<Int>(60_000, 1)
        runCatching { cache.get("a") { error("offline") } }
        assertEquals(1, cache.get("a") { 1 })
        assertEquals(2, cache.get("b") { 2 })
        assertEquals(3, cache.get("a") { 3 })
    }
    @Test fun explicitRefreshInvalidatesOnlyCompletedResultsForTheSelectedAccount() {
        val cache = SharedRequestCache<Int>(60_000)
        assertEquals(1, cache.get("fx:alice") { 1 })
        assertEquals(2, cache.get("fx:bob") { 2 })
        cache.invalidateCompleted { it.endsWith(":alice") }
        assertEquals(3, cache.get("fx:alice") { 3 })
        assertEquals(2, cache.get("fx:bob") { 4 })
    }

}
