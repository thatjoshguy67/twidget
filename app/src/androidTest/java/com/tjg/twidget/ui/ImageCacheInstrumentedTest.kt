package com.tjg.twidget.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCacheInstrumentedTest {
    @Test fun collisionsDoNotMixAvatarsAndRepeatedDecodesShareSampledBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "https://example.com/audit-${System.nanoTime()}/"
        val urls = listOf(prefix + "Aa", prefix + "BB")
        assertEquals(urls[0].hashCode(), urls[1].hashCode())
        val files = urls.mapIndexed { index, url ->
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            File(context.cacheDir, "profile_avatar_$digest.png").also { file ->
                val bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.RED else Color.BLUE)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Bitmap?> { ProfileImageLoader.cachedBitmap(context, urls[0]) }
            val b = pool.submit<Bitmap?> { ProfileImageLoader.cachedBitmap(context, urls[0]) }
            val first = a.get(5, TimeUnit.SECONDS)!!
            assertSame(first, b.get(5, TimeUnit.SECONDS))
            assertTrue(first.width <= 400 && first.height <= 400)
            assertEquals(Color.RED, first.getPixel(0, 0))
            assertEquals(Color.BLUE, ProfileImageLoader.cachedBitmap(context, urls[1])!!.getPixel(0, 0))
            files[0].delete()
            assertSame("Decoded offline hit needs no file", first, ProfileImageLoader.cachedBitmap(context, urls[0]))
        } finally { pool.shutdownNow(); files.forEach { it.delete() } }
    }
    @Test fun mediaLoadRetriesAfterTheImageQueueIsFull() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val url = "https://example.invalid/queued-media-${System.nanoTime()}.png"
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "tweet_media_$hash.img")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val release = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(2)
        var image: android.widget.ImageView? = null
        try {
            androidx.test.core.app.ActivityScenario.launch(com.tjg.twidget.brief.BriefCoachTestActivity::class.java).use { scenario ->
                repeat(2) { assertTrue(com.tjg.twidget.core.AppExecutors.executeImage {
                    started.countDown(); release.await(10, TimeUnit.SECONDS)
                }) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                repeat(64) { assertTrue(com.tjg.twidget.core.AppExecutors.executeImage {}) }
                scenario.onActivity { activity ->
                    image = MediaAspectImageView(activity)
                    activity.setContentView(image)
                    ProfileImageLoader.loadMediaInto(activity, image!!, url, 0)
                    assertNull(image!!.drawable)
                }
                release.countDown()
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var loaded = false
                do {
                    instrumentation.runOnMainSync { loaded = image!!.drawable != null }
                    if (!loaded) Thread.sleep(50)
                } while (!loaded && android.os.SystemClock.uptimeMillis() < deadline)
                assertTrue("A full queue must not permanently discard a photo", loaded)
                instrumentation.runOnMainSync { assertEquals(android.view.View.VISIBLE, image!!.visibility) }
            }
        } finally { release.countDown(); file.delete() }
    }

}
