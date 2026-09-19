package com.tjg.twidget.social

import android.content.Context
import android.content.res.ColorStateList
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import java.text.NumberFormat

val SocialPlatform.label: String get() = when (this) {
    SocialPlatform.X -> "Twitter/X"
    SocialPlatform.INSTAGRAM -> "Instagram"
    SocialPlatform.YOUTUBE -> "YouTube"
    SocialPlatform.BLUESKY -> "Bluesky"
    SocialPlatform.GITHUB -> "GitHub"
}
val SocialPlatform.iconRes: Int get() = when (this) {
    SocialPlatform.X -> R.drawable.ic_platform_x
    SocialPlatform.INSTAGRAM -> R.drawable.ic_platform_instagram
    SocialPlatform.YOUTUBE -> R.drawable.ic_platform_youtube
    SocialPlatform.BLUESKY -> R.drawable.ic_platform_bluesky
    SocialPlatform.GITHUB -> R.drawable.ic_platform_github
}
fun SocialPlatform.icon(context: Context) = AppCompatResources.getDrawable(context, iconRes)?.mutate()?.apply {
    setTintList(ColorStateList.valueOf(context.getColor(R.color.oneui_text_primary)))
}
val SocialMetric.labelRes: Int get() = when (this) {
    SocialMetric.FOLLOWERS -> R.string.social_followers
    SocialMetric.FOLLOWING -> R.string.social_following
    SocialMetric.POSTS -> R.string.social_posts
    SocialMetric.LIKES_GIVEN -> R.string.social_likes_given
    SocialMetric.SUBSCRIBERS -> R.string.social_subscribers
    SocialMetric.VIDEOS -> R.string.social_videos
    SocialMetric.VIEWS -> R.string.social_views
    SocialMetric.REPOSITORIES -> R.string.social_repositories
    SocialMetric.STARS -> R.string.social_stars
    SocialMetric.FORKS -> R.string.social_forks
}
fun MetricObservation.displayValue(context: Context): String = value?.let {
    (if (precision == MetricPrecision.ROUNDED) "≈ " else "") + NumberFormat.getIntegerInstance().format(it)
} ?: context.getString(R.string.social_unavailable)
