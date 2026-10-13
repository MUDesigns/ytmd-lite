package app.ytmdesktop.lite.playback

import android.util.Log
import app.ytmdesktop.lite.data.Api
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube video id to a playable audio URL.
 *
 * The desktop backend uses yt-dlp with a Node PO-token helper and JS challenge solver,
 * neither of which runs on Android. NewPipeExtractor is the primary resolver here; the
 * backend's yt-dlp proxy remains as a fallback.
 */
object StreamResolver {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val TAG = "StreamResolver"
    // googlevideo URLs live ~6 h; refresh well before that.
    private const val TTL_MS = 4L * 60 * 60 * 1000

    data class Resolved(val url: String, val viaBackend: Boolean, val at: Long = System.currentTimeMillis())

    private val cache = ConcurrentHashMap<String, Resolved>()
    private val locks = ConcurrentHashMap<String, Any>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        NewPipe.init(OkHttpDownloader(client), Localization("en", "US"))
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    /** Blocking; called from ExoPlayer's loader threads. */
    fun resolve(videoId: String): Resolved {
        cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it }
        synchronized(locks.getOrPut(videoId) { Any() }) {
            cache[videoId]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it }
            val started = System.currentTimeMillis()
            val resolved = try {
                Resolved(resolveWithNewPipe(videoId), viaBackend = false)
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe could not resolve $videoId, trying backend: ${e.message}")
                try {
                    Resolved(runBlocking { Api.warmBackendStream(videoId) }, viaBackend = true)
                } catch (fallback: Exception) {
                    throw IOException(e.message ?: fallback.message ?: "Could not resolve audio stream", e)
                }
            }
            Log.i(TAG, "[playback timing] $videoId resolved in ${System.currentTimeMillis() - started} ms (backend=${resolved.viaBackend})")
            cache[videoId] = resolved
            return resolved
        }
    }

    private fun resolveWithNewPipe(videoId: String): String {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            // Original-language track first (dubbed tracks exist on some videos), then best bitrate.
            .sortedWith(compareBy({ it.audioTrackType.let { type -> type != null && type.name != "ORIGINAL" } }, { -it.averageBitrate }))
            .firstOrNull()
            ?: throw IOException("No audio stream available")
        return audio.content
    }

    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .header("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { res ->
                if (res.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return Response(
                    res.code,
                    res.message,
                    res.headers.toMultimap(),
                    res.body.string(),
                    res.request.url.toString(),
                )
            }
        }
    }
}
