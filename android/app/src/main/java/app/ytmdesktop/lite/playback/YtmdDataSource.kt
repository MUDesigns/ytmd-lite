package app.ytmdesktop.lite.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.ytmdesktop.lite.backend.Backend
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Turns `ytmd://<videoId>` into a googlevideo URL at open time, for playback and downloads alike.
 * A 403/410 means the cached URL expired or is bound to another network: resolve again once.
 */
@UnstableApi
class YtmdDataSource(private val upstream: DataSource) : DataSource {
    class Factory(http: OkHttpClient) : DataSource.Factory {
        private val upstream = OkHttpDataSource.Factory(http).setUserAgent(StreamResolver.USER_AGENT)
        override fun createDataSource(): DataSource = YtmdDataSource(upstream.createDataSource())
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme != MediaItems.SCHEME) return upstream.open(dataSpec)
        val videoId = dataSpec.uri.host ?: throw IOException("Missing video id in ${dataSpec.uri}")
        return try {
            openResolved(videoId, dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 403 && e.responseCode != 410) throw e
            upstream.close()
            StreamResolver.invalidate(videoId)
            openResolved(videoId, dataSpec)
        }
    }

    private fun openResolved(videoId: String, dataSpec: DataSpec): Long {
        val resolved = StreamResolver.resolve(videoId)
        var next = dataSpec.withUri(Uri.parse(resolved.url))
        if (resolved.viaBackend) next = next.withAdditionalHeaders(mapOf("X-YTMD-Token" to Backend.token))
        return upstream.open(next)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
