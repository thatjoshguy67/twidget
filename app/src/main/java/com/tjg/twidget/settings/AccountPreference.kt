package com.tjg.twidget.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetTheme
import com.tjg.twidget.ui.VerifiedBadge
import dev.oneuiproject.oneui.R as IconR
import dev.oneuiproject.oneui.design.R as OneUiDesignR

/**
 * Standard One UI preference row for an account — lives inside a
 * [PreferenceCategory] card so theming comes from the SESL preference pipeline.
 */
class AccountPreference(
    context: Context,
    val accountUsername: String,
    private val onLongPress: (View) -> Unit,
) : Preference(context) {
    init {
        key = "account_${accountUsername.lowercase()}"
        isIconSpaceReserved = true
        widgetLayoutResource = R.layout.preference_account_widget
        updateContent()
    }

    fun refreshFromStore() {
        updateContent()
        notifyChanged()
    }

    private fun isDefaultAccount(): Boolean =
        accountUsername.equals(TwidgetStore.settings(context).username, ignoreCase = true)

    private fun updateContent() {
        val stats = TwidgetStore.currentStats(context, accountUsername)
        title = VerifiedBadge.decorate(
            context,
            stats.fullName.ifBlank { accountUsername },
            stats.isVerified,
            stats.isPrivate,
            dp(16),
        )
        summary = context.getString(R.string.account_handle, accountUsername.trimStart('@'))
        icon = accountIcon(stats.profileImage)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener { anchor ->
            onLongPress(anchor)
            true
        }
        val stats = TwidgetStore.currentStats(context, accountUsername)
        (holder.findViewById(android.R.id.icon) as? ImageView)?.let { iconView ->
            ProfileImageLoader.loadInto(context, iconView, stats.profileImage)
        }
        val isDefault = isDefaultAccount()
        (holder.findViewById(R.id.account_favorite) as? ImageView)?.apply {
            setImageResource(
                if (isDefault) IconR.drawable.ic_oui_favorite_on else IconR.drawable.ic_oui_favorite_off,
            )
            imageTintList = ColorStateList.valueOf(
                if (isDefault) TwidgetTheme.accent(context) else TwidgetTheme.textSecondary(context),
            )
        }
    }

    private fun accountIcon(profileUrl: String) = run {
        val iconSize = context.resources.getDimensionPixelSize(
            OneUiDesignR.dimen.oui_des_drawer_menu_item_icon_size,
        )
        ProfileImageLoader.cachedCircularBitmap(context, profileUrl, iconSize)?.let { bitmap ->
            BitmapDrawable(context.resources, bitmap)
        } ?: context.getDrawable(R.drawable.avatar_twidget)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
