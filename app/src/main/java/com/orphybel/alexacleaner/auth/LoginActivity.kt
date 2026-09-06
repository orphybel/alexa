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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.model.Region
import com.orphybel.alexacleaner.graph

/**
 * Shows Amazon's own sign-in page in a WebView (so the password never goes through this app)
 * and captures the OAuth authorization code from the final `maplanding` redirect.
 */
class LoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var hint: TextView
    private var done = false
    private lateinit var region: Region
    private lateinit var signInHost: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tld = intent.getStringExtra(EXTRA_TLD) ?: "fr"
        val signInUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        region = Region.byTld(tld)
        signInHost = intent.getStringExtra(EXTRA_SIGN_IN_HOST) ?: "www.amazon.com"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = TextView(this).apply {
            text = "Connectez-vous avec votre compte Amazon (page $signInHost). Le mot de passe est saisi directement sur la page Amazon."
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                capture(request.url.toString(), "override")

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = capture(url, "override-legacy")

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.isIndeterminate = true
                log("WebView → $url")
                if (!capture(url, "start")) hint.text = shortUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.isIndeterminate = false
                progress.progress = 100
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    val url = request.url.toString()
                    log("WebView HTTP ${errorResponse.statusCode} sur $url")
                    hint.text = "Amazon a répondu HTTP ${errorResponse.statusCode} pour ${shortUrl(url)}. " +
                        "Si cela se produit dès l'ouverture, essayez l'autre mode de connexion dans l'écran précédent."
                }
            }
        }
        log("Ouverture de la page de connexion $signInHost (marketplace ${region.tld})")
        webView.loadUrl(signInUrl)
    }

    /** Returns true (and finishes) when [url] is the OAuth landing redirect carrying a code. */
    private fun capture(url: String, via: String): Boolean {
        if (done) return true
        if (!AmazonAuth.isLandingUrl(url)) return false
        val code = AmazonAuth.extractAuthorizationCode(url)
        if (code == null) {
            val error = AmazonAuth.extractLandingError(url) ?: "aucun code d'autorisation dans l'URL"
            log("Page d'atterrissage sans code ($via): $error — $url")
            hint.text = "Amazon n'a pas renvoyé de code d'autorisation : $error. Fermez cette page et réessayez."
            return false
        }
        done = true
        log("Code d'autorisation capturé ($via)")
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = listOf("https://$signInHost", "https://${region.amazonHost}", "https://www.amazon.com")
            .distinct()
            .mapNotNull { cookieManager.getCookie(it) }
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
            .distinctBy { it.substringBefore('=') }
            .joinToString("; ")
        val frc = cookieHeader.split("; ").firstOrNull { it.startsWith("frc=") }?.substringAfter("frc=")
        log("Cookies de connexion: ${cookieHeader.split("; ").map { it.substringBefore('=') }}")
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(RESULT_CODE, code).putExtra(RESULT_COOKIES, cookieHeader).putExtra(RESULT_FRC, frc),
        )
        webView.stopLoading()
        finish()
        return true
    }

    private fun shortUrl(url: String): String = url.substringBefore('?').take(120)

    private fun log(message: String) {
        runCatching { graph.logger.log(message) }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TLD = "tld"
        const val EXTRA_URL = "url"
        const val EXTRA_SIGN_IN_HOST = "signInHost"
        const val RESULT_CODE = "code"
        const val RESULT_COOKIES = "cookies"
        const val RESULT_FRC = "frc"

        fun intent(context: Context, region: Region, signInUrl: String, signInHost: String): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_TLD, region.tld)
                .putExtra(EXTRA_URL, signInUrl)
                .putExtra(EXTRA_SIGN_IN_HOST, signInHost)
    }
}
