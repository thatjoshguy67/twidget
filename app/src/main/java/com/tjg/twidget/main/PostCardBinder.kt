package com.tjg.twidget.main

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.oneUiDivider

internal object PostCardBinder {
    const val TAG_ROOT = "post_card_root"
    const val TAG_BODY = "post_card_body"
    const val TAG_MEDIA = "post_card_media"
    const val TAG_AVATAR = "post_card_avatar"

    private var cachedMediaGhost: GradientDrawable? = null
    private var cachedAvatarGhost: GradientDrawable? = null

    fun applyEditModeState(activity: MainActivity, root: View, editMode: Boolean) {
        val shell = root as? LinearLayout ?: return
        val postUrl = shell.getTag(R.id.post_card_open_url) as? String
        val opensPost = postUrl?.isNotBlank() == true && !editMode

        shell.background = AppCompatResources.getDrawable(
            activity,
            if (opensPost) R.drawable.metric_card_clickable_bg else R.drawable.metric_card_bg,
        )
        shell.isClickable = opensPost
        shell.isFocusable = opensPost
        if (opensPost) {
            shell.setOnClickListener {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)))
            }
        } else {
            shell.setOnClickListener(null)
        }

        val body = findTaggedView(shell, TAG_BODY) as? TextView
        if (body != null) {
            body.linksClickable = !editMode
            body.movementMethod = if (editMode) null else LinkMovementMethod.getInstance()
        }
        if (editMode) {
            (findTaggedView(shell, TAG_MEDIA) as? ImageView)?.let { applyMediaGhost(activity, it) }
            (findTaggedView(shell, TAG_AVATAR) as? ImageView)?.let { applyAvatarGhost(activity, it) }
        }
    }

    fun applyMediaGhost(activity: MainActivity, imageView: ImageView) {
        imageView.setTag(R.id.profile_image_request, null)
        imageView.setImageDrawable(null)
        imageView.imageTintList = null
        imageView.background = mediaGhostDrawable(activity)
    }

    fun applyAvatarGhost(activity: MainActivity, imageView: ImageView) {
        imageView.setTag(R.id.profile_image_request, null)
        imageView.setImageDrawable(null)
        imageView.imageTintList = null
        imageView.setPadding(0, 0, 0, 0)
        ProfileImageLoader.applyCircleClip(imageView)
        imageView.background = avatarGhostDrawable(activity)
    }

    private fun mediaGhostDrawable(activity: MainActivity): GradientDrawable {
        val template = cachedMediaGhost ?: GradientDrawable().apply {
            cornerRadius = activity.dp(14).toFloat()
            setColor(activity.oneUiDivider())
        }.also { cachedMediaGhost = it }
        return (template.constantState?.newDrawable()?.mutate() as? GradientDrawable) ?: template
    }

    private fun avatarGhostDrawable(activity: MainActivity): GradientDrawable {
        val template = cachedAvatarGhost ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(activity.oneUiDivider())
        }.also { cachedAvatarGhost = it }
        return (template.constantState?.newDrawable()?.mutate() as? GradientDrawable) ?: template
    }

    private fun findTaggedView(root: ViewGroup, tag: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.tag == tag) return child
            if (child is ViewGroup) {
                for (nestedIndex in 0 until child.childCount) {
                    val nested = child.getChildAt(nestedIndex)
                    if (nested.tag == tag) return nested
                }
            }
        }
        return null
    }
}
