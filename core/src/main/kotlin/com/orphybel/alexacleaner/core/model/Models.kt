package com.orphybel.alexacleaner.core.model

import kotlinx.serialization.Serializable

/** Amazon marketplace on which the Alexa account lives. */
@Serializable
data class Region(val tld: String, val label: String, val language: String) {
    val amazonHost: String get() = "www.amazon.$tld"
    val alexaHost: String get() = "alexa.amazon.$tld"
    val cookieDomain: String get() = ".amazon.$tld"

    companion object {
        val ALL: List<Region> = listOf(
            Region("fr", "France (amazon.fr)", "fr-FR"),
            Region("com", "États-Unis (amazon.com)", "en-US"),
            Region("de", "Allemagne (amazon.de)", "de-DE"),
            Region("co.uk", "Royaume-Uni (amazon.co.uk)", "en-GB"),
            Region("it", "Italie (amazon.it)", "it-IT"),
            Region("es", "Espagne (amazon.es)", "es-ES"),
            Region("ca", "Canada (amazon.ca)", "en-CA"),
            Region("com.au", "Australie (amazon.com.au)", "en-AU"),
            Region("co.jp", "Japon (amazon.co.jp)", "ja-JP"),
            Region("com.br", "Brésil (amazon.com.br)", "pt-BR"),
            Region("com.mx", "Mexique (amazon.com.mx)", "es-MX"),
            Region("in", "Inde (amazon.in)", "en-IN"),
            Region("nl", "Pays-Bas (amazon.nl)", "nl-NL"),
        )

        fun byTld(tld: String): Region = ALL.firstOrNull { it.tld == tld } ?: ALL.first()
    }
}

enum class Reachability { REACHABLE, UNREACHABLE, UNKNOWN }

/** A "smart home" appliance (light, plug, thermostat…) exposed to Alexa by a skill or a hub. */
@Serializable
data class SmartHomeDevice(
    val applianceId: String,
    val entityId: String? = null,
    val friendlyName: String,
    val manufacturerName: String? = null,
    val friendlyDescription: String? = null,
    val modelName: String? = null,
    val applianceTypes: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val reachability: Reachability = Reachability.UNKNOWN,
    val connectedVia: String? = null,
    /** Human-readable origin: skill name, hub or manufacturer. Used for grouping/filtering. */
    val source: String = "Inconnu",
    val skillId: String? = null,
    val bridgeKey: String? = null,
    val createdAt: Long? = null,
    val lastSeenAt: Long? = null,
    val capabilityCount: Int = 0,
    /** Original JSON object, kept for backups and the "raw" view. */
    val raw: String? = null,
) {
    val isOffline: Boolean get() = reachability == Reachability.UNREACHABLE
    val isOnline: Boolean get() = reachability == Reachability.REACHABLE
    val primaryType: String get() = applianceTypes.firstOrNull() ?: "AUTRE"
}

/** An Echo / Fire / third-party Alexa-enabled device registered on the account (read-only in this app). */
@Serializable
data class EchoDevice(
    val serialNumber: String,
    val deviceType: String,
    val accountName: String,
    val online: Boolean,
    val deviceFamily: String? = null,
    val softwareVersion: String? = null,
)

@Serializable
data class DeviceSnapshot(
    val fetchedAt: Long,
    val smartHome: List<SmartHomeDevice>,
    val echos: List<EchoDevice>,
)
