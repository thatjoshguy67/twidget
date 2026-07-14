package com.tjg.twidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ProfileImageLoader {
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024L
    private const val MAX_IMAGE_CACHE_BYTES = 32 * 1024 * 1024L
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

        cachedBitmap(context, imageUrl)?.let {
            imageView.setPadding(0, 0, 0, 0)
            imageView.setImageBitmap(it)
            return
        }

        showFallback(imageView)
        AppExecutors.execute {
            val bitmap = downloadToCache(context, imageUrl)
            imageView.post {
                if (imageView.isAttachedToWindow &&
                    imageView.getTag(R.id.profile_image_request) == requestToken && bitmap != null
                ) {
                    applyCircleClip(imageView)
                    imageView.setPadding(0, 0, 0, 0)
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
        cachedBitmap(context, imageUrl)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageDrawable(null)
        AppExecutors.execute {
            val bitmap = downloadToCache(context, imageUrl)
            imageView.post {
                if (imageView.isAttachedToWindow &&
                    imageView.getTag(R.id.profile_image_request) == requestToken && bitmap != null
                ) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun loadMediaInto(context: Context, imageView: ImageView, url: String, radiusPx: Int) {
        applyRoundedClip(imageView, radiusPx)
        val imageUrl = tweetMediaUrl(url)
        val requestToken = "media:$imageUrl"
        imageView.setTag(R.id.profile_image_request, requestToken)
        if (imageUrl.isBlank()) {
            imageView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        cachedMediaBitmap(context, imageUrl)?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageDrawable(null)
        AppExecutors.execute {
            val bitmap = downloadMediaToCache(context, imageUrl)
            imageView.post {
                if (!imageView.isAttachedToWindow ||
                    imageView.getTag(R.id.profile_image_request) != requestToken
                ) return@post
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                } else {
                    imageView.visibility = View.GONE
                }
            }
        }
    }

    fun cachedBitmap(context: Context, url: String): Bitmap? {
        val imageUrl = highResolutionUrl(url)
        if (imageUrl.isBlank()) return null
        return cacheFile(context, imageUrl).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    fun cachedCircularBitmap(context: Context, url: String, sizePx: Int): Bitmap? =
        cachedBitmap(context, url)?.let { circularCrop(it, sizePx) }

    fun downloadToCache(context: Context, url: String): Bitmap? = runCatching {
        val imageUrl = highResolutionUrl(url)
        val file = cacheFile(context, imageUrl)
        downloadFile(imageUrl, file)
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun cachedMediaBitmap(context: Context, url: String): Bitmap? =
        mediaCacheFile(context, url).takeIf { it.exists() }?.let { decodeSampledBitmap(it, 1_600) }

    private fun downloadMediaToCache(context: Context, url: String): Bitmap? = runCatching {
        val file = mediaCacheFile(context, url)
        downloadFile(url, file)
        decodeSampledBitmap(file, 1_600)
    }.getOrNull()

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
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("Image HTTP $code")
        }
        if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
            connection.disconnect()
            error("Image is too large")
        }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) error("Image is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (file.exists() && !file.delete()) error("Unable to replace image cache file")
            if (!temporary.renameTo(file)) error("Unable to commit image cache file")
            file.parentFile?.let { trimImageCache(it, file) }
        } finally {
            temporary.delete()
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
        imageView.clipToOutline = false
        imageView.setBackgroundResource(0)
        imageView.setPadding(0, 0, 0, 0)
        imageView.setImageResource(R.mipmap.ic_launcher)
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
        File(context.cacheDir, "profile_avatar_${url.hashCode()}.png")

    private fun mediaCacheFile(context: Context, url: String): File =
        File(context.cacheDir, "tweet_media_${url.hashCode()}.img")

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
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun tweetMediaUrl(url: String): String =
        url.trim()
            .replace(Regex("([?&]name=)orig(?=(&|$))")) { "${it.groupValues[1]}large" }
}
