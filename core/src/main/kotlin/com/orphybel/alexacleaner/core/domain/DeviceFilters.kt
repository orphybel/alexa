package com.orphybel.alexacleaner.core.domain

import com.orphybel.alexacleaner.core.model.Reachability
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.Serializable
import java.text.Normalizer

enum class StatusFilter(val label: String) {
    ALL("Tous"),
    OFFLINE("Hors ligne"),
    ONLINE("En ligne"),
    UNKNOWN("Statut inconnu"),
}

enum class SortKey(val label: String) {
    NAME("Nom"),
    SOURCE("Source / fabricant"),
    TYPE("Type"),
    STATUS("Statut"),
    OFFLINE_DURATION("Durée hors ligne"),
    CREATED("Date d'ajout"),
}

@Serializable
data class FilterState(
    val status: StatusFilter = StatusFilter.ALL,
    val query: String = "",
    /** Empty = every source. */
    val sources: Set<String> = emptySet(),
    /** Empty = every type. */
    val types: Set<String> = emptySet(),
    val onlyDisabled: Boolean = false,
    val onlyDuplicates: Boolean = false,
    val onlyDeadSources: Boolean = false,
    val minOfflineDays: Int = 0,
    val minOfflineScans: Int = 0,
    val sort: SortKey = SortKey.NAME,
    val sortDescending: Boolean = false,
) {
    val isDefault: Boolean
        get() = this == FilterState(sort = sort, sortDescending = sortDescending)
}

/** Everything the UI needs to describe a device row, precomputed once per snapshot. */
data class DeviceInsight(
    val device: SmartHomeDevice,
    val history: DeviceHistory?,
    val offlineDays: Int?,
    val consecutiveOfflineScans: Int,
    val isDuplicate: Boolean,
    val sourceIsDead: Boolean,
) {
    val id: String get() = device.applianceId
}

object DeviceFilters {

    fun insights(devices: List<SmartHomeDevice>, history: HistoryDb, now: Long): List<DeviceInsight> {
        val duplicateIds = duplicateIds(devices)
        val deadSources = deadSources(devices)
        return devices.map { d ->
            val h = history.entries[d.applianceId]
            DeviceInsight(
                device = d,
                history = h,
                offlineDays = if (d.isOffline) h?.offlineDays(now) else null,
                consecutiveOfflineScans = if (d.isOffline) (h?.consecutiveOfflineScans ?: 0) else 0,
                isDuplicate = d.applianceId in duplicateIds,
                sourceIsDead = d.source in deadSources,
            )
        }
    }

    fun apply(insights: List<DeviceInsight>, f: FilterState): List<DeviceInsight> {
        val q = normalize(f.query)
        val filtered = insights.filter { i ->
            val d = i.device
            val statusOk = when (f.status) {
                StatusFilter.ALL -> true
                StatusFilter.OFFLINE -> d.isOffline
                StatusFilter.ONLINE -> d.isOnline
                StatusFilter.UNKNOWN -> d.reachability == Reachability.UNKNOWN
            }
            statusOk &&
                (f.sources.isEmpty() || d.source in f.sources) &&
                (f.types.isEmpty() || d.applianceTypes.any { it in f.types } || (d.applianceTypes.isEmpty() && "AUTRE" in f.types)) &&
                (!f.onlyDisabled || !d.isEnabled) &&
                (!f.onlyDuplicates || i.isDuplicate) &&
                (!f.onlyDeadSources || i.sourceIsDead) &&
                (f.minOfflineDays <= 0 || (i.offlineDays ?: -1) >= f.minOfflineDays) &&
                (f.minOfflineScans <= 0 || i.consecutiveOfflineScans >= f.minOfflineScans) &&
                (q.isEmpty() || matchesQuery(d, q))
        }
        val comparator: Comparator<DeviceInsight> = when (f.sort) {
            SortKey.NAME -> compareBy { normalize(it.device.friendlyName) }
            SortKey.SOURCE -> compareBy<DeviceInsight> { normalize(it.device.source) }.thenBy { normalize(it.device.friendlyName) }
            SortKey.TYPE -> compareBy<DeviceInsight> { it.device.primaryType }.thenBy { normalize(it.device.friendlyName) }
            SortKey.STATUS -> compareBy<DeviceInsight> { statusRank(it.device) }.thenBy { normalize(it.device.friendlyName) }
            SortKey.OFFLINE_DURATION -> compareByDescending<DeviceInsight> { it.offlineDays ?: -1 }
                .thenByDescending { it.consecutiveOfflineScans }
                .thenBy { normalize(it.device.friendlyName) }
            SortKey.CREATED -> compareByDescending<DeviceInsight> { it.device.createdAt ?: 0L }.thenBy { normalize(it.device.friendlyName) }
        }
        val sorted = filtered.sortedWith(comparator)
        return if (f.sortDescending) sorted.asReversed() else sorted
    }

    /** Offline first, then unknown, then online: the order in which a cleanup is interesting. */
    private fun statusRank(d: SmartHomeDevice): Int = when {
        d.isOffline -> 0
        d.reachability == Reachability.UNKNOWN -> 1
        else -> 2
    }

    private fun matchesQuery(d: SmartHomeDevice, q: String): Boolean =
        normalize(d.friendlyName).contains(q) ||
            normalize(d.source).contains(q) ||
            (d.manufacturerName?.let { normalize(it).contains(q) } ?: false) ||
            (d.friendlyDescription?.let { normalize(it).contains(q) } ?: false) ||
            (d.modelName?.let { normalize(it).contains(q) } ?: false) ||
            d.applianceTypes.any { normalize(it).contains(q) } ||
            d.applianceId.lowercase().contains(q)

    /**
     * Ids of devices that share a name with another device and are the *less* desirable copy:
     * unreachable before reachable, disabled before enabled, older before newer.
     */
    fun duplicateIds(devices: List<SmartHomeDevice>): Set<String> {
        val result = HashSet<String>()
        devices.groupBy { normalize(it.friendlyName) }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val keep = group.sortedWith(
                    compareByDescending<SmartHomeDevice> { it.isOnline }
                        .thenByDescending { it.isEnabled }
                        .thenByDescending { it.lastSeenAt ?: 0L }
                        .thenByDescending { it.createdAt ?: 0L },
                ).first()
                group.filter { it.applianceId != keep.applianceId }.forEach { result.add(it.applianceId) }
            }
        return result
    }

    /** Sources (skills / hubs) whose devices are *all* offline: the skill was probably removed or the hub is gone. */
    fun deadSources(devices: List<SmartHomeDevice>): Set<String> =
        devices.groupBy { it.source }
            .filterValues { list -> list.isNotEmpty() && list.all { it.isOffline } }
            .keys

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
}
