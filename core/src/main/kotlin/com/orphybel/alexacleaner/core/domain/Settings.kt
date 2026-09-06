package com.orphybel.alexacleaner.core.domain

import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.Serializable

enum class PurgeMethod(val label: String, val description: String) {
    SEQUENTIAL(
        "Un par un (recommandé)",
        "Supprime les appareils l'un après l'autre avec une pause entre chaque appel. Le plus sûr vis-à-vis des limites de débit d'Amazon.",
    ),
    PARALLEL(
        "En parallèle",
        "Plusieurs suppressions simultanées. Plus rapide, mais Amazon peut répondre 429 (limite de débit) ; l'application ralentit alors automatiquement.",
    ),
    WIPE_AND_REDISCOVER(
        "Tout oublier puis redécouvrir",
        "Équivaut à « Oublier tous les appareils » : supprime TOUS les appareils connectés du compte (pas seulement la sélection), puis relance une découverte. Les appareils réellement joignables reviennent, les fantômes non. Les groupes, noms personnalisés et routines liées sont perdus.",
    ),
}

@Serializable
data class PurgeOptions(
    val method: PurgeMethod = PurgeMethod.SEQUENTIAL,
    val delayMs: Long = 600,
    val parallelism: Int = 3,
    val maxRetries: Int = 3,
    val dryRun: Boolean = false,
    val abortAfterConsecutiveFailures: Int = 8,
    val rediscoverAfterWipe: Boolean = true,
    val backupBeforePurge: Boolean = true,
)

/** Rule applied by the scheduled scan to pick devices for automatic deletion. */
@Serializable
data class AutoPurgeRule(
    val enabled: Boolean = false,
    /** Only report what *would* be deleted. Strongly recommended for the first runs. */
    val dryRun: Boolean = true,
    val minConsecutiveOfflineScans: Int = 3,
    val minOfflineDays: Int = 7,
    /** Empty = every source. */
    val sources: Set<String> = emptySet(),
    val excludeTypes: Set<String> = emptySet(),
    val includeDisabled: Boolean = true,
    val onlyDeadSources: Boolean = false,
    val maxDevicesPerRun: Int = 50,
) {
    fun select(insights: List<DeviceInsight>): List<SmartHomeDevice> = insights
        .filter { i ->
            val d = i.device
            d.isOffline &&
                i.consecutiveOfflineScans >= minConsecutiveOfflineScans &&
                (i.offlineDays ?: -1) >= minOfflineDays &&
                (sources.isEmpty() || d.source in sources) &&
                d.applianceTypes.none { it in excludeTypes } &&
                (includeDisabled || d.isEnabled) &&
                (!onlyDeadSources || i.sourceIsDead)
        }
        .sortedByDescending { it.offlineDays ?: 0 }
        .take(maxDevicesPerRun)
        .map { it.device }
}

@Serializable
data class AppSettings(
    /** 0 = no scheduled scan. */
    val scanIntervalHours: Int = 0,
    val autoPurge: AutoPurgeRule = AutoPurgeRule(),
    val purgeDefaults: PurgeOptions = PurgeOptions(),
    val notifyOnScan: Boolean = true,
    val keepRawJson: Boolean = true,
) {
    companion object {
        val SCAN_INTERVALS: List<Pair<Int, String>> = listOf(
            0 to "Désactivé",
            6 to "Toutes les 6 heures",
            12 to "Toutes les 12 heures",
            24 to "Une fois par jour",
            72 to "Tous les 3 jours",
            168 to "Une fois par semaine",
        )
    }
}
