package com.tjg.twidget.followers

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.R as OneUiIconR

class TopFollowersBrowseActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var allFollowers = emptyList<TopFollower>()
    private var query = ""
    private lateinit var adapter: FollowerAdapter
    private lateinit var emptyView: TextView
    private lateinit var listView: RecyclerView
    private lateinit var refreshView: SwipeRefreshLayout
    private lateinit var toolbarLayout: ToolbarLayout
    private lateinit var searchView: SearchView
    private var refreshItem: MenuItem? = null
    private var refreshGeneration = 0
    private var refreshing = false
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                searchView.setQuery(it, false)
                searchView.clearFocus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_top_followers_browse)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
        if (username.isBlank()) {
            finish()
            return
        }

        toolbarLayout = findViewById(R.id.top_followers_browse_root)
        toolbarLayout.setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }

        emptyView = findViewById(R.id.top_followers_browse_empty)
        listView = findViewById(R.id.top_followers_browse_list)
        refreshView = findViewById<SwipeRefreshLayout>(R.id.top_followers_browse_refresh).apply {
            isEnabled = canRefresh()
            setOnChildScrollUpCallback { _, _ ->
                listView.visibility == View.VISIBLE && listView.canScrollVertically(-1)
            }
            setOnRefreshListener { refreshFollowers(showOutcome = true) }
        }
        adapter = FollowerAdapter { openProfile(it.username) }
        listView.layoutManager = LinearLayoutManager(this).apply {
            initialPrefetchItemCount = 0
        }
        listView.adapter = adapter
        listView.seslSetScrollbarVerticalPadding(dp(26), dp(26))
        listView.seslSetGoToTopEnabled(true)
        listView.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(getColor(R.color.oneui_card_bg))
        }
        listView.clipToOutline = true

        searchView = findViewById(R.id.top_followers_browse_search)
        searchView.setSearchableInfo(getSystemService(SearchManager::class.java).getSearchableInfo(componentName))
        searchView.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).setOnClickListener {
            try {
                voiceSearch.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.top_followers_browser_search))
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            } catch (_: ActivityNotFoundException) {
                // A recognizer can be removed after SESL checks its availability.
                searchView.requestFocus()
            }
        }
        searchView.setOnQueryTextFocusChangeListener { _, _ -> updateSearchWidth() }
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        searchView.setQuery(query, false)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(value: String?): Boolean {
                updateQuery(value)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(value: String?): Boolean {
                updateQuery(value)
                return true
            }
        })
        updateSearchWidth()
        // Reserve scrollable space for the native search bar; content can still
        // move behind it, and the final follower can be brought fully above it.
        applyEdgeToEdgeInsets(toolbarLayout) { navigationInset ->
            searchView.updateBottomMarginForNavigationBar(0, navigationInset)
        }

        // AppBarLayout's scrolling child can extend below the window while the
        // header is expanded. Use the actual overlap, not just the bar's height.
        val listPosition = IntArray(2)
        val searchPosition = IntArray(2)
        listView.viewTreeObserver.addOnPreDrawListener {
            listView.getLocationInWindow(listPosition)
            searchView.getLocationInWindow(searchPosition)
            val bottomPadding = (listPosition[1] + listView.height - searchPosition[1]).coerceAtLeast(0) + dp(4)
            if (listView.paddingBottom != bottomPadding) {
                listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, bottomPadding)
            }
            true
        }

        allFollowers = TopFollowersArchiveStore.readAll(this, username)
        render()
        val expectedCount = TopFollowersStore.read(this, username).scanned
        if (TopFollowersBrowserRefreshPolicy.shouldAutoRefresh(
                shareHistory = TwidgetStore.settings(this).shareHistory,
                archivedCount = allFollowers.size,
                expectedCount = expectedCount,
            )
        ) {
            refreshArchive(showOutcome = false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        refreshItem = menu
            .add(Menu.NONE, View.generateViewId(), Menu.NONE, R.string.top_followers_browser_refresh)
            .apply {
                setIcon(OneUiIconR.drawable.ic_oui_refresh)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                isVisible = canRefresh()
                isEnabled = !refreshing
                setOnMenuItemClickListener {
                    refreshFollowers(showOutcome = true)
                    true
                }
            }

        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        refreshGeneration += 1
        super.onDestroy()
    }

    private fun refreshMode(): TopFollowersBrowserRefreshMode = selectTopFollowersBrowserRefreshMode(
        shareHistory = TwidgetStore.settings(this).shareHistory,
    )

    private fun canRefresh(): Boolean = refreshMode() != TopFollowersBrowserRefreshMode.UNAVAILABLE

    private fun refreshFollowers(showOutcome: Boolean) {
        if (refreshing) return
        when (refreshMode()) {
            TopFollowersBrowserRefreshMode.BRIDGE_DOWNLOAD -> refreshArchive(showOutcome)
            TopFollowersBrowserRefreshMode.UNAVAILABLE -> refreshView.isRefreshing = false
        }
    }

    private fun refreshArchive(showOutcome: Boolean) {
        if (refreshing || !TwidgetStore.settings(this).shareHistory) return
        beginRefresh()
        val generation = refreshGeneration
        AppExecutors.execute(onRejected = {
            runOnUiThread { finishArchiveRefresh(generation, null, showOutcome) }
        }) {
            val refreshed = runCatching {
                TopFollowersBridgeSync.refresh(
                    this,
                    username,
                    notifyChanges = false,
                    forceArchiveRefresh = true,
                )
                    ?: error("Top Followers archive is not available")
                TopFollowersArchiveStore.readAll(this, username)
                    .takeIf { it.isNotEmpty() }
                    ?: error("Top Followers archive is empty")
            }.getOrNull()
            runOnUiThread { finishArchiveRefresh(generation, refreshed, showOutcome) }
        }
    }

    private fun beginRefresh() {
        refreshing = true
        refreshItem?.isEnabled = false
        refreshView.isRefreshing = true
        refreshGeneration += 1
    }

    private fun finishArchiveRefresh(
        generation: Int,
        followers: List<TopFollower>?,
        showOutcome: Boolean,
    ) {
        if (generation != refreshGeneration || isFinishing || isDestroyed) return
        refreshing = false
        refreshItem?.isEnabled = true
        refreshView.isRefreshing = false
        if (followers != null) {
            allFollowers = followers
            render()
            if (showOutcome) {
                Toast.makeText(
                    this,
                    getString(R.string.top_followers_browser_refreshed, followers.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } else if (showOutcome) {
            Toast.makeText(this, R.string.top_followers_browser_refresh_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val visible = TopFollowersBrowserPolicy.apply(allFollowers, query)
        adapter.submitList(visible)
        val empty = visible.isEmpty()
        emptyView.setText(
            if (query.isBlank()) {
                R.string.top_followers_browser_empty
            } else {
                R.string.top_followers_browser_no_results
            },
        )
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateSearchWidth() {
        searchView.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).isSelected = searchView.hasFocus()
        val width = resources.getDimensionPixelSize(if (searchView.hasFocus() || query.isNotBlank()) {
            androidx.appcompat.R.dimen.sesl_search_view_preferred_width
        } else {
            R.dimen.top_followers_search_compact_width
        })
        if (searchView.maxWidth == width) return
        if (searchView.isLaidOut) {
            TransitionManager.beginDelayedTransition(searchView.parent as ViewGroup, ChangeBounds())
        }
        searchView.maxWidth = width
    }

    private fun updateQuery(value: String?) {
        val nextQuery = value.orEmpty()
        if (query == nextQuery) return
        query = nextQuery
        updateSearchWidth()
        render()
    }

    private fun openProfile(handle: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(handle)}")))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class FollowerAdapter(
        private val onClick: (TopFollower) -> Unit,
    ) : ListAdapter<RankedTopFollower, FollowerAdapter.Holder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_top_follower_row, parent, false)
            return Holder(view, onClick)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), showDivider = position < itemCount - 1)
        }

        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            holder.loadAvatarIfVisible()
        }

        override fun onViewDetachedFromWindow(holder: Holder) {
            holder.clearAvatar()
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        class Holder(
            itemView: View,
            private val onClick: (TopFollower) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val rank = itemView.findViewById<TextView>(R.id.top_follower_row_rank)
            private val avatar = itemView.findViewById<ImageView>(R.id.top_follower_row_avatar)
            private val name = itemView.findViewById<TextView>(R.id.top_follower_row_name)
            private val handle = itemView.findViewById<TextView>(R.id.top_follower_row_username)
            private val count = itemView.findViewById<TextView>(R.id.top_follower_row_count)
            private val divider = itemView.findViewById<View>(R.id.top_follower_row_divider)
            private var boundFollower: TopFollower? = null
            private var requestedIdentity: String? = null

            init {
                // RecyclerView rows are inflated after the activity's initial font pass.
                // Apply the app typeface before first draw so recycled/new rows never
                // fall back to Roboto while waiting for another global layout.
                rank.typeface = TwidgetFonts.forApp(itemView.context, 200)
                name.typeface = TwidgetFonts.forApp(itemView.context, 700)
                handle.typeface = TwidgetFonts.forApp(itemView.context, 400)
                count.typeface = TwidgetFonts.forApp(itemView.context, 400)
                TwidgetFonts.setRole(name, TwidgetFonts.Role.LABEL)
                TwidgetFonts.setRole(handle, TwidgetFonts.Role.SUMMARY)
                TwidgetFonts.setRole(count, TwidgetFonts.Role.SUMMARY)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    rank,
                    12,
                    30,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            }

            fun bind(ranked: RankedTopFollower, showDivider: Boolean) {
                clearAvatar()
                val follower = ranked.follower
                boundFollower = follower
                rank.text = ranked.rank.toString()
                name.text = follower.name
                handle.text = "@${follower.username}"
                count.text = TwidgetStore.compactNumber(follower.followers)
                divider.visibility = if (showDivider) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onClick(follower) }
                loadAvatarIfVisible()
            }

            fun loadAvatarIfVisible() {
                val follower = boundFollower ?: return
                val identity = follower.identity()
                if (!TopFollowerAvatarLoadPolicy.shouldLoad(
                        itemView.isAttachedToWindow,
                        identity,
                        requestedIdentity,
                    )
                ) return
                requestedIdentity = identity
                ProfileImageLoader.loadInto(itemView.context, avatar, follower.avatarUrl)
            }

            fun clearAvatar() {
                requestedIdentity = null
                avatar.setTag(R.id.profile_image_request, null)
                ProfileImageLoader.applyCircleClip(avatar)
                avatar.setPadding(0, 0, 0, 0)
                avatar.imageTintList = null
                avatar.setImageDrawable(null)
                avatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(itemView.context.getColor(R.color.top_followers_skeleton))
                }
            }

            fun recycle() {
                clearAvatar()
                boundFollower = null
                itemView.setOnClickListener(null)
            }

            private fun TopFollower.identity(): String =
                id.ifBlank { username }.lowercase()
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<RankedTopFollower>() {
                override fun areItemsTheSame(
                    oldItem: RankedTopFollower,
                    newItem: RankedTopFollower,
                ): Boolean =
                    oldItem.follower.id == newItem.follower.id &&
                        oldItem.follower.username == newItem.follower.username

                override fun areContentsTheSame(
                    oldItem: RankedTopFollower,
                    newItem: RankedTopFollower,
                ): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        private const val STATE_QUERY = "top_followers_query"
        const val EXTRA_USERNAME = "username"
    }
}

internal object TopFollowersBrowserRefreshPolicy {
    fun shouldAutoRefresh(
        shareHistory: Boolean,
        archivedCount: Int,
        expectedCount: Int,
    ): Boolean =
        shareHistory && expectedCount > archivedCount.coerceAtLeast(0)
}

internal enum class TopFollowersBrowserRefreshMode {
    BRIDGE_DOWNLOAD,
    UNAVAILABLE,
}

internal fun selectTopFollowersBrowserRefreshMode(
    shareHistory: Boolean,
): TopFollowersBrowserRefreshMode = when {
    shareHistory -> TopFollowersBrowserRefreshMode.BRIDGE_DOWNLOAD
    else -> TopFollowersBrowserRefreshMode.UNAVAILABLE
}
