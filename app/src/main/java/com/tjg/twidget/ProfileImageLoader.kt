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
        Thread {
            val bitmap = downloadToCache(context, imageUrl)
            imageView.post {
                if (bitmap != null) {
                    imageView.setPadding(0, 0, 0, 0)
                    imageView.setImageBitmap(bitmap)
                }
            }
        }.start()
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
        val connection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Twidget/0.1 Android")
        }
        connection.inputStream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    private fun showFallback(imageView: ImageView) {
        val padding = (imageView.resources.displayMetrics.density * 10).toInt()
        imageView.setPadding(padding, padding, padding, padding)
        imageView.setImageResource(R.drawable.twidget_fg)
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

    private fun highResolutionUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))"), "${'$'}1400x400")
}
