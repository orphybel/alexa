package com.orphybel.alexacleaner.core.auth

import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Serializable cookie, independent from OkHttp so it can be persisted. */
@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    /** Epoch millis, or null when it is a session cookie. */
    val expiresAt: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
) {
    fun toOkHttp(): Cookie {
        val b = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain.removePrefix("."))
            .path(path.ifBlank { "/" })
        expiresAt?.let { b.expiresAt(it) }
        if (secure) b.secure()
        if (httpOnly) b.httpOnly()
        return b.build()
    }

    companion object {
        fun from(c: Cookie): StoredCookie = StoredCookie(
            name = c.name,
            value = c.value,
            domain = c.domain,
            path = c.path,
            expiresAt = c.expiresAt.takeIf { !c.persistent || it < 253402300799999L },
            secure = c.secure,
            httpOnly = c.httpOnly,
        )
    }
}

/** Everything needed to talk to Alexa without asking the user to log in again. */
@Serializable
data class AlexaSession(
    val regionTld: String,
    val deviceSerial: String,
    val refreshToken: String,
    val accessToken: String? = null,
    val accessTokenExpiresAt: Long = 0,
    val cookies: List<StoredCookie> = emptyList(),
    val cookiesFetchedAt: Long = 0,
    val csrf: String? = null,
    val customerName: String? = null,
    val loggedInAt: Long = 0,
)

/** Simple in-memory cookie jar. All cookies are kept, and served to any URL they match. */
class MemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) store[key(c)] = c
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized
    fun replaceAll(cookies: List<Cookie>) {
        store.clear()
        cookies.forEach { store[key(it)] = it }
    }

    @Synchronized
    fun add(cookie: Cookie) {
        store[key(cookie)] = cookie
    }

    @Synchronized
    fun all(): List<Cookie> = store.values.toList()

    @Synchronized
    fun find(name: String): Cookie? = store.values.lastOrNull { it.name == name }

    @Synchronized
    fun clear() = store.clear()
}
