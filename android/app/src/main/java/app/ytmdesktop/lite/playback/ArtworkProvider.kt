package app.ytmdesktop.lite.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.ytmdesktop.lite.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Serves remote thumbnails as content:// URIs. Android Auto only loads browse artwork from
 * local URIs, so this downloads YouTube image URLs into the cache on first request.
 * Only Google image hosts are fetched, so other apps cannot use this as a general proxy.
 */
class ArtworkProvider : ContentProvider() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Artwork is read-only")
        val remote = uri.getQueryParameter(PARAM_URL)?.toHttpUrlOrNull()
        if (remote == null || remote.scheme != "https" || !ALLOWED_HOST.matches(remote.host)) {
            throw FileNotFoundException("Unsupported artwork URL")
        }
        val dir = File(context!!.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(remote.toString()))
        if (!file.exists()) {
            client.newCall(Request.Builder().url(remote).build()).execute().use { res ->
                if (!res.isSuccessful) throw FileNotFoundException("Artwork HTTP ${res.code}")
                val tmp = File.createTempFile("art", null, dir)
                tmp.outputStream().use { out -> res.body.byteStream().copyTo(out) }
                if (!tmp.renameTo(file)) tmp.delete()
            }
            trim(dir)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.takeIf { it.size > MAX_FILES } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"
        private const val PARAM_URL = "u"
        private const val MAX_FILES = 400
        private val ALLOWED_HOST = Regex("""^(?:[a-z0-9-]+\.)*(?:googleusercontent\.com|ggpht\.com|ytimg\.com)$""")

        fun uriFor(remote: String?): Uri? {
            if (remote.isNullOrEmpty()) return null
            val url = when {
                remote.startsWith("//") -> "https:$remote"
                remote.startsWith("http://") -> "https://" + remote.removePrefix("http://")
                else -> remote
            }
            return Uri.Builder().scheme("content").authority(AUTHORITY).path("img").appendQueryParameter(PARAM_URL, url).build()
        }
    }
}
