package app.ytmdesktop.lite.auth

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.toArgb
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import app.ytmdesktop.lite.R
import app.ytmdesktop.lite.data.Api
import app.ytmdesktop.lite.data.Prefs
import app.ytmdesktop.lite.ui.theme.accentColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Google sign-in in a WebView, cookies only — same flow as src-tauri/src/auth.rs.
 *
 * Nothing is submitted automatically: once YouTube Music shows a signed-in session, the user can
 * switch accounts (or brand channels) from the avatar menu and then confirm with
 * "Use this account". The cookies and brand-account id are read at that moment.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var confirmBar: View
    private lateinit var confirm: Button
    private var submitting = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        val colors = accentColors(Prefs.accent.value)
        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = updateConfirmBar()
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = updateConfirmBar()
            }
        }
        BrowserIdentity.apply(web)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0A0D12.toInt())
            setPadding(dp(12), dp(6), dp(6), dp(6))
            addView(TextView(context).apply {
                text = "// sign in, then tap “Use this account”"
                setTextColor(0xFF7F8AA3.toInt())
                textSize = 11f
                typeface = mono
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = "+ add account"
                isAllCaps = false
                textSize = 12f
                typeface = mono
                setTextColor(colors.primary.toArgb())
                setOnClickListener { web.loadUrl(ADD_ACCOUNT_URL) }
            })
        }

        confirm = Button(this).apply {
            text = "Use this account"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create(mono, Typeface.BOLD)
            setTextColor(colors.onPrimary.toArgb())
            background = GradientDrawable().apply {
                setColor(colors.primary.toArgb())
                cornerRadius = dp(2).toFloat()
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { submit() }
        }
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0F131A.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "Switch account via the avatar if needed, then confirm."
                setTextColor(0xFFB4B4B4.toInt())
                textSize = 12f
                typeface = mono
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(confirm)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(0xFF0A0D12.toInt())
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(context).apply { setBackgroundColor(0xFF2A3344.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(confirmBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        // Already signed in on this device: open YouTube Music so the avatar menu can switch accounts.
        web.loadUrl(if (sessionCookie().contains("SAPISID=")) MUSIC_URL else ADD_ACCOUNT_URL)

        // YouTube Music navigates in-page; keep the bar in step with the session.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                updateConfirmBar()
            }
        }
    }

    private fun sessionCookie(): String = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()

    private fun onMusicPage(): Boolean = web.url?.let { Uri.parse(it).host == "music.youtube.com" } == true

    private fun updateConfirmBar() {
        val ready = onMusicPage() && sessionCookie().contains("SAPISID=")
        confirmBar.visibility = if (ready) View.VISIBLE else View.GONE
    }

    private fun submit() {
        if (submitting) return
        submitting = true
        confirm.isEnabled = false
        confirm.alpha = 0.6f
        // Brand accounts: the selected account's id lives in ytcfg (see auth.rs).
        web.evaluateJavascript(
            "(function(){try{return (window.ytcfg&&ytcfg.get('DELEGATED_SESSION_ID'))||''}catch(e){return ''}})()",
        ) { raw ->
            val dsid = raw?.trim('"')?.takeIf { it != "null" }.orEmpty()
            lifecycleScope.launch {
                try {
                    Api.cookieLogin(sessionCookie(), BACKEND_USER_AGENT, dsid.ifEmpty { null })
                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    submitting = false
                    confirm.isEnabled = true
                    confirm.alpha = 1f
                    Toast.makeText(this@LoginActivity, "Sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        // The backend talks to music.youtube.com as desktop web, like the desktop app (auth.rs).
        const val BACKEND_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MUSIC_URL = "https://music.youtube.com/"
        private const val ADD_ACCOUNT_URL =
            "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F&flowName=GlifWebSignIn"
    }
}
