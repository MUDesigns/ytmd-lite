package app.ytmdesktop.lite.data

import kotlinx.serialization.Serializable

/** Mirrors src/lib/types.ts so behavior matches the desktop app. */
enum class ItemType { song, album, artist, playlist, shelf, mood }

@Serializable
data class ArtistLink(val name: String, val browseId: String? = null)

data class MusicItem(
    val type: ItemType,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnails: List<String> = emptyList(),
    val browseId: String? = null,
    val playlistId: String? = null,
    val videoId: String? = null,
    val params: String? = null,
    val color: String? = null,
    val items: List<MusicItem>? = null,
    val artistBrowseId: String? = null,
    val artistLinks: List<ArtistLink>? = null,
    val album: String? = null,
    val albumId: String? = null,
) {
    val isCollection: Boolean
        get() = type == ItemType.album || type == ItemType.playlist || type == ItemType.artist || type == ItemType.mood

    val canPlay: Boolean
        get() = videoId != null || type == ItemType.album || type == ItemType.playlist || playlistId != null || browseId != null

    val artistLine: String
        get() = artistLinks?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: subtitle.orEmpty()
}

enum class BrowseKind { album, playlist, artist, mood, collection }

data class BrowseResult(
    val title: String? = null,
    val shelves: List<MusicItem> = emptyList(),
    val items: List<MusicItem> = emptyList(),
    val thumbnails: List<String> = emptyList(),
    val subtitle: String? = null,
    val artistBrowseId: String? = null,
    val artistLinks: List<ArtistLink>? = null,
    val meta: String? = null,
    val kind: BrowseKind? = null,
    val subscribed: Boolean = false,
    val radioId: String? = null,
    val channelId: String? = null,
)

data class DetailTarget(
    val browseId: String? = null,
    val playlistId: String? = null,
    val title: String? = null,
    val params: String? = null,
)

data class PlaylistOption(val playlistId: String, val title: String)

data class AuthState(val valid: Boolean?, val profile: String? = null, val reason: String? = null, val type: String? = null)

@Serializable
data class QueueItem(
    val videoId: String,
    val title: String,
    val author: String,
    val thumbnails: List<String> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val channelId: String? = null,
    val autoplay: Boolean = false,
)

fun MusicItem.toQueueItem(): QueueItem? {
    val id = videoId ?: return null
    return QueueItem(
        videoId = id,
        title = title,
        author = artistLine,
        thumbnails = thumbnails,
        album = album,
        albumId = albumId,
        channelId = artistBrowseId ?: artistLinks?.firstOrNull { it.browseId != null }?.browseId,
    )
}
