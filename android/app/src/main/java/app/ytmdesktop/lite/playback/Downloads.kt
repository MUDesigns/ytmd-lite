package app.ytmdesktop.lite.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.BrowseKind
import app.ytmdesktop.lite.data.BrowseResult
import app.ytmdesktop.lite.data.ItemType
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.OfflineCollection
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.toMusicItem
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline library: Media3 downloads into a non-evicting cache, plus an index of which
 * collections (playlists, albums, artist top songs, saved queues) and pinned songs want
 * each track. A track is removed when nothing references it anymore.
 */
@OptIn(UnstableApi::class)
object Downloads {
    private const val TAG = "Downloads"
    private const val KIND_QUEUE = "queue"

    data class Progress(val state: Int, val percent: Float, val error: String? = null) {
        val downloading get() = state == Download.STATE_DOWNLOADING
        val queued get() = state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
        val failed get() = state == Download.STATE_FAILED
    }

    data class State(
        val initialized: Boolean = false,
        /** Completed tracks, newest first. */
        val songs: List<QueueItem> = emptyList(),
        val downloadedIds: Set<String> = emptySet(),
        /** Tracks still in flight or failed, by video id. */
        val progress: Map<String, Progress> = emptyMap(),
        val collections: List<OfflineCollection> = emptyList(),
        val pinned: Set<String> = emptySet(),
        val totalBytes: Long = 0,
    ) {
        val active: Int get() = progress.values.count { it.downloading }
        val queued: Int get() = progress.values.count { it.queued }
        val failed: Int get() = progress.values.count { it.failed }
        val isEmpty: Boolean get() = songs.isEmpty() && progress.isEmpty() && collections.isEmpty()

        fun isDownloaded(item: MusicItem): Boolean {
            if (item.videoId != null && item.type == ItemType.song) return item.videoId in downloadedIds
            val key = keyFor(item) ?: return false
            return collections.any { it.key == key }
        }

        fun collection(key: String): OfflineCollection? = collections.firstOrNull { it.key == key }

        /** done / total / still pending for a saved collection; null when it is not saved. */
        fun collectionProgress(key: String): Triple<Int, Int, Int>? {
            val c = collection(key) ?: return null
            val done = c.videoIds.count { it in downloadedIds }
            val pending = c.videoIds.count { progress[it]?.let { p -> p.downloading || p.queued } == true }
            return Triple(done, c.tracks.size, pending)
        }
    }

    @Serializable
    private data class Index(val collections: List<OfflineCollection> = emptyList(), val pinned: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var app: Context
    @Volatile private var initialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val indexFile: File get() = File(app.filesDir, "offline-library.json")
    private var index = Index()
    private var refreshJob: Job? = null
    private var progressJob: Job? = null

    /** One database for every cache in the app; Media3 forbids several providers for the same file. */
    val databaseProvider: StandaloneDatabaseProvider by lazy { StandaloneDatabaseProvider(app) }

    /** Downloaded audio. Never evicted: entries leave only through [remove]. */
    val cache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    val manager: DownloadManager by lazy {
        DownloadManager(app, databaseProvider, cache, YtmdDataSource.Factory(http), Executors.newFixedThreadPool(2)).apply {
            maxParallelDownloads = 2
            minRetryCount = 3
            addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) = refresh()
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    if (finalException != null) Log.w(TAG, "Download ${download.request.id} failed", finalException)
                    refresh()
                }
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
                override fun onIdle(downloadManager: DownloadManager) = refresh()
            })
        }
    }

    fun init(context: Context) {
        synchronized(this) {
            if (initialized) return
            app = context.applicationContext
            initialized = true
        }
        index = runCatching { json.decodeFromString(Index.serializer(), indexFile.readText()) }.getOrDefault(Index())
        manager // Starts loading the index; the listener publishes the first state.
        refresh()
    }

    /** Playback reads downloaded audio first and never writes into the download cache. */
    fun playbackDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // ── Keys ─────────────────────────────────────────────────────────────────

    /** The library key a collection item is saved under; null for songs and moods. */
    fun keyFor(item: MusicItem?): String? {
        item ?: return null
        item.offlineKey?.let { return it }
        return when {
            item.type == ItemType.playlist || (item.playlistId != null && item.type != ItemType.song) -> {
                val id = item.playlistId ?: item.browseId?.removePrefix("VL") ?: return null
                "playlist:$id"
            }
            item.type == ItemType.album && item.browseId != null -> "album:${item.browseId}"
            item.type == ItemType.artist && item.browseId != null -> "artist:${item.browseId}"
            else -> null
        }
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    /** Download a song on its own; it survives removal of any collection. */
    fun pin(item: QueueItem) {
        updateIndex { it.copy(pinned = (it.pinned + item.videoId).distinct()) }
        enqueue(listOf(item))
    }

    fun unpin(videoId: String) {
        updateIndex { it.copy(pinned = it.pinned - videoId) }
        if (index.collections.none { videoId in it.videoIds }) remove(videoId)
        else refresh()
    }

    /** Returns how many tracks were newly queued. */
    fun addCollection(key: String, title: String, subtitle: String?, thumbnail: String?, tracks: List<QueueItem>): Int {
        val unique = tracks.distinctBy { it.videoId }.map { it.copy(autoplay = false) }
        require(unique.isNotEmpty()) { "This collection has no downloadable tracks" }
        val (kind, id) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val collection = OfflineCollection(key, kind, id, title, subtitle, thumbnail, System.currentTimeMillis(), unique)
        updateIndex { it.copy(collections = it.collections.filter { c -> c.key != key } + collection) }
        thumbnail?.let { prefetchArtwork(listOf(it)) }
        return enqueue(unique)
    }

    /** Save the current queue under a timestamped name. */
    fun addQueue(tracks: List<QueueItem>, title: String): Int =
        addCollection("$KIND_QUEUE:${System.currentTimeMillis()}", title, "${tracks.size} tracks", tracks.firstOrNull()?.thumbnails?.firstOrNull(), tracks)

    fun removeCollection(key: String) {
        val collection = index.collections.firstOrNull { it.key == key } ?: return
        updateIndex { it.copy(collections = it.collections.filter { c -> c.key != key }) }
        for (videoId in collection.videoIds) {
            if (videoId in index.pinned || index.collections.any { videoId in it.videoIds }) continue
            remove(videoId)
        }
        refresh()
    }

    fun removeAll() {
        updateIndex { Index() }
        for (download in manager.currentDownloads) manager.removeDownload(download.request.id)
        for (id in _state.value.downloadedIds) manager.removeDownload(id)
        refresh()
    }

    fun cancel(videoId: String) {
        updateIndex { it.copy(pinned = it.pinned - videoId) }
        remove(videoId)
    }

    fun retryFailed() {
        manager.resumeDownloads()
        for (download in manager.currentDownloads) {
            if (download.state == Download.STATE_FAILED) manager.addDownload(download.request)
        }
        startService()
    }

    private fun enqueue(tracks: List<QueueItem>): Int {
        val existing = _state.value.downloadedIds
        var queued = 0
        for (track in tracks) {
            if (track.videoId in existing) continue
            val request = DownloadRequest.Builder(track.videoId, Uri.parse("${MediaItems.SCHEME}://${track.videoId}"))
                .setCustomCacheKey(track.videoId)
                .setData(json.encodeToString(QueueItem.serializer(), track).toByteArray())
                .build()
            manager.addDownload(request)
            queued++
        }
        prefetchArtwork(tracks.flatMap { it.thumbnails.take(1) })
        if (queued > 0) startService()
        refresh()
        return queued
    }

    private fun remove(videoId: String) {
        manager.removeDownload(videoId)
    }

    /** Keep the process alive with a foreground notification while downloads run. */
    private fun startService() {
        try {
            DownloadService.sendResumeDownloads(app, YtmdDownloadService::class.java, true)
        } catch (e: Exception) {
            // Background start restrictions: downloads still run inside this process.
            Log.w(TAG, "Download service not started: ${e.message}")
        }
    }

    /** Called from the launcher activity, where starting a foreground service is allowed. */
    fun resumePending(context: Context) {
        init(context)
        scope.launch {
            // The manager loads its index asynchronously; wait briefly for it.
            repeat(20) { if (manager.isInitialized) return@repeat else delay(100) }
            if (manager.currentDownloads.any { it.state != Download.STATE_COMPLETED }) startService()
        }
    }

    // ── Index ────────────────────────────────────────────────────────────────

    @Synchronized
    private fun updateIndex(transform: (Index) -> Index) {
        index = transform(index)
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(json.encodeToString(Index.serializer(), index))
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(json.encodeToString(Index.serializer(), index))
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not save the offline index", it) }
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(150) // Coalesce bursts of listener callbacks.
            val downloads = withContext(Dispatchers.IO) {
                val out = mutableListOf<Download>()
                runCatching {
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) out += cursor.download
                    }
                }.onFailure { Log.w(TAG, "Could not read the download index", it) }
                out
            }
            publish(downloads)
        }
    }

    private fun publish(downloads: List<Download>) {
        val completed = downloads.filter { it.state == Download.STATE_COMPLETED }.sortedByDescending { it.updateTimeMs }
        val songs = completed.mapNotNull { d ->
            runCatching { json.decodeFromString(QueueItem.serializer(), String(d.request.data)) }.getOrNull()
                ?: QueueItem(d.request.id, d.request.id, "")
        }
        val progress = downloads.filter { it.state != Download.STATE_COMPLETED && it.state != Download.STATE_REMOVING }
            .associate { d ->
                d.request.id to Progress(
                    d.state, d.percentDownloaded.takeIf { it >= 0 } ?: 0f,
                    if (d.state == Download.STATE_FAILED) failureText(d.failureReason) else null,
                )
            }
        _state.value = State(
            initialized = true,
            songs = songs,
            downloadedIds = completed.map { it.request.id }.toSet(),
            progress = progress,
            collections = index.collections.sortedByDescending { it.addedAt },
            pinned = index.pinned.toSet(),
            totalBytes = completed.sumOf { it.bytesDownloaded },
        )
        if (progress.values.any { it.downloading }) watchProgress() else { progressJob?.cancel(); progressJob = null }
    }

    private fun watchProgress() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                delay(1000)
                val current = manager.currentDownloads
                if (current.none { it.state == Download.STATE_DOWNLOADING }) break
                _state.value = _state.value.copy(
                    progress = _state.value.progress.mapValues { (id, p) ->
                        current.firstOrNull { it.request.id == id }?.let { p.copy(percent = it.percentDownloaded.takeIf { v -> v >= 0 } ?: p.percent) } ?: p
                    },
                )
            }
        }
    }

    private fun failureText(reason: Int) = when (reason) {
        Download.FAILURE_REASON_UNKNOWN -> "Download failed"
        else -> "Download failed ($reason)"
    }

    // ── Artwork ──────────────────────────────────────────────────────────────

    /** Warm the image caches the phone UI, notification and Android Auto read from. */
    private fun prefetchArtwork(urls: List<String>) {
        if (urls.isEmpty()) return
        val loader = SingletonImageLoader.get(app)
        scope.launch(Dispatchers.IO) {
            for (url in urls.distinct()) {
                for (size in listOf(320, 1024)) {
                    val sized = Api.thumbUrl(listOf(url), size) ?: continue
                    loader.enqueue(ImageRequest.Builder(app).data(sized).build())
                    runCatching {
                        ArtworkProvider.uriFor(sized)?.let { app.contentResolver.openFileDescriptor(it, "r")?.close() }
                    }
                }
            }
        }
    }

    // ── Views ────────────────────────────────────────────────────────────────

    fun collectionItem(c: OfflineCollection, downloaded: Set<String>): MusicItem {
        val items = c.tracks.filter { it.videoId in downloaded }.map { it.toMusicItem() }
        val cover = c.thumbnail ?: items.firstNotNullOfOrNull { it.thumbnails.firstOrNull() }
        return MusicItem(
            type = if (c.kind == "album") ItemType.album else ItemType.playlist,
            id = c.key,
            title = c.title,
            subtitle = c.subtitle ?: "${items.size} of ${c.tracks.size} downloaded",
            thumbnails = listOfNotNull(cover),
            browseId = if (c.kind == "album") c.id else null,
            playlistId = if (c.kind == "playlist") c.id else null,
            items = items,
            offlineKey = c.key,
        )
    }

    fun collectionItems(state: State): List<MusicItem> = state.collections.map { collectionItem(it, state.downloadedIds) }

    /** A saved collection rendered from disk, so it opens without a connection. */
    fun detail(state: State, key: String): BrowseResult? {
        val c = state.collection(key) ?: return null
        val item = collectionItem(c, state.downloadedIds)
        return BrowseResult(
            title = item.title, subtitle = item.subtitle, items = item.items.orEmpty(), thumbnails = item.thumbnails,
            kind = if (item.type == ItemType.album) BrowseKind.album else BrowseKind.playlist,
        )
    }

    /** Backend errors that mean YouTube is unreachable rather than the request being wrong. */
    fun isOfflineError(message: String?): Boolean {
        message ?: return false
        if (message.contains("Catalog API")) return false
        return Regex("getaddrinfo|name resolution|ENOTFOUND|ECONNRESET|Max retries|NewConnectionError|Temporary failure|Network is unreachable|ConnectionError|Failed to establish|unreachable|timed out|Timeout|UnknownHost|Unable to resolve host", RegexOption.IGNORE_CASE)
            .containsMatchIn(message)
    }

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024) else if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
    }
}
