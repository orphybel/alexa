package com.orphybel.alexacleaner.core.http

import com.orphybel.alexacleaner.core.model.Region
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Base URLs used by the auth flow and the API. Overridable so tests can point at a mock server.
 *
 * The OAuth sign-in is done on `www.amazon.com` by default, whatever the account's marketplace:
 * Amazon accounts are global, the `amzn_dp_project_dee_ios` association handle is registered
 * there, and regional cookies are obtained afterwards from the refresh token. This is what
 * alexa-cookie2 does. Japan is the exception (own handle on amazon.co.jp). [regionalSignIn]
 * forces the sign-in on the regional domain instead.
 */
open class Endpoints(val region: Region, val regionalSignIn: Boolean = false) {
    /** `https://www.amazon.<tld>/` — cookie exchange (and sign-in when [regionalSignIn]). */
    open val amazon: HttpUrl get() = "https://${region.amazonHost}/".toHttpUrl()

    /** `https://api.amazon.com/` — device registration and token refresh. */
    open val api: HttpUrl get() = "https://api.amazon.com/".toHttpUrl()

    /** `https://alexa.amazon.<tld>/` — the private Alexa web API. */
    open val alexa: HttpUrl get() = "https://${region.alexaHost}/".toHttpUrl()

    /** Domain on which the OAuth sign-in page is opened. */
    open val signIn: HttpUrl
        get() = when {
            regionalSignIn -> amazon
            region.tld == "co.jp" -> "https://www.amazon.co.jp/".toHttpUrl()
            else -> "https://www.amazon.com/".toHttpUrl()
        }

    /** Association handle / pageId matching [signIn]. */
    open val signInHandle: String
        get() {
            val host = signIn.host
            val tld = host.removePrefix("www.").removePrefix("amazon.")
            return when (tld) {
                "com" -> "amzn_dp_project_dee_ios"
                "co.jp" -> "amzn_dp_project_dee_ios_jp"
                else -> "amzn_dp_project_dee_ios_" + tld.substringAfterLast('.')
            }
        }

    /** Cookie domain of the sign-in page, e.g. `.amazon.com`. */
    open val signInCookieDomain: String get() = "." + signIn.host.removePrefix("www.")

    open val identityAuthDomain: String get() = "api.amazon.com"
    open val regionalIdentityAuthDomain: String get() = "api.amazon.${region.tld}"

    fun amazon(path: String): HttpUrl = amazon.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
    fun signIn(path: String): HttpUrl = signIn.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
    fun api(path: String): HttpUrl = api.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
    fun alexa(path: String): HttpUrl = alexa.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
}
