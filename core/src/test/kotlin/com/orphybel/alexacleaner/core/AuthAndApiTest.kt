package com.orphybel.alexacleaner.core

import com.orphybel.alexacleaner.core.api.AlexaApi
import com.orphybel.alexacleaner.core.api.DeleteOutcome
import com.orphybel.alexacleaner.core.auth.AlexaSession
import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.auth.MemoryCookieJar
import com.orphybel.alexacleaner.core.auth.SessionManager
import com.orphybel.alexacleaner.core.auth.SessionStore
import com.orphybel.alexacleaner.core.http.Endpoints
import com.orphybel.alexacleaner.core.model.Region
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthAndApiTest {

    private lateinit var server: MockWebServer
    private val region = Region.byTld("fr")
    private val jar = MemoryCookieJar()
    private lateinit var http: OkHttpClient
    private lateinit var endpoints: Endpoints

    private class MemoryStore : SessionStore {
        var session: AlexaSession? = null
        override fun load() = session
        override fun save(session: AlexaSession?) { this.session = session }
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).build()
        val base = server.url("/")
        endpoints = object : Endpoints(region) {
            override val amazon: HttpUrl get() = base
            override val api: HttpUrl get() = base
            override val alexa: HttpUrl get() = base
        }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private val host: String get() = server.hostName

    private fun exchangeBody() = """
        {"response":{"tokens":{"cookies":{"$host":[
          {"Name":"session-id","Value":"sid-1","Path":"/","Secure":false,"HttpOnly":false,"Expires":"27 Sep 2099 07:53:16 GMT"},
          {"Name":"at-acbfr","Value":"\"at-1\"","Path":"/","Secure":false,"HttpOnly":true,"Expires":"27 Sep 2099 07:53:16 GMT"}
        ]}}},"request_id":"r"}
    """.trimIndent()

    @Test
    fun `sign in url carries the pkce challenge and device client id`() {
        val auth = AmazonAuth.create(region, http, endpoints = endpoints)
        val url = auth.signInUrl()
        assertTrue(url.contains("openid.oa2.code_challenge=" + auth.codeChallenge))
        assertTrue(url.contains("openid.oa2.client_id=device%3A" + auth.clientId))
        assertTrue(url.contains("openid.oa2.response_type=code"))
        assertEquals(32, auth.deviceSerial.length)
        assertNull(AmazonAuth.extractAuthorizationCode("https://www.amazon.fr/ap/signin?foo=bar"))
        assertNull(AmazonAuth.extractAuthorizationCode("https://www.amazon.fr/ap/maplanding?openid.oa2.error=access_denied"))
        assertTrue(AmazonAuth.isLandingUrl("https://www.amazon.fr/ap/maplanding?openid.oa2.error=access_denied"))
        assertEquals("access_denied", AmazonAuth.extractLandingError("https://www.amazon.fr/ap/maplanding?openid.oa2.error=access_denied"))
        assertEquals(
            "ANabc.123",
            AmazonAuth.extractAuthorizationCode("https://www.amazon.fr/ap/maplanding?openid.oa2.authorization_code=ANabc.123&openid.mode=id_res"),
        )
        assertEquals(
            "ANx/y",
            AmazonAuth.extractAuthorizationCode("amzn://landing#openid.oa2.authorization_code=ANx%2Fy&foo=1"),
        )
    }

    @Test
    fun `sign in defaults to amazon com whatever the marketplace`() {
        val fr = AmazonAuth.create(Region.byTld("fr"), http)
        assertTrue(fr.signInUrl().startsWith("https://www.amazon.com/ap/signin?"))
        assertTrue(fr.signInUrl().contains("openid.assoc_handle=amzn_dp_project_dee_ios&"))
        assertTrue(fr.signInUrl().contains("openid.return_to=https%3A%2F%2Fwww.amazon.com%2Fap%2Fmaplanding"))
        assertEquals("www.amazon.com", fr.signInHost)

        val frRegional = AmazonAuth.create(Region.byTld("fr"), http, endpoints = Endpoints(Region.byTld("fr"), regionalSignIn = true))
        assertTrue(frRegional.signInUrl().startsWith("https://www.amazon.fr/ap/signin?"))
        assertTrue(frRegional.signInUrl().contains("openid.assoc_handle=amzn_dp_project_dee_ios_fr&"))
        assertEquals(".amazon.fr", Endpoints(Region.byTld("fr"), regionalSignIn = true).signInCookieDomain)

        val jp = Endpoints(Region.byTld("co.jp"))
        assertEquals("www.amazon.co.jp", jp.signIn.host)
        assertEquals("amzn_dp_project_dee_ios_jp", jp.signInHandle)

        val uk = Endpoints(Region.byTld("co.uk"), regionalSignIn = true)
        assertEquals("amzn_dp_project_dee_ios_uk", uk.signInHandle)
        assertEquals(".amazon.com", Endpoints(Region.byTld("de")).signInCookieDomain)
    }

    @Test
    fun `expires parsing accepts the amazon formats`() {
        assertNotNull(AmazonAuth.parseExpires("27 Sep 2026 07:53:16 GMT"))
        assertNotNull(AmazonAuth.parseExpires("Sun, 27 Sep 2026 07:53:16 GMT"))
        assertEquals(1_700_000_000_000, AmazonAuth.parseExpires("1700000000"))
        assertNull(AmazonAuth.parseExpires("n/a"))
    }

    @Test
    fun `register then exchange then csrf builds a session`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/auth/register" -> {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val authData = body["auth_data"]!!.jsonObject
                    assertEquals("CODE1", authData["authorization_code"]!!.jsonPrimitive.content)
                    assertEquals("frc-cookie", body["user_context_map"]!!.jsonObject["frc"]!!.jsonPrimitive.content)
                    assertEquals("session-id=abc", request.getHeader("Cookie"))
                    MockResponse().setBody(
                        """{"response":{"success":{"extensions":{"customer_info":{"name":"Orphy","user_id":"u"}},
                        "tokens":{"bearer":{"access_token":"AT","refresh_token":"RT","expires_in":"3600"},"website_cookies":[]}}}}""",
                    )
                }
                "/ap/exchangetoken/cookies" -> {
                    assertTrue(request.body.readUtf8().contains("source_token=RT"))
                    MockResponse().setBody(exchangeBody())
                }
                "/api/language" -> MockResponse().setResponseCode(200)
                    .addHeader("Set-Cookie", "csrf=CSRF123; Path=/")
                    .setBody("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val store = MemoryStore()
        val sessions = SessionManager(store, http, jar, endpointsFor = { endpoints })
        val auth = AmazonAuth.create(region, http, endpoints = endpoints)
        val session = sessions.completeLogin(auth, "CODE1", "session-id=abc", "frc-cookie")
        assertEquals("RT", session.refreshToken)
        assertEquals("CSRF123", session.csrf)
        assertEquals("Orphy", session.customerName)
        assertEquals(2, session.cookies.size)
        assertEquals("at-1", session.cookies.first { it.name == "at-acbfr" }.value)
        assertNotNull(store.session)
        assertTrue(sessions.isLoggedIn)
    }

    @Test
    fun `api refreshes cookies on 401 and retries once`() = runBlocking {
        val deleteCalls = AtomicInteger()
        val exchangeCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                return when {
                    path == "/ap/exchangetoken/cookies" -> { exchangeCalls.incrementAndGet(); MockResponse().setBody(exchangeBody()) }
                    path == "/api/language" -> MockResponse().addHeader("Set-Cookie", "csrf=NEWCSRF; Path=/").setBody("{}")
                    path == "/api/phoenix" && request.method == "GET" -> MockResponse().setBody(Samples.phoenixBody())
                    path == "/api/devices-v2/device" -> MockResponse().setBody(Samples.DEVICES_V2)
                    path!!.startsWith("/api/phoenix/appliance/") && request.method == "DELETE" -> {
                        val n = deleteCalls.incrementAndGet()
                        if (n == 1) MockResponse().setResponseCode(401)
                        else {
                            assertEquals("NEWCSRF", request.getHeader("csrf"))
                            assertTrue(request.getHeader("Cookie")!!.contains("session-id=sid-1"))
                            assertEquals("/api/phoenix/appliance/SKILL_a%2Fb", request.path)
                            MockResponse().setBody("{}")
                        }
                    }
                    path == "/api/phoenix" && request.method == "DELETE" -> MockResponse().setResponseCode(429).addHeader("Retry-After", "7")
                    path == "/api/phoenix/discovery" -> MockResponse().setResponseCode(500).setBody("nope")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val store = MemoryStore().apply {
            session = AlexaSession(
                regionTld = "fr", deviceSerial = "S", refreshToken = "RT",
                cookies = emptyList(), cookiesFetchedAt = 0, csrf = null,
            )
        }
        val sessions = SessionManager(store, http, jar, endpointsFor = { endpoints })
        val api = AlexaApi(http, sessions)

        val snapshot = api.fetchSnapshot()
        assertEquals(4, snapshot.smartHome.size)
        assertEquals(2, snapshot.echos.size)
        assertEquals(1, exchangeCalls.get()) // stale (empty) cookies were exchanged once

        val outcome = api.deleteAppliance("SKILL_a/b")
        assertEquals(DeleteOutcome.Ok, outcome)
        assertEquals(2, deleteCalls.get())
        assertEquals(2, exchangeCalls.get()) // the 401 forced a second exchange

        val wipe = api.deleteAllAppliances()
        assertTrue(wipe is DeleteOutcome.RateLimited)
        assertEquals(7000L, (wipe as DeleteOutcome.RateLimited).retryAfterMs)

        val disc = api.startDiscovery()
        assertTrue(disc is DeleteOutcome.ServerError)
    }
}
