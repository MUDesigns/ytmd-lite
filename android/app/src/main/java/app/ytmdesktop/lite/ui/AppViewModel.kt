package app.ytmdesktop.lite.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ytmdesktop.lite.backend.Backend
import app.ytmdesktop.lite.data.Account
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.ArtistLink
import app.ytmdesktop.lite.data.BrowseResult
import app.ytmdesktop.lite.data.DetailTarget
import app.ytmdesktop.lite.data.ItemType
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.PlaylistOption
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.SearchFilter
import app.ytmdesktop.lite.data.Sharing
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.playback.Downloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

enum class Panel(val label: String) {
    home("Home"), explore("Discover"), library("Library"), queue("Queue"), downloads("Downloads"),
    settings("Settings"), lastfm("Last.fm"), search("Search"), detail("Collection"),
}

/** An open collection page; kept as a stack so Back returns to the previous one. */
data class DetailEntry(val target: DetailTarget, val item: MusicItem?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val player = PlayerConnection(app, viewModelScope)

    var panel by mutableStateOf(Panel.home)
        private set
    var statusMsg by mutableStateOf("ready")
    var apiReady by mutableStateOf(false)
        private set
    var apiError by mutableStateOf<String?>(null)
        private set
    var signedIn by mutableStateOf(false)
        private set
    var authProfile by mutableStateOf<String?>(null)
        private set
    /** Browsing with the backend's local (no-Google) profile. */
    var guest by mutableStateOf(false)
        private set
    var likedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var homeData by mutableStateOf<BrowseResult?>(null)
        private set
    var exploreData by mutableStateOf<BrowseResult?>(null)
        private set
    var libraryData by mutableStateOf<BrowseResult?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    val detailStack = mutableStateListOf<DetailEntry>()
    var detailData by mutableStateOf<BrowseResult?>(null)
        private set
    var radioBusy by mutableStateOf(false)
        private set
    var subscribeBusy by mutableStateOf(false)
        private set

    var searchQuery by mutableStateOf("")
    var searchFilter by mutableStateOf(SearchFilter.all)
        private set
    var searchData by mutableStateOf<BrowseResult?>(null)
        private set
    var searchBusy by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var recentSearches by mutableStateOf(Prefs.recentSearches())
        private set

    private var panelJob: Job? = null
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    val isSearching: Boolean get() = panel == Panel.search

    init {
        viewModelScope.launch { Account.likedIds.collect { likedIds = it } }
        viewModelScope.launch {
            // Offline collection pages follow the download index as tracks finish or are removed.
            Downloads.state.collect { st ->
                val key = detailStack.lastOrNull()?.target?.offlineKey
                if (panel == Panel.detail && key != null) detailData = Downloads.detail(st, key)
            }
        }
        viewModelScope.launch {
            when (val s = Backend.state.first { it !is Backend.State.Starting }) {
                is Backend.State.Ready -> {
                    apiReady = true
                    refreshAuth()
                }
                is Backend.State.Failed -> {
                    apiError = s.message
                    statusMsg = "API failed: ${s.message}"
                }
                else -> {}
            }
        }
    }

    /** A (possibly different) Google account was confirmed: drop everything loaded for the old one. */
    fun onAccountChanged() {
        homeData = null
        exploreData = null
        libraryData = null
        Account.changed()
        if (panel == Panel.detail) {
            detailStack.clear()
            panel = Panel.home
        }
        refreshAuth()
    }

    fun refreshAuth() = viewModelScope.launch {
        val auth = Api.validateAuth()
        signedIn = auth.valid == true && auth.type != "local"
        guest = auth.valid == true && auth.type == "local"
        authProfile = auth.profile
        Account.setSignedIn(signedIn)
        if (signedIn) Account.loadLikes()
        loadPanel(panel, force = true)
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    fun showPanel(next: Panel) {
        if (next != Panel.detail) detailStack.clear()
        if (next != Panel.search) clearSearchState()
        panel = next
        loadPanel(next)
    }

    /** Returns false when there is nothing left to go back to. */
    fun goBack(): Boolean {
        return when {
            panel == Panel.detail && detailStack.size > 1 -> {
                detailStack.removeAt(detailStack.lastIndex)
                loadDetail()
                true
            }
            panel == Panel.detail -> {
                detailStack.clear()
                panel = if (searchData != null) Panel.search else Panel.home
                loadPanel(panel)
                true
            }
            panel == Panel.search -> { clearSearch(); true }
            panel != Panel.home -> { showPanel(Panel.home); true }
            else -> false
        }
    }

    fun refreshPanel() = loadPanel(panel, force = true)

    private fun loadPanel(p: Panel, force: Boolean = false) {
        val needs = when (p) {
            Panel.home -> force || homeData == null
            Panel.explore -> force || exploreData == null
            Panel.library -> force || libraryData == null
            Panel.detail -> true
            else -> false
        }
        if (!needs) return
        if (p == Panel.detail) return loadDetail()
        panelJob?.cancel()
        loadError = null
        if (!apiReady) return
        panelJob = viewModelScope.launch {
            loading = true
            try {
                when (p) {
                    Panel.home -> homeData = Api.loadHome()
                    Panel.explore -> exploreData = Api.loadExplore(Prefs.heard())
                    Panel.library -> libraryData = Api.loadLibrary()
                    else -> {}
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) loadError = e.message
            } finally {
                loading = false
            }
        }
    }

    private fun loadDetail() {
        val entry = detailStack.lastOrNull() ?: return
        panelJob?.cancel()
        detailData = null
        loadError = null
        entry.target.offlineKey?.let { key ->
            detailData = Downloads.detail(Downloads.state.value, key)
            loadError = if (detailData == null) "This download was removed." else null
            loading = false
            return
        }
        panelJob = viewModelScope.launch {
            loading = true
            try {
                detailData = Api.browseDetail(entry.target)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) loadError = e.message
            } finally {
                loading = false
            }
        }
    }

    fun openDetail(target: DetailTarget, item: MusicItem? = null) {
        if (panel != Panel.detail && panel != Panel.search) clearSearchState()
        detailStack.add(DetailEntry(target, item))
        panel = Panel.detail
        loadDetail()
    }

    val detailItem: MusicItem? get() = detailStack.lastOrNull()?.item

    fun openItem(item: MusicItem) {
        when {
            item.type == ItemType.song && item.videoId != null -> playFromUi(item)
            item.offlineKey != null -> openDetail(DetailTarget(offlineKey = item.offlineKey, title = item.title), item)
            item.type == ItemType.mood || item.params != null ->
                openDetail(DetailTarget(params = item.params, title = item.title), item)
            item.playlistId != null -> openDetail(DetailTarget(playlistId = item.playlistId, title = item.title), item)
            item.browseId != null -> openDetail(DetailTarget(browseId = item.browseId, title = item.title), item)
        }
    }

    fun openArtist(link: ArtistLink) {
        val id = link.browseId
        if (id != null) {
            openDetail(DetailTarget(browseId = id, title = link.name), MusicItem(ItemType.artist, id, link.name, browseId = id))
            return
        }
        viewModelScope.launch {
            statusMsg = "resolving artist ${link.name}…"
            val resolved = runCatching { Api.resolveArtist(link.name) }.getOrNull()
            if (resolved?.browseId != null) openArtist(resolved) else statusMsg = "Artist page not found for ${link.name}"
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    private fun clearSearchState() {
        searchJob?.cancel()
        searchData = null
        searchError = null
        suggestions = emptyList()
    }

    fun clearSearch() {
        searchQuery = ""
        clearSearchState()
        if (panel == Panel.search) {
            panel = Panel.home
            loadPanel(Panel.home)
        }
    }

    fun onSearchInput(text: String) {
        searchQuery = text
        suggestJob?.cancel()
        val q = text.trim()
        if (q.length < 2 || !apiReady) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = runCatching { Api.searchSuggestions(q) }.getOrDefault(emptyList()).take(8)
        }
    }

    fun runSearch(term: String = searchQuery) {
        val q = term.trim()
        if (q.isEmpty()) return
        searchQuery = q
        suggestJob?.cancel()
        suggestions = emptyList()
        recentSearches = Prefs.pushRecentSearch(q)
        detailStack.clear()
        panel = Panel.search
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searchBusy = true
            searchError = null
            try {
                searchData = Api.search(q, searchFilter)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) searchError = e.message
            } finally {
                searchBusy = false
            }
        }
    }

    fun selectSearchFilter(filter: SearchFilter) {
        searchFilter = filter
        if (searchQuery.isNotBlank()) runSearch()
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun report(e: Throwable) {
        statusMsg = e.message ?: "Something went wrong"
    }

    fun playFromUi(item: MusicItem) {
        if (item.videoId != null) {
            player.playItems(listOf(item))
            statusMsg = "playing ${item.title}"
            return
        }
        viewModelScope.launch {
            try {
                val items = collectionTracks(item)
                if (!player.playItems(items)) statusMsg = "No playable tracks"
                else statusMsg = "playing ${item.title}"
            } catch (e: Exception) { report(e) }
        }
    }

    private suspend fun collectionTracks(item: MusicItem): List<MusicItem> {
        val current = detailStack.lastOrNull()
        if (current?.item?.id == item.id && detailData != null) return detailData!!.items
        if (item.type == ItemType.artist && item.browseId != null) return Api.loadArtistRadio(item.browseId)
        val target = DetailTarget(browseId = item.browseId, playlistId = item.playlistId, params = item.params, title = item.title)
        return Api.browseDetail(target).items
    }

    fun playItems(items: List<MusicItem>, index: Int) {
        if (!player.playItems(items, index)) statusMsg = "No playable tracks"
    }

    fun shufflePlay(item: MusicItem?) {
        val items = detailData?.items
        if (item == null && items != null) {
            player.playItems(items, shuffled = true)
            return
        }
        item ?: return
        viewModelScope.launch {
            try { player.playItems(collectionTracks(item), shuffled = true) } catch (e: Exception) { report(e) }
        }
    }

    fun queueItem(item: MusicItem, next: Boolean = false) {
        viewModelScope.launch {
            try {
                val items = if (item.videoId != null) listOf(item) else collectionTracks(item)
                val n = if (next) player.playNext(items) else player.addToQueue(items)
                statusMsg = if (n == 0) "Nothing to queue" else if (next) "playing next: ${item.title}" else "queued $n track${if (n == 1) "" else "s"}"
            } catch (e: Exception) { report(e) }
        }
    }

    fun startArtistRadio() {
        val data = detailData ?: return
        val browseId = data.channelId ?: detailStack.lastOrNull()?.target?.browseId ?: return
        viewModelScope.launch {
            radioBusy = true
            try {
                player.playItems(Api.loadArtistRadio(browseId))
                statusMsg = "artist radio: ${data.title}"
            } catch (e: Exception) { report(e) } finally { radioBusy = false }
        }
    }

    fun toggleArtistSubscribe() {
        val data = detailData ?: return
        val browseId = detailStack.lastOrNull()?.target?.browseId ?: return
        viewModelScope.launch {
            subscribeBusy = true
            try {
                Api.setArtistSubscribed(browseId, !data.subscribed, data.channelId)
                detailData = data.copy(subscribed = !data.subscribed)
            } catch (e: Exception) { report(e) } finally { subscribeBusy = false }
        }
    }

    fun toggleLike(videoId: String?, item: QueueItem? = null) {
        videoId ?: return
        if (!signedIn) {
            statusMsg = "Sign in to like songs"
            return
        }
        viewModelScope.launch {
            try {
                statusMsg = if (Account.toggleLike(videoId, item)) "liked" else "removed like"
            } catch (e: Exception) { report(e) }
        }
    }

    suspend fun playlistOptions(): List<PlaylistOption> = Api.loadPlaylistOptions()

    // ── Offline downloads ────────────────────────────────────────────────────

    val offline get() = Downloads.state

    fun downloadItem(item: MusicItem) {
        item.toQueueItem()?.let { q ->
            val already = q.videoId in Downloads.state.value.downloadedIds
            Downloads.pin(q)
            statusMsg = if (already) "${item.title} is already downloaded" else "downloading ${item.title}"
            return
        }
        val key = Downloads.keyFor(item)
        if (key == null) {
            statusMsg = "Only songs, albums, playlists, and artists can be downloaded"
            return
        }
        viewModelScope.launch {
            try {
                statusMsg = "preparing ${item.title}…"
                val data = if (detailItem?.id == item.id) detailData else null
                val tracks = (data?.items ?: downloadTracks(item)).mapNotNull { it.toQueueItem() }
                val title = (data?.title ?: item.title).let { if (item.type == ItemType.artist) "$it · Top songs" else it }
                val n = Downloads.addCollection(
                    key, title, data?.subtitle ?: item.subtitle,
                    data?.thumbnails?.firstOrNull() ?: item.thumbnails.firstOrNull(), tracks,
                )
                statusMsg = if (n == 0) "${item.title} is already downloaded" else "downloading $n track${if (n == 1) "" else "s"} from ${item.title}"
            } catch (e: Exception) { report(e) }
        }
    }

    /** Tracks to save for a collection: an artist's top songs rather than its radio. */
    private suspend fun downloadTracks(item: MusicItem): List<MusicItem> =
        Api.browseDetail(DetailTarget(browseId = item.browseId, playlistId = item.playlistId, params = item.params, title = item.title)).items

    fun removeDownload(item: MusicItem) {
        if (item.type == ItemType.song && item.videoId != null) {
            Downloads.unpin(item.videoId)
            statusMsg = "removed download: ${item.title}"
            return
        }
        val key = Downloads.keyFor(item) ?: return
        Downloads.removeCollection(key)
        statusMsg = "removed download: ${item.title}"
        if (panel == Panel.detail && detailStack.lastOrNull()?.target?.offlineKey == key) goBack()
    }

    fun downloadQueue() {
        val queue = player.state.queue
        if (queue.isEmpty()) {
            statusMsg = "The queue is empty"
            return
        }
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val n = Downloads.addQueue(queue, "Queue · $stamp")
        statusMsg = "downloading $n of ${queue.size} queued tracks"
    }

    fun addToPlaylist(option: PlaylistOption, track: QueueItem) {
        viewModelScope.launch {
            try {
                Api.addTrackToPlaylist(option.playlistId, track)
                statusMsg = "added to ${option.title}"
            } catch (e: Exception) { report(e) }
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun browseAsGuest() = viewModelScope.launch {
        try {
            Api.createGuestProfile()
            statusMsg = "browsing as guest"
            refreshAuth()
        } catch (e: Exception) { report(e) }
    }

    fun signOut() = viewModelScope.launch {
        try {
            Api.logout()
            signedIn = false
            authProfile = null
            Account.changed()
            homeData = null; libraryData = null; exploreData = null
            statusMsg = "signed out"
            loadPanel(panel, force = true)
        } catch (e: Exception) { report(e) }
    }

    // ── Shared links ─────────────────────────────────────────────────────────

    fun handleLink(link: Sharing.Link) {
        when (link) {
            is Sharing.Link.Song -> viewModelScope.launch {
                try {
                    statusMsg = "opening shared song…"
                    val song = Api.loadSharedSong(link.videoId)
                    player.playItems(listOf(song))
                    statusMsg = "playing ${song.title}"
                } catch (e: Exception) { report(e) }
            }
            is Sharing.Link.Playlist -> openDetail(
                DetailTarget(playlistId = link.playlistId),
                MusicItem(ItemType.playlist, link.playlistId, "Playlist", playlistId = link.playlistId),
            )
        }
    }

    override fun onCleared() {
        player.release()
    }
}
