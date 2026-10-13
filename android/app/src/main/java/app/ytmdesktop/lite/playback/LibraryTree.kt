package app.ytmdesktop.lite.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.ytmdesktop.lite.BuildConfig
import app.ytmdesktop.lite.data.Account
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.ApiException
import app.ytmdesktop.lite.data.DetailTarget
import app.ytmdesktop.lite.data.ItemType
import app.ytmdesktop.lite.data.MusicItem
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.QueueItem
import app.ytmdesktop.lite.data.SearchFilter
import app.ytmdesktop.lite.data.toQueueItem
import java.util.Collections

/**
 * Android Auto browse tree over the same catalog API as the phone UI.
 *
 * Browse ids are `auto:` URIs so they never collide with queue items, whose media id is the
 * video id. Songs are listed with an `auto:play` id that remembers the list they came from,
 * so picking one queues the rest of that list after it.
 */
object LibraryTree {
    private const val PREFIX = "auto:"
    const val ROOT = "${PREFIX}root"
    const val HOME = "${PREFIX}home"
    const val LIBRARY = "${PREFIX}library"
    const val EXPLORE = "${PREFIX}explore"
    const val RECENT = "${PREFIX}recent"
    private const val CACHE_TTL_MS = 10L * 60 * 1000

    fun isBrowseId(mediaId: String) = mediaId.startsWith(PREFIX)

    private data class Section(val title: String?, val items: List<MusicItem>)

    private data class Listing(
        val sections: List<Section>,
        /** Offer "Shuffle" above the tracks (albums and playlists). */
        val shuffle: Boolean = false,
        /** Offer "Artist radio" (artist pages). */
        val artistRadio: String? = null,
        /** Shown instead of an empty list. */
        val empty: String = "This list is empty.",
        val generation: Int = Account.generation.value,
        val at: Long = System.currentTimeMillis(),
    ) {
        val songs: List<MusicItem> get() = sections.flatMap { s -> s.items.filter { it.videoId != null } }
    }

    private val cache: MutableMap<String, Listing> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Listing>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>) = size > 40
        },
    )

    // ── Ids ──────────────────────────────────────────────────────────────────

    private fun id(kind: String, vararg params: Pair<String, String?>): String {
        val query = params.filter { it.second != null }
            .joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        return if (query.isEmpty()) "$PREFIX$kind" else "$PREFIX$kind?$query"
    }

    private class Parsed(val kind: String, private val uri: Uri) {
        operator fun get(key: String): String? = uri.getQueryParameter(key)
    }

    private fun parse(mediaId: String): Parsed {
        val rest = mediaId.removePrefix(PREFIX)
        val kind = rest.substringBefore('?')
        // Parse as a hierarchical URI so getQueryParameter works.
        return Parsed(kind, Uri.parse("auto://x/?${rest.substringAfter('?', "")}"))
    }

    private fun detailId(item: MusicItem) = id(
        "detail",
        "b" to item.browseId, "p" to item.playlistId, "m" to item.params, "t" to item.title,
    )

    private fun searchId(query: String) = id("search", "q" to query)

    // ── Browsing ─────────────────────────────────────────────────────────────

    private fun iconUri(name: String) = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/$name")

    private fun folder(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artwork: Uri? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        group: String? = null,
    ) = MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artwork)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(type)
            .setExtras(groupExtras(group))
            .build(),
    ).build()

    private fun action(mediaId: String, title: String, subtitle: String?, icon: String) = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(iconUri(icon))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        ).build()

    /** A non-actionable row, used to explain an empty or failed list. */
    private fun message(title: String, subtitle: String?) = MediaItem.Builder()
        .setMediaId(id("message", "t" to title))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        ).build()

    private fun groupExtras(group: String?): Bundle? =
        group?.let { Bundle().apply { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } }

    /** Root hints: collections as a grid, tracks as a list. */
    fun rootExtras() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun root(): MediaItem = folder(ROOT, "YTMD Lite")

    /** Android Auto shows up to four root children as tabs. */
    private fun tabs() = listOf(
        folder(HOME, "Home", artwork = iconUri("ic_auto_home")),
        folder(LIBRARY, "Library", artwork = iconUri("ic_auto_library")),
        folder(EXPLORE, "Explore", artwork = iconUri("ic_auto_explore")),
        folder(RECENT, "Recent", artwork = iconUri("ic_auto_recent")),
    )

    fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> root()
        else -> tabs().firstOrNull { it.mediaId == mediaId }
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val all = if (parentId == ROOT) tabs() else page(parentId)
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return all
        val from = page.toLong() * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from.toInt(), minOf(all.size, from.toInt() + pageSize))
    }

    suspend fun search(query: String): List<MediaItem> = page(searchId(query))

    /** A loaded list, or a row explaining why it couldn't load (Auto has no room for error screens). */
    private suspend fun page(nodeId: String): List<MediaItem> = try {
        render(nodeId, load(nodeId))
    } catch (e: SignInRequired) {
        listOf(message("Sign in on your phone", e.message))
    } catch (e: Exception) {
        listOf(message("Couldn't load this page", e.message ?: "Check your connection and try again."))
    }

    private fun render(nodeId: String, listing: Listing): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        listing.artistRadio?.let { out += action(id("radio", "b" to it), "Artist radio", "Songs like this artist", "ic_auto_radio") }
        if (listing.shuffle && listing.songs.size > 1) {
            out += action(id("shuffle", "n" to nodeId), "Shuffle", "${listing.songs.size} songs", "ic_auto_shuffle")
        }
        val grouped = listing.sections.count { it.items.isNotEmpty() } > 1
        listing.sections.forEachIndexed { index, section ->
            val group = if (grouped) section.title else null
            for (item in section.items) {
                out += when {
                    item.videoId != null -> song(nodeId, index, item, group)
                    item.isCollection || item.playlistId != null -> folder(
                        detailId(item), item.title, item.artistLine.ifEmpty { null },
                        ArtworkProvider.uriFor(Api.thumbUrl(item.thumbnails, 300)),
                        when (item.type) {
                            ItemType.album -> MediaMetadata.MEDIA_TYPE_ALBUM
                            ItemType.artist -> MediaMetadata.MEDIA_TYPE_ARTIST
                            ItemType.mood -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                            else -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                        },
                        group,
                    )
                    else -> continue
                }
            }
        }
        if (out.isEmpty()) out += message("Nothing here yet", listing.empty)
        return out
    }

    private fun song(nodeId: String, section: Int, item: MusicItem, group: String?) = MediaItem.Builder()
        .setMediaId(id("play", "n" to nodeId, "s" to section.toString(), "v" to item.videoId))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artistLine)
                .setSubtitle(item.artistLine)
                .setAlbumTitle(item.album)
                .setArtworkUri(ArtworkProvider.uriFor(Api.thumbUrl(item.thumbnails, 300)))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(groupExtras(group))
                .build(),
        ).build()

    // ── Loading ──────────────────────────────────────────────────────────────

    private class SignInRequired(message: String) : Exception(message)

    private suspend fun requireSignedIn() {
        if (!Account.signedIn.value) Account.refresh()
        if (!Account.signedIn.value) throw SignInRequired("Your library needs a signed-in Google account.")
    }

    private suspend fun load(nodeId: String, force: Boolean = false): Listing {
        if (nodeId == RECENT) {
            return Listing(listOf(Section(null, Prefs.recentTracks().map(::recentItem))), empty = "Songs you play will show up here.")
        }
        if (!force) {
            cache[nodeId]?.takeIf { it.generation == Account.generation.value && System.currentTimeMillis() - it.at < CACHE_TTL_MS }
                ?.let { return it }
        }
        val listing = try {
            fetch(nodeId)
        } catch (e: ApiException) {
            // Distinguish "no session at all" from an ordinary failure.
            if (Account.refresh().valid == false) {
                throw SignInRequired("Open YTMD Lite on your phone to sign in or browse as a guest.")
            }
            throw e
        }
        cache[nodeId] = listing
        return listing
    }

    private suspend fun fetch(nodeId: String): Listing {
        if (nodeId == HOME) return Listing(Api.loadHome().shelves.map { Section(it.title, it.items.orEmpty()) })
        if (nodeId == LIBRARY) {
            requireSignedIn()
            return Listing(Api.loadLibrary().shelves.map { Section(it.title, it.items.orEmpty()) })
        }
        if (nodeId == EXPLORE) {
            val explore = Api.loadExplore(Prefs.heard())
            return Listing(explore.shelves.map { Section(it.title, it.items.orEmpty()) }, empty = explore.subtitle ?: "No recommendations yet.")
        }
        val p = parse(nodeId)
        return when (p.kind) {
            "search" -> {
                val results = Api.search(p["q"].orEmpty(), SearchFilter.all).items
                Listing(
                    listOf(
                        Section("Songs", results.filter { it.videoId != null }),
                        Section("Artists, albums & playlists", results.filter { it.videoId == null }),
                    ),
                )
            }
            "detail" -> {
                val browseId = p["b"]
                val data = Api.browseDetail(DetailTarget(browseId = browseId, playlistId = p["p"], params = p["m"], title = p["t"]))
                if (browseId?.startsWith("UC") == true) {
                    Listing(
                        listOf(Section("Songs", data.items)) + data.shelves.map { Section(it.title, it.items.orEmpty()) },
                        artistRadio = browseId,
                    )
                } else {
                    Listing(listOf(Section(null, data.items)), shuffle = true)
                }
            }
            else -> throw ApiException("Unknown page")
        }
    }

    private fun recentItem(q: QueueItem) = MusicItem(
        type = ItemType.song,
        id = q.videoId,
        title = q.title,
        subtitle = q.author,
        thumbnails = q.thumbnails,
        videoId = q.videoId,
        album = q.album,
        albumId = q.albumId,
        artistBrowseId = q.channelId,
    )

    // ── Playback ─────────────────────────────────────────────────────────────

    data class Queue(val items: List<MediaItem>, val startIndex: Int = 0)

    private fun queueOf(items: List<MusicItem>, startIndex: Int = 0): Queue {
        val mapped = items.mapNotNull { it.toQueueItem() }
        if (mapped.isEmpty()) throw ApiException("No playable tracks")
        return Queue(mapped.map(MediaItems::from), startIndex.coerceIn(0, mapped.lastIndex))
    }

    /** Expand a playable browse id into a queue. */
    suspend fun resolve(mediaId: String): Queue {
        val p = parse(mediaId)
        return when (p.kind) {
            "play" -> {
                val nodeId = p["n"] ?: throw ApiException("Missing list")
                val section = p["s"]?.toIntOrNull() ?: 0
                val videoId = p["v"]
                fun pick(listing: Listing): Queue? {
                    val songs = listing.sections.getOrNull(section)?.items.orEmpty().filter { it.videoId != null }
                    val index = songs.indexOfFirst { it.videoId == videoId }
                    return if (index < 0) null else queueOf(songs, index)
                }
                pick(load(nodeId)) ?: pick(load(nodeId, force = true)) ?: throw ApiException("This song is no longer in the list")
            }
            "shuffle" -> queueOf(load(p["n"] ?: throw ApiException("Missing list")).songs.shuffled())
            "radio" -> queueOf(Api.loadArtistRadio(p["b"] ?: throw ApiException("Missing artist")))
            "detail" -> {
                val listing = load(mediaId)
                listing.artistRadio?.let { queueOf(Api.loadArtistRadio(it)) } ?: queueOf(listing.songs)
            }
            else -> throw ApiException("Not playable")
        }
    }

    /** Voice requests ("play … on YTMD Lite"): the top search result decides what plays. */
    suspend fun playFromSearch(query: String): Queue {
        if (query.isBlank()) {
            Prefs.recentTracks().firstOrNull()?.let { return queueOf(listOf(recentItem(it))) }
            val home = load(HOME)
            return queueOf(home.sections.firstOrNull { s -> s.items.any { it.videoId != null } }?.items.orEmpty().filter { it.videoId != null })
        }
        val results = Api.search(query, SearchFilter.all).items
        val top = results.firstOrNull { it.videoId != null || it.isCollection } ?: throw ApiException("No results for \"$query\"")
        return when {
            top.videoId != null -> {
                val radio = runCatching { Api.loadSongRadio(top.videoId) }.getOrDefault(emptyList())
                queueOf(listOf(top) + radio.filter { it.videoId != top.videoId })
            }
            top.type == ItemType.artist && top.browseId != null -> queueOf(Api.loadArtistRadio(top.browseId))
            else -> queueOf(
                Api.browseDetail(DetailTarget(browseId = top.browseId, playlistId = top.playlistId, params = top.params, title = top.title))
                    .items,
            )
        }
    }

    /** System playback resumption: pick up with the last played track (autoplay continues from it). */
    fun resumption(): Queue? = Prefs.recentTracks().firstOrNull()?.let { queueOf(listOf(recentItem(it))) }
}
