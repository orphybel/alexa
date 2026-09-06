package com.orphybel.alexacleaner.core

import com.orphybel.alexacleaner.core.api.DeleteOutcome
import com.orphybel.alexacleaner.core.api.DeviceDeleter
import com.orphybel.alexacleaner.core.domain.PurgeEngine
import com.orphybel.alexacleaner.core.domain.PurgeEvent
import com.orphybel.alexacleaner.core.domain.PurgeMethod
import com.orphybel.alexacleaner.core.domain.PurgeOptions
import com.orphybel.alexacleaner.core.domain.PurgeStatus
import com.orphybel.alexacleaner.core.domain.PurgeTarget
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PurgeEngineTest {

    private class FakeDeleter(private val script: Map<String, List<DeleteOutcome>> = emptyMap()) : DeviceDeleter {
        val calls = Collections.synchronizedList(ArrayList<String>())
        var wipeCalls = 0
        var discoverCalls = 0
        private val counters = HashMap<String, Int>()

        override suspend fun deleteAppliance(applianceId: String): DeleteOutcome {
            calls.add(applianceId)
            val n = synchronized(counters) { counters.merge(applianceId, 1, Int::plus)!! }
            val list = script[applianceId] ?: return DeleteOutcome.Ok
            return list.getOrElse(n - 1) { list.last() }
        }

        override suspend fun deleteAllAppliances(): DeleteOutcome { wipeCalls++; return DeleteOutcome.Ok }
        override suspend fun startDiscovery(): DeleteOutcome { discoverCalls++; return DeleteOutcome.Ok }
    }

    private val sleeps = ArrayList<Long>()
    private val noSleep: suspend (Long) -> Unit = { sleeps.add(it) }
    private fun targets(n: Int) = (1..n).map { PurgeTarget("id$it", "Device $it") }

    private fun run(deleter: DeviceDeleter, targets: List<PurgeTarget>, options: PurgeOptions): List<PurgeEvent> =
        runBlocking { PurgeEngine(deleter, clock = { 42L }, sleep = noSleep).run(targets, options).toList() }

    @Test
    fun `sequential deletes everything and reports a summary`() {
        val fake = FakeDeleter()
        val events = run(fake, targets(3), PurgeOptions(delayMs = 100))
        val finished = events.last() as PurgeEvent.Finished
        assertEquals(3, finished.summary.ok)
        assertEquals(0, finished.summary.failed)
        assertFalse(finished.summary.aborted)
        assertEquals(listOf("id1", "id2", "id3"), fake.calls)
        assertEquals(listOf(100L, 100L), sleeps) // no pause after the last one
        assertEquals(3, events.filterIsInstance<PurgeEvent.Item>().size)
    }

    @Test
    fun `rate limit triggers backoff then success`() {
        val fake = FakeDeleter(mapOf("id2" to listOf(DeleteOutcome.RateLimited(5000), DeleteOutcome.Ok)))
        val events = run(fake, targets(3), PurgeOptions(delayMs = 0, maxRetries = 3))
        val finished = events.last() as PurgeEvent.Finished
        assertEquals(3, finished.summary.ok)
        assertTrue(events.any { it is PurgeEvent.Waiting && it.millis >= 5000 })
        assertEquals(4, fake.calls.size)
        val rec = events.filterIsInstance<PurgeEvent.Item>().first { it.record.target.applianceId == "id2" }.record
        assertEquals(2, rec.attempts)
    }

    @Test
    fun `gives up after max retries and aborts on consecutive failures`() {
        val script = (1..5).associate { "id$it" to listOf(DeleteOutcome.ServerError(500, "boom")) }
        val fake = FakeDeleter(script)
        val events = run(fake, targets(5), PurgeOptions(delayMs = 0, maxRetries = 1, abortAfterConsecutiveFailures = 2))
        val finished = events.last() as PurgeEvent.Finished
        assertEquals(2, finished.summary.failed)
        assertTrue(finished.summary.aborted)
        assertTrue(finished.summary.abortReason!!.contains("échecs consécutifs"))
        assertEquals(4, fake.calls.size) // 2 targets × (1 + 1 retry)
    }

    @Test
    fun `already gone counts as success`() {
        val fake = FakeDeleter(mapOf("id1" to listOf(DeleteOutcome.AlreadyGone)))
        val finished = run(fake, targets(1), PurgeOptions(delayMs = 0)).last() as PurgeEvent.Finished
        assertEquals(1, finished.summary.alreadyGone)
        assertEquals(1, finished.summary.succeeded)
    }

    @Test
    fun `dry run never calls the api`() {
        val fake = FakeDeleter()
        val finished = run(fake, targets(4), PurgeOptions(dryRun = true)).last() as PurgeEvent.Finished
        assertEquals(4, finished.summary.dryRun)
        assertTrue(fake.calls.isEmpty())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `parallel mode deletes everything`() {
        val fake = FakeDeleter()
        val finished = run(fake, targets(10), PurgeOptions(method = PurgeMethod.PARALLEL, parallelism = 4, delayMs = 0)).last() as PurgeEvent.Finished
        assertEquals(10, finished.summary.ok)
        assertEquals(10, fake.calls.toSet().size)
    }

    @Test
    fun `wipe mode calls delete all then discovery`() {
        val fake = FakeDeleter()
        val events = run(fake, targets(2), PurgeOptions(method = PurgeMethod.WIPE_AND_REDISCOVER, rediscoverAfterWipe = true))
        val finished = events.last() as PurgeEvent.Finished
        assertEquals(1, fake.wipeCalls)
        assertEquals(1, fake.discoverCalls)
        assertTrue(fake.calls.isEmpty())
        assertEquals(2, finished.summary.ok)
        assertTrue(events.filterIsInstance<PurgeEvent.Item>().all { it.record.status == PurgeStatus.OK })
    }

    @Test
    fun `auth failure is retried once then aborts the run`() {
        val script = mapOf("id1" to listOf(DeleteOutcome.AuthFailed))
        val fake = FakeDeleter(script)
        val finished = run(fake, targets(3), PurgeOptions(delayMs = 0)).last() as PurgeEvent.Finished
        assertEquals(1, finished.summary.failed)
        assertTrue(finished.summary.aborted)
        assertEquals(2, fake.calls.count { it == "id1" })
        assertEquals(2, fake.calls.size)
    }
}
