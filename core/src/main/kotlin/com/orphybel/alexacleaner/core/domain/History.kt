package com.orphybel.alexacleaner.core.domain

import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.Serializable

/**
 * What the app has observed about one appliance across scans. Amazon does not expose a reliable
 * "offline since" timestamp, so we build our own by scanning periodically.
 */
@Serializable
data class DeviceHistory(
    val applianceId: String,
    val name: String,
    val firstSeenAt: Long,
    val lastScanAt: Long,
    val lastOnlineAt: Long? = null,
    val lastOfflineAt: Long? = null,
    val consecutiveOfflineScans: Int = 0,
    val totalScans: Int = 0,
    val offlineScans: Int = 0,
) {
    /** Millis since the device was last seen online (or since first observation if never online). */
    fun offlineSinceMs(now: Long): Long? {
        if (lastOfflineAt == null) return null
        if (lastOnlineAt != null && lastOnlineAt >= lastOfflineAt) return null
        return now - (lastOnlineAt ?: firstSeenAt)
    }

    fun offlineDays(now: Long): Int? = offlineSinceMs(now)?.let { (it / DAY_MS).toInt() }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

@Serializable
data class HistoryDb(
    val entries: Map<String, DeviceHistory> = emptyMap(),
    val scanTimes: List<Long> = emptyList(),
) {
    val scanCount: Int get() = scanTimes.size
    val lastScanAt: Long? get() = scanTimes.lastOrNull()

    /** Folds a fresh device list into the history. Devices that disappeared are dropped. */
    fun record(devices: List<SmartHomeDevice>, now: Long): HistoryDb {
        val updated = LinkedHashMap<String, DeviceHistory>()
        for (d in devices) {
            val prev = entries[d.applianceId]
            val offline = d.isOffline
            val online = d.isOnline
            val base = prev ?: DeviceHistory(d.applianceId, d.friendlyName, firstSeenAt = now, lastScanAt = now)
            updated[d.applianceId] = base.copy(
                name = d.friendlyName,
                lastScanAt = now,
                lastOnlineAt = if (online) now else base.lastOnlineAt,
                lastOfflineAt = if (offline) now else base.lastOfflineAt,
                consecutiveOfflineScans = when {
                    offline -> base.consecutiveOfflineScans + 1
                    online -> 0
                    else -> base.consecutiveOfflineScans
                },
                totalScans = base.totalScans + 1,
                offlineScans = base.offlineScans + (if (offline) 1 else 0),
            )
        }
        return HistoryDb(updated, (scanTimes + now).takeLast(MAX_SCANS))
    }

    fun forget(applianceIds: Collection<String>): HistoryDb = copy(entries = entries - applianceIds.toSet())

    companion object {
        const val MAX_SCANS = 2000
    }
}
