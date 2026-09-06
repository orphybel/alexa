package com.orphybel.alexacleaner.core.api

import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.auth.AuthException
import com.orphybel.alexacleaner.core.auth.SessionManager
import com.orphybel.alexacleaner.core.http.Logger
import com.orphybel.alexacleaner.core.http.await
import com.orphybel.alexacleaner.core.model.DeviceSnapshot
import com.orphybel.alexacleaner.core.model.EchoDevice
import com.orphybel.alexacleaner.core.model.Reachability
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Result of a destructive call, expressed so the purge engine can decide whether to retry. */
sealed class DeleteOutcome(val message: String) {
    object Ok : DeleteOutcome("OK")
    object AlreadyGone : DeleteOutcome("Déjà supprimé")
    class RateLimited(val retryAfterMs: Long?) : DeleteOutcome("Limite de débit atteinte (429)")
    object AuthFailed : DeleteOutcome("Session expirée")
    class ServerError(val code: Int, detail: String) : DeleteOutcome("Erreur serveur $code: $detail")
    class NetworkError(detail: String) : DeleteOutcome("Erreur réseau: $detail")

    val isRetryable: Boolean
        get() = this is RateLimited || this is NetworkError || (this is ServerError && this.code != AlexaApi.RETIRED_ENDPOINT_CODE)
}

/** The subset of the API the purge engine needs; makes the engine testable with a fake. */
interface DeviceDeleter {
    suspend fun deleteAppliance(applianceId: String): DeleteOutcome
    suspend fun deleteAllAppliances(): DeleteOutcome
    suspend fun startDiscovery(): DeleteOutcome
}

class ApiException(message: String, val code: Int = 0) : IOException(message)

/** Client for the private `alexa.amazon.*` web API, authenticated by cookies + CSRF. */
class AlexaApi(
    private val http: OkHttpClient,
    private val sessions: SessionManager,
    private val log: Logger = Logger.NONE,
) : DeviceDeleter {

    private val baseUrl: HttpUrl get() = sessions.endpoints.alexa

    suspend fun fetchSnapshot(): DeviceSnapshot {
        val smartHome = fetchSmartHome()
        val echos = try {
            fetchEchoDevices()
        } catch (e: Exception) {
            log.log("Liste Echo indisponible: ${e.message}")
            emptyList()
        }
        return DeviceSnapshot(System.currentTimeMillis(), smartHome, echos)
    }

    /**
     * Lists smart-home appliances. Tries the historical `GET /api/phoenix` first; when Amazon
     * answers with the "retired endpoint" status (299) or an empty body, falls back to the
     * GraphQL listing used by the current Alexa app. Reachability missing from the listing is
     * then resolved through `POST /api/phoenix/state`.
     */
    suspend fun fetchSmartHome(): List<SmartHomeDevice> {
        var devices: List<SmartHomeDevice>? = null
        val r = call("GET", "api/phoenix", query = mapOf("includeRelationships" to "true"))
        if (r.code in 200..299 && r.code != RETIRED_ENDPOINT_CODE && r.body.isNotBlank()) {
            devices = runCatching { PhoenixParser.parseSmartHome(r.body) }
                .onFailure { log.log("phoenix: réponse illisible (${r.body.length} octets): ${it.message}") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } else {
            log.log("phoenix: HTTP ${r.code}, ${r.body.length} octets — bascule sur GraphQL")
        }
        if (devices == null) devices = fetchSmartHomeGraphQl()
        return resolveReachability(devices)
    }

    /** GraphQL listing, trimming the query until the server accepts it. */
    suspend fun fetchSmartHomeGraphQl(): List<SmartHomeDevice> {
        val root = SmartHomeQuery.full()
        var lastError = "?"
        repeat(MAX_GRAPHQL_REPAIRS) { attempt ->
            val query = SmartHomeQuery.render(root)
            val body = buildJsonObject { put("query", query) }.toString()
            val r = call("POST", "nexus/v1/graphql", body = body.toRequestBody(JSON_MEDIA))
            if (r.code !in 200..299 || r.code == RETIRED_ENDPOINT_CODE) {
                throw ApiException("GraphQL /nexus/v1/graphql -> HTTP ${r.code} ${r.body.take(160)}", r.code)
            }
            val errors = PhoenixParser.graphQlErrors(r.body)
            val parsed = runCatching { PhoenixParser.parseGraphQlEndpoints(r.body) }.getOrDefault(emptyList())
            if (errors.isEmpty() || parsed.isNotEmpty()) {
                if (errors.isNotEmpty()) log.log("GraphQL: ${errors.size} erreur(s) ignorée(s), ${parsed.size} appareils")
                log.log("GraphQL: ${parsed.size} appareils (essai ${attempt + 1}, ${root.leafCount()} champs)")
                return parsed
            }
            lastError = errors.first()
            var repaired = false
            for (msg in errors) {
                val err = GqlValidationError.parse(msg)
                if (SmartHomeQuery.repair(root, err)) {
                    repaired = true
                    log.log("GraphQL: champ retiré '${err.fieldName}' (${err.kind})")
                }
            }
            if (!repaired) {
                if (attempt == 0) {
                    log.log("GraphQL: erreur non réparable, essai avec la requête minimale: ${lastError.take(200)}")
                    val minimal = SmartHomeQuery.minimal()
                    root.children.clear()
                    root.children.addAll(minimal.children)
                } else {
                    throw ApiException("GraphQL refusé: ${lastError.take(300)}")
                }
            }
        }
        throw ApiException("GraphQL: trop de champs rejetés (${lastError.take(200)})")
    }

    /** Fills in reachability for devices the listing left unknown, in batches. */
    suspend fun resolveReachability(devices: List<SmartHomeDevice>): List<SmartHomeDevice> {
        val pending = devices.filter { it.reachability == Reachability.UNKNOWN && !it.entityId.isNullOrBlank() }
        if (pending.isEmpty()) return devices
        val states = HashMap<String, Reachability>()
        for (batch in pending.chunked(STATE_BATCH)) {
            val body = buildJsonObject {
                put(
                    "stateRequests",
                    buildJsonArray {
                        batch.forEach { d ->
                            add(buildJsonObject { put("entityId", d.entityId!!); put("entityType", "APPLIANCE") })
                        }
                    },
                )
            }.toString()
            val r = try {
                call("POST", "api/phoenix/state", body = body.toRequestBody(JSON_MEDIA))
            } catch (e: IOException) {
                log.log("phoenix/state: ${e.message}")
                break
            }
            if (r.code !in 200..299 || r.code == RETIRED_ENDPOINT_CODE || r.body.isBlank()) {
                log.log("phoenix/state: HTTP ${r.code}, statut en ligne indisponible")
                break
            }
            runCatching { states.putAll(PhoenixParser.parseStateReachability(r.body)) }
                .onFailure { log.log("phoenix/state: réponse illisible: ${it.message}") }
        }
        if (states.isEmpty()) return devices
        log.log("phoenix/state: ${states.count { it.value == Reachability.UNREACHABLE }} hors ligne sur ${states.size} interrogés")
        return devices.map { d ->
            val s = d.entityId?.let { states[it] }
            if (d.reachability == Reachability.UNKNOWN && s != null) d.copy(reachability = s) else d
        }
    }

    suspend fun fetchEchoDevices(): List<EchoDevice> {
        val r = call("GET", "api/devices-v2/device", query = mapOf("cached" to "false"))
        if (r.code !in 200..299 || r.code == RETIRED_ENDPOINT_CODE) throw ApiException("GET /api/devices-v2/device -> HTTP ${r.code}", r.code)
        return PhoenixParser.parseEchoDevices(r.body)
    }

    override suspend fun deleteAppliance(applianceId: String): DeleteOutcome = outcome {
        call("DELETE", "api/phoenix/appliance/${encodeSegment(applianceId)}")
    }

    /** "Forget all devices": wipes every smart-home appliance of the account in one call. */
    override suspend fun deleteAllAppliances(): DeleteOutcome = outcome { call("DELETE", "api/phoenix") }

    /** Asks every enabled skill / hub to report its devices again. */
    override suspend fun startDiscovery(): DeleteOutcome = outcome {
        call("POST", "api/phoenix/discovery", body = "{}".toRequestBody(JSON_MEDIA))
    }

    /**
     * Calls every endpoint the app relies on (read-only ones, plus a DELETE on a non-existent
     * appliance id) and reports status codes and body excerpts. Used to see what Amazon still
     * serves for this account.
     */
    suspend fun diagnostics(sampleEntityId: String?): List<String> {
        val out = ArrayList<String>()
        suspend fun probe(label: String, block: suspend () -> ApiResponse) {
            try {
                val r = block()
                out += "$label -> HTTP ${r.code}, ${r.body.length} octets: ${r.body.replace(Regex("\\s+"), " ").take(220)}"
            } catch (e: Exception) {
                out += "$label -> ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        probe("GET /api/phoenix") { call("GET", "api/phoenix") }
        probe("POST /nexus/v1/graphql (minimal)") {
            val body = buildJsonObject { put("query", SmartHomeQuery.render(SmartHomeQuery.minimal())) }.toString()
            call("POST", "nexus/v1/graphql", body = body.toRequestBody(JSON_MEDIA))
        }
        probe("POST /nexus/v1/graphql (complet)") {
            val body = buildJsonObject { put("query", SmartHomeQuery.render(SmartHomeQuery.full())) }.toString()
            call("POST", "nexus/v1/graphql", body = body.toRequestBody(JSON_MEDIA))
        }
        if (sampleEntityId != null) {
            probe("POST /api/phoenix/state") {
                val body = buildJsonObject {
                    put("stateRequests", buildJsonArray { add(buildJsonObject { put("entityId", sampleEntityId); put("entityType", "APPLIANCE") }) })
                }.toString()
                call("POST", "api/phoenix/state", body = body.toRequestBody(JSON_MEDIA))
            }
        }
        probe("GET /api/behaviors/entities?skillId=amzn1.ask.1p.smarthome") {
            call("GET", "api/behaviors/entities", query = mapOf("skillId" to "amzn1.ask.1p.smarthome"))
        }
        probe("GET /api/devices-v2/device") { call("GET", "api/devices-v2/device", query = mapOf("cached" to "false")) }
        probe("DELETE /api/phoenix/appliance/<id inexistant>") {
            call("DELETE", "api/phoenix/appliance/${encodeSegment("AlexaCleaner_probe_does_not_exist")}")
        }
        out.forEach { log.log("Diag: $it") }
        return out
    }

    private suspend fun outcome(block: suspend () -> ApiResponse): DeleteOutcome = try {
        val r = block()
        when (r.code) {
            RETIRED_ENDPOINT_CODE -> DeleteOutcome.ServerError(RETIRED_ENDPOINT_CODE, "point d'API retiré par Amazon")
            in 200..299 -> DeleteOutcome.Ok
            404, 410 -> DeleteOutcome.AlreadyGone
            429 -> DeleteOutcome.RateLimited(r.retryAfterMs)
            401, 403 -> DeleteOutcome.AuthFailed
            else -> DeleteOutcome.ServerError(r.code, r.body.take(160))
        }
    } catch (e: AuthException) {
        DeleteOutcome.AuthFailed
    } catch (e: IOException) {
        DeleteOutcome.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    class ApiResponse(val code: Int, val body: String, val retryAfterMs: Long?)

    /**
     * Performs an authenticated request. On an authentication failure the cookies are refreshed
     * from the refresh token and the request is retried once.
     */
    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        allowAuthRetry: Boolean = true,
    ): ApiResponse {
        sessions.ensureReady()
        val session = sessions.session ?: throw AuthException("Non connecté")
        val region = sessions.region
        val url = baseUrl.newBuilder().addEncodedPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AmazonAuth.USER_AGENT)
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", region.language)
            .header("DNT", "1")
            .header("Origin", baseUrl.toString().trimEnd('/'))
            .header("Referer", baseUrl.newBuilder().addEncodedPathSegments("spa/index.html").build().toString())
            .apply { session.csrf?.let { header("csrf", it) } }
            .method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") "".toRequestBody(null) else null)
            .build()
        val response = http.newCall(request).await()
        val text: String
        val code: Int
        val retryAfter: Long?
        response.use { r ->
            text = r.body?.string().orEmpty()
            code = r.code
            retryAfter = r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000 }
        }
        log.log("$method /${path} -> $code")
        val redirectedToLogin = code in 300..399 && (response.header("Location")?.contains("signin") == true)
        val looksLikeHtml = code in 200..299 && text.trimStart().startsWith("<")
        val authFailure = code == 401 || code == 403 || redirectedToLogin || looksLikeHtml
        if (authFailure && allowAuthRetry) {
            log.log("Authentification rejetée, renouvellement de la session")
            sessions.ensureReady(force = true)
            return call(method, path, query, body, allowAuthRetry = false)
        }
        if (authFailure) throw AuthException("Session Amazon invalide (HTTP $code). Reconnectez-vous.")
        return ApiResponse(code, text, retryAfter)
    }

    private fun encodeSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        /** Non-standard status Amazon returns for endpoints it has retired. */
        const val RETIRED_ENDPOINT_CODE = 299
        private const val MAX_GRAPHQL_REPAIRS = 14
        private const val STATE_BATCH = 40
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
