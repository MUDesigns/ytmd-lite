package app.ytmdesktop.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.ytmdesktop.lite.BuildConfig
import app.ytmdesktop.lite.data.BrowseKind
import app.ytmdesktop.lite.data.BrowseResult
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.SearchFilter
import app.ytmdesktop.lite.data.Sharing
import app.ytmdesktop.lite.data.toMusicItem
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.lastfm.LastFm
import app.ytmdesktop.lite.playback.Downloads
import app.ytmdesktop.lite.ui.components.AccentPicker
import app.ytmdesktop.lite.ui.components.ArtistLinksText
import app.ytmdesktop.lite.ui.components.Artwork
import app.ytmdesktop.lite.ui.components.Callout
import app.ytmdesktop.lite.ui.components.IconBtn
import app.ytmdesktop.lite.ui.components.IdeButton
import app.ytmdesktop.lite.ui.components.IdeChip
import app.ytmdesktop.lite.ui.components.ItemActions
import app.ytmdesktop.lite.ui.components.Muted
import app.ytmdesktop.lite.ui.components.PageHead
import app.ytmdesktop.lite.ui.components.ResultList
import app.ytmdesktop.lite.ui.components.SectionLabel
import app.ytmdesktop.lite.ui.components.Shape
import app.ytmdesktop.lite.ui.components.ShelfRow
import app.ytmdesktop.lite.ui.components.SpinnerOverlay
import app.ytmdesktop.lite.ui.components.TrackRow
import app.ytmdesktop.lite.ui.components.hairlineBottom
import app.ytmdesktop.lite.ui.theme.Type
import app.ytmdesktop.lite.ui.theme.Ytmd
import app.ytmdesktop.lite.ui.theme.mixWith

/** Platform hooks the screens need from the activity. */
class Host(
    val signIn: () -> Unit,
    val shareText: (String) -> Unit,
    val openUrl: (String) -> Unit,
    val showSongMenu: (QueueItem) -> Unit,
    val showItemMenu: (MusicItem) -> Unit,
    val shareSong: (QueueItem) -> Unit,
    val sharePlaylist: (String) -> Unit,
    val lastFmSignIn: () -> Unit,
)

private val pagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)

// ── Search bar / chips ───────────────────────────────────────────────────────

@Composable
fun SearchBar(vm: AppViewModel, focus: FocusRequester) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(c.lowest, Shape)
            .border(1.dp, if (code) c.outlineVariant else c.outline, Shape)
            .padding(start = 10.dp, end = 4.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (code) Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = c.muted)
        else Text("$", style = Type.base.copy(fontSize = Type.base.fontSize * 1.2f), color = c.primary)
        Box(Modifier.weight(1f)) {
            if (vm.searchQuery.isEmpty()) Muted("search — songs, albums, artists…", style = Type.base)
            BasicTextField(
                vm.searchQuery,
                onValueChange = vm::onSearchInput,
                textStyle = Type.base.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (vm.searchQuery.isNotBlank()) IconBtn(Icons.Outlined.Close, "Clear", size = 34.dp, iconSize = 18.dp) { vm.clearSearch() }
        IdeButton("Search", primary = true, enabled = !vm.searchBusy) { vm.runSearch() }
    }
}

@Composable
private fun ChipsBlock(label: String, terms: List<String>, onTerm: (String) -> Unit) {
    val c = Ytmd.colors
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.muted.copy(alpha = 0.6f))) { append("// ") }
                append(label.lowercase())
            },
            Modifier.padding(bottom = 6.dp),
            style = Type.tiny,
            color = c.muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            terms.forEach { IdeChip(it) { onTerm(it) } }
        }
    }
}

@Composable
private fun AuthBanner(onSignIn: () -> Unit, onGuest: (() -> Unit)?) {
    val c = Ytmd.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(c.high, Shape)
            .border(1.dp, c.outline, Shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Login, null, tint = c.onSurface)
        Column(Modifier.weight(1f)) {
            Text("Sign in to YouTube Music", style = Type.small.copy(fontWeight = FontWeight.SemiBold))
            Muted("Opens a Google login page. Library and personalized home need it.", style = Type.tiny)
            if (onGuest != null) {
                Text(
                    "or browse as guest →",
                    Modifier.padding(top = 4.dp).clickable(onClick = onGuest),
                    style = Type.tiny,
                    color = c.primary,
                )
            }
        }
        IdeButton("Sign in", primary = true, onClick = onSignIn)
    }
}

@Composable
private fun ErrorRetry(vm: AppViewModel, host: Host, error: String, retry: () -> Unit) {
    val offline by Downloads.state.collectAsState()
    val hint = Downloads.isOfflineError(error) && offline.songs.isNotEmpty()
    Callout(error, error = true)
    if (hint) Callout("You appear to be offline. Downloaded music is available in Downloads.", modifier = Modifier.padding(top = 8.dp))
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IdeButton(if (vm.signedIn) "Retry" else "Sign in") { if (vm.signedIn) retry() else host.signIn() }
        if (hint) IdeButton("Open downloads", icon = Icons.Outlined.DownloadForOffline, primary = true) { vm.showPanel(Panel.downloads) }
    }
    Spacer(Modifier.height(12.dp))
}

private fun shelvesOf(data: BrowseResult?): List<MusicItem> = when {
    data == null -> emptyList()
    data.shelves.isNotEmpty() -> data.shelves
    data.items.isNotEmpty() -> listOf(MusicItem(app.ytmdesktop.lite.data.ItemType.shelf, "items", data.title ?: "Items", items = data.items))
    else -> emptyList()
}

// ── Panels ───────────────────────────────────────────────────────────────────

@Composable
fun PanelContent(vm: AppViewModel, host: Host, actions: ItemActions, searchFocus: FocusRequester) {
    Box(Modifier.fillMaxSize()) {
        val dimmed = vm.loading || (vm.isSearching && vm.searchBusy)
        LazyColumn(Modifier.fillMaxSize().alpha(if (dimmed) 0.45f else 1f), contentPadding = pagePadding) {
            val showSearch = vm.panel in setOf(Panel.home, Panel.explore, Panel.library, Panel.search)
            if (!vm.signedIn && vm.apiReady && showSearch) item { AuthBanner(host.signIn, if (vm.guest) null else ({ vm.browseAsGuest() })) }
            if (vm.apiError != null) item { Callout("Catalog API failed to start: ${vm.apiError}", error = true) }
            if (showSearch) item { SearchBar(vm, searchFocus) }
            when (vm.panel) {
                Panel.search -> searchPanel(vm, host, actions)
                Panel.detail -> detailPanel(vm, host, actions)
                Panel.queue -> queuePanel(vm, host)
                Panel.downloads -> downloadsPanel(vm, host, actions)
                Panel.settings -> item { SettingsPanel(vm, host) }
                Panel.lastfm -> item { LastFmPanel(host) }
                else -> browsePanel(vm, host, actions)
            }
        }
        if (dimmed) SpinnerOverlay()
    }
}

private fun LazyListScope.browsePanel(vm: AppViewModel, host: Host, actions: ItemActions) {
    if (vm.recentSearches.isNotEmpty()) item { ChipsBlock("Recent", vm.recentSearches) { vm.runSearch(it) } }
    if (vm.panel != Panel.home) item {
        PageHead(vm.panel.label) {
            if (vm.panel == Panel.explore) {
                IdeButton("Play all", icon = Icons.Filled.PlayArrow, primary = true, enabled = !vm.loading && vm.exploreData?.items?.isNotEmpty() == true) {
                    vm.exploreData?.items?.let { vm.playItems(it, 0) }
                }
            }
            if (vm.panel == Panel.library) {
                IdeButton("Downloads", icon = Icons.Outlined.DownloadForOffline) { vm.showPanel(Panel.downloads) }
            }
            IconBtn(Icons.Outlined.Refresh, "Refresh", enabled = !vm.loading) { vm.refreshPanel() }
        }
    }
    vm.loadError?.let { err -> item { ErrorRetry(vm, host, err) { vm.refreshPanel() } } }
    val data = when (vm.panel) {
        Panel.home -> vm.homeData
        Panel.explore -> vm.exploreData
        else -> vm.libraryData
    }
    if (vm.panel == Panel.explore) data?.subtitle?.let { item { Muted(it, Modifier.padding(bottom = 12.dp)) } }
    shelvesOf(data).forEach { shelf -> item(key = shelf.id + shelf.title) { ShelfRow(shelf, actions) } }
}

private fun LazyListScope.searchPanel(vm: AppViewModel, host: Host, actions: ItemActions) {
    item {
        FlowRow(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchFilter.entries.forEach { f -> IdeChip(f.label, active = vm.searchFilter == f) { vm.selectSearchFilter(f) } }
        }
    }
    if (vm.suggestions.isNotEmpty()) item { ChipsBlock("Suggestions", vm.suggestions) { vm.runSearch(it) } }
    item {
        PageHead(if (vm.searchFilter == SearchFilter.all) "Results" else vm.searchFilter.label) {
            IconBtn(Icons.Outlined.Close, "Clear search") { vm.clearSearch() }
        }
    }
    vm.searchError?.let { err -> item { ErrorRetry(vm, host, err) { vm.runSearch() } } }
    val data = vm.searchData
    if (!vm.searchBusy && vm.searchError == null && data != null && shelvesOf(data).isEmpty()) {
        item { Muted("No results for “${vm.searchQuery.trim()}”.") }
    }
    if (vm.searchFilter == SearchFilter.library) {
        shelvesOf(data).forEach { shelf -> item(key = shelf.id) { ShelfRow(shelf, actions) } }
    } else {
        data?.items?.takeIf { it.isNotEmpty() }?.let { items -> item { ResultList(items, actions) } }
    }
}

private fun LazyListScope.detailPanel(vm: AppViewModel, host: Host, actions: ItemActions) {
    val data = vm.detailData
    val entry = vm.detailStack.lastOrNull()
    val item = vm.detailItem
    item {
        Row(
            Modifier.clip(Shape).clickable { vm.goBack() }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp), tint = Ytmd.colors.primary)
            Text("Back", style = Type.small, color = Ytmd.colors.primary)
        }
    }
    item { DetailHero(vm, host, data, item, entry?.target?.title) }
    vm.loadError?.let { err -> item { ErrorRetry(vm, host, err) { vm.refreshPanel() } } }
    val tracks = data?.items.orEmpty()
    if (tracks.isNotEmpty()) {
        itemsIndexed(tracks, key = { i, t -> "track-$i-${t.id}" }) { i, track ->
            val offline by Downloads.state.collectAsState()
            val pending = track.videoId?.let { offline.progress[it] }?.takeIf { it.downloading || it.queued }
            val inner = @Composable {
                TrackRow(
                    i, track,
                    liked = track.videoId != null && track.videoId in vm.likedIds,
                    downloaded = track.videoId != null && track.videoId in offline.downloadedIds,
                    downloadPercent = pending?.percent?.toInt(),
                    onPlay = { if (track.videoId != null) vm.playItems(tracks, i) else vm.openItem(track) },
                    onLike = { vm.toggleLike(track.videoId, track.toQueueItem()) },
                    onMenu = { host.showItemMenu(track) },
                    onArtist = vm::openArtist,
                )
            }
            if (Ytmd.code) {
                val c = Ytmd.colors
                Box(Modifier.padding(start = 12.dp).drawBehind { drawLine(c.outline, Offset(0f, 0f), Offset(0f, size.height), 1f) }.padding(start = 8.dp)) { inner() }
            } else inner()
        }
    }
    shelvesOf(data).filter { !(tracks.isNotEmpty() && it.id == "items") }.forEach { shelf ->
        item(key = "shelf-" + shelf.id) { Spacer(Modifier.height(16.dp)); ShelfRow(shelf, actions) }
    }
}

@Composable
private fun DetailHero(vm: AppViewModel, host: Host, data: BrowseResult?, item: MusicItem?, fallbackTitle: String?) {
    val c = Ytmd.colors
    val code = Ytmd.code
    val trackCount = data?.items?.size ?: 0
    val isArtist = data?.kind == BrowseKind.artist
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .hairlineBottom(c.outlineVariant)
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = if (code) Alignment.CenterVertically else Alignment.Bottom) {
            if (code) Artwork(data?.thumbnails, 76.dp, codeFile = true)
            else Box(Modifier.border(1.dp, c.outlineVariant, Shape)) { Artwork(data?.thumbnails, 120.dp, round = isArtist) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val kind = (item?.type?.name ?: data?.kind?.name ?: "collection")
                Muted(if (code) "\"type\": \"$kind\"" else kind, style = Type.tiny)
                Text(
                    data?.title ?: fallbackTitle ?: "Collection",
                    style = if (code) Type.hero.copy(fontSize = Type.hero.fontSize * 1.1f) else Type.hero,
                    color = c.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data?.artistLinks != null || !data?.subtitle.isNullOrEmpty()) {
                    ArtistLinksText(data?.artistLinks, data?.subtitle.orEmpty(), vm::openArtist)
                }
                if (isArtist) data?.meta?.let { Muted(it) }
                else Muted("$trackCount track${if (trackCount == 1) "" else "s"}")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = trackCount > 0 && !vm.loading
            IdeButton("Play", icon = Icons.Filled.PlayArrow, primary = true, enabled = enabled) {
                data?.items?.let { vm.playItems(it, 0) }
            }
            IdeButton("Shuffle", icon = Icons.Outlined.Shuffle, enabled = enabled) { vm.shufflePlay(null) }
            IdeButton("Add to queue", icon = Icons.AutoMirrored.Outlined.PlaylistAdd, enabled = enabled) {
                if (item != null) vm.queueItem(item) else data?.items?.let { vm.player.addToQueue(it) }
            }
            val key = Downloads.keyFor(item)
            if (item != null && key != null) {
                val offline by Downloads.state.collectAsState()
                val progress = offline.collectionProgress(key)
                var confirm by remember(key) { mutableStateOf(false) }
                val busy = progress != null && progress.third > 0
                val label = when {
                    confirm -> "Remove download?"
                    progress == null -> "Download"
                    busy -> "Downloading ${progress.first}/${progress.second}"
                    progress.first < progress.second -> "${progress.first}/${progress.second} downloaded"
                    else -> "Downloaded"
                }
                IdeButton(
                    label,
                    icon = when {
                        progress == null -> Icons.Outlined.Download
                        busy -> Icons.Outlined.Downloading
                        else -> Icons.Outlined.DownloadDone
                    },
                    active = progress != null,
                    enabled = enabled && !busy,
                ) {
                    when {
                        progress == null -> vm.downloadItem(item)
                        !confirm -> confirm = true
                        else -> { confirm = false; vm.removeDownload(item) }
                    }
                }
            }
            Sharing.shareablePlaylistId(item)?.let { pid ->
                IdeButton("Share playlist", icon = Icons.Outlined.Share) { host.sharePlaylist(pid) }
            }
            if (isArtist) {
                IdeButton(if (vm.radioBusy) "Starting radio…" else "Start artist radio", icon = Icons.Outlined.Radio, enabled = !vm.radioBusy && !vm.loading) {
                    vm.startArtistRadio()
                }
                IdeButton(
                    if (data?.subscribed == true) "Subscribed" else "Subscribe",
                    icon = if (data?.subscribed == true) Icons.Outlined.NotificationsActive else Icons.Outlined.PersonAdd,
                    active = data?.subscribed == true,
                    enabled = !vm.subscribeBusy && !vm.loading,
                ) { vm.toggleArtistSubscribe() }
            }
        }
    }
}

private fun LazyListScope.queuePanel(vm: AppViewModel, host: Host) {
    val state = vm.player.state
    item {
        PageHead("Queue") {
            IdeButton("Download", icon = Icons.Outlined.Download, enabled = state.queue.isNotEmpty()) { vm.downloadQueue() }
            IdeButton("Clear", enabled = state.queue.isNotEmpty()) {
                vm.player.clearQueue()
                vm.statusMsg = "Queue cleared"
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Autoplay", style = Type.small)
                Muted("Continue with similar songs when the queue ends.", style = Type.tiny)
            }
            val autoplay by Prefs.autoplay.collectAsStateCompat()
            IdeSwitch(autoplay) { Prefs.setAutoplay(it) }
        }
    }
    if (state.queue.isEmpty()) {
        item { Muted("Queue is empty — play something from Home.") }
        return
    }
    itemsIndexed(state.queue, key = { i, q -> "q-$i-${q.videoId}" }) { index, q ->
        QueueRow(vm, host, index, q, selected = index == state.queueIndex, last = index == state.queue.lastIndex)
    }
    state.autoplayError?.let { item { Muted("# $it", Modifier.padding(top = 8.dp)) } }
}

@Composable
private fun QueueRow(vm: AppViewModel, host: Host, index: Int, q: QueueItem, selected: Boolean, last: Boolean) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (code) Modifier.padding(start = 12.dp).drawBehind { drawLine(c.outline, Offset(0f, 0f), Offset(0f, size.height), 1f) }.padding(start = 8.dp) else Modifier)
            .clip(Shape)
            .background(if (selected) c.primary.mixWith(Color.Transparent, 0.10f) else Color.Transparent)
            .drawBehind { if (selected) drawRect(c.primary, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
            .clickable { vm.player.playQueueIndex(index) }
            .padding(start = 6.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            IconBtn(Icons.Outlined.ArrowUpward, "Move up", size = 26.dp, iconSize = 15.dp, enabled = index > 0) { vm.player.moveQueueItem(index, index - 1) }
            IconBtn(Icons.Outlined.ArrowDownward, "Move down", size = 26.dp, iconSize = 15.dp, enabled = !last) { vm.player.moveQueueItem(index, index + 1) }
        }
        if (code) Artwork(q.thumbnails, 36.dp, codeFile = true, fallbackIcon = Icons.Outlined.MusicNote)
        else Artwork(q.thumbnails, 40.dp, fallbackIcon = Icons.Outlined.MusicNote)
        Column(Modifier.weight(1f)) {
            Text(
                if (code) buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted)) { append("song({ title: \"") }
                    withStyle(SpanStyle(color = c.primary)) { append(q.title) }
                    withStyle(SpanStyle(color = c.muted)) { append("\" });") }
                } else buildAnnotatedString { append(q.title) },
                style = Type.base, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            ArtistLinksText(
                q.channelId?.let { listOf(app.ytmdesktop.lite.data.ArtistLink(q.author, it)) }, q.author, vm::openArtist,
                prefix = if (code) buildAnnotatedString { append("// ") } else null,
            )
        }
        if (q.autoplay) Muted("auto", style = Type.tiny)
        val offline by Downloads.state.collectAsState()
        if (q.videoId in offline.downloadedIds) Icon(Icons.Outlined.OfflinePin, "Available offline", Modifier.size(15.dp), tint = c.primary)
        Text("${index + 1}", style = Type.small, color = c.muted)
        IconBtn(Icons.Outlined.MoreVert, "More", size = 32.dp, iconSize = 18.dp) { host.showSongMenu(q) }
        IconBtn(Icons.Outlined.Delete, "Remove", size = 32.dp, iconSize = 17.dp) { vm.player.removeQueueItem(index) }
    }
}

// ── Downloads ────────────────────────────────────────────────────────────────

private fun LazyListScope.downloadsPanel(vm: AppViewModel, host: Host, actions: ItemActions) {
    item {
        val offline by Downloads.state.collectAsState()
        PageHead("Downloads") {
            if (offline.failed > 0) IdeButton("Retry failed", icon = Icons.Outlined.Replay) { Downloads.retryFailed() }
        }
        Muted(
            if (!offline.initialized) "Loading your downloads…"
            else "${offline.songs.size} song${if (offline.songs.size == 1) "" else "s"} · " +
                "${offline.collections.size} collection${if (offline.collections.size == 1) "" else "s"} · " +
                "${Downloads.formatBytes(offline.totalBytes)} on this phone. Downloaded music plays without a connection, even when Home and Library cannot load.",
            Modifier.padding(bottom = 12.dp),
        )
    }
    item { DownloadProgress() }
    item {
        val offline by Downloads.state.collectAsState()
        if (offline.initialized && offline.isEmpty) {
            Callout("Nothing downloaded yet. Long-press a song, album, or playlist and choose Download, or open a collection and use its Download button.")
        }
        val collections = Downloads.collectionItems(offline)
        if (collections.isNotEmpty()) ShelfRow(MusicItem(app.ytmdesktop.lite.data.ItemType.shelf, "offline-collections", "Downloaded collections", items = collections), actions)
    }
    item { DownloadedSongs(vm, host) }
}

@Composable
private fun DownloadProgress() {
    val c = Ytmd.colors
    val offline by Downloads.state.collectAsState()
    val entries = offline.progress.entries.sortedBy { (_, p) -> if (p.downloading) 0 else if (p.queued) 1 else 2 }
    if (entries.isEmpty()) return
    val titles = (offline.collections.flatMap { it.tracks } + offline.songs).associate { it.videoId to it.title }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .background(c.low, Shape)
            .border(1.dp, c.outlineVariant, Shape)
            .padding(10.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.muted.copy(alpha = 0.6f))) { append("// ") }
                append("in progress")
                withStyle(SpanStyle(color = c.muted)) {
                    append("  ${offline.active} active · ${offline.queued} queued${if (offline.failed > 0) " · ${offline.failed} failed" else ""}")
                }
            },
            Modifier.padding(bottom = 6.dp),
            style = Type.tiny,
            color = c.muted,
        )
        entries.take(40).forEach { (videoId, p) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when { p.failed -> Icons.Outlined.ErrorOutline; p.downloading -> Icons.Outlined.Downloading; else -> Icons.Outlined.Schedule },
                    null, Modifier.size(16.dp), tint = if (p.failed) c.error else c.muted,
                )
                Column(Modifier.weight(1f)) {
                    Text(titles[videoId] ?: videoId, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (p.failed) c.error else c.onSurface)
                    if (p.failed) Muted(p.error ?: "Download failed", style = Type.tiny)
                    else Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp).background(c.highest)) {
                        Box(Modifier.fillMaxWidth(p.percent.coerceIn(0f, 100f) / 100f).height(3.dp).background(c.primary))
                    }
                }
                Muted(when { p.failed -> "failed"; p.queued -> "queued"; else -> "${p.percent.toInt()}%" }, style = Type.tiny)
                IconBtn(Icons.Outlined.Close, if (p.failed) "Dismiss" else "Cancel download", size = 30.dp, iconSize = 16.dp) { Downloads.cancel(videoId) }
            }
        }
        if (entries.size > 40) Muted("…and ${entries.size - 40} more.", style = Type.tiny)
    }
}

@Composable
private fun DownloadedSongs(vm: AppViewModel, host: Host) {
    val c = Ytmd.colors
    val offline by Downloads.state.collectAsState()
    val songs = offline.songs
    if (songs.isEmpty()) return
    val items = songs.map { it.toMusicItem() }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.muted.copy(alpha = 0.55f))) { append("// ") }
                    append("downloaded songs")
                },
                Modifier.weight(1f),
                style = Type.small.copy(fontWeight = FontWeight.SemiBold),
                color = c.muted,
            )
            IdeButton("Play all", icon = Icons.Filled.PlayArrow) { vm.playItems(items, 0) }
            Spacer(Modifier.width(8.dp))
            IdeButton("Shuffle", icon = Icons.Outlined.Shuffle) { vm.player.playItems(items, shuffled = true) }
        }
        songs.forEachIndexed { i, q ->
            TrackRow(
                i, items[i],
                liked = q.videoId in vm.likedIds,
                downloaded = true,
                onPlay = { vm.playItems(items, i) },
                onLike = { vm.toggleLike(q.videoId, q) },
                onMenu = { host.showSongMenu(q) },
                onArtist = vm::openArtist,
            )
        }
    }
}

@Composable
fun IdeSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Ytmd.colors
    Switch(
        checked, onChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, checkedBorderColor = c.primary,
            uncheckedTrackColor = c.highest, uncheckedThumbColor = c.muted, uncheckedBorderColor = c.outline,
        ),
    )
}

@Composable
private fun SettingsBlock(label: String, content: @Composable () -> Unit) {
    val c = Ytmd.colors
    val code = Ytmd.code
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .then(if (code) Modifier.drawBehind { drawLine(c.outline, Offset(0f, 0f), Offset(0f, size.height), 1f) }.padding(start = 16.dp) else Modifier)
            .hairlineBottom(if (code) Color.Transparent else c.outlineVariant)
            .padding(bottom = 16.dp),
    ) {
        SectionLabel(label)
        content()
        if (code) Muted("},", Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun SettingsPanel(vm: AppViewModel, host: Host) {
    val c = Ytmd.colors
    val accent by Prefs.accent.collectAsStateCompat()
    val codeTheme by Prefs.codeTheme.collectAsStateCompat()
    val autoplay by Prefs.autoplay.collectAsStateCompat()
    Column {
        PageHead("Settings")
        Muted("Account, appearance, and playback.", Modifier.padding(bottom = 16.dp))

        SettingsBlock("Google / YouTube Music") {
            if (vm.signedIn) {
                SignedIn("Signed in", vm.authProfile ?: "Google account")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IdeButton("Sign out") { vm.signOut() }
                    IdeButton("Switch account", onClick = host.signIn)
                }
            } else {
                Muted(
                    if (vm.guest) "Browsing as guest. Sign in for your library, likes, and personalized home."
                    else "Not signed in. Library and personalized home need a Google session.",
                    Modifier.padding(bottom = 12.dp),
                )
                IdeButton("Sign in with Google", primary = true, onClick = host.signIn)
            }
        }

        SettingsBlock("Appearance") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(c.lowest)
                    .border(1.dp, c.outline)
                    .clickable { Prefs.setCodeTheme(!codeTheme) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Code theme", style = Type.base.copy(fontWeight = FontWeight.SemiBold))
                    Muted("Browse music as a file tree, with compact artwork and your selected accent color. All your usual controls stay available.", style = Type.tiny)
                }
                IdeSwitch(codeTheme) { Prefs.setCodeTheme(it) }
            }
            Muted("Accent color for buttons, highlights, and the playbar.", Modifier.padding(bottom = 10.dp))
            AccentPicker(accent) { Prefs.setAccent(it) }
        }

        SettingsBlock("Playback") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay", style = Type.small)
                    Muted("Add similar songs when the queue runs out.", style = Type.tiny)
                }
                IdeSwitch(autoplay) { Prefs.setAutoplay(it) }
            }
            Spacer(Modifier.height(10.dp))
            Muted("Audio streams are resolved on-device with NewPipeExtractor, with the yt-dlp backend as a fallback. Up to 256 MB of audio is cached.", style = Type.tiny)
        }

        SettingsBlock("Downloads") {
            val offline by Downloads.state.collectAsState()
            var confirm by remember { mutableStateOf(false) }
            Muted(
                "${offline.songs.size} song${if (offline.songs.size == 1) "" else "s"} and ${offline.collections.size} collection${if (offline.collections.size == 1) "" else "s"} use ${Downloads.formatBytes(offline.totalBytes)}. " +
                    "Downloads use the best available audio stream and keep playing without a connection.",
                Modifier.padding(bottom = 10.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IdeButton("Open downloads", icon = Icons.Outlined.DownloadForOffline) { vm.showPanel(Panel.downloads) }
                if (confirm) {
                    IdeButton("Confirm: remove everything") { confirm = false; Downloads.removeAll(); vm.statusMsg = "all downloads removed" }
                    IdeButton("Cancel") { confirm = false }
                } else {
                    IdeButton("Remove all downloads", enabled = !offline.isEmpty) { confirm = true }
                }
            }
        }

        SettingsBlock("Last.fm") {
            val st by LastFm.status.collectAsStateCompat()
            Muted(
                when {
                    !st.hasCredentials -> "Not available in this build."
                    st.authenticated -> "Signed in as ${st.username ?: "Last.fm user"} · scrobbling ${if (st.enabled) "on" else "off"}"
                    else -> "Not connected."
                },
                Modifier.padding(bottom = 10.dp),
            )
            IdeButton("Open Last.fm panel") { vm.showPanel(Panel.lastfm) }
        }

        Muted("YTMD Lite for Android ${BuildConfig.VERSION_NAME} · API ${if (vm.apiReady) "ready" else vm.apiError ?: "starting…"}", style = Type.tiny)
    }
}

@Composable
private fun SignedIn(label: String, user: String) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.AccountCircle, null, Modifier.size(28.dp), tint = Ytmd.colors.onSurface)
        Column {
            Muted(label, style = Type.tiny)
            Text(user, style = Type.base.copy(fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun LastFmPanel(host: Host) {
    val c = Ytmd.colors
    val st by LastFm.status.collectAsStateCompat()
    Column {
        PageHead("Last.fm")
        Muted("Scrobble what you play — approve YTMD Lite once in your browser.", Modifier.padding(bottom = 12.dp))
        when {
            !st.hasCredentials -> Callout("Last.fm is not available in this build.", error = true)
            !st.authenticated -> {
                Muted("Approve YTMD Lite in your browser, then come back. Scrobbling turns on automatically after sign-in.", Modifier.padding(bottom = 12.dp))
                IdeButton(if (st.signingIn) "Waiting for approval…" else "Sign in with Last.fm", primary = true, enabled = !st.signingIn, onClick = host.lastFmSignIn)
                if (st.signingIn) {
                    Spacer(Modifier.height(8.dp))
                    IdeButton("Cancel") { LastFm.cancelSignIn() }
                }
            }
            else -> {
                SignedIn("Signed in as", st.username ?: "Last.fm user")
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IdeSwitch(st.enabled) { LastFm.setEnabled(it) }
                    Text("Enable scrobbling", style = Type.base)
                }
                Text("Scrobble at ${st.scrobblePercent}%", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                var percent by remember(st.scrobblePercent) { mutableFloatStateOf(st.scrobblePercent.toFloat()) }
                Slider(
                    percent, { percent = it }, valueRange = 50f..95f, steps = 8,
                    onValueChangeFinished = { LastFm.setScrobblePercent(percent.toInt()) },
                    colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.outline),
                )
                IdeButton("Sign out") { LastFm.logout() }
            }
        }
        st.lastError?.let { Callout(it, error = true) }
        st.nowPlaying?.let { Muted("Now playing: $it", Modifier.padding(top = 8.dp)) }
        st.lastScrobble?.let { Muted("Last scrobble: $it", Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat() = collectAsState()
