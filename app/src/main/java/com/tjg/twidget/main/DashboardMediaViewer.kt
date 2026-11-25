package com.tjg.twidget.main

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.ui.ProfileImageLoader

/** Paged photos and provider-supplied video/animated-GIF playback. */
internal object DashboardMediaViewer {
    fun open(activity: MainActivity, media: List<PostMedia>, postUrl: String) {
        if (media.isEmpty()) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(activity)
        val pager = ViewPager2(activity)
        fun dp(value: Int) = activity.dp(value)
        val videos = mutableSetOf<android.widget.VideoView>()
        dialog.setOnDismissListener { videos.forEach { it.stopPlayback() }; videos.clear() }
        val counter = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        fun update(index: Int) { counter.text = "${index + 1} / ${media.size}" }
        pager.adapter = object : RecyclerView.Adapter<MediaHolder>() {
            override fun getItemCount() = media.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaHolder =
                MediaHolder(FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                })
            override fun onBindViewHolder(holder: MediaHolder, position: Int) {
                val item = media[position]
                holder.frame.removeAllViews()
                holder.frame.addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = item.alt
                    ProfileImageLoader.loadMediaInto(activity, this, item.url, 0)
                }, FrameLayout.LayoutParams(-1, -1))
                if (item.type != "photo" && item.type != "image") {
                    holder.frame.addView(TextView(activity).apply {
                        text = "▶"
                        textSize = 48f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        contentDescription = activity.getString(com.tjg.twidget.R.string.open_post)
                        setOnClickListener {
                            if (item.playbackUrl.isBlank()) {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)))
                            } else {
                                val video = android.widget.VideoView(activity)
                                videos += video
                                holder.frame.addView(video, FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
                                video.setMediaController(android.widget.MediaController(activity))
                                video.setVideoURI(Uri.parse(item.playbackUrl))
                                video.setOnPreparedListener { player ->
                                    player.isLooping = item.type.contains("gif", true)
                                    video.start()
                                }
                            }
                        }
                    }, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
                }
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                videos.forEach { it.pause() }
                update(position)
            }
        })
        root.addView(pager, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(56); bottomMargin = dp(24) })
        root.addView(counter, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        root.addView(TextView(activity).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = activity.getString(com.tjg.twidget.R.string.done)
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        update(0)
        dialog.setContentView(root)
        dialog.window?.setLayout(-1, -1)
        dialog.show()
    }
    private class MediaHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
}
