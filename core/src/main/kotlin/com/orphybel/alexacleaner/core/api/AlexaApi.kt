package com.orphybel.alexacleaner.core.api

import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.auth.AuthException
import com.orphybel.alexacleaner.core.auth.SessionManager
import com.orphybel.alexacleaner.core.http.Logger
import com.orphybel.alexacleaner.core.http.await
import com.orphybel.alexacleaner.core.model.DeviceSnapshot
import com.orphybel.alexacleaner.core.model.EchoDevice
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    val isRetryable: Boolean get() = this is RateLimited || this is ServerError || this is NetworkError
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

    suspend fun fetchSmartHome(): List<SmartHomeDevice> {
        val r = call("GET", "api/phoenix", query = mapOf("includeRelationships" to "true"))
        if (r.code !in 200..299) throw ApiException("GET /api/phoenix -> HTTP ${r.code}", r.code)
        return PhoenixParser.parseSmartHome(r.body)
    }

    suspend fun fetchEchoDevices(): List<EchoDevice> {
        val r = call("GET", "api/devices-v2/device", query = mapOf("cached" to "false"))
        if (r.code !in 200..299) throw ApiException("GET /api/devices-v2/device -> HTTP ${r.code}", r.code)
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

    private suspend fun outcome(block: suspend () -> ApiResponse): DeleteOutcome = try {
        val r = block()
        when (r.code) {
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
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
