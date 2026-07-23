package com.tjg.twidget.followers

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.ProfileImageLoader
import dev.oneuiproject.oneui.layout.ToolbarLayout

class TopFollowersBrowseActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var allFollowers = emptyList<TopFollower>()
    private var filter = TopFollowersFilter.ALL
    private lateinit var adapter: FollowerAdapter
    private lateinit var summaryView: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_top_followers_browse)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
        if (username.isBlank()) {
            finish()
            return
        }

        findViewById<ToolbarLayout>(R.id.top_followers_browse_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.top_followers_browse_root))

        summaryView = findViewById(R.id.top_followers_browse_summary)
        emptyView = findViewById(R.id.top_followers_browse_empty)
        listView = findViewById(R.id.top_followers_browse_list)
        adapter = FollowerAdapter { openProfile(it.username) }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        allFollowers = TopFollowersArchiveStore.readAll(this, username)
        buildFilters()
        render()
    }

    private fun buildFilters() {
        val row = findViewById<LinearLayout>(R.id.top_followers_filter_row)
        row.removeAllViews()
        val mutualAvailable = TopFollowersFilterPolicy.mutualFilterAvailable(allFollowers)
        FILTER_OPTIONS.forEach { option ->
            row.addView(createFilterChip(option, enabled = option != TopFollowersFilter.MUTUAL || mutualAvailable))
        }
    }

    private fun createFilterChip(option: TopFollowersFilter, enabled: Boolean): AppCompatButton =
        AppCompatButton(this).apply {
            text = filterLabel(option)
            isAllCaps = false
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            typeface = Typeface.create("sec", Typeface.BOLD)
            setTextColor(getColor(if (option == filter) android.R.color.white else R.color.oneui_text_primary))
            background = chipBackground(option == filter)
            setOnClickListener {
                if (!enabled && option == TopFollowersFilter.MUTUAL) {
                    Toast.makeText(this@TopFollowersBrowseActivity, R.string.top_followers_filter_mutual_unavailable, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                filter = option
                buildFilters()
                render()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
        }

    private fun chipBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(getColor(if (selected) R.color.oneui_accent else R.color.oneui_card_bg))
        }

    private fun render() {
        val visible = TopFollowersFilterPolicy.apply(allFollowers, filter)
        adapter.submitList(visible)
        summaryView.text = getString(R.string.top_followers_browse_count, visible.size)
        val empty = visible.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun filterLabel(option: TopFollowersFilter): String = when (option) {
        TopFollowersFilter.ALL -> getString(R.string.top_followers_filter_all)
        TopFollowersFilter.ALPHABETICAL -> getString(R.string.top_followers_filter_alpha)
        TopFollowersFilter.RECENT -> getString(R.string.top_followers_filter_recent)
        TopFollowersFilter.VERIFIED -> getString(R.string.top_followers_filter_verified)
        TopFollowersFilter.MUTUAL -> getString(R.string.top_followers_filter_mutual)
    }

    private fun openProfile(handle: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(handle)}")))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class FollowerAdapter(
        private val onClick: (TopFollower) -> Unit,
    ) : ListAdapter<TopFollower, FollowerAdapter.Holder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_top_follower_row, parent, false)
            return Holder(view, onClick)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), position + 1)
        }

        class Holder(
            itemView: View,
            private val onClick: (TopFollower) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val rank = itemView.findViewById<TextView>(R.id.top_follower_row_rank)
            private val avatar = itemView.findViewById<android.widget.ImageView>(R.id.top_follower_row_avatar)
            private val name = itemView.findViewById<TextView>(R.id.top_follower_row_name)
            private val handle = itemView.findViewById<TextView>(R.id.top_follower_row_username)
            private val count = itemView.findViewById<TextView>(R.id.top_follower_row_count)

            fun bind(follower: TopFollower, rankValue: Int) {
                rank.text = rankValue.toString()
                name.text = follower.name
                handle.text = "@${follower.username}"
                count.text = TwidgetStore.compactNumber(follower.followers)
                ProfileImageLoader.loadInto(itemView.context, avatar, follower.avatarUrl)
                itemView.setOnClickListener { onClick(follower) }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<TopFollower>() {
                override fun areItemsTheSame(oldItem: TopFollower, newItem: TopFollower): Boolean =
                    oldItem.id == newItem.id && oldItem.username == newItem.username

                override fun areContentsTheSame(oldItem: TopFollower, newItem: TopFollower): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        const val EXTRA_USERNAME = "username"

        private val FILTER_OPTIONS = listOf(
            TopFollowersFilter.ALL,
            TopFollowersFilter.ALPHABETICAL,
            TopFollowersFilter.RECENT,
            TopFollowersFilter.VERIFIED,
            TopFollowersFilter.MUTUAL,
        )
    }
}
