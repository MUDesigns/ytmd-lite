package app.ytmdesktop.lite.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.ytmdesktop.lite.MainActivity
import app.ytmdesktop.lite.R
import app.ytmdesktop.lite.backend.Backend
import app.ytmdesktop.lite.data.Account
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.lastfm.LastFm
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared between the service and UI (same process): autoplay status the player itself doesn't model. */
object PlaybackExtras {
    val autoplayLoading = MutableStateFlow(false)
    val autoplayError = MutableStateFlow<String?>(null)
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()
    internal fun setPlaybackError(message: String?) { _playbackError.value = message }
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var autoplayAttempt: String? = null
    private var autoplayJob: Job? = null
    private var retriedFor: String? = null

    override fun onCreate() {
        super.onCreate()
        Backend.start(this)

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val upstream = OkHttpDataSource.Factory(http).setUserAgent(StreamResolver.USER_AGENT)
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.scheme != MediaItems.SCHEME) return@Factory spec
            val videoId = spec.uri.host ?: return@Factory spec
            val resolved = StreamResolver.resolve(videoId)
            val next = spec.withUri(Uri.parse(resolved.url))
            if (resolved.viaBackend) next.withAdditionalHeaders(mapOf("X-YTMD-Token" to Backend.token)) else next
        }
        val dataSource = CacheDataSource.Factory()
            .setCache(cache(this))
            .setUpstreamDataSourceFactory(resolving)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        scrobbleLoop()
        scope.launch {
            combine(Account.likedIds, Account.signedIn, Prefs.autoplay) { _, _, _ -> }.collect { updateButtons() }
        }
        // Android Auto can start the service before the phone UI: learn the account and likes here too.
        scope.launch { Account.refresh() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // ── Android Auto / media browsers ────────────────────────────────────────

    /** Like and autoplay buttons for the car's Now Playing screen (also shown in the media notification). */
    private fun updateButtons() {
        val session = session ?: return
        val buttons = mutableListOf<CommandButton>()
        val videoId = player.currentMediaItem?.mediaId
        if (videoId != null && Account.signedIn.value) {
            val liked = videoId in Account.likedIds.value
            buttons += CommandButton.Builder(if (liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED)
                .setDisplayName(if (liked) "Remove like" else "Like")
                .setSessionCommand(CMD_LIKE)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
        val autoplay = Prefs.autoplay.value
        buttons += CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (autoplay) R.drawable.ic_autoplay_on else R.drawable.ic_autoplay_off)
            .setDisplayName(if (autoplay) "Autoplay on" else "Autoplay off")
            .setSessionCommand(CMD_AUTOPLAY)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        session.setMediaButtonPreferences(buttons)
    }

    private fun toggleLike(): ListenableFuture<SessionResult> = scope.future {
        val item = player.currentMediaItem ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
        try {
            Account.toggleLike(item.mediaId, MediaItems.toQueueItem(item))
            SessionResult(SessionResult.RESULT_SUCCESS)
        } catch (e: Exception) {
            Log.w("PlaybackService", "Like failed", e)
            SessionResult(SessionError(SessionError.ERROR_IO, e.message ?: "Could not update like"))
        }
    }

    private fun toggleAutoplay() {
        val enabled = !Prefs.autoplay.value
        Prefs.setAutoplay(enabled)
        if (enabled) {
            autoplayAttempt = null
            extendAutoplay()
        }
    }

    private fun <T : Any> libraryCall(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = scope.future {
        try {
            block()
        } catch (e: Exception) {
            Log.w("PlaybackService", "Library request failed", e)
            LibraryResult.ofError(SessionError(SessionError.ERROR_IO, e.message ?: "Request failed"))
        }
    }

    /** Requested items → queue: phone UI queue items pass through; car browse ids and voice searches are expanded. */
    private suspend fun expand(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val single = items.singleOrNull()
        val query = single?.requestMetadata?.searchQuery
        val queue = when {
            single != null && single.mediaId.isEmpty() && query != null -> LibraryTree.playFromSearch(query)
            single != null && LibraryTree.isBrowseId(single.mediaId) -> LibraryTree.resolve(single.mediaId)
            else -> return MediaSession.MediaItemsWithStartPosition(items.map(MediaItems::withPlaybackUri), startIndex, startPositionMs)
        }
        return MediaSession.MediaItemsWithStartPosition(queue.items, queue.startIndex, 0)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_LIKE)
                .add(CMD_AUTOPLAY)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            CMD_LIKE.customAction -> toggleLike()
            CMD_AUTOPLAY.customAction -> {
                toggleAutoplay()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (LibraryTree.isBrowseId(item.mediaId)) LibraryTree.resolve(item.mediaId).items else listOf(MediaItems.withPlaybackUri(item))
            }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            expand(mediaItems, startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val queue = LibraryTree.resumption()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing played yet"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue.items, queue.startIndex, 0))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(LibraryTree.root(), LibraryParams.Builder().setExtras(LibraryTree.rootExtras()).build()),
        )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryTree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryCall {
            LibraryResult.ofItemList(LibraryTree.children(parentId, page, pageSize), params)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                session.notifySearchResultChanged(browser, query, LibraryTree.search(query).size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryCall {
            val all = LibraryTree.search(query)
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            LibraryResult.ofItemList(all.subList(from, minOf(all.size, from + pageSize)), params)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackExtras.setPlaybackError(null)
            mediaItem?.let {
                Prefs.rememberHeard(it.mediaId)
                Prefs.rememberRecentTrack(MediaItems.toQueueItem(it))
            }
            updateButtons()
            resetScrobble()
            extendAutoplay()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) extendAutoplay()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = extendAutoplay()

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            val httpStatus = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            // Expired or IP-bound stream URL: resolve again once, then surface the error.
            if (retriedFor != id && (httpStatus == 403 || httpStatus == 410 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)) {
                retriedFor = id
                StreamResolver.invalidate(id)
                val position = player.currentPosition
                player.prepare()
                player.seekTo(position)
                player.play()
                return
            }
            Log.w("PlaybackService", "Playback failed for $id", error)
            PlaybackExtras.setPlaybackError("Audio stream unavailable — press Play to retry from this position")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                PlaybackExtras.setPlaybackError(null)
                sendNowPlaying()
            }
        }
    }

    /** Append song radio when the last queued track starts, like the desktop player's autoplay. */
    private fun extendAutoplay() {
        if (!Prefs.autoplay.value || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val seed = player.currentMediaItem ?: return
        if (player.hasNextMediaItem() || autoplayAttempt == seed.mediaId || autoplayJob?.isActive == true) return
        autoplayAttempt = seed.mediaId
        PlaybackExtras.autoplayError.value = null
        PlaybackExtras.autoplayLoading.value = true
        autoplayJob = scope.launch {
            try {
                val tracks = Api.loadSongRadio(seed.mediaId)
                if (player.currentMediaItem?.mediaId != seed.mediaId) return@launch
                val seen = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toMutableSet()
                val added = tracks.mapNotNull { it.toQueueItem() }
                    .filter { seen.add(it.videoId) }
                    .take(20)
                    .map { MediaItems.from(it.copy(autoplay = true)) }
                if (added.isEmpty()) {
                    PlaybackExtras.autoplayError.value = "No more similar songs found. Choose another song to continue."
                } else {
                    player.addMediaItems(added)
                }
            } catch (e: Exception) {
                PlaybackExtras.autoplayError.value = "Autoplay unavailable: ${e.message}"
            } finally {
                PlaybackExtras.autoplayLoading.value = false
            }
        }
    }

    // ── Last.fm ──────────────────────────────────────────────────────────────

    private var playedMs = 0L
    private var startedAtSec = 0L
    private var scrobbled = false
    private var nowPlayingSentFor: String? = null

    private fun resetScrobble() {
        playedMs = 0
        scrobbled = false
        startedAtSec = System.currentTimeMillis() / 1000
        retriedFor = null
    }

    private fun currentTrack(): LastFm.Track? {
        val item = player.currentMediaItem ?: return null
        val q = MediaItems.toQueueItem(item)
        val artist = q.author.split(",").first().trim()
        if (artist.isEmpty() || q.title.isEmpty()) return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000) ?: 0
        return LastFm.Track(artist, q.title, q.album, duration)
    }

    private fun sendNowPlaying() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (nowPlayingSentFor == id) return
        val track = currentTrack() ?: return
        nowPlayingSentFor = id
        scope.launch { LastFm.nowPlaying(track) }
    }

    private fun scrobbleLoop() = scope.launch {
        while (isActive) {
            delay(1000)
            if (!player.isPlaying) continue
            playedMs += 1000
            val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: continue
            if (scrobbled || durationMs < 30_000) continue
            val threshold = minOf(durationMs * LastFm.status.value.scrobblePercent / 100, 240_000L)
            if (playedMs >= threshold) {
                scrobbled = true
                val track = currentTrack() ?: continue
                launch { LastFm.scrobble(track, startedAtSec) }
            }
        }
    }

    companion object {
        private val CMD_LIKE = SessionCommand("app.ytmdesktop.lite.LIKE", Bundle.EMPTY)
        private val CMD_AUTOPLAY = SessionCommand("app.ytmdesktop.lite.AUTOPLAY", Bundle.EMPTY)

        private var cacheInstance: SimpleCache? = null

        @Synchronized
        fun cache(service: PlaybackService): SimpleCache = cacheInstance ?: SimpleCache(
            File(service.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(service),
        ).also { cacheInstance = it }
    }
}
