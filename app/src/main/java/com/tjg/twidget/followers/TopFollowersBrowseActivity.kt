package com.tjg.twidget.followers

import android.animation.ValueAnimator
import android.app.SearchManager
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import com.tjg.twidget.ui.Sesl95SearchView
import androidx.activity.OnBackPressedCallback
import com.tjg.twidget.ui.SeslKeyboardFocus
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.VerifiedBadge
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.R as OneUiIconR

class TopFollowersBrowseActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var allFollowers = emptyList<RankedTopFollower>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchGeneration = 0
    private var archiveLoad: java.util.concurrent.Future<*>? = null
    private var searchWork: java.util.concurrent.Future<*>? = null
    private var query = ""
    private lateinit var adapter: FollowerAdapter
    private lateinit var emptyView: TextView
    private lateinit var listView: RecyclerView
    private lateinit var refreshView: SwipeRefreshLayout
    private lateinit var toolbarLayout: ToolbarLayout
    private lateinit var searchView: Sesl95SearchView
    internal lateinit var searchFilters: FollowerSearchMenu
    private var filters = FollowerFilters()
    private var refreshItem: MenuItem? = null
    private var refreshGeneration = 0
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_top_followers_browse)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(getColor(R.color.oneui_bg)))
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
        listView.seslSetFillBottomEnabled(false)
        listView.seslSetFastScrollerEnabled(true)
        com.tjg.twidget.ui.SeslFastScrollerTypography.applyTo(listView)
        listView.seslSetGoToTopEnabled(true)
        listView.seslSetOnGoToTopClickListener { recycler ->
            (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            toolbarLayout.setExpanded(false, false)
            recycler.seslHideGoToTop()
            true
        }

        onBackPressedDispatcher.addCallback(this, collapseSearchOnBack)
        searchFab = findViewById(R.id.top_followers_browse_search_fab)
        searchContainer = findViewById(R.id.top_followers_browse_search_container)
        searchView = findViewById(R.id.top_followers_browse_search)
        val closeSearch = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (::searchFilters.isInitialized && searchFilters.popup != null) searchFilters.dismiss() else searchView.collapse()
            }
        }
        onBackPressedDispatcher.addCallback(this, closeSearch)
        filters = FollowerFilters.restored(savedInstanceState?.getString("filter_sort"), savedInstanceState?.getLong("filter_minimum") ?: 0L)
        searchView.setSearchableInfo(getSystemService(SearchManager::class.java).getSearchableInfo(componentName))
        searchFilters = FollowerSearchMenu(searchView, filters) { value -> filters = value; render() }
        searchView.onExpansionChanged = {
            closeSearch.isEnabled = it
            if (!it) searchFilters.dismiss()
        }
        com.tjg.twidget.ui.KeyboardNavigation.register(this, "search", R.string.floating_shortcut_search, KeyEvent.KEYCODE_F) {
            searchView.expand()
        }
        com.tjg.twidget.ui.KeyboardNavigation.register(this, "filters", R.string.floating_shortcut_filters, KeyEvent.KEYCODE_F,
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON) { searchFilters.show() }
        // Read the delivered insets, rather than the previous root snapshot while it is dispatching.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(searchView) { _, insets ->
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            searchView.onImeVisibilityChanged(imeVisible)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            searchContainer,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) imeAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    searchContainer.translationY = imeTranslationY(insets)
                    findViewById<View>(android.R.id.content)?.invalidate()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        imeAnimating = false
                        ViewCompat.getRootWindowInsets(toolbarLayout)?.let {
                            searchContainer.translationY = imeTranslationY(it)
                        }
                        findViewById<View>(android.R.id.content)?.invalidate()
                    }
                }
            },
        )
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        searchView.setQuery(query, false)
        if (query.isNotEmpty() || savedInstanceState?.getBoolean("search_expanded") == true) {
            searchView.expand(showKeyboard = false, animate = false)
        }
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
        // Float search over the full-height list.
        applyEdgeToEdgeInsets(toolbarLayout) { navigationInset ->
            searchView.updateBottomMarginForNavigationBar(
                if (intent.getBooleanExtra(EXTRA_POPOVER, false)) dp(8) else 0, navigationInset)
        }

        // AppBarLayout's scrolling child can extend below the window while the
        // header is expanded. Use the actual overlap, not just the bar's height.
        val listPosition = IntArray(2)
        val searchPosition = IntArray(2)
        val toolbarPosition = IntArray(2)
        var scrollbarTop = -1
        var scrollbarBottom = -1
        listView.viewTreeObserver.addOnPreDrawListener {
            listView.getLocationInWindow(listPosition)
            searchView.getLocationInWindow(searchPosition)
            toolbarLayout.toolbar.getLocationInWindow(toolbarPosition)
            val firstVisible = (listView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            adapter.currentList.getOrNull(firstVisible)?.let {
                com.tjg.twidget.ui.SeslFastScrollerTypography.setPreviewRank(listView, it.rank)
            }
            val trackTop = (toolbarPosition[1] + toolbarLayout.toolbar.height - listPosition[1])
                .coerceAtLeast(0)
            val trackBottom = (listPosition[1] + listView.height - searchPosition[1]).coerceAtLeast(0)
            val goToTopPadding = trackBottom + dp(8)
            if (listView.seslGetGoToTopBottomPadding() != goToTopPadding) {
                listView.seslSetGoToTopBottomPadding(goToTopPadding)
            }
            // Scroll clearance belongs to the viewport, not the rounded card surface.
            val clearance = trackBottom + dp(12)
            if (listView.paddingBottom != clearance) {
                listView.setPadding(listView.paddingLeft, listView.paddingTop, listView.paddingRight, clearance)
            }
            // SESL already applies floating-toolbar offsets and list padding.
            val additionalTop = trackTop - listView.paddingTop - listView.seslGetScrollBarTopOffset()
            val additionalBottom = trackBottom - listView.paddingBottom - listView.seslGetScrollBarBottomOffset()
            if (additionalTop != scrollbarTop || additionalBottom != scrollbarBottom) {
                scrollbarTop = additionalTop
                scrollbarBottom = additionalBottom
                listView.seslSetFastScrollerAdditionalPadding(additionalTop, additionalBottom)
            }
            true
        }

        render()
        val generation = refreshGeneration
        archiveLoad = AppExecutors.submit {
            val ranked = runCatching { TopFollowersBrowserPolicy.rank(TopFollowersArchiveStore.readAll(applicationContext, username)) }.getOrDefault(emptyList())
            val expectedCount = TopFollowersStore.read(applicationContext, username).scanned
            runOnUiThread {
                if (generation != refreshGeneration || isFinishing || isDestroyed) return@runOnUiThread
                allFollowers = ranked
                render()
                if (TopFollowersBrowserRefreshPolicy.shouldAutoRefresh(
                        shareHistory = TwidgetStore.settings(this).shareHistory,
                        archivedCount = ranked.size,
                        expectedCount = expectedCount,
                    )) refreshArchive(showOutcome = false)
            }
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
        outState.putString("filter_sort", filters.sort.name)
        outState.putLong("filter_minimum", filters.minimum)
        outState.putBoolean("search_expanded", searchView.isFloatingExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        refreshGeneration += 1
        searchGeneration += 1
        AppExecutors.cancel(archiveLoad)
        AppExecutors.cancel(searchWork)
        searchHandler.removeCallbacksAndMessages(null)
        if (::searchFilters.isInitialized) searchFilters.dismiss()
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
                TopFollowersBrowserPolicy.rank(TopFollowersArchiveStore.readAll(this, username))
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
        followers: List<RankedTopFollower>?,
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
        val generation = ++searchGeneration
        val ranked = allFollowers
        val requested = query
        val requestedFilters = filters
        AppExecutors.cancel(searchWork)
        searchHandler.removeCallbacksAndMessages(null)
        searchHandler.postDelayed({
            searchWork = AppExecutors.submit {
                val visible = requestedFilters.apply(ranked, requested)
                runOnUiThread {
                    if (generation == searchGeneration && !isFinishing && !isDestroyed) publishResults(visible)
                }
            }
        }, if (query.isBlank()) 0L else 120L)
    }

    private var lastPublishedFilters = FollowerFilters()
    private var lastPublishedQuery = ""

    private fun publishResults(visible: List<RankedTopFollower>) {
        val publishedFilters = filters
        val publishedQuery = query
        adapter.submitList(visible) {
            // A changed filter/order starts at its first result, retaining original rank labels.
            if (publishedFilters != lastPublishedFilters || publishedQuery != lastPublishedQuery) listView.scrollToPosition(0)
            lastPublishedFilters = publishedFilters
            lastPublishedQuery = publishedQuery
        }
        val empty = visible.isEmpty()
        emptyView.setText(
            if (query.isBlank() && filters.activeCount == 0) {
                R.string.top_followers_browser_empty
            } else {
                R.string.top_followers_browser_no_results
            },
        )
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::searchView.isInitialized && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_F && event.hasModifiers(KeyEvent.META_CTRL_ON)) {
                searchView.expand()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && searchView.isFloatingExpanded) {
                searchView.collapse()
                listView.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateQuery(value: String?) {
        val nextQuery = value.orEmpty()
        if (query == nextQuery) return
        query = nextQuery
        render()
    }

    private fun openProfile(handle: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(handle)}")))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class FollowerAdapter(
        private val onClick: (TopFollower) -> Unit,
    ) : ListAdapter<RankedTopFollower, FollowerAdapter.Holder>(DIFF), android.widget.SectionIndexer {
        private val checkpoints get() = followerCheckpointPositions(itemCount)

        override fun getSections(): Array<String> = checkpoints.map { currentList[it].rank.toString() }.toTypedArray()

        override fun getPositionForSection(sectionIndex: Int): Int =
            checkpoints.let { if (it.isEmpty()) 0 else it[sectionIndex.coerceIn(it.indices)] }

        override fun getSectionForPosition(position: Int): Int =
            checkpoints.indexOfLast { it <= position }.coerceAtLeast(0)

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
            init {
                itemView.isFocusable = true
                SeslKeyboardFocus.install(itemView, 12f)
            }

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
                name.text = VerifiedBadge.decorate(
                    itemView.context,
                    follower.name,
                    follower.verified,
                    null,
                    (18 * itemView.resources.displayMetrics.density).toInt(),
                )
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

    private fun launchVoiceSearch(sviEnabled: Boolean) {
        if (sviEnabled) {
            try {
                voiceSearch.launch(Intent(ACTION_HONEYVOICE_RECOGNIZE_SPEECH))
                return
            } catch (_: ActivityNotFoundException) {
                // Fall back to standard Google speech recognition if Honeyvoice is unavailable.
            }
        }
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

    companion object {
        private const val ACTION_HONEYVOICE_RECOGNIZE_SPEECH = "samsung.honeyboard.honeyvoice.action.RECOGNIZE_SPEECH"
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
