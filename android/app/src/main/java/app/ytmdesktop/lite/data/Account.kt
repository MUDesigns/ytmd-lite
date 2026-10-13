package app.ytmdesktop.lite.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Sign-in and liked-song state shared by the phone UI and the playback service (Android Auto),
 * which run in the same process but can each be the first to start.
 */
object Account {
    private val _signedIn = MutableStateFlow(false)
    /** Signed in with a Google account (not the local guest profile). */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** Bumped when the account changes, so cached catalog data can be dropped. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun setSignedIn(signedIn: Boolean) {
        _signedIn.value = signedIn
        if (!signedIn) _likedIds.value = emptySet()
    }

    /** Re-check the backend session; returns the raw auth state. */
    suspend fun refresh(): AuthState {
        val auth = Api.validateAuth()
        if (auth.valid != null) {
            setSignedIn(auth.valid && auth.type != "local")
            if (_signedIn.value) loadLikes()
        }
        return auth
    }

    suspend fun loadLikes() {
        _likedIds.value = Api.getLikedIds()
    }

    fun changed() {
        _signedIn.value = false
        _likedIds.value = emptySet()
        _generation.update { it + 1 }
    }

    /** Optimistically flip the like, reverting if the request fails. Returns the new state. */
    suspend fun toggleLike(videoId: String, item: QueueItem? = null): Boolean {
        val like = videoId !in _likedIds.value
        _likedIds.update { if (like) it + videoId else it - videoId }
        try {
            Api.likeSong(videoId, like, item)
        } catch (e: Exception) {
            _likedIds.update { if (like) it - videoId else it + videoId }
            throw e
        }
        return like
    }
}
