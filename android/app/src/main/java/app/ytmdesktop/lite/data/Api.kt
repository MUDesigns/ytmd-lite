package app.ytmdesktop.lite.data

import app.ytmdesktop.lite.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val status: Int = 0) : Exception(message)

enum class SearchFilter(val label: String) { all("all"), songs("songs"), artists("artists"), albums("albums"), playlists("playlists"), library("library") }

/** Client for the shared python-backend. Port of src/lib/api.ts. */
object Api {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val moodColors = listOf(
        "#e57373", "#f06292", "#ba68c8", "#9575cd", "#7986cb", "#64b5f6", "#4fc3f7",
        "#4dd0e1", "#4db6ac", "#81c784", "#aed581", "#ffb74d", "#ff8a65", "#a1887f",
    )

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val local = req.url.host == "127.0.0.1"
            chain.proceed(if (local) req.newBuilder().header("X-YTMD-Token", Backend.token).build() else req)
        }
        .build()

    private suspend fun baseUrl(): String {
        Backend.baseUrl?.let { return it }
        val state = withTimeout(90_000) { Backend.state.first { it !is Backend.State.Starting } }
        return when (state) {
            is Backend.State.Ready -> state.baseUrl
            is Backend.State.Failed -> throw ApiException("Catalog API failed to start: ${state.message}")
            Backend.State.Starting -> throw ApiException("Catalog API is still starting")
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) = cont.resume(response)
        })
        cont.invokeOnCancellation { cancel() }
    }

    private suspend fun api(path: String, body: JsonElement? = null, method: String? = null, timeoutMs: Long? = null): JsonElement {
        val url = baseUrl() + path
        val builder = Request.Builder().url(url)
        when {
            body != null -> builder.method(method ?: "POST", body.toString().toRequestBody("application/json".toMediaType()))
            method != null -> builder.method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
        }
        val call = http.newCall(builder.build())
        if (timeoutMs != null) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val res = try {
            call.await()
        } catch (e: IOException) {
            throw ApiException(if (call.isCanceled()) "Request timed out" else "Catalog API unreachable — ${e.message}")
        }
        val text = withContext(Dispatchers.IO) { res.use { it.body.string() } }
        val data = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonObject(emptyMap()) }
        val obj = data as? JsonObject
        if (!res.isSuccessful) {
            throw ApiException(obj?.str("error") ?: obj?.str("message") ?: "HTTP ${res.code}", res.code)
        }
        val err = obj?.get("error")
        if (err != null && err !is JsonNull && !(err is JsonPrimitive && (err.content.isEmpty() || err.content == "false"))) {
            throw ApiException((err as? JsonPrimitive)?.content ?: err.toString())
        }
        return data
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        if (v is JsonNull) return null
        return v.content.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.arr(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject.with(vararg pairs: Pair<String, String?>): JsonObject =
        JsonObject(this + pairs.associate { (k, v) -> k to (v?.let { JsonPrimitive(it) } ?: JsonNull) })

    private fun artistsLine(artists: JsonElement?): String = when (artists) {
        is JsonPrimitive -> if (artists is JsonNull) "" else artists.content
        is JsonArray -> artists.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.content
                is JsonObject -> it.str("name")
                else -> null
            }
        }.filter { it.isNotEmpty() }.joinToString(", ")
        else -> ""
    }

    fun parseArtistLinks(raw: JsonObject): List<ArtistLink> {
        val fromLinks = raw["artistLinks"] as? JsonArray
        if (!fromLinks.isNullOrEmpty()) {
            return fromLinks.mapNotNull { a ->
                val o = a as? JsonObject ?: return@mapNotNull null
                val name = o.str("name")?.trim().orEmpty()
                if (name.isEmpty()) null else ArtistLink(name, (o.str("browseId") ?: o.str("id"))?.trim()?.ifEmpty { null })
            }
        }
        val primary = raw.str("artistBrowseId") ?: raw.str("browseId")?.takeIf { it.startsWith("UC") }
        val artists = raw["artists"]
        if (artists is JsonArray && artists.any { it is JsonObject }) {
            return artists.mapIndexedNotNull { i, a ->
                val o = a as? JsonObject ?: return@mapIndexedNotNull null
                val name = o.str("name")?.trim().orEmpty()
                if (name.isEmpty()) return@mapIndexedNotNull null
                ArtistLink(name, (o.str("browseId") ?: o.str("id"))?.trim()?.ifEmpty { null } ?: if (i == 0) primary else null)
            }
        }
        val line = artistsLine(artists)
        if (line.isEmpty()) return emptyList()
        return line.split(",").mapIndexed { i, name -> ArtistLink(name.trim(), if (i == 0) primary else null) }
            .filter { it.name.isNotEmpty() }
    }

    private fun pickThumbUrl(raw: JsonObject): String {
        when (val t = raw["thumbnail"]) {
            is JsonPrimitive -> if (t !is JsonNull && t.content.isNotEmpty()) return t.content
            is JsonObject -> t.str("url")?.let { return it }
            else -> {}
        }
        val list = raw["thumbnails"] as? JsonArray ?: return ""
        for (t in list.reversed()) {
            when (t) {
                is JsonPrimitive -> if (t !is JsonNull && t.content.isNotEmpty()) return t.content
                is JsonObject -> t.str("url")?.let { return it }
                else -> {}
            }
        }
        return ""
    }

    private fun colorFor(title: String): String {
        var h = 0L
        for (c in title) h = (h * 31 + c.code) and 0xffffffffL
        return moodColors[(h % moodColors.size).toInt()]
    }

    /** Map a backend item to MusicItem (same rules as mapItem in api.ts). */
    fun mapItem(raw: JsonObject): MusicItem {
        val typeRaw = raw.str("type")?.lowercase().orEmpty()
        val browseId = raw.str("browseId")
        var playlistId = raw.str("playlistId")
        if (playlistId?.startsWith("VL") == true) playlistId = playlistId.drop(2)
        if (playlistId == null && browseId?.startsWith("VL") == true) playlistId = browseId.drop(2)
        if (playlistId == null && browseId == "LM") playlistId = "LM"
        val videoId = raw.str("videoId")

        val type = when {
            playlistId == "LM" -> ItemType.playlist
            typeRaw == "album" || typeRaw == "single" || typeRaw == "ep" -> ItemType.album
            typeRaw == "artist" -> ItemType.artist
            typeRaw == "playlist" || typeRaw == "podcast" -> ItemType.playlist
            typeRaw == "mood" -> ItemType.mood
            browseId?.startsWith("UC") == true -> ItemType.artist
            browseId?.startsWith("VL") == true || playlistId != null -> ItemType.playlist
            browseId?.startsWith("MPRE") == true || browseId?.startsWith("OLAK5") == true || browseId?.startsWith("MPSP") == true -> ItemType.album
            typeRaw == "song" || typeRaw == "video" || videoId != null -> ItemType.song
            browseId != null -> ItemType.album
            else -> ItemType.song
        }
        val isCollection = type == ItemType.album || type == ItemType.playlist || type == ItemType.artist ||
            type == ItemType.mood || playlistId != null
        val playableVideoId = if (isCollection) null else videoId
        val thumb = pickThumbUrl(raw)
        val title = raw.str("title") ?: raw.str("artist") ?: "Untitled"
        val artistLinks = parseArtistLinks(raw)
        val artistBrowseId = raw.str("artistBrowseId") ?: artistLinks.firstOrNull { it.browseId != null }?.browseId
        val subtitle = raw.str("subtitle")
            ?: artistsLine(raw["artists"]).ifEmpty { null }
            ?: artistLinks.joinToString(", ") { it.name }.ifEmpty { null }
            ?: raw.str("year")
            ?: raw.str("count")?.let { "$it tracks" }
        return MusicItem(
            type = type,
            id = playableVideoId ?: browseId ?: playlistId ?: title,
            title = title,
            subtitle = subtitle,
            thumbnails = if (thumb.isNotEmpty()) listOf(thumb) else emptyList(),
            browseId = browseId,
            playlistId = playlistId,
            videoId = playableVideoId,
            params = raw.str("params"),
            color = raw.str("color"),
            artistBrowseId = artistBrowseId,
            artistLinks = artistLinks.ifEmpty { null },
            album = raw.str("album"),
            albumId = raw.str("albumBrowseId"),
        )
    }

    private fun mapTrack(t: JsonObject): MusicItem = mapItem(
        buildJsonObject {
            put("type", "song")
            listOf("videoId", "title", "artists", "artistBrowseId", "artistLinks", "album", "albumBrowseId", "thumbnail")
                .forEach { k -> t[k]?.let { put(k, it) } }
        },
    )

    private fun uniqueItems(items: List<MusicItem>): List<MusicItem> {
        val seen = HashSet<String>()
        return items.filter { seen.add(it.id) }
    }

    private fun shelf(id: String, title: String, items: List<MusicItem>) =
        MusicItem(type = ItemType.shelf, id = id, title = title, items = items)

    // ── Endpoints ────────────────────────────────────────────────────────────

    suspend fun validateAuth(): AuthState = try {
        val r = api("/auth/validate") as JsonObject
        val valid = (r["valid"] as? JsonPrimitive)?.booleanOrNull ?: throw ApiException("Invalid auth response")
        AuthState(valid, r.str("profile"), r.str("reason"), r.str("type"))
    } catch (e: Exception) {
        // A failed check tells us nothing about the saved Google session.
        AuthState(null, reason = "api_unreachable")
    }

    suspend fun cookieLogin(cookie: String, userAgent: String, delegatedSessionId: String?) {
        api("/auth/cookie-login", buildJsonObject {
            put("cookie", cookie)
            put("user_agent", userAgent)
            put("profile_name", "default")
            put("delegated_session_id", delegatedSessionId.orEmpty())
        }, timeoutMs = 60_000)
    }

    /** Local profile: anonymous YTMusic for home, search, and radio without a Google account. */
    suspend fun createGuestProfile() {
        api("/auth/local-create", buildJsonObject { put("displayName", "Guest") })
    }

    suspend fun logout() {
        api("/auth/logout", JsonObject(emptyMap()))
    }

    suspend fun loadSharedSong(videoId: String): MusicItem {
        val data = api("/song/meta/${enc(videoId)}", timeoutMs = 15_000) as JsonObject
        if (data.str("title") == null) throw ApiException("This shared song is unavailable.")
        return mapTrack(data.with("videoId" to videoId))
    }

    suspend fun loadHome(): BrowseResult {
        val data = api("/home") as JsonObject
        val shelves = data.arr("sections").mapIndexed { i, sec ->
            shelf("home-$i-${sec.str("title").orEmpty()}", sec.str("title") ?: "Shelf", sec.arr("items").map(::mapItem))
        }
        return BrowseResult(title = "Home", shelves = shelves)
    }

    /** Build discovery from personal seeds, never from unrelated genre charts. */
    suspend fun loadExplore(heardIds: List<String>): BrowseResult = coroutineScope {
        val heard = heardIds.toSet()
        var seeds = heardIds.reversed().distinct().take(3).map { it to "" }
        val fromLikes = seeds.isEmpty()
        if (fromLikes) {
            val liked = api("/liked?limit=30", timeoutMs = 15_000) as JsonObject
            seeds = uniqueItems(liked.arr("tracks").map(::mapTrack)).filter { it.videoId != null }
                .take(3).map { it.videoId!! to it.title }
        }
        if (seeds.isEmpty()) {
            return@coroutineScope BrowseResult(
                title = "Discover",
                subtitle = "Play some songs or like a few favorites to build your Discover page.",
            )
        }
        val radios = seeds.map { (id, _) -> async { runCatching { loadSongRadio(id) } } }.awaitAll()
        if (radios.all { it.isFailure }) throw ApiException("Could not load recommendations. Try refreshing Discover.")
        val shelves = mutableListOf<MusicItem>()
        val seen = HashSet(heard + seeds.map { it.first })
        val artists = LinkedHashMap<String, String>()
        radios.forEachIndexed { i, result ->
            val tracks = result.getOrNull() ?: return@forEachIndexed
            val (seedId, seedTitle) = seeds[i]
            val source = tracks.find { it.videoId == seedId }
            source?.artistBrowseId?.let { artists[it] = source.subtitle ?: "this artist" }
            val items = tracks.filter { it.videoId != null && it.videoId !in seen }.take(12)
            items.forEach { seen.add(it.videoId!!) }
            if (items.isNotEmpty()) {
                val name = source?.title ?: seedTitle.ifEmpty { "a recent favorite" }
                shelves += shelf("discover-$seedId", "Because you ${if (fromLikes) "like" else "played"} $name", items)
            }
        }
        val related = artists.entries.map { (browseId, name) ->
            async {
                runCatching {
                    val data = api("/artist/${enc(browseId)}", timeoutMs = 15_000) as JsonObject
                    name to uniqueItems(data.arr("related").map { mapItem(it.with("type" to "artist")) })
                }
            }
        }.awaitAll()
        val seenArtists = HashSet(artists.keys)
        related.forEachIndexed { i, result ->
            val (name, list) = result.getOrNull() ?: return@forEachIndexed
            val items = list.filter { it.browseId != null && it.browseId !in seenArtists }.take(8)
            items.forEach { seenArtists.add(it.browseId!!) }
            if (items.isNotEmpty()) shelves += shelf("discover-artists-$i", "Artists related to $name", items)
        }
        BrowseResult(
            title = "Discover",
            shelves = shelves,
            items = shelves.flatMap { it.items.orEmpty() }.filter { it.type == ItemType.song && it.videoId != null },
            subtitle = if (shelves.isNotEmpty()) {
                "Inspired by your ${if (fromLikes) "liked songs" else "recent listening on this device"}. Tracks already heard here are hidden."
            } else {
                "No new recommendations from these favorites yet. Try listening to another artist, then refresh."
            },
        )
    }

    suspend fun loadSongRadio(videoId: String): List<MusicItem> {
        val data = api("/radio/seed?videoId=${enc(videoId)}", timeoutMs = 15_000) as JsonObject
        return uniqueItems(data.arr("tracks").map(::mapTrack).filter { it.videoId != null })
    }

    suspend fun loadArtistRadio(browseId: String): List<MusicItem> {
        val artist = browseDetail(DetailTarget(browseId = browseId))
        val tracks = if (artist.radioId != null) {
            val data = api("/radio/${enc(artist.radioId)}", timeoutMs = 15_000) as JsonObject
            uniqueItems(data.arr("tracks").map(::mapTrack).filter { it.videoId != null })
        } else {
            artist.items.firstOrNull { it.videoId != null }?.let { loadSongRadio(it.videoId!!) } ?: emptyList()
        }
        if (tracks.isEmpty()) throw ApiException("No radio tracks are available for this artist. Try another artist.")
        return tracks
    }

    suspend fun loadLibrary(): BrowseResult = coroutineScope {
        val empty = JsonObject(emptyMap())
        val playlists = async { runCatching { api("/library/playlists") as JsonObject }.getOrDefault(empty) }
        val albums = async { runCatching { api("/library/albums") as JsonObject }.getOrDefault(empty) }
        val artists = async { runCatching { api("/library/artists") as JsonObject }.getOrDefault(empty) }
        val shelves = mutableListOf<MusicItem>()
        val pl = uniqueItems(playlists.await().arr("playlists").map { mapItem(it.with("type" to "playlist")) }).toMutableList()
        // Liked Songs has its own API and may be absent from library playlists.
        if (pl.none { it.playlistId == "LM" }) {
            pl.add(0, mapItem(buildJsonObject { put("type", "playlist"); put("playlistId", "LM"); put("title", "Liked Songs") }))
        }
        shelves += shelf("lib-playlists", "Playlists", pl)
        val al = uniqueItems(albums.await().arr("albums").map { mapItem(it.with("type" to "album")) })
        if (al.isNotEmpty()) shelves += shelf("lib-albums", "Albums", al)
        val ar = uniqueItems(artists.await().arr("artists").map {
            mapItem(it.with("type" to "artist", "title" to (it.str("artist") ?: it.str("title"))))
        })
        if (ar.isNotEmpty()) shelves += shelf("lib-artists", "Artists", ar)
        BrowseResult(title = "Library", shelves = shelves)
    }

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.all): BrowseResult {
        if (filter == SearchFilter.library) return searchLibrary(query)
        val path = buildString {
            append("/search?q=").append(enc(query))
            if (filter != SearchFilter.all) append("&filter=").append(filter.name)
        }
        val data = api(path) as JsonObject
        val items = uniqueItems(data.arr("results").map(::mapItem))
        val title = if (filter == SearchFilter.all) "Results" else filter.name.replaceFirstChar { it.uppercase() }
        return BrowseResult(
            title = "Search",
            shelves = if (items.isNotEmpty()) listOf(shelf("search-${filter.name}", title, items)) else emptyList(),
            items = items,
        )
    }

    /** Filter the signed-in library (playlists / albums / artists / liked) by name. */
    suspend fun searchLibrary(query: String): BrowseResult = coroutineScope {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@coroutineScope BrowseResult(title = "Library")
        fun match(item: MusicItem) = item.title.lowercase().contains(q) ||
            item.subtitle.orEmpty().lowercase().contains(q) ||
            item.artistLinks.orEmpty().any { it.name.lowercase().contains(q) }
        val lib = async { loadLibrary() }
        val liked = async { runCatching { api("/liked?limit=500") as JsonObject }.getOrDefault(JsonObject(emptyMap())) }
        val shelves = mutableListOf<MusicItem>()
        val likedItems = liked.await().arr("tracks").map(::mapTrack).filter(::match)
        if (likedItems.isNotEmpty()) shelves += shelf("lib-search-liked", "Liked songs", likedItems)
        for (s in lib.await().shelves) {
            val items = s.items.orEmpty().filter(::match)
            if (items.isNotEmpty()) shelves += s.copy(id = "lib-search-${s.id}", items = items)
        }
        BrowseResult(title = "Library", shelves = shelves, items = shelves.flatMap { it.items.orEmpty() })
    }

    suspend fun searchSuggestions(query: String): List<String> {
        val data = api("/search/suggestions?q=${enc(query)}") as JsonObject
        return (data["suggestions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }

    /** Look up an artist channel id by name when YouTube omitted it from the shelf/track. */
    suspend fun resolveArtist(name: String): ArtistLink? {
        val q = name.trim()
        if (q.isEmpty()) return null
        val data = api("/search?q=${enc(q)}&filter=artists") as JsonObject
        for (raw in data.arr("results")) {
            val item = mapItem(raw.with("type" to "artist"))
            if (item.browseId?.startsWith("UC") == true) return ArtistLink(item.title.ifEmpty { q }, item.browseId)
        }
        val mixed = api("/search?q=${enc(q)}") as JsonObject
        for (raw in mixed.arr("results")) {
            val item = mapItem(raw)
            if (item.type == ItemType.artist && item.browseId?.startsWith("UC") == true) return ArtistLink(item.title.ifEmpty { q }, item.browseId)
        }
        return null
    }

    suspend fun likeSong(videoId: String, like: Boolean, item: QueueItem? = null) {
        api("/like/${enc(videoId)}", buildJsonObject {
            put("rating", if (like) "LIKE" else "INDIFFERENT")
            if (item != null) {
                put("title", item.title)
                put("artists", item.author)
                put("album", item.album.orEmpty())
                put("thumbnail", item.thumbnails.firstOrNull().orEmpty())
            }
        })
    }

    suspend fun getLikedIds(): Set<String> = try {
        val data = api("/liked/ids") as JsonObject
        (data["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    suspend fun loadPlaylistOptions(): List<PlaylistOption> {
        val data = api("/library/playlists") as JsonObject
        val seen = HashSet<String>()
        return data.arr("playlists").mapNotNull { p ->
            val id = p.str("playlistId")?.removePrefix("VL") ?: return@mapNotNull null
            if (id == "LM" || (p["isEditable"] as? JsonPrimitive)?.booleanOrNull == false || !seen.add(id)) return@mapNotNull null
            PlaylistOption(id, p.str("title") ?: "Untitled playlist")
        }
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: QueueItem) {
        val result = api("/playlist/${enc(playlistId)}/add", buildJsonObject {
            putJsonArray("videoIds") { add(JsonPrimitive(track.videoId)) }
            putJsonArray("tracks") {
                add(buildJsonObject {
                    put("videoId", track.videoId)
                    put("title", track.title)
                    put("artists", track.author)
                    put("album", track.album.orEmpty())
                    put("thumbnail", track.thumbnails.firstOrNull().orEmpty())
                })
            }
        }) as JsonObject
        if ((result["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw ApiException("Could not add this song to the playlist.")
    }

    suspend fun setArtistSubscribed(browseId: String, subscribed: Boolean, channelId: String?) {
        api("/artist/${enc(browseId)}/${if (subscribed) "subscribe" else "unsubscribe"}", buildJsonObject {
            put("channelId", channelId ?: browseId)
        })
    }

    suspend fun browseDetail(target: DetailTarget): BrowseResult {
        if (target.params != null) {
            val data = api("/mood/playlists?params=${enc(target.params)}")
            val list = (data as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
            return BrowseResult(title = target.title ?: "Mood", items = list.map(::mapItem), kind = BrowseKind.mood)
        }

        val bid = target.browseId.orEmpty()
        if (target.playlistId != null || bid.startsWith("VL") || bid == "LM") {
            val pid = (target.playlistId ?: bid).removePrefix("VL")
            // Radio / auto-mix ids are infinite watch playlists — use /radio instead.
            val isRadioMix = pid.startsWith("RD")
            val data = api("${if (isRadioMix) "/radio" else "/playlist"}/${enc(pid)}") as JsonObject
            val items = data.arr("tracks").map(::mapTrack)
            val cover = pickThumbUrl(data).ifEmpty { items.firstNotNullOfOrNull { it.thumbnails.firstOrNull() }.orEmpty() }
            val links = parseArtistLinks(data)
            return BrowseResult(
                title = data.str("title") ?: target.title ?: if (isRadioMix) "Mix" else "Playlist",
                subtitle = artistsLine(data["artists"]).ifEmpty { null } ?: data.str("author"),
                items = items,
                thumbnails = listOfNotNull(cover.ifEmpty { null }),
                artistBrowseId = data.str("artistBrowseId") ?: links.firstOrNull()?.browseId,
                artistLinks = links.ifEmpty { null },
                kind = BrowseKind.playlist,
            )
        }

        if (bid.startsWith("UC")) {
            val data = api("/artist/${enc(bid)}", timeoutMs = 15_000) as JsonObject
            val tracks = data.arr("tracks").map(::mapTrack)
            val releases = linkedMapOf<String, MutableList<MusicItem>>(
                "albums" to mutableListOf(), "eps" to mutableListOf(), "singles" to mutableListOf(), "other" to mutableListOf(),
            )
            for ((list, fallback) in listOf(data.arr("albums") to "albums", data.arr("singles") to "other")) {
                for (release in list) {
                    val label = (release.str("releaseType") ?: release.str("type")).orEmpty().trim().lowercase()
                    val group = when (label) { "ep" -> "eps"; "single" -> "singles"; "album" -> "albums"; else -> fallback }
                    releases.getValue(group).add(mapItem(release.with("type" to "album")))
                }
            }
            val shelves = mutableListOf<MusicItem>()
            for ((group, title) in listOf("albums" to "Albums", "eps" to "EPs", "singles" to "Singles", "other" to "Singles & EPs")) {
                val items = releases.getValue(group)
                if (items.isNotEmpty()) shelves += shelf("artist-$group", title, items)
            }
            val related = uniqueItems(data.arr("related").map { mapItem(it.with("type" to "artist")) }).filter { it.browseId != null }
            if (related.isNotEmpty()) shelves += shelf("artist-related", "Fans might also like", related)
            val cover = pickThumbUrl(data).ifEmpty {
                (tracks + shelves.flatMap { it.items.orEmpty() }).firstNotNullOfOrNull { it.thumbnails.firstOrNull() }.orEmpty()
            }
            val listeners = data.str("monthlyListeners")?.trim().orEmpty()
            val subs = data.str("subscribers")?.trim().orEmpty()
            val meta = when {
                listeners.isNotEmpty() -> if (listeners.contains("listener", true)) listeners else "$listeners monthly listeners"
                subs.isNotEmpty() -> if (subs.contains("subscriber", true)) subs else "$subs subscribers"
                else -> null
            }
            return BrowseResult(
                title = data.str("name") ?: target.title ?: "Artist",
                shelves = shelves,
                items = tracks,
                thumbnails = listOfNotNull(cover.ifEmpty { null }),
                meta = meta,
                kind = BrowseKind.artist,
                radioId = data.str("radioId"),
                subscribed = (data["subscribed"] as? JsonPrimitive)?.booleanOrNull == true,
                channelId = data.str("channelId") ?: bid,
            )
        }

        if (bid.isNotEmpty()) {
            val data = api("/album/${enc(bid)}") as JsonObject
            val albumTitle = data.str("title") ?: target.title
            val items = data.arr("tracks").map { t ->
                mapTrack(t.with("album" to (t.str("album") ?: albumTitle), "albumBrowseId" to (t.str("albumBrowseId") ?: bid)))
            }
            val cover = pickThumbUrl(data).ifEmpty { items.firstNotNullOfOrNull { it.thumbnails.firstOrNull() }.orEmpty() }
            val links = parseArtistLinks(data)
            return BrowseResult(
                title = albumTitle ?: "Album",
                subtitle = artistsLine(data["artists"]).ifEmpty { links.joinToString(", ") { it.name } },
                items = items,
                thumbnails = listOfNotNull(cover.ifEmpty { null }),
                artistBrowseId = data.str("artistBrowseId") ?: links.firstOrNull()?.browseId,
                artistLinks = links.ifEmpty { null },
                kind = BrowseKind.album,
            )
        }
        throw ApiException("Nothing to browse")
    }

    /** Stream through the backend's yt-dlp proxy; used only when NewPipe can't resolve a track. */
    suspend fun warmBackendStream(videoId: String): String {
        val data = api("/audio-stream/${enc(videoId)}/warm", timeoutMs = 30_000) as JsonObject
        if ((data["premium_only"] as? JsonPrimitive)?.booleanOrNull == true) throw ApiException("This track requires YouTube Premium")
        if ((data["ok"] as? JsonPrimitive)?.booleanOrNull != true) throw ApiException("Could not resolve audio stream")
        return "${baseUrl()}/audio-stream/${enc(videoId)}"
    }

    fun thumbUrl(thumbnails: List<String>?, size: Int? = null): String? {
        val raw = thumbnails?.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (size == null) return raw
        // Preserve Google's crop/format suffix; only resize known thumbnail hosts.
        return runCatching {
            val url = raw.toHttpUrl()
            if (Regex("^(?:lh\\d+\\.googleusercontent\\.com|yt3\\.(?:ggpht\\.com|googleusercontent\\.com))$").matches(url.host)) {
                val px = minOf(1024, size)
                raw.replace(Regex("=w\\d+-h\\d+"), "=w$px-h$px")
            } else raw
        }.getOrDefault(raw)
    }

    fun moodColor(item: MusicItem): String = item.color ?: colorFor(item.title)
}
