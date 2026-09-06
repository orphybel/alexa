package com.orphybel.alexacleaner.core.api

/**
 * Small, persisted memory of what Amazon's API accepted last time, so each refresh is one
 * round-trip instead of rediscovering the working GraphQL query and re-probing the retired
 * `GET /api/phoenix`. Implementations must be safe to read and write from any thread.
 */
interface ApiHints {
    /** The last GraphQL query string Amazon accepted, or null if none is known yet. */
    var graphQlQuery: String?

    /** Epoch millis until which `GET /api/phoenix` is known retired and should be skipped. */
    var phoenixRetiredUntil: Long

    object None : ApiHints {
        override var graphQlQuery: String? = null
        override var phoenixRetiredUntil: Long = 0
    }
}
