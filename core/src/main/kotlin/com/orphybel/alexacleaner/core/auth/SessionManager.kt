package com.orphybel.alexacleaner.core.auth

import com.orphybel.alexacleaner.core.http.Endpoints
import com.orphybel.alexacleaner.core.http.Logger
import com.orphybel.alexacleaner.core.http.await
import com.orphybel.alexacleaner.core.model.Region
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** Persistence of the session. The Android app backs it with encrypted preferences. */
interface SessionStore {
    fun load(): AlexaSession?
    fun save(session: AlexaSession?)
}

/**
 * Owns the [AlexaSession]: keeps the cookie jar in sync, re-exchanges cookies from the refresh
 * token when they get old, and fetches the CSRF token required by mutating API calls.
 */
class SessionManager(
    private val store: SessionStore,
    private val http: OkHttpClient,
    val cookieJar: MemoryCookieJar,
    private val log: Logger = Logger.NONE,
    private val endpointsFor: (Region) -> Endpoints = { Endpoints(it) },
) {
    @Volatile
    var session: AlexaSession? = store.load()
        private set

    private val mutex = Mutex()

    val isLoggedIn: Boolean get() = session != null
    val region: Region get() = Region.byTld(session?.regionTld ?: Region.ALL.first().tld)
    val endpoints: Endpoints get() = endpointsFor(region)

    init {
        session?.let { cookieJar.replaceAll(it.cookies.map(StoredCookie::toOkHttp)) }
    }

    /** Finishes the login started in the WebView: registration, cookie exchange, CSRF. */
    suspend fun completeLogin(auth: AmazonAuth, authorizationCode: String, loginCookieHeader: String?, frc: String?): AlexaSession =
        mutex.withLock {
            val reg = auth.register(authorizationCode, loginCookieHeader, frc)
            val now = System.currentTimeMillis()
            var s = AlexaSession(
                regionTld = auth.region.tld,
                deviceSerial = auth.deviceSerial,
                refreshToken = reg.refreshToken,
                accessToken = reg.accessToken,
                accessTokenExpiresAt = now + reg.expiresInSeconds * 1000,
                customerName = reg.customerName,
                loggedInAt = now,
            )
            val cookies = auth.exchangeCookies(reg.refreshToken)
            s = s.copy(cookies = cookies, cookiesFetchedAt = now)
            cookieJar.replaceAll(cookies.map(StoredCookie::toOkHttp))
            persist(s)
            val csrf = fetchCsrf(auth.region)
            s = s.copy(csrf = csrf)
            persist(s)
            s
        }

    /**
     * Makes sure cookies and CSRF are usable. With [force] the cookies are re-exchanged
     * unconditionally (used after a 401/403 from the API).
     */
    suspend fun ensureReady(force: Boolean = false) {
        mutex.withLock {
            var s = session ?: throw AuthException("Non connecté")
            val region = Region.byTld(s.regionTld)
            val now = System.currentTimeMillis()
            val cookiesStale = s.cookies.isEmpty() ||
                s.cookies.any { it.expiresAt != null && it.expiresAt < now + COOKIE_MARGIN_MS } ||
                now - s.cookiesFetchedAt > COOKIE_MAX_AGE_MS
            if (force || cookiesStale) {
                log.log("Renouvellement des cookies (force=$force, stale=$cookiesStale)")
                val auth = AmazonAuth(region, s.deviceSerial, "", http, log, endpointsFor(region))
                val cookies = auth.exchangeCookies(s.refreshToken)
                s = s.copy(cookies = cookies, cookiesFetchedAt = now, csrf = null)
                cookieJar.replaceAll(cookies.map(StoredCookie::toOkHttp))
                persist(s)
            }
            if (s.csrf.isNullOrBlank()) {
                s = s.copy(csrf = fetchCsrf(region))
                persist(s)
            }
        }
    }

    fun logout() {
        cookieJar.clear()
        persist(null)
    }

    private fun persist(s: AlexaSession?) {
        session = s
        store.save(s)
    }

    /** Alexa sets the `csrf` cookie on one of these endpoints; we take the first that yields it. */
    private suspend fun fetchCsrf(region: Region): String {
        cookieJar.find("csrf")?.let { return it.value }
        val ep = endpointsFor(region)
        val candidates = listOf(
            ep.alexa("api/language"),
            ep.alexa("templates/oobe/d-device-pick.handlebars"),
            ep.alexa("api/devices-v2/device").newBuilder().addQueryParameter("cached", "false").build(),
            ep.alexa("spa/index.html"),
        )
        for (url in candidates) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AmazonAuth.USER_AGENT)
                .header("Accept-Language", region.language)
                .header("Accept", "*/*")
                .header("DNT", "1")
                .header("Referer", ep.alexa("spa/index.html").toString())
                .header("Origin", ep.alexa.toString().trimEnd('/'))
                .get()
                .build()
            try {
                http.newCall(request).await().use { r ->
                    log.log("GET ${url.encodedPath} -> ${r.code}")
                    r.body?.close()
                }
            } catch (e: Exception) {
                log.log("CSRF: échec sur $url: ${e.message}")
            }
            cookieJar.find("csrf")?.let { return it.value }
        }
        throw AuthException("Impossible d'obtenir le jeton CSRF (cookies invalides ?)")
    }

    companion object {
        private const val COOKIE_MARGIN_MS = 6 * 60 * 60 * 1000L
        private const val COOKIE_MAX_AGE_MS = 6 * 24 * 60 * 60 * 1000L
    }
}
