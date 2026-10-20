package app.ytmdesktop.lite.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the PackageInstaller result for an update session started by [AppUpdater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppUpdater.INSTALL_ACTION) AppUpdater.onInstallStatus(context, intent)
    }
}
