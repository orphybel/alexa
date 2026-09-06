package com.orphybel.alexacleaner.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.model.Region

/**
 * Shows Amazon's own sign-in page in a WebView (so the password never goes through this app)
 * and captures the OAuth authorization code from the final `maplanding` redirect.
 */
class LoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tld = intent.getStringExtra(EXTRA_TLD) ?: "fr"
        val signInUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val region = Region.byTld(tld)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            text = "Connectez-vous avec votre compte Amazon (${region.amazonHost}). Le mot de passe est saisi directement sur la page Amazon."
            setPadding(32, 24, 32, 16)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(hint)
        root.addView(progress)
        root.addView(webView)
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        cookies.removeAllCookies(null)
        cookies.flush()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Amazon serves a degraded page to bare WebViews; present ourselves as a mobile browser.
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return capture(url, region)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = capture(url, region)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.isIndeterminate = true
                capture(url, region)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.isIndeterminate = false
                progress.progress = 100
            }
        }
        webView.loadUrl(signInUrl)
    }

    /** Returns true (and finishes) when [url] is the OAuth landing redirect. */
    private fun capture(url: String, region: Region): Boolean {
        if (done) return true
        val code = AmazonAuth.extractAuthorizationCode(url) ?: return false
        done = true
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = listOf("https://${region.amazonHost}", "https://amazon.${region.tld}", "https://www.amazon.com")
            .mapNotNull { cookieManager.getCookie(it) }
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .distinctBy { it.substringBefore('=') }
            .joinToString("; ")
        val frc = cookieHeader.split("; ").firstOrNull { it.startsWith("frc=") }?.substringAfter("frc=")
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(RESULT_CODE, code).putExtra(RESULT_COOKIES, cookieHeader).putExtra(RESULT_FRC, frc),
        )
        webView.stopLoading()
        finish()
        return true
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TLD = "tld"
        const val EXTRA_URL = "url"
        const val RESULT_CODE = "code"
        const val RESULT_COOKIES = "cookies"
        const val RESULT_FRC = "frc"

        fun intent(context: Context, region: Region, signInUrl: String): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_TLD, region.tld)
                .putExtra(EXTRA_URL, signInUrl)
    }
}
