package app.ytmdesktop.lite.auth

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Makes the sign-in WebView present itself as the mobile Chrome it is built on.
 *
 * Google refuses sign-in ("This browser or app may not be secure") when a page looks like an
 * embedded WebView. It checks the `X-Requested-With: <package>` header, the `; wv` / `Version/4.0`
 * user-agent markers, and the "Android WebView" brand in client hints (Sec-CH-UA headers and
 * navigator.userAgentData). Spoofing a desktop user agent does not help: the client hints still
 * say Android WebView, and the mismatch is itself a signal. Instead, keep the real engine version
 * and platform and drop only the WebView markers, so every signal describes the same browser.
 */
object BrowserIdentity {
    private const val WEBVIEW_BRAND = "Android WebView"
    private const val CHROME_BRAND = "Google Chrome"

    private val SIGN_IN_ORIGINS = setOf(
        "https://accounts.google.com",
        "https://accounts.youtube.com",
        "https://www.youtube.com",
        "https://music.youtube.com",
    )

    private val CHROME_OBJECT_SCRIPT = """
        (() => {
          if (window.chrome) return;
          const fn = () => undefined;
          Object.defineProperty(window, "chrome", {
            value: {
              app: {
                isInstalled: false,
                InstallState: { DISABLED: "disabled", INSTALLED: "installed", NOT_INSTALLED: "not_installed" },
                RunningState: { CANNOT_RUN: "cannot_run", READY_TO_RUN: "ready_to_run", RUNNING: "running" },
                getDetails: () => null,
                getIsInstalled: () => false,
                runningState: () => "cannot_run",
              },
              csi: fn,
              loadTimes: fn,
            },
            configurable: true,
            writable: true,
          });
        })();
    """.trimIndent()

    /** Default WebView user agent without the WebView markers. */
    fun userAgent(defaultUserAgent: String): String = defaultUserAgent
        .replace("; wv)", ")")
        .replace(Regex("""Version/[\d.]+ """), "")

    fun apply(web: WebView) {
        val settings = web.settings
        settings.userAgentString = userAgent(WebSettings.getDefaultUserAgent(web.context))

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            // Send X-Requested-With to no origin.
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Chrome defines window.chrome on every page; WebView does not.
            WebViewCompat.addDocumentStartJavaScript(web, CHROME_OBJECT_SCRIPT, SIGN_IN_ORIGINS)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            val brands = current.brandVersionList.filter { it.brand != WEBVIEW_BRAND }.toMutableList()
            val chromium = brands.firstOrNull { it.brand == "Chromium" }
            if (chromium != null && brands.none { it.brand == CHROME_BRAND }) {
                brands += UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(CHROME_BRAND)
                    .setMajorVersion(chromium.majorVersion)
                    .setFullVersion(chromium.fullVersion)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(current).setBrandVersionList(brands).build(),
            )
        }
    }
}
