package com.tjg.twidget.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.HttpTransport
import dev.oneuiproject.oneui.R as OneUiIconR
import java.io.File
import android.util.LruCache
import java.security.MessageDigest
import java.util.concurrent.FutureTask

object ProfileImageLoader {
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024L
    private const val MAX_IMAGE_CACHE_BYTES = 32 * 1024 * 1024L
    private val bitmaps = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val requests = mutableMapOf<String, FutureTask<Bitmap?>>()

    private fun sharedBitmap(context: Context, url: String, media: Boolean, download: Boolean): Bitmap? {
        if (url.isBlank()) return null
        val key = "${if (media) "media" else "avatar"}:$url"
        bitmaps.get(key)?.let { return it }
        val requestKey = "$key:download=$download"
        val request = synchronized(requests) {
            requests[requestKey] ?: FutureTask<Bitmap?> {
                runCatching {
                    val file = if (media) mediaCacheFile(context, url) else cacheFile(context, url)
                    if (!file.exists() && download) downloadFile(url, file)
                    val bitmap = file.takeIf { it.exists() }?.let { decodeSampledBitmap(it, if (media) 1600 else 400) }
                    bitmap?.also { bitmaps.put(key, it) }
                }.getOrNull()
            }.also { requests[requestKey] = it }
        }
        return try {
            request.run() // FutureTask runs exactly once, on the winning caller, without nested executor waits.
            request.get()
        } finally {
            synchronized(requests) { if (requests[requestKey] === request && request.isDone) requests.remove(requestKey) }
        }
    }

    fun hasCachedImage(context: Context, url: String): Boolean = highResolutionUrl(url).let {
        it.isNotBlank() && (bitmaps.get("avatar:$it") != null || cacheFile(context, it).exists())
    }

    fun applyCircleClip(imageView: ImageView) {
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        imageView.clipToOutline = true
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    fun loadInto(context: Context, imageView: ImageView, url: String) {
        applyCircleClip(imageView)
        val imageUrl = highResolutionUrl(url)
        val requestToken = "profile:$imageUrl"
        imageView.setTag(R.id.profile_image_request, requestToken)
        if (imageUrl.isBlank()) {
            showFallback(imageView)
            return
        }

        showFallback(imageView)
        AppExecutors.executeImage {
            val bitmap = cachedBitmap(context, imageUrl) ?: downloadToCache(context, imageUrl)
            imageView.post {
                // Dashboard rows are often populated before attachment. The old
                // attachment guard discarded fast avatar responses permanently.
                if (imageView.getTag(R.id.profile_image_request) == requestToken && bitmap != null) {
                    applyCircleClip(imageView)
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.imageTintList = null
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun loadRoundedInto(context: Context, imageView: ImageView, url: String, radiusPx: Int) {
        applyRoundedClip(imageView, radiusPx)
        val imageUrl = highResolutionUrl(url)
        val requestToken = "rounded:$imageUrl"
        imageView.setTag(R.id.profile_image_request, requestToken)
        if (imageUrl.isBlank()) {
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(null)
        AppExecutors.executeImage {
            val bitmap = cachedBitmap(context, imageUrl) ?: downloadToCache(context, imageUrl)
            imageView.post {
                if (imageView.getTag(R.id.profile_image_request) == requestToken && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun loadMediaInto(context: Context, imageView: ImageView, url: String, radiusPx: Int) {
        applyRoundedClip(imageView, radiusPx)
        if (imageView is MediaAspectImageView) imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        val imageUrl = tweetMediaUrl(url)
        val requestToken = "media:$imageUrl"
        imageView.setTag(R.id.profile_image_request, requestToken)
        if (imageUrl.isBlank()) {
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.setImageDrawable(null)
        fun enqueue(attempt: Int) {
            if (imageView.getTag(R.id.profile_image_request) != requestToken) return
            fun retry() {
                imageView.postDelayed({
                    if (imageView.getTag(R.id.profile_image_request) == requestToken) enqueue(attempt + 1)
                }, 500L * (attempt + 1))
            }
            AppExecutors.executeImage(onRejected = {
                if (attempt < 2) retry() else imageView.visibility = View.GONE
            }) {
                val bitmap = downloadMediaToCache(context.applicationContext, imageUrl)
                imageView.post {
                    if (imageView.getTag(R.id.profile_image_request) != requestToken) return@post
                    if (bitmap != null) {
                        imageView.visibility = View.VISIBLE
                        imageView.setImageBitmap(bitmap)
                    } else if (attempt < 2) {
                        retry()
                    } else {
                        imageView.visibility = View.GONE
                    }
                }
            }
        }
        enqueue(0)
    }

    fun cachedBitmap(context: Context, url: String): Bitmap? =
        sharedBitmap(context, highResolutionUrl(url), media = false, download = false)

    /** UI callers may use a decoded memory hit; disk warming belongs on the image executor. */
    fun memoryCircularBitmap(url: String, sizePx: Int): Bitmap? {
        val imageUrl = highResolutionUrl(url)
        val key = "circle:$sizePx:$imageUrl"
        return bitmaps.get(key) ?: bitmaps.get("avatar:$imageUrl")?.let {
            circularCrop(it, sizePx).also { crop -> bitmaps.put(key, crop) }
        }
    }

    fun cachedCircularBitmap(context: Context, url: String, sizePx: Int): Bitmap? =
        cachedBitmap(context, url)?.let { source ->
            val key = "circle:$sizePx:${highResolutionUrl(url)}"
            bitmaps.get(key) ?: circularCrop(source, sizePx).also { bitmaps.put(key, it) }
        }

    fun downloadToCache(context: Context, url: String): Bitmap? =
        sharedBitmap(context, highResolutionUrl(url), media = false, download = true)

    private fun cachedMediaBitmap(context: Context, url: String): Bitmap? =
        sharedBitmap(context, url, media = true, download = false)

    private fun downloadMediaToCache(context: Context, url: String): Bitmap? =
        sharedBitmap(context, url, media = true, download = true)

    /** A separately owned, bounded image for on-demand multimodal coaching. Call off the UI thread. */
    internal fun coachingBitmap(context: Context, url: String): Bitmap? = downloadMediaToCache(context, url)?.let { source ->
        val scale = minOf(1.0, 1024.0 / maxOf(source.width, source.height))
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        if (width == source.width && height == source.height) source.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun downloadFile(url: String, file: File) {
        val connection = HttpTransport.openConnection(
            url,
            headers = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Android) Twidget/0.1",
            ),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
        ).apply { instanceFollowRedirects = true }
        var temporary: File? = null
        val deadline = System.nanoTime() + 20_000_000_000L
        try {
            val code = connection.responseCode
            check(code in 200..299) { "Image HTTP $code" }
            check(connection.contentLengthLong <= MAX_IMAGE_BYTES) { "Image is too large" }
            val pending = File.createTempFile(file.name, ".tmp", file.parentFile)
            temporary = pending
            connection.inputStream.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) throw java.io.InterruptedIOException("Image request cancelled or timed out")
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) error("Image is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            java.nio.file.Files.move(pending.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            file.parentFile?.let { trimImageCache(it, file) }
        } finally {
            temporary?.delete()
            connection.disconnect()
        }
    }

    private fun trimImageCache(directory: File, keep: File) {
        val cached = directory.listFiles()
            ?.filter { it.name.startsWith("profile_avatar_") || it.name.startsWith("tweet_media_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var retained = 0L
        cached.forEach { candidate ->
            retained += candidate.length()
            if (retained > MAX_IMAGE_CACHE_BYTES && candidate != keep) candidate.delete()
        }
    }

    private fun showFallback(imageView: ImageView) {
        applyCircleClip(imageView)
        imageView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(243, 243, 245))
        }
        val inset = (8 * imageView.resources.displayMetrics.density).toInt()
        imageView.setPadding(inset, inset, inset, inset)
        imageView.setImageResource(OneUiIconR.drawable.ic_oui_samsung_account)
        imageView.imageTintList = ColorStateList.valueOf(Color.rgb(132, 132, 135))
    }

    private fun applyRoundedClip(imageView: ImageView, radiusPx: Int) {
        imageView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
            }
        }
        imageView.clipToOutline = true
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private fun circularCrop(source: Bitmap, sizePx: Int): Bitmap {
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val src = Rect(left, top, left + side, top + side)
        val dst = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())

        canvas.drawOval(dst, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, src, dst, paint)
        paint.xfermode = null
        return output
    }

    private fun cacheFile(context: Context, url: String): File =
        keyedFile(context, "profile_avatar_", url, ".png")

    private fun mediaCacheFile(context: Context, url: String): File =
        keyedFile(context, "tweet_media_", url, ".img")

    private fun keyedFile(context: Context, prefix: String, url: String, suffix: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "$prefix$digest$suffix")
        // Preserve already-downloaded offline avatars during upgrades.
        if (!file.exists()) {
            val legacy = File(context.cacheDir, "$prefix${url.hashCode()}$suffix")
            if (legacy.exists()) legacy.renameTo(file)
        }
        return file
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun highResolutionUrl(url: String): String =
        url.trim()
            .let { if (it.startsWith("//")) "https:$it" else it }
            .replace(Regex("^http://pbs\\.twimg\\.com/", RegexOption.IGNORE_CASE), "https://pbs.twimg.com/")
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun tweetMediaUrl(url: String): String =
        url.trim()
            .replace(Regex("([?&]name=)orig(?=(&|$))")) { "${it.groupValues[1]}large" }
}
