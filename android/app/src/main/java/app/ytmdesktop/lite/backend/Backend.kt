package app.ytmdesktop.lite.backend

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Runs the shared python-backend (server.py) in-process and exposes its loopback address. */
object Backend {
    sealed interface State {
        data object Starting : State
        data class Ready(val baseUrl: String) : State
        data class Failed(val message: String) : State
    }

    /** Required on every request; see ytmd_android.py. */
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var started = false

    val baseUrl: String? get() = (state.value as? State.Ready)?.baseUrl

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        thread(name = "ytmd-backend-start") {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(app))
                val dataDir = File(app.filesDir, "backend").apply { mkdirs() }
                val port = Python.getInstance().getModule("ytmd_android")
                    .callAttr("start", dataDir.absolutePath, token).toInt()
                _state.value = State.Ready("http://127.0.0.1:$port")
            } catch (e: Throwable) {
                Log.e("Backend", "Catalog API failed to start", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
