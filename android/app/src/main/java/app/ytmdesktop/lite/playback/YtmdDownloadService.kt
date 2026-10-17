package app.ytmdesktop.lite.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.ytmdesktop.lite.MainActivity
import app.ytmdesktop.lite.R

/** Foreground service that keeps the process alive and shows progress while songs download. */
@OptIn(UnstableApi::class)
class YtmdDownloadService : DownloadService(
    NOTIFICATION_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager {
        Downloads.init(this)
        return Downloads.manager
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val queued = downloads.count { it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }
        val percents = active.map { it.percentDownloaded }.filter { it >= 0 }
        val percent = if (percents.isEmpty()) 0 else percents.average().toInt()
        val title = when {
            notMetRequirements != 0 -> "Waiting for a network connection"
            active.isEmpty() && queued == 0 -> "Finishing downloads"
            active.size == 1 -> "Downloading ${titleOf(active[0])}"
            else -> "Downloading ${active.size} songs"
        }
        val text = buildString {
            if (active.isNotEmpty()) append("$percent%")
            if (queued > 0) { if (isNotEmpty()) append(" · "); append("$queued queued") }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { null })
            .setProgress(100, percent, percents.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun titleOf(download: Download): String =
        runCatching { Regex("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(String(download.request.data))?.groupValues?.get(1) }
            .getOrNull()?.replace("\\\"", "\"") ?: download.request.id

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2
    }
}
