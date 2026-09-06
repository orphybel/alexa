package com.orphybel.alexacleaner.core.auth

import com.orphybel.alexacleaner.core.http.Endpoints
import com.orphybel.alexacleaner.core.http.Logger
import com.orphybel.alexacleaner.core.http.await
import com.orphybel.alexacleaner.core.model.Region
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RegistrationResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val customerName: String?,
    val websiteCookies: List<StoredCookie>,
)

/**
 * Implements the OAuth "device registration" flow used by the official Alexa mobile app
 * (PKCE authorization code → /auth/register → refresh token → regional website cookies).
 *
 * This is the same technique used by alexa-cookie2 / alexa-remote-control / alexapy. It is an
 * undocumented, private Amazon API and may stop working at any time.
 */
class AmazonAuth(
    val region: Region,
    val deviceSerial: String,
    val codeVerifier: String,
    private val http: OkHttpClient,
    private val log: Logger = Logger.NONE,
    private val endpoints: Endpoints = Endpoints(region),
) {
    val clientId: String = hex("$deviceSerial#$DEVICE_TYPE".toByteArray(Charsets.US_ASCII))
    val codeChallenge: String = base64Url(sha256(codeVerifier.toByteArray(Charsets.US_ASCII)))

    fun signInUrl(): String = endpoints.amazon("ap/signin").newBuilder()
        .addQueryParameter("openid.return_to", endpoints.amazon("ap/maplanding").toString())
        .addQueryParameter("openid.oa2.code_challenge_method", "S256")
        .addQueryParameter("openid.assoc_handle", "amzn_dp_project_dee_ios")
        .addQueryParameter("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select")
        .addQueryParameter("pageId", "amzn_dp_project_dee_ios")
        .addQueryParameter("accountStatusPolicy", "P1")
        .addQueryParameter("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select")
        .addQueryParameter("openid.mode", "checkid_setup")
        .addQueryParameter("openid.ns.oa2", "http://www.amazon.com/ap/ext/oauth/2")
        .addQueryParameter("openid.oa2.client_id", "device:$clientId")
        .addQueryParameter("openid.ns.pape", "http://specs.openid.net/extensions/pape/1.0")
        .addQueryParameter("openid.oa2.code_challenge", codeChallenge)
        .addQueryParameter("openid.oa2.scope", "device_auth_access")
        .addQueryParameter("openid.ns", "http://specs.openid.net/auth/2.0")
        .addQueryParameter("openid.pape.max_auth_age", "0")
        .addQueryParameter("openid.oa2.response_type", "code")
        .addQueryParameter("language", region.language.replace('-', '_'))
        .build()
        .toString()

    /** Registers this pseudo-device with the authorization code captured from the `maplanding` redirect. */
    suspend fun register(authorizationCode: String, loginCookieHeader: String?, frc: String?): RegistrationResult {
        val payload = buildJsonObject {
            putJsonArray("requested_extensions") { add(JsonPrimitive("device_info")); add(JsonPrimitive("customer_info")) }
            putJsonObject("cookies") {
                putJsonArray("website_cookies") {}
                put("domain", region.cookieDomain)
            }
            putJsonObject("registration_data") {
                put("domain", "Device")
                put("app_version", APP_VERSION)
                put("device_type", DEVICE_TYPE)
                put("device_name", "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%Alexa Cleaner")
                put("os_version", OS_VERSION)
                put("device_serial", deviceSerial)
                put("device_model", "iPhone")
                put("app_name", "Alexa Cleaner")
                put("software_version", "1")
            }
            putJsonObject("auth_data") {
                put("client_id", clientId)
                put("authorization_code", authorizationCode)
                put("code_verifier", codeVerifier)
                put("code_algorithm", "SHA-256")
                put("client_domain", "DeviceLegacy")
            }
            if (!frc.isNullOrBlank()) putJsonObject("user_context_map") { put("frc", frc) }
            putJsonArray("requested_token_type") {
                add(JsonPrimitive("bearer")); add(JsonPrimitive("mac_dms")); add(JsonPrimitive("website_cookies"))
            }
        }
        val request = Request.Builder()
            .url(endpoints.api("auth/register"))
            .header("x-amzn-identity-auth-domain", endpoints.identityAuthDomain)
            .header("Accept-Language", region.language)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply { if (!loginCookieHeader.isNullOrBlank()) header("Cookie", loginCookieHeader) }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        log.log("POST /auth/register")
        val body = http.newCall(request).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AuthException("Enregistrement refusé (HTTP ${r.code}): ${errorMessage(text)}")
            text
        }
        val root = json.parseToJsonElement(body).jsonObject
        val response = root["response"]?.jsonObject ?: throw AuthException("Réponse d'enregistrement inattendue")
        val success = response["success"]?.jsonObject
            ?: throw AuthException("Enregistrement refusé: ${errorMessage(body)}")
        val tokens = success["tokens"]?.jsonObject ?: throw AuthException("Jetons absents de la réponse")
        val bearer = tokens["bearer"]?.jsonObject ?: throw AuthException("Jeton bearer absent")
        val access = bearer.str("access_token") ?: throw AuthException("access_token absent")
        val refresh = bearer.str("refresh_token") ?: throw AuthException("refresh_token absent")
        val expiresIn = (bearer["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 3600
        val customerName = success["extensions"]?.jsonObject?.get("customer_info")?.jsonObject?.str("name")
        val websiteCookies = (tokens["website_cookies"] as? JsonArray)
            ?.mapNotNull { parseCookieObject(it as? JsonObject ?: return@mapNotNull null, ".amazon.com") }
            ?: emptyList()
        return RegistrationResult(access, refresh, expiresIn, customerName, websiteCookies)
    }

    /** Exchanges the long-lived refresh token for website cookies valid on the regional Amazon/Alexa domain. */
    suspend fun exchangeCookies(refreshToken: String): List<StoredCookie> {
        val form = FormBody.Builder()
            .add("di.os.name", "iOS")
            .add("app_version", APP_VERSION)
            .add("domain", region.cookieDomain)
            .add("source_token", refreshToken)
            .add("requested_token_type", "auth_cookies")
            .add("source_token_type", "refresh_token")
            .add("di.hw.version", "iPhone")
            .add("di.sdk.version", SDK_VERSION)
            .add("cookies", "")
            .add("app_name", "Amazon Alexa")
            .add("di.os.version", OS_VERSION)
            .build()
        val request = Request.Builder()
            .url(endpoints.amazon("ap/exchangetoken/cookies"))
            .header("x-amzn-identity-auth-domain", endpoints.regionalIdentityAuthDomain)
            .header("Accept-Language", region.language)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        log.log("POST /ap/exchangetoken/cookies")
        val body = http.newCall(request).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AuthException("Échange de cookies refusé (HTTP ${r.code}): ${errorMessage(text)}")
            text
        }
        val root = json.parseToJsonElement(body).jsonObject
        val cookiesByDomain = root["response"]?.jsonObject?.get("tokens")?.jsonObject?.get("cookies")?.jsonObject
            ?: throw AuthException("Cookies absents de la réponse: ${errorMessage(body)}")
        val result = ArrayList<StoredCookie>()
        for ((domain, arr) in cookiesByDomain) {
            (arr as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { parseCookieObject(it, domain) }?.let(result::add)
            }
        }
        if (result.isEmpty()) throw AuthException("Aucun cookie renvoyé par Amazon")
        return result
    }

    /** Refreshes the bearer access token. Mostly used to verify the refresh token is still valid. */
    suspend fun refreshAccessToken(refreshToken: String): Pair<String, Long> {
        val form = FormBody.Builder()
            .add("app_name", "Amazon Alexa")
            .add("app_version", APP_VERSION)
            .add("di.sdk.version", SDK_VERSION)
            .add("source_token", refreshToken)
            .add("package_name", "com.amazon.echo")
            .add("di.hw.version", "iPhone")
            .add("platform", "iOS")
            .add("requested_token_type", "access_token")
            .add("source_token_type", "refresh_token")
            .add("di.os.name", "iOS")
            .add("di.os.version", OS_VERSION)
            .add("current_version", SDK_VERSION)
            .add("previous_version", SDK_VERSION)
            .build()
        val request = Request.Builder()
            .url(endpoints.api("auth/token"))
            .header("x-amzn-identity-auth-domain", endpoints.identityAuthDomain)
            .header("Accept-Language", region.language)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        val body = http.newCall(request).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw AuthException("Rafraîchissement du jeton refusé (HTTP ${r.code}): ${errorMessage(text)}")
            text
        }
        val root = json.parseToJsonElement(body).jsonObject
        val token = root.str("access_token") ?: throw AuthException("access_token absent")
        val expiresIn = (root["expires_in"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 3600
        return token to (System.currentTimeMillis() + expiresIn * 1000)
    }

    private fun parseCookieObject(o: JsonObject, defaultDomain: String): StoredCookie? {
        val name = o.str("Name") ?: o.str("name") ?: return null
        val value = (o.str("Value") ?: o.str("value") ?: return null).trim('"')
        val domain = o.str("Domain") ?: o.str("domain") ?: defaultDomain
        val path = o.str("Path") ?: o.str("path") ?: "/"
        val expires = (o.str("Expires") ?: o.str("expires"))?.let(::parseExpires)
        val secure = (o["Secure"] as? JsonPrimitive)?.booleanOrNull ?: (o["secure"] as? JsonPrimitive)?.booleanOrNull ?: true
        val httpOnly = (o["HttpOnly"] as? JsonPrimitive)?.booleanOrNull ?: (o["httpOnly"] as? JsonPrimitive)?.booleanOrNull ?: false
        return StoredCookie(name, value, domain, path, expires, secure, httpOnly)
    }

    private fun errorMessage(body: String): String = try {
        val root = json.parseToJsonElement(body).jsonObject
        val err = root["response"]?.jsonObject?.get("error")?.jsonObject ?: root["error"]?.jsonObject
        err?.str("message") ?: err?.str("code") ?: body.take(200)
    } catch (_: Throwable) {
        body.take(200)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEVICE_TYPE = "A2IVLV5VM2W81"
        const val APP_VERSION = "2.2.556530.0"
        const val OS_VERSION = "16.6"
        const val SDK_VERSION = "6.12.4"
        const val USER_AGENT = "AmazonWebView/Amazon Alexa/$APP_VERSION/iOS/$OS_VERSION/iPhone"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val random = SecureRandom()

        private val EXPIRES_FORMATS = listOf(
            "dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "d MMM yyyy HH:mm:ss zzz",
        ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

        fun newDeviceSerial(): String = ByteArray(16).also(random::nextBytes).let(::hex).uppercase(Locale.ROOT)

        fun newCodeVerifier(): String = base64Url(ByteArray(32).also(random::nextBytes))

        fun create(region: Region, http: OkHttpClient, log: Logger = Logger.NONE, endpoints: Endpoints = Endpoints(region)): AmazonAuth =
            AmazonAuth(region, newDeviceSerial(), newCodeVerifier(), http, log, endpoints)

        /** Returns the authorization code when [url] is the `maplanding` redirect, null otherwise. */
        fun extractAuthorizationCode(url: String): String? {
            if (!url.contains("/ap/maplanding")) return null
            val parsed = url.toHttpUrlOrNullSafe() ?: return null
            return parsed.queryParameter("openid.oa2.authorization_code")?.takeIf { it.isNotBlank() }
        }

        private fun String.toHttpUrlOrNullSafe(): HttpUrl? = try {
            this.toHttpUrlOrNull()
        } catch (_: Throwable) {
            null
        }

        internal fun parseExpires(text: String): Long? {
            val t = text.trim()
            t.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000 else it }
            for (f in EXPIRES_FORMATS) {
                try {
                    return ZonedDateTime.parse(t, f).toInstant().toEpochMilli()
                } catch (_: Throwable) {
                }
            }
            return null
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
