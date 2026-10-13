package app.ytmdesktop.lite.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.toQueueItem
import app.ytmdesktop.lite.playback.MediaItems
import app.ytmdesktop.lite.playback.PlaybackExtras
import app.ytmdesktop.lite.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class Repeat { off, one, all }

/** UI snapshot of the player, shaped like PlayerState in src/lib/types.ts. */
data class PlayerUiState(
    val current: QueueItem? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: Repeat = Repeat.off,
    val error: String? = null,
    val autoplayLoading: Boolean = false,
    val autoplayError: String? = null,
) {
    /** run / buf / stop / error / nil — the dock's state pill. */
    val stateTag: String
        get() = when {
            error != null -> "error"
            buffering -> "buf"
            isPlaying -> "run"
            current != null -> "stop"
            else -> "nil"
        }
}

class PlayerConnection(context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf(PlayerUiState())
        private set

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            pending.forEach { it(c) }
            pending.clear()
            refresh()
        }, ContextCompat.getMainExecutor(context))

        scope.launch {
            combine(PlaybackExtras.autoplayLoading, PlaybackExtras.autoplayError, PlaybackExtras.playbackError) { a, b, c -> Triple(a, b, c) }
                .collect { refresh() }
        }
        scope.launch {
            while (true) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun refresh() {
        val c = controller ?: return
        val queue = (0 until c.mediaItemCount).map { MediaItems.toQueueItem(c.getMediaItemAt(it)) }
        val index = if (queue.isEmpty()) -1 else c.currentMediaItemIndex
        state = PlayerUiState(
            current = queue.getOrNull(index),
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            queue = queue,
            queueIndex = index,
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> Repeat.one
                Player.REPEAT_MODE_ALL -> Repeat.all
                else -> Repeat.off
            },
            error = PlaybackExtras.playbackError.value,
            autoplayLoading = PlaybackExtras.autoplayLoading.value,
            autoplayError = PlaybackExtras.autoplayError.value,
        )
    }

    private fun playable(items: List<MusicItem>) = items.mapNotNull { it.toQueueItem() }

    fun playItems(items: List<MusicItem>, startIndex: Int = 0, shuffled: Boolean = false): Boolean {
        var mapped = playable(items)
        if (mapped.isEmpty()) return false
        var start = startIndex.coerceIn(0, mapped.lastIndex)
        if (shuffled) {
            mapped = mapped.shuffled()
            start = 0
        }
        withController { c ->
            c.setMediaItems(mapped.map(MediaItems::from), start, 0)
            if (shuffled) c.shuffleModeEnabled = true
            c.prepare()
            c.play()
        }
        return true
    }

    /** Append before any autoplay suggestions, like the desktop queue. */
    fun addToQueue(items: List<MusicItem>): Int {
        val mapped = playable(items)
        if (mapped.isEmpty()) return 0
        withController { c ->
            val q = state.queue
            val firstAuto = q.indices.firstOrNull { it > state.queueIndex && q[it].autoplay } ?: q.size
            c.addMediaItems(firstAuto, mapped.map(MediaItems::from))
            if (q.isEmpty()) c.prepare()
        }
        return mapped.size
    }

    fun playNext(items: List<MusicItem>): Int {
        val mapped = playable(items)
        if (mapped.isEmpty()) return 0
        withController { c ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(mapped.map(MediaItems::from))
                c.prepare()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, mapped.map(MediaItems::from))
            }
        }
        return mapped.size
    }

    fun playQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.prepare()
            c.play()
        }
    }

    fun playPause() = withController { c ->
        if (c.mediaItemCount == 0) return@withController
        when {
            state.error != null || c.playerError != null -> { c.prepare(); c.play() }
            c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING) -> c.pause()
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            else -> { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        }
    }

    fun next() = withController { c ->
        if (c.repeatMode == Player.REPEAT_MODE_ONE) {
            c.seekTo(0)
            c.play()
        } else if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.play()
        }
    }

    fun previous() = withController { c ->
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seek(ms: Long) = withController { it.seekTo(ms) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun toggleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
