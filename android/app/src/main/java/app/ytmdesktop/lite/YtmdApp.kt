package app.ytmdesktop.lite

import android.app.Application
import app.ytmdesktop.lite.backend.Backend
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.playback.Downloads
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class YtmdApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Backend.start(this)
        Downloads.init(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
