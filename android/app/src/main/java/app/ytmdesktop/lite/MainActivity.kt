package app.ytmdesktop.lite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.ytmdesktop.lite.auth.LoginActivity
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.data.Sharing
import app.ytmdesktop.lite.lastfm.LastFm
import app.ytmdesktop.lite.playback.Downloads
import app.ytmdesktop.lite.ui.App
import app.ytmdesktop.lite.ui.AppViewModel
import app.ytmdesktop.lite.ui.Platform
import app.ytmdesktop.lite.ui.theme.YtmdTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            vm.statusMsg = "signed in"
            vm.onAccountChanged()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(0xFF0A0D12.toInt())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)

        // Media notifications need this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val platform = Platform(
            signIn = { login.launch(Intent(this, LoginActivity::class.java)) },
            shareText = { text ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, null))
            },
            openUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
            lastFmSignIn = {
                lifecycleScope.launch {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LastFm.beginSignIn())))
                    } catch (e: Exception) {
                        LastFm.cancelSignIn()
                        vm.statusMsg = "Last.fm: ${e.message}"
                    }
                }
            },
        )

        setContent {
            val accent by Prefs.accent.collectAsState()
            val codeTheme by Prefs.codeTheme.collectAsState()
            YtmdTheme(accent, codeTheme) { App(vm, platform) }
        }

        if (savedInstanceState == null) handleIntent(intent)
        // Downloads interrupted by a process death continue under their foreground service.
        Downloads.resumePending(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the Last.fm approval page.
        if (LastFm.status.value.signingIn) {
            lifecycleScope.launch {
                if (LastFm.completeSignIn()) vm.statusMsg = "Last.fm connected"
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        Sharing.parse(intent.data)?.let(vm::handleLink)
    }
}
