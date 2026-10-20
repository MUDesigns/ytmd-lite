package app.ytmdesktop.lite.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.ytmdesktop.lite.BuildConfig
import app.ytmdesktop.lite.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * In-app updates from GitHub Releases: the Android counterpart of the desktop `src/lib/updater.ts`.
 *
 * `android.yml` uploads `YTMD-Lite_<version>_android.apk` (and its `.sha256`) to the repository's
 * releases, signed with the release keystore held in CI secrets so consecutive builds install over
 * each other. The app lists published releases, picks the newest APK with a higher version
 * code than its own, downloads it into the cache directory, verifies the checksum and signing key, and
 * installs it through [PackageInstaller]. Android still asks the user to confirm the first update (and
 * to allow installs from this app once); later updates on Android 12+ can go through without a prompt.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val RELEASES_URL = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases?per_page=30"
    private val apkName = Regex("""_android(-debug)?\.apk$""")
    private const val LAUNCH_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val INSTALL_ACTION = "${BuildConfig.APPLICATION_ID}.UPDATE_INSTALL_STATUS"

    data class Release(
        val version: String,
        val versionCode: Int,
        val tag: String,
        val apkName: String,
        val apkUrl: String,
        val size: Long,
        val sha256Url: String?,
        val htmlUrl: String,
    )

    data class Status(
        /** Look for updates when the app starts (Settings → Updates). */
        val autoCheck: Boolean,
        val checking: Boolean = false,
        /** A release newer than the installed build, if one has been found. */
        val available: Release? = null,
        val lastChecked: Long = 0,
        /** 0..1 while the APK downloads. */
        val progress: Float? = null,
        /** Downloaded and verified APK, waiting for [install]. */
        val apk: File? = null,
        val installing: Boolean = false,
        /** "Install unknown apps" must be allowed for this app in system settings first. */
        val needsPermission: Boolean = false,
        val error: String? = null,
        /** The home banner was closed for this launch; Settings still shows the update. */
        val dismissed: Boolean = false,
    ) {
        val busy get() = checking || progress != null || installing
    }

    @Serializable
    private data class GhAsset(val name: String, val size: Long = 0, val browser_download_url: String)

    @Serializable
    private data class GhRelease(
        val tag_name: String,
        val html_url: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var downloadJob: Job? = null

    private val _status = MutableStateFlow(Status(autoCheck = true))
    val status: StateFlow<Status> = _status.asStateFlow()

    val enabled get() = BuildConfig.UPDATER_ENABLED

    private val updateDir get() = File(app.cacheDir, "updates")

    fun init(context: Context) {
        app = context.applicationContext
        _status.value = Status(
            autoCheck = Prefs.getBoolean("updates.auto", true),
            lastChecked = Prefs.getString("updates.lastCheck")?.toLongOrNull() ?: 0,
        )
        // A previous launch installed (or abandoned) whatever is here.
        updateDir.deleteRecursively()
        if (!enabled) return
        if (_status.value.autoCheck && System.currentTimeMillis() - _status.value.lastChecked > LAUNCH_CHECK_INTERVAL_MS) {
            check(manual = false)
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        Prefs.putBoolean("updates.auto", enabled)
        _status.update { it.copy(autoCheck = enabled) }
    }

    fun dismiss() = _status.update { it.copy(dismissed = true) }

    /** Same formula as `versionCode` in app/build.gradle.kts, so tags compare to [BuildConfig.VERSION_CODE]. */
    fun versionCode(version: String): Int? {
        val parts = version.removePrefix("v").split(".")
        if (parts.size != 3) return null
        val (a, b, c) = parts.map { it.toIntOrNull() ?: return null }
        return a * 10_000_000 + b * 100_000 + c
    }

    /** Looks for a newer release. Network errors stay quiet unless the user asked ([manual]). */
    fun check(manual: Boolean = true) {
        if (!enabled || _status.value.busy) return
        _status.update { it.copy(checking = true, error = null) }
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { fetchNewest() }
                val now = System.currentTimeMillis()
                Prefs.putString("updates.lastCheck", now.toString())
                _status.update { s ->
                    val changed = release?.versionCode != s.available?.versionCode
                    s.copy(
                        checking = false,
                        available = release,
                        lastChecked = now,
                        // A different release makes any downloaded APK stale.
                        apk = if (changed) null else s.apk,
                        dismissed = if (changed) false else s.dismissed,
                    )
                }
                if (release != null) Log.i(TAG, "Update available: ${release.version} (installed ${BuildConfig.VERSION_NAME})")
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                _status.update { it.copy(checking = false, error = if (manual) "Update check failed: ${e.message}" else null) }
            }
        }
    }

    private fun fetchNewest(): Release? {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "YTMD-Lite-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val body = http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("GitHub returned HTTP ${res.code}")
            res.body.string()
        }
        val releases = json.decodeFromString<List<GhRelease>>(body)
        // Desktop-only releases have no APK; Android-only releases are never "Latest" — so scan them all.
        return releases.asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { r ->
                val code = versionCode(r.tag_name) ?: return@mapNotNull null
                val apk = r.assets.firstOrNull { apkName.containsMatchIn(it.name) } ?: return@mapNotNull null
                val sha = r.assets.firstOrNull { it.name == apk.name + ".sha256" }
                Release(r.tag_name.removePrefix("v"), code, r.tag_name, apk.name, apk.browser_download_url, apk.size, sha?.browser_download_url, r.html_url)
            }
            .filter { it.versionCode > BuildConfig.VERSION_CODE }
            .maxByOrNull { it.versionCode }
    }

    /** Downloads and verifies [Status.available]; the result lands in [Status.apk]. */
    fun download() {
        val release = _status.value.available ?: return
        if (_status.value.busy) return
        _status.update { it.copy(progress = 0f, error = null, apk = null) }
        downloadJob = scope.launch {
            val file = File(updateDir, release.apkName)
            try {
                withContext(Dispatchers.IO) {
                    updateDir.deleteRecursively()
                    updateDir.mkdirs()
                    fetchFile(release.apkUrl, file, release.size)
                    verify(file, release)
                }
                _status.update { it.copy(progress = null, apk = file) }
            } catch (e: CancellationException) {
                file.delete()
                _status.update { it.copy(progress = null) }
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                file.delete()
                _status.update { it.copy(progress = null, error = "Download failed: ${e.message}") }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    private suspend fun fetchFile(url: String, target: File, expectedSize: Long) {
        val request = Request.Builder().url(url).header("User-Agent", "YTMD-Lite-Android/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val total = res.body.contentLength().takeIf { it > 0 } ?: expectedSize
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var lastReport = 0L
            res.body.byteStream().use { input ->
                target.outputStream().use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            _status.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 0.99f)) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun verify(file: File, release: Release) {
        // Checksum published by CI next to the APK (releases from before it shipped have none).
        release.sha256Url?.let { url ->
            val text = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("checksum HTTP ${res.code}")
                res.body.string()
            }
            val expected = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
            if (expected == null || expected.length != 64) throw IOException("unreadable checksum file")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("checksum mismatch")
        }

        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(file.path, signatureFlags()) ?: throw IOException("not a valid APK")
        if (info.packageName != app.packageName) throw IOException("APK is for ${info.packageName}")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) throw IOException("APK is not newer than the installed app")
        // Android refuses updates signed with another key; say so before the system dialog does.
        // Archive signatures are not always readable, so only compare when both sides are known.
        val installed = signaturesOf(pm.getPackageInfo(app.packageName, signatureFlags()))
        val incoming = signaturesOf(info)
        if (installed.isNotEmpty() && incoming.isNotEmpty() && installed.intersect(incoming).isEmpty()) {
            throw IOException("this release is signed with a different key than the installed app (debug build?). Uninstall first")
        }
    }

    private fun signatureFlags() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun signaturesOf(info: android.content.pm.PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toSet() ?: emptySet()
        else @Suppress("DEPRECATION") info.signatures?.toSet() ?: emptySet()

    /**
     * Hands the downloaded APK to the system installer. Without the "install unknown apps" permission
     * this opens the system setting instead; [onResume] finishes the install when the user comes back.
     */
    fun install(context: Context) {
        val file = _status.value.apk ?: return
        if (_status.value.installing) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _status.update { it.copy(needsPermission = true) }
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _status.update { it.copy(installing = true, needsPermission = false, error = null) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(file) }
            } catch (e: Exception) {
                Log.w(TAG, "Update install failed", e)
                _status.update { it.copy(installing = false, error = "Install failed: ${e.message}") }
            }
        }
    }

    /** Continues a pending install after returning from the unknown-sources setting. */
    fun onResume(context: Context) {
        val s = _status.value
        if (s.needsPermission && s.apk != null && context.packageManager.canRequestPackageInstalls()) install(context)
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Honored on Android 12+ once this app is the installer of record (i.e. from the second update on).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(app, UpdateReceiver::class.java).setAction(INSTALL_ACTION)
            // Mutable: the installer fills in the status extras.
            val pending = PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        }
    }

    /** Called by [UpdateReceiver] with the installer's result. */
    internal fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else _status.update { it.copy(installing = false, error = "Install failed: no confirmation dialog") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The system restarts the app on the new version; nothing to do here.
                _status.update { it.copy(installing = false, apk = null, available = null) }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _status.update { it.copy(installing = false) }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w(TAG, "Install failed: $message")
                _status.update { it.copy(installing = false, error = "Install failed: $message") }
            }
        }
    }
}
