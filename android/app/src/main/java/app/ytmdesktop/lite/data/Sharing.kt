package app.ytmdesktop.lite.data

import android.net.Uri

/** Same link formats as src/lib/sharing.ts. */
object Sharing {
    enum class Destination(val label: String) { player("YTMD Lite"), music("YouTube Music"), youtube("YouTube") }

    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val playlistIdPattern = Regex("^[A-Za-z0-9_-]{2,200}$")
    private const val SHARE_PAGE = "https://mudesigns.github.io/ytmd-lite/"

    private fun normalizedPlaylistId(value: String): String? =
        value.removePrefix("VL").takeIf { playlistIdPattern.matches(it) && it != "LM" }

    fun shareablePlaylistId(item: MusicItem?): String? {
        if (item == null || item.type != ItemType.playlist) return null
        return normalizedPlaylistId(item.playlistId ?: item.browseId ?: item.id)
    }

    fun songUrl(videoId: String, destination: Destination): String {
        require(videoIdPattern.matches(videoId)) { "This song has no valid YouTube video ID." }
        return when (destination) {
            Destination.player -> "$SHARE_PAGE?song=$videoId"
            Destination.music -> "https://music.youtube.com/watch?v=$videoId"
            Destination.youtube -> "https://www.youtube.com/watch?v=$videoId"
        }
    }

    fun playlistUrl(playlistId: String, destination: Destination): String {
        val id = requireNotNull(normalizedPlaylistId(playlistId)) { "This playlist has no shareable YouTube playlist ID." }
        return when (destination) {
            Destination.player -> "$SHARE_PAGE?playlist=$id"
            Destination.music -> "https://music.youtube.com/playlist?list=$id"
            Destination.youtube -> "https://www.youtube.com/playlist?list=$id"
        }
    }

    sealed interface Link {
        data class Song(val videoId: String) : Link
        data class Playlist(val playlistId: String) : Link
    }

    /** Parse ytmd-lite://song/<id>, ytmd-lite://playlist/<id>, or the HTTPS share page. */
    fun parse(uri: Uri?): Link? {
        uri ?: return null
        when (uri.scheme) {
            "ytmd-lite" -> {
                val id = uri.pathSegments.singleOrNull() ?: return null
                return when (uri.host) {
                    "song" -> id.takeIf { videoIdPattern.matches(it) }?.let(Link::Song)
                    "playlist" -> normalizedPlaylistId(id)?.let(Link::Playlist)
                    else -> null
                }
            }
            "https" -> {
                if (uri.host != "mudesigns.github.io") return null
                uri.getQueryParameter("song")?.takeIf { videoIdPattern.matches(it) }?.let { return Link.Song(it) }
                uri.getQueryParameter("playlist")?.let(::normalizedPlaylistId)?.let { return Link.Playlist(it) }
            }
        }
        return null
    }
}
