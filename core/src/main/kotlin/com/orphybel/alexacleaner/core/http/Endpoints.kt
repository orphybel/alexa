package com.orphybel.alexacleaner.core.http

import com.orphybel.alexacleaner.core.model.Region
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Base URLs used by the auth flow and the API. Overridable so tests can point at a mock server. */
open class Endpoints(val region: Region) {
    /** `https://www.amazon.<tld>/` — sign-in page and cookie exchange. */
    open val amazon: HttpUrl get() = "https://${region.amazonHost}/".toHttpUrl()

    /** `https://api.amazon.com/` — device registration and token refresh. */
    open val api: HttpUrl get() = "https://api.amazon.com/".toHttpUrl()

    /** `https://alexa.amazon.<tld>/` — the private Alexa web API. */
    open val alexa: HttpUrl get() = "https://${region.alexaHost}/".toHttpUrl()

    open val identityAuthDomain: String get() = "api.amazon.com"
    open val regionalIdentityAuthDomain: String get() = "api.amazon.${region.tld}"

    fun amazon(path: String): HttpUrl = amazon.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
    fun api(path: String): HttpUrl = api.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
    fun alexa(path: String): HttpUrl = alexa.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()
}
