package app.ytmdesktop.lite.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.QueueItem

/** QueueItem ⇄ MediaItem. Media URIs use ytmd://<videoId> and are resolved lazily at load time. */
object MediaItems {
    const val SCHEME = "ytmd"
    private const val KEY_CHANNEL = "channelId"
    private const val KEY_ALBUM_ID = "albumId"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_THUMBS = "thumbnails"

    fun from(item: QueueItem): MediaItem {
        val extras = Bundle().apply {
            putString(KEY_CHANNEL, item.channelId)
            putString(KEY_ALBUM_ID, item.albumId)
            putBoolean(KEY_AUTOPLAY, item.autoplay)
            putStringArrayList(KEY_THUMBS, ArrayList(item.thumbnails))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.author)
            .setAlbumTitle(item.album)
            // Served through the artwork provider's disk cache, so downloaded music keeps its art offline.
            .setArtworkUri(ArtworkProvider.uriFor(Api.thumbUrl(item.thumbnails, 544)))
            .setExtras(extras)
            .build()
        return withPlaybackUri(
            MediaItem.Builder().setMediaId(item.videoId).setMediaMetadata(metadata).build(),
        )
    }

    /** Controllers strip local configuration over IPC; the session restores it here. */
    fun withPlaybackUri(item: MediaItem): MediaItem = item.buildUpon()
        .setUri(Uri.parse("$SCHEME://${item.mediaId}"))
        .setCustomCacheKey(item.mediaId)
        .build()

    fun toQueueItem(item: MediaItem): QueueItem {
        val m = item.mediaMetadata
        val extras = m.extras
        return QueueItem(
            videoId = item.mediaId,
            title = m.title?.toString().orEmpty(),
            author = m.artist?.toString().orEmpty(),
            thumbnails = extras?.getStringArrayList(KEY_THUMBS).orEmpty(),
            album = m.albumTitle?.toString(),
            albumId = extras?.getString(KEY_ALBUM_ID),
            channelId = extras?.getString(KEY_CHANNEL),
            autoplay = extras?.getBoolean(KEY_AUTOPLAY) == true,
        )
    }
}
