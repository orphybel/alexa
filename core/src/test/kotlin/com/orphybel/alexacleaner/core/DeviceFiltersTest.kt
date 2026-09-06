package com.orphybel.alexacleaner.core

import com.orphybel.alexacleaner.core.domain.AutoPurgeRule
import com.orphybel.alexacleaner.core.domain.DeviceFilters
import com.orphybel.alexacleaner.core.domain.DeviceHistory
import com.orphybel.alexacleaner.core.domain.FilterState
import com.orphybel.alexacleaner.core.domain.HistoryDb
import com.orphybel.alexacleaner.core.domain.SortKey
import com.orphybel.alexacleaner.core.domain.StatusFilter
import com.orphybel.alexacleaner.core.model.Reachability
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceFiltersTest {

    private fun dev(
        id: String,
        name: String,
        reach: Reachability = Reachability.UNREACHABLE,
        source: String = "Tuya",
        type: String = "LIGHT",
        enabled: Boolean = true,
        created: Long? = null,
    ) = SmartHomeDevice(
        applianceId = id, friendlyName = name, reachability = reach, source = source,
        applianceTypes = listOf(type), isEnabled = enabled, createdAt = created,
    )

    private val devices = listOf(
        dev("1", "Lampe Salon", Reachability.REACHABLE),
        dev("2", "Lampe salon", Reachability.UNREACHABLE),
        dev("3", "Prise TV", Reachability.UNREACHABLE, source = "Meross", type = "SMARTPLUG"),
        dev("4", "Prise Cuisine", Reachability.UNREACHABLE, source = "Meross", type = "SMARTPLUG", enabled = false),
        dev("5", "Thermostat", Reachability.REACHABLE, source = "Netatmo", type = "THERMOSTAT"),
    )

    @Test
    fun `duplicate detection keeps the reachable copy`() {
        val dups = DeviceFilters.duplicateIds(devices)
        assertEquals(setOf("2"), dups)
    }

    @Test
    fun `dead sources are those with every device offline`() {
        assertEquals(setOf("Meross"), DeviceFilters.deadSources(devices))
    }

    @Test
    fun `status source type and search filters compose`() {
        val insights = DeviceFilters.insights(devices, HistoryDb(), now = 0)
        // Default sort is by name: "Lampe salon", "Prise Cuisine", "Prise TV".
        assertEquals(listOf("2", "4", "3"), DeviceFilters.apply(insights, FilterState(status = StatusFilter.OFFLINE)).map { it.id })
        assertEquals(listOf("4", "3"), DeviceFilters.apply(insights, FilterState(sources = setOf("Meross"))).map { it.id })
        assertEquals(listOf("4"), DeviceFilters.apply(insights, FilterState(onlyDisabled = true)).map { it.id })
        assertEquals(listOf("2"), DeviceFilters.apply(insights, FilterState(onlyDuplicates = true)).map { it.id })
        assertEquals(listOf("4", "3"), DeviceFilters.apply(insights, FilterState(onlyDeadSources = true)).map { it.id })
        assertEquals(listOf("3", "4"), DeviceFilters.apply(insights, FilterState(onlyDeadSources = true, sortDescending = true)).map { it.id })
        assertEquals(listOf("4", "3"), DeviceFilters.apply(insights, FilterState(query = "prise", sort = SortKey.NAME)).map { it.id })
        assertEquals(listOf("5"), DeviceFilters.apply(insights, FilterState(query = "THERMO")).map { it.id })
        assertEquals(listOf("5"), DeviceFilters.apply(insights, FilterState(types = setOf("THERMOSTAT"))).map { it.id })
    }

    @Test
    fun `history tracks consecutive offline scans and offline duration`() {
        val day = DeviceHistory.DAY_MS
        var db = HistoryDb()
        db = db.record(listOf(dev("2", "L", Reachability.REACHABLE)), now = 0)
        db = db.record(listOf(dev("2", "L", Reachability.UNREACHABLE)), now = day)
        db = db.record(listOf(dev("2", "L", Reachability.UNREACHABLE)), now = 2 * day)
        db = db.record(listOf(dev("2", "L", Reachability.UNREACHABLE)), now = 9 * day)
        val h = db.entries.getValue("2")
        assertEquals(3, h.consecutiveOfflineScans)
        assertEquals(4, h.totalScans)
        assertEquals(9, h.offlineDays(9 * day))
        assertEquals(4, db.scanCount)

        // A device that comes back online resets the counter.
        db = db.record(listOf(dev("2", "L", Reachability.REACHABLE)), now = 10 * day)
        assertEquals(0, db.entries.getValue("2").consecutiveOfflineScans)
        assertNull(db.entries.getValue("2").offlineDays(10 * day))

        // Devices no longer listed are forgotten.
        db = db.record(emptyList(), now = 11 * day)
        assertTrue(db.entries.isEmpty())
    }

    @Test
    fun `offline duration filters and sort`() {
        val day = DeviceHistory.DAY_MS
        var db = HistoryDb()
        repeat(4) { i -> db = db.record(devices, now = i * day) }
        val now = 3 * day
        val insights = DeviceFilters.insights(devices, db, now)
        // Every offline device was never seen online: offline since first scan (3 days).
        assertEquals(listOf("2", "3", "4"), DeviceFilters.apply(insights, FilterState(minOfflineDays = 3, sort = SortKey.NAME)).map { it.id }.sorted())
        assertTrue(DeviceFilters.apply(insights, FilterState(minOfflineDays = 4)).isEmpty())
        assertEquals(3, DeviceFilters.apply(insights, FilterState(minOfflineScans = 4)).size)
        val byDuration = DeviceFilters.apply(insights, FilterState(sort = SortKey.OFFLINE_DURATION))
        assertTrue(byDuration.first().device.isOffline)
        assertTrue(byDuration.last().device.isOnline)
    }

    @Test
    fun `auto purge rule selects only long-offline devices`() {
        val day = DeviceHistory.DAY_MS
        var db = HistoryDb()
        repeat(5) { i -> db = db.record(devices, now = i * day) }
        val insights = DeviceFilters.insights(devices, db, now = 8 * day)
        val rule = AutoPurgeRule(enabled = true, minConsecutiveOfflineScans = 3, minOfflineDays = 7, sources = setOf("Meross"))
        assertEquals(setOf("3", "4"), rule.select(insights).map { it.applianceId }.toSet())
        val strict = rule.copy(includeDisabled = false, maxDevicesPerRun = 1)
        assertEquals(listOf("3"), strict.select(insights).map { it.applianceId })
        assertTrue(rule.copy(minOfflineDays = 30).select(insights).isEmpty())
    }
}
