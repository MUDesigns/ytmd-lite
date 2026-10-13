package app.ytmdesktop.lite.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.ytmdesktop.lite.data.Api
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Google sign-in in a WebView, cookies only — same idea as src-tauri/src/auth.rs.
 * Once music.youtube.com has auth cookies they go to the backend's /auth/cookie-login.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var watcher: Job? = null
    private var submitting = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.contains("music.youtube.com")) watchForSession()
                }
            }
        }
        val hint = TextView(this).apply {
            text = "// sign in to YouTube Music — this window closes automatically"
            setTextColor(0xFF7F8AA3.toInt())
            setBackgroundColor(0xFF0A0D12.toInt())
            textSize = 11f
            setPadding(24, 16, 24, 16)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(0xFF0A0D12.toInt())
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(LOGIN_URL)
    }

    private fun readDelegatedSessionId(callback: (String) -> Unit) {
        // Brand accounts: the selected account's id lives in ytcfg (see auth.rs).
        web.evaluateJavascript(
            "(function(){try{return (window.ytcfg&&ytcfg.get('DELEGATED_SESSION_ID'))||''}catch(e){return ''}})()",
        ) { raw -> callback(raw?.trim('"')?.takeIf { it != "null" }.orEmpty()) }
    }

    private fun watchForSession() {
        if (watcher?.isActive == true) return
        watcher = lifecycleScope.launch {
            while (!submitting) {
                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                if (cookie.contains("SAPISID=") && web.url?.contains("music.youtube.com") == true) {
                    submitting = true
                    readDelegatedSessionId { dsid -> submit(cookie, dsid) }
                    return@launch
                }
                delay(1000)
            }
        }
    }

    private fun submit(cookie: String, dsid: String) {
        lifecycleScope.launch {
            try {
                Api.cookieLogin(cookie, USER_AGENT, dsid.ifEmpty { null })
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                submitting = false
                Toast.makeText(this@LoginActivity, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        // Desktop Chrome UA: Google rejects sign-in from user agents marked as embedded WebViews.
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    }
}
