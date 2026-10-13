package app.ytmdesktop.lite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Local settings — the Android counterpart of the desktop app's localStorage keys. */
object Prefs {
    const val DEFAULT_ACCENT = "#a8c7fa"
    private const val RECENT_MAX = 8
    private const val HEARD_MAX = 5000
    private const val RECENT_TRACKS_MAX = 50

    private lateinit var prefs: SharedPreferences
    private val stringList = ListSerializer(String.serializer())

    private val _accent = MutableStateFlow(DEFAULT_ACCENT)
    val accent: StateFlow<String> = _accent.asStateFlow()

    private val _codeTheme = MutableStateFlow(false)
    val codeTheme: StateFlow<Boolean> = _codeTheme.asStateFlow()

    private val _autoplay = MutableStateFlow(true)
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ytmd", Context.MODE_PRIVATE)
        _accent.value = prefs.getString("accent", DEFAULT_ACCENT) ?: DEFAULT_ACCENT
        _codeTheme.value = prefs.getBoolean("codeTheme", false)
        _autoplay.value = prefs.getBoolean("autoplay", true)
    }

    fun setAccent(hex: String) {
        _accent.value = hex
        prefs.edit { putString("accent", hex) }
    }

    fun setCodeTheme(enabled: Boolean) {
        _codeTheme.value = enabled
        prefs.edit { putBoolean("codeTheme", enabled) }
    }

    fun setAutoplay(enabled: Boolean) {
        _autoplay.value = enabled
        prefs.edit { putBoolean("autoplay", enabled) }
    }

    private fun readList(key: String): List<String> =
        runCatching { Json.decodeFromString(stringList, prefs.getString(key, "[]") ?: "[]") }.getOrDefault(emptyList())

    private fun writeList(key: String, list: List<String>) =
        prefs.edit { putString(key, Json.encodeToString(stringList, list)) }

    fun recentSearches(): List<String> = readList("recentSearches").take(RECENT_MAX)

    fun pushRecentSearch(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return recentSearches()
        val next = (listOf(q) + recentSearches().filter { !it.equals(q, ignoreCase = true) }).take(RECENT_MAX)
        writeList("recentSearches", next)
        return next
    }

    /** Videos heard on this device, oldest first (feeds Discover, like the desktop app). */
    fun heard(): List<String> = readList("heard")

    @Synchronized
    fun rememberHeard(videoId: String) {
        val list = heard().filter { it != videoId } + videoId
        writeList("heard", list.takeLast(HEARD_MAX))
    }

    private val queueItemList = ListSerializer(QueueItem.serializer())

    /** Recently played tracks with metadata, newest first (Android Auto's Recently played tab). */
    fun recentTracks(): List<QueueItem> =
        runCatching { Json.decodeFromString(queueItemList, prefs.getString("recentTracks", "[]") ?: "[]") }.getOrDefault(emptyList())

    @Synchronized
    fun rememberRecentTrack(item: QueueItem) {
        val next = (listOf(item.copy(autoplay = false)) + recentTracks().filter { it.videoId != item.videoId }).take(RECENT_TRACKS_MAX)
        prefs.edit { putString("recentTracks", Json.encodeToString(queueItemList, next)) }
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
    fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
}
