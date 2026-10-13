package app.ytmdesktop.lite.lastfm

import android.util.Log
import app.ytmdesktop.lite.BuildConfig
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest

/** Last.fm scrobbling, same flow as src-tauri/src/lastfm.rs (desktop-style browser approval). */
object LastFm {
    private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
    private const val TAG = "LastFm"

    data class Status(
        val hasCredentials: Boolean,
        val enabled: Boolean,
        val authenticated: Boolean,
        val username: String?,
        val signingIn: Boolean = false,
        val scrobblePercent: Int,
        val lastError: String? = null,
        val nowPlaying: String? = null,
        val lastScrobble: String? = null,
    )

    private val apiKey get() = BuildConfig.LASTFM_API_KEY
    private val secret get() = BuildConfig.LASTFM_SECRET
    private var pendingToken: String? = null

    private val _status = MutableStateFlow(load())
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun load() = Status(
        hasCredentials = BuildConfig.LASTFM_API_KEY.isNotEmpty() && BuildConfig.LASTFM_SECRET.isNotEmpty(),
        enabled = Prefs.getBoolean("lastfm.enabled", false),
        authenticated = Prefs.getString("lastfm.session") != null,
        username = Prefs.getString("lastfm.username"),
        scrobblePercent = Prefs.getInt("lastfm.percent", 50),
    )

    fun setEnabled(enabled: Boolean) {
        Prefs.putBoolean("lastfm.enabled", enabled)
        _status.update { it.copy(enabled = enabled) }
    }

    fun setScrobblePercent(percent: Int) {
        val p = percent.coerceIn(50, 95)
        Prefs.putInt("lastfm.percent", p)
        _status.update { it.copy(scrobblePercent = p) }
    }

    fun logout() {
        Prefs.putString("lastfm.session", null)
        Prefs.putString("lastfm.username", null)
        _status.update { it.copy(authenticated = false, username = null, signingIn = false) }
    }

    private fun sign(params: Map<String, String>): String {
        val raw = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun call(params: Map<String, String>, post: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val signed = params + ("api_key" to apiKey)
        val all = signed + ("api_sig" to sign(signed)) + ("format" to "json")
        val request = if (post) {
            val form = FormBody.Builder().apply { all.forEach { (k, v) -> add(k, v) } }.build()
            Request.Builder().url(API_ROOT).post(form).build()
        } else {
            val url = API_ROOT.toHttpUrl().newBuilder().apply { all.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            Request.Builder().url(url).build()
        }
        Api.http.newCall(request).execute().use { res ->
            val body = Json.parseToJsonElement(res.body.string()).jsonObject
            body["error"]?.let { throw IllegalStateException(body["message"]?.jsonPrimitive?.content ?: "Last.fm error $it") }
            body
        }
    }

    /** Returns the approval URL to open in the browser. */
    suspend fun beginSignIn(): String {
        val token = call(mapOf("method" to "auth.getToken"), post = false)["token"]!!.jsonPrimitive.content
        pendingToken = token
        _status.update { it.copy(signingIn = true, lastError = null) }
        return "https://www.last.fm/api/auth/?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
    }

    /** Call after the user returns from the browser; succeeds once they approved. */
    suspend fun completeSignIn(): Boolean {
        val token = pendingToken ?: return false
        return try {
            val session = call(mapOf("method" to "auth.getSession", "token" to token), post = false)["session"]!!.jsonObject
            Prefs.putString("lastfm.session", session["key"]!!.jsonPrimitive.content)
            Prefs.putString("lastfm.username", session["name"]?.jsonPrimitive?.content)
            Prefs.putBoolean("lastfm.enabled", true)
            pendingToken = null
            _status.value = load()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cancelSignIn() {
        pendingToken = null
        _status.update { it.copy(signingIn = false) }
    }

    data class Track(val artist: String, val title: String, val album: String?, val durationSec: Long)

    private suspend fun send(method: String, track: Track, extra: Map<String, String> = emptyMap()) {
        val st = _status.value
        val sk = Prefs.getString("lastfm.session") ?: return
        if (!st.hasCredentials || !st.enabled) return
        val params = buildMap {
            put("method", method)
            put("sk", sk)
            put("artist", track.artist)
            put("track", track.title)
            track.album?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
            if (track.durationSec > 0) put("duration", track.durationSec.toString())
            putAll(extra)
        }
        try {
            call(params, post = true)
            _status.update { it.copy(lastError = null) }
        } catch (e: Exception) {
            Log.w(TAG, "$method failed", e)
            _status.update { it.copy(lastError = e.message) }
        }
    }

    suspend fun nowPlaying(track: Track) {
        send("track.updateNowPlaying", track)
        _status.update { it.copy(nowPlaying = "${track.artist} — ${track.title}") }
    }

    suspend fun scrobble(track: Track, startedAtSec: Long) {
        send("track.scrobble", track, mapOf("timestamp" to startedAtSec.toString()))
        _status.update { it.copy(lastScrobble = "${track.artist} — ${track.title}") }
    }
}
