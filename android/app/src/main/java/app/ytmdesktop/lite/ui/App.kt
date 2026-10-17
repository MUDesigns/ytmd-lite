package app.ytmdesktop.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.ytmdesktop.lite.data.ArtistLink
import app.ytmdesktop.lite.data.DetailTarget
import app.ytmdesktop.lite.data.ItemType
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.Sharing
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.lastfm.LastFm
import app.ytmdesktop.lite.playback.Downloads
import app.ytmdesktop.lite.ui.components.ActionSheet
import app.ytmdesktop.lite.ui.components.ItemActions
import app.ytmdesktop.lite.ui.components.MenuAction
import app.ytmdesktop.lite.ui.components.NowPlaying
import app.ytmdesktop.lite.ui.components.PlayerActions
import app.ytmdesktop.lite.ui.components.PlayerDock
import app.ytmdesktop.lite.ui.components.PlaylistPicker
import app.ytmdesktop.lite.ui.theme.Ytmd

class Platform(
    val signIn: () -> Unit,
    val shareText: (String) -> Unit,
    val openUrl: (String) -> Unit,
    val lastFmSignIn: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(vm: AppViewModel, platform: Platform) {
    val c = Ytmd.colors
    val state = vm.player.state
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<MusicItem?>(null) }
    var songMenu by remember { mutableStateOf<QueueItem?>(null) }
    var shareSong by remember { mutableStateOf<QueueItem?>(null) }
    var sharePlaylist by remember { mutableStateOf<String?>(null) }
    var playlistPickerFor by remember { mutableStateOf<QueueItem?>(null) }
    val searchFocus = remember { FocusRequester() }
    val lastFm by LastFm.status.collectAsState()
    val focusManager = LocalFocusManager.current
    val typing = WindowInsets.isImeVisible
    // Close the keyboard once a search or a new track starts.
    LaunchedEffect(vm.searchBusy, state.current?.videoId) {
        if (vm.searchBusy || state.current != null) focusManager.clearFocus()
    }

    val itemActions = remember(vm) {
        ItemActions(open = vm::openItem, play = vm::playFromUi, menu = { itemMenu = it }, artist = vm::openArtist)
    }
    val host = Host(
        signIn = platform.signIn,
        shareText = platform.shareText,
        openUrl = platform.openUrl,
        showSongMenu = { songMenu = it },
        showItemMenu = { itemMenu = it },
        shareSong = { shareSong = it },
        sharePlaylist = { sharePlaylist = it },
        lastFmSignIn = platform.lastFmSignIn,
    )
    val playerActions = PlayerActions(
        playPause = vm.player::playPause,
        next = vm.player::next,
        previous = vm.player::previous,
        seek = vm.player::seek,
        shuffle = vm.player::toggleShuffle,
        repeat = vm.player::toggleRepeat,
        like = { vm.toggleLike(it.videoId, it) },
        share = { shareSong = it },
        addToPlaylist = { playlistPickerFor = it },
        queue = { nowPlayingOpen = false; vm.showPanel(Panel.queue) },
        artist = { nowPlayingOpen = false; vm.openArtist(it) },
    )

    BackHandler(enabled = nowPlayingOpen || vm.panel != Panel.home) {
        if (nowPlayingOpen) nowPlayingOpen = false else vm.goBack()
    }

    val breadcrumb = when (vm.panel) {
        Panel.detail -> vm.detailData?.title ?: vm.detailStack.lastOrNull()?.target?.title ?: "collection"
        Panel.search -> "search"
        else -> vm.panel.label.lowercase()
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
            AppBar(
                breadcrumb = breadcrumb,
                signedIn = vm.signedIn,
                onSignIn = platform.signIn,
                onSettings = { vm.showPanel(if (vm.panel == Panel.settings) Panel.home else Panel.settings) },
                onCommand = {
                    if (vm.panel !in setOf(Panel.home, Panel.explore, Panel.library, Panel.search)) vm.showPanel(Panel.home)
                    runCatching { searchFocus.requestFocus() }
                },
            )
            if (Ytmd.code) WorkbenchTabs(vm.panel, vm.detailData?.title, breadcrumb, vm::showPanel)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PanelContent(vm, host, itemActions, searchFocus)
            }
            if (!typing) PlayerDock(state, playerActions) { nowPlayingOpen = true }
            if (!typing) StatusLine(
                vm.statusMsg, vm.signedIn, vm.apiReady,
                state.queue.takeIf { it.isNotEmpty() }?.let { "Track ${state.queueIndex + 1} of ${it.size}" },
            )
            if (!typing) Rail(
                active = if (vm.panel in setOf(Panel.search, Panel.detail)) null else vm.panel,
                lastFmEnabled = lastFm.enabled,
                lastFmOk = lastFm.authenticated,
                onPanel = vm::showPanel,
            )
        }

        AnimatedVisibility(nowPlayingOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Box(Modifier.fillMaxSize().background(c.termBg).systemBarsPadding()) {
                NowPlaying(
                    state,
                    liked = state.current?.videoId?.let { it in vm.likedIds } == true,
                    actions = playerActions,
                    onCollapse = { nowPlayingOpen = false },
                )
            }
        }
    }

    // ── Sheets ───────────────────────────────────────────────────────────────

    itemMenu?.let { item ->
        ActionSheet(item.title, item.subtitle, itemMenuActions(vm, item, host) { playlistPickerFor = it }) { itemMenu = null }
    }
    songMenu?.let { q ->
        ActionSheet(q.title, q.author, songActions(vm, q, host) { playlistPickerFor = it }) { songMenu = null }
    }
    shareSong?.let { q ->
        ActionSheet("Share song", q.title, Sharing.Destination.entries.map { d ->
            MenuAction("${d.label} link", Icons.Outlined.Link) { platform.shareText(Sharing.songUrl(q.videoId, d)) }
        }) { shareSong = null }
    }
    sharePlaylist?.let { pid ->
        ActionSheet("Share playlist", null, Sharing.Destination.entries.map { d ->
            MenuAction("${d.label} link", Icons.Outlined.Link) { platform.shareText(Sharing.playlistUrl(pid, d)) }
        }) { sharePlaylist = null }
    }
    playlistPickerFor?.let { q ->
        PlaylistPicker(vm::playlistOptions, onPick = { vm.addToPlaylist(it, q) }) { playlistPickerFor = null }
    }
}

/** Song actions shared by track menus and the queue. */
private fun songActions(vm: AppViewModel, q: QueueItem, host: Host, pickPlaylist: (QueueItem) -> Unit): List<MenuAction> {
    val item = MusicItem(ItemType.song, q.videoId, q.title, q.author, q.thumbnails, videoId = q.videoId, album = q.album, albumId = q.albumId, artistBrowseId = q.channelId)
    val liked = q.videoId in vm.likedIds
    return buildList {
        add(MenuAction("Play", Icons.Filled.PlayArrow) { vm.playFromUi(item) })
        add(MenuAction("Play next", Icons.Outlined.QueuePlayNext) { vm.queueItem(item, next = true) })
        add(MenuAction("Add to queue", Icons.AutoMirrored.Outlined.PlaylistPlay) { vm.queueItem(item) })
        if (vm.signedIn) add(MenuAction("Add to playlist…", Icons.AutoMirrored.Outlined.PlaylistAdd) { pickPlaylist(q) })
        add(MenuAction(if (liked) "Unlike" else "Like", if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder) { vm.toggleLike(q.videoId, q) })
        if (q.author.isNotEmpty()) add(MenuAction("Go to artist", Icons.Outlined.Person) { vm.openArtist(ArtistLink(q.author.split(",").first().trim(), q.channelId)) })
        q.albumId?.let { albumId ->
            add(MenuAction("Go to album", Icons.Outlined.Album) {
                vm.openDetail(DetailTarget(browseId = albumId, title = q.album), MusicItem(ItemType.album, albumId, q.album ?: "Album", browseId = albumId))
            })
        }
        add(MenuAction("Share…", Icons.Outlined.Link) { host.shareSong(q) })
        val offline = Downloads.state.value
        val pending = offline.progress[q.videoId]
        when {
            q.videoId in offline.downloadedIds -> add(MenuAction("Remove download", Icons.Outlined.DownloadDone) { vm.removeDownload(item) })
            pending != null && !pending.failed -> add(MenuAction("Cancel download (${pending.percent.toInt()}%)", Icons.Outlined.Close) { Downloads.cancel(q.videoId) })
            else -> add(MenuAction("Download", Icons.Outlined.Download) { vm.downloadItem(item) })
        }
    }
}

/** Download / remove entry for albums, playlists and artists (their top songs). */
private fun MutableList<MenuAction>.addCollectionDownload(vm: AppViewModel, item: MusicItem) {
    val key = Downloads.keyFor(item) ?: return
    if (Downloads.state.value.collection(key) != null) {
        add(MenuAction("Remove download", Icons.Outlined.DownloadDone) { vm.removeDownload(item) })
    } else {
        add(MenuAction(if (item.type == ItemType.artist) "Download top songs" else "Download", Icons.Outlined.Download) { vm.downloadItem(item) })
    }
}

/** contextActions() in +page.svelte. */
private fun itemMenuActions(vm: AppViewModel, item: MusicItem, host: Host, pickPlaylist: (QueueItem) -> Unit): List<MenuAction> {
    item.toQueueItem()?.let { return songActions(vm, it, host, pickPlaylist) }
    return buildList {
        add(MenuAction("Open", Icons.Outlined.FolderOpen) { vm.openItem(item) })
        when (item.type) {
            ItemType.artist -> item.browseId?.let { id ->
                add(MenuAction("Start artist radio", Icons.Outlined.Radio) {
                    vm.openDetail(DetailTarget(browseId = id, title = item.title), item)
                    vm.playFromUi(item)
                })
                addCollectionDownload(vm, item)
            }
            ItemType.mood, ItemType.shelf -> {}
            else -> {
                add(MenuAction("Play", Icons.Filled.PlayArrow) { vm.playFromUi(item) })
                add(MenuAction("Shuffle", Icons.Outlined.Shuffle) { vm.shufflePlay(item) })
                add(MenuAction("Play next", Icons.Outlined.QueuePlayNext) { vm.queueItem(item, next = true) })
                add(MenuAction("Add to queue", Icons.AutoMirrored.Outlined.PlaylistPlay) { vm.queueItem(item) })
                Sharing.shareablePlaylistId(item)?.let { pid -> add(MenuAction("Share playlist…", Icons.Outlined.Link) { host.sharePlaylist(pid) }) }
                addCollectionDownload(vm, item)
            }
        }
    }
}
