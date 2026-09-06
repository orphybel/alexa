package com.orphybel.alexacleaner.core

import com.orphybel.alexacleaner.core.api.AlexaApi
import com.orphybel.alexacleaner.core.api.DeleteOutcome
import com.orphybel.alexacleaner.core.api.GqlValidationError
import com.orphybel.alexacleaner.core.api.PhoenixParser
import com.orphybel.alexacleaner.core.api.SmartHomeQuery
import com.orphybel.alexacleaner.core.auth.AlexaSession
import com.orphybel.alexacleaner.core.auth.MemoryCookieJar
import com.orphybel.alexacleaner.core.auth.SessionManager
import com.orphybel.alexacleaner.core.auth.SessionStore
import com.orphybel.alexacleaner.core.auth.StoredCookie
import com.orphybel.alexacleaner.core.http.Endpoints
import com.orphybel.alexacleaner.core.model.Reachability
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQlTest {

    private val graphQlBody = """
    {"data":{"endpoints":{"items":[
      {"endpointId":"amzn1.alexa.endpoint.1","id":"e1","friendlyName":"Lampe Bureau","enablement":"ENABLED",
       "displayCategories":{"primary":{"value":"LIGHT"},"all":[{"value":"LIGHT"}]},
       "legacyIdentifiers":{"chrsIdentifier":{"entityId":"ent-1"}},
       "legacyAppliance":{"applianceId":"SKILL_x1","manufacturerName":"Tuya","friendlyName":"Lampe Bureau","modelName":"T1",
         "friendlyDescription":"via Smart Life","connectedVia":"","mergedApplianceIds":["SKILL_x1"],"driverIdentity":{"namespace":"SKILL","identifier":"amzn1.ask.skill.aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"}}},
      {"endpointId":"amzn1.alexa.endpoint.2","id":"e2","friendlyName":"Prise TV","enablement":"DISABLED",
       "displayCategories":{"primary":{"value":"SMARTPLUG"}},
       "legacyIdentifiers":{"chrsIdentifier":{"entityId":"ent-2"}},
       "legacyAppliance":{"applianceId":"SKILL_x2","manufacturerName":"Meross","applianceNetworkState":{"reachability":"UNREACHABLE","lastSeenAt":1700000000000}}},
      {"endpointId":"amzn1.alexa.endpoint.3","id":"e3","friendlyName":"Echo Cuisine","displayCategories":{"primary":{"value":"ALEXA_VOICE_ENABLED"}}}
    ]}}}
    """.trimIndent()

    @Test
    fun `parses graphql endpoints`() {
        val devices = PhoenixParser.parseGraphQlEndpoints(graphQlBody)
        assertEquals(3, devices.size)
        val lamp = devices.first { it.applianceId == "SKILL_x1" }
        assertEquals("Lampe Bureau", lamp.friendlyName)
        assertEquals("Tuya", lamp.source)
        assertEquals(listOf("LIGHT"), lamp.applianceTypes)
        assertEquals("ent-1", lamp.entityId)
        assertEquals("amzn1.alexa.endpoint.1", lamp.endpointId)
        assertEquals("amzn1.ask.skill.aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", lamp.skillId)
        assertEquals(Reachability.UNKNOWN, lamp.reachability)
        assertTrue(lamp.isEnabled)

        val plug = devices.first { it.applianceId == "SKILL_x2" }
        assertEquals(Reachability.UNREACHABLE, plug.reachability)
        assertFalse(plug.isEnabled)
        assertEquals(1700000000000, plug.lastSeenAt)

        val echo = devices.first { it.applianceId == "amzn1.alexa.endpoint.3" }
        assertEquals(listOf("ALEXA_VOICE_ENABLED"), echo.applianceTypes)
    }

    @Test
    fun `parses validation errors from graphql-java and graphql-js`() {
        val a = GqlValidationError.parse("Validation error (FieldUndefined@[endpoints/items/legacyAppliance/isEnabled]) : Field 'isEnabled' in type 'LegacyAppliance' is undefined")
        assertEquals(GqlValidationError.Kind.UNDEFINED_FIELD, a.kind)
        assertEquals(listOf("endpoints", "items", "legacyAppliance", "isEnabled"), a.path)
        assertEquals("isEnabled", a.fieldName)

        val b = GqlValidationError.parse("Validation error of type FieldUndefined: Field 'capabilities' in type 'LegacyAppliance' is undefined @ 'endpoints/items/legacyAppliance/capabilities'")
        assertEquals(listOf("endpoints", "items", "legacyAppliance", "capabilities"), b.path)

        val c = GqlValidationError.parse("Cannot query field \"enablement\" on type \"Endpoint\".")
        assertEquals(GqlValidationError.Kind.UNDEFINED_FIELD, c.kind)
        assertEquals("enablement", c.fieldName)
        assertEquals(null, c.path)

        val d = GqlValidationError.parse("Validation error (SubselectionNotAllowed@[endpoints/items/legacyAppliance/aliases]) : Subselection not allowed on leaf type 'String' of field 'aliases'")
        assertEquals(GqlValidationError.Kind.SUBSELECTION_NOT_ALLOWED, d.kind)

        val e = GqlValidationError.parse("Field \"capabilities\" of type \"[Capability]\" must have a selection of subfields.")
        assertEquals(GqlValidationError.Kind.SUBSELECTION_REQUIRED, e.kind)
    }

    @Test
    fun `repair trims the query`() {
        val root = SmartHomeQuery.full()
        val before = root.leafCount()
        assertTrue(SmartHomeQuery.repair(root, GqlValidationError.parse("Validation error (FieldUndefined@[endpoints/items/legacyAppliance/isEnabled]) : Field 'isEnabled' in type 'LegacyAppliance' is undefined")))
        assertEquals(before - 1, root.leafCount())
        assertFalse(SmartHomeQuery.render(root).contains("isEnabled"))
        // Path-less error: every field with that name goes.
        assertTrue(SmartHomeQuery.repair(root, GqlValidationError.parse("Cannot query field \"friendlyName\" on type \"LegacyAppliance\".")))
        assertFalse(SmartHomeQuery.render(root).contains("friendlyName"))
        // Sub-selection not allowed keeps the field but drops its children.
        assertTrue(SmartHomeQuery.repair(root, GqlValidationError.parse("Validation error (SubselectionNotAllowed@[endpoints/items/legacyAppliance/aliases]) : Subselection not allowed on leaf type 'String' of field 'aliases'")))
        assertTrue(SmartHomeQuery.render(root).contains(" aliases "))
        assertFalse(SmartHomeQuery.render(root).contains("aliases {"))
        assertFalse(SmartHomeQuery.repair(root, GqlValidationError.parse("Internal server error")))
        assertTrue(SmartHomeQuery.render(root).startsWith("query CustomerSmartHome { endpoints(endpointsQueryParams: { paginationParams: { disablePagination: true } }) { items {"))
    }

    @Test
    fun `state response maps reachability including not-found ghosts`() {
        val body = """{"deviceStates":[{"entity":{"entityId":"ent-1","entityType":"APPLIANCE"},"capabilityStates":["{\"namespace\":\"Alexa.PowerController\"}"]}],
          "errors":[
            {"entity":{"entityId":"ent-2","entityType":"APPLIANCE"},"code":"ENDPOINT_UNREACHABLE","message":"Unable to reach"},
            {"entity":{"entityId":"ent-3","entityType":"APPLIANCE"},"code":"TargetApplianceNotFoundException","message":"No appliance found for target!"}
          ]}"""
        val m = PhoenixParser.parseStateReachability(body)
        assertEquals(Reachability.REACHABLE, m["ent-1"])
        assertEquals(Reachability.UNREACHABLE, m["ent-2"])
        // A listed endpoint that the state API can no longer find is a ghost: treat as offline.
        assertEquals(Reachability.UNREACHABLE, m["ent-3"])
    }

    @Test
    fun `parses network state and driver identity serialized as json strings`() {
        val body = """
        {"data":{"endpoints":{"items":[
          {"endpointId":"e9","friendlyName":"Vieux capteur",
           "legacyIdentifiers":{"chrsIdentifier":{"entityId":"ent-9"}},
           "legacyAppliance":{"applianceId":"SKILL_x9","manufacturerName":"Aqara",
             "applianceNetworkState":"{\"reachability\":\"UNREACHABLE\",\"lastSeenAt\":1699999999000}",
             "driverIdentity":"{\"namespace\":\"SKILL\",\"identifier\":\"amzn1.ask.skill.99999999-8888-7777-6666-555555555555\"}"}}
        ]}}}
        """.trimIndent()
        val d = PhoenixParser.parseGraphQlEndpoints(body).single()
        assertEquals(Reachability.UNREACHABLE, d.reachability)
        assertEquals(1699999999000, d.lastSeenAt)
        assertEquals("amzn1.ask.skill.99999999-8888-7777-6666-555555555555", d.skillId)
    }

    // ------------------------------------------------------------------ end to end against a mock server

    private lateinit var server: MockWebServer
    private lateinit var api: AlexaApi
    private val hints = object : com.orphybel.alexacleaner.core.api.ApiHints {
        override var graphQlQuery: String? = null
        override var phoenixRetiredUntil: Long = 0
    }

    private class MemoryStore(var session: AlexaSession?) : SessionStore {
        override fun load() = session
        override fun save(session: AlexaSession?) { this.session = session }
    }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        val jar = MemoryCookieJar()
        val http = OkHttpClient.Builder().cookieJar(jar).followRedirects(false).build()
        val base = server.url("/")
        val region = Region.byTld("fr")
        val endpoints = object : Endpoints(region) {
            override val amazon: HttpUrl get() = base
            override val api: HttpUrl get() = base
            override val alexa: HttpUrl get() = base
        }
        val now = System.currentTimeMillis()
        val store = MemoryStore(
            AlexaSession(
                regionTld = "fr", deviceSerial = "S", refreshToken = "RT", csrf = "CSRF",
                cookies = listOf(StoredCookie("session-id", "sid", server.hostName, "/", now + 86_400_000, secure = false)),
                cookiesFetchedAt = now,
            ),
        )
        val sessions = SessionManager(store, http, jar, endpointsFor = { endpoints })
        api = AlexaApi(http, sessions, hints = hints)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `falls back to graphql on 299, repairs the query, resolves reachability`() = runBlocking {
        val graphQlCalls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                return when {
                    path == "/api/phoenix" && request.method == "GET" -> MockResponse().setResponseCode(299)
                    path == "/nexus/v1/graphql" -> {
                        val query = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["query"]!!.jsonPrimitive.content
                        assertEquals("CSRF", request.getHeader("csrf"))
                        graphQlCalls.incrementAndGet()
                        // Amazon rejects `isEnabled`/`enablement`; every other query is accepted.
                        if (query.contains("isEnabled") || query.contains("enablement")) {
                            MockResponse().setBody("""{"errors":[{"message":"Validation error (FieldUndefined@[endpoints/items/legacyAppliance/isEnabled]) : Field 'isEnabled' in type 'LegacyAppliance' is undefined"},{"message":"Validation error (FieldUndefined@[endpoints/items/enablement]) : Field 'enablement' in type 'Endpoint' is undefined"}]}""")
                        } else {
                            MockResponse().setBody(graphQlBody)
                        }
                    }
                    path == "/api/phoenix/state" -> {
                        val body = request.body.readUtf8()
                        assertTrue(body.contains("ent-1"))
                        assertFalse(body.contains("ent-2")) // already known offline
                        MockResponse().setBody("""{"deviceStates":[{"entity":{"entityId":"ent-1","entityType":"APPLIANCE"},"capabilityStates":["{}"]}],"errors":[]}""")
                    }
                    path == "/api/devices-v2/device" -> MockResponse().setBody(Samples.DEVICES_V2)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val devices = api.fetchSmartHome()
        assertEquals(2, graphQlCalls.get())
        assertEquals(3, devices.size)
        assertEquals(Reachability.REACHABLE, devices.first { it.applianceId == "SKILL_x1" }.reachability)
        assertEquals(Reachability.UNREACHABLE, devices.first { it.applianceId == "SKILL_x2" }.reachability)
        assertEquals(Reachability.UNKNOWN, devices.first { it.applianceId == "amzn1.alexa.endpoint.3" }.reachability)

        // The winning query and the retired-phoenix fact were remembered.
        assertTrue(hints.graphQlQuery!!.isNotBlank())
        assertTrue(hints.phoenixRetiredUntil > System.currentTimeMillis())

        // Next refresh skips GET /api/phoenix and reuses the cached query: a single GraphQL call.
        graphQlCalls.set(0)
        val again = api.fetchSmartHome()
        assertEquals(3, again.size)
        assertEquals(1, graphQlCalls.get())
    }

    @Test
    fun `retired delete endpoint is reported and not retried`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(299)
        }
        val outcome = api.deleteAppliance("SKILL_x1")
        assertTrue(outcome is DeleteOutcome.ServerError)
        assertEquals(299, (outcome as DeleteOutcome.ServerError).code)
        assertFalse(outcome.isRetryable)
    }
}
