package app.ytmdesktop.lite.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.ytmdesktop.lite.MainActivity
import app.ytmdesktop.lite.backend.Backend
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.lastfm.LastFm
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
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
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
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map(MediaItems::withPlaybackUri).toMutableList())
            })
            .build()

        scrobbleLoop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
            mediaItem?.let { Prefs.rememberHeard(it.mediaId) }
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
        private var cacheInstance: SimpleCache? = null

        @Synchronized
        fun cache(service: PlaybackService): SimpleCache = cacheInstance ?: SimpleCache(
            File(service.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(service),
        ).also { cacheInstance = it }
    }
}
