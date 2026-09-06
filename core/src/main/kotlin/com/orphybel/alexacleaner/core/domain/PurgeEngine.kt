package com.orphybel.alexacleaner.core.domain

import com.orphybel.alexacleaner.core.api.DeleteOutcome
import com.orphybel.alexacleaner.core.api.DeviceDeleter
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class PurgeTarget(val applianceId: String, val friendlyName: String, val source: String = "") {
    companion object {
        fun of(d: SmartHomeDevice) = PurgeTarget(d.applianceId, d.friendlyName, d.source)
    }
}

enum class PurgeStatus(val label: String) {
    OK("Supprimé"),
    ALREADY_GONE("Déjà absent"),
    FAILED("Échec"),
    DRY_RUN("Simulation"),
    SKIPPED("Ignoré"),
}

@Serializable
data class PurgeRecord(
    val target: PurgeTarget,
    val status: PurgeStatus,
    val message: String,
    val attempts: Int,
    val at: Long,
)

@Serializable
data class PurgeSummary(
    val runId: String,
    val method: PurgeMethod,
    val startedAt: Long,
    val finishedAt: Long,
    val total: Int,
    val ok: Int,
    val alreadyGone: Int,
    val failed: Int,
    val dryRun: Int,
    val aborted: Boolean,
    val abortReason: String? = null,
) {
    val succeeded: Int get() = ok + alreadyGone + dryRun
    val durationMs: Long get() = finishedAt - startedAt
}

sealed class PurgeEvent {
    data class Started(val runId: String, val total: Int, val options: PurgeOptions) : PurgeEvent()
    data class Item(val index: Int, val total: Int, val record: PurgeRecord) : PurgeEvent()
    data class Info(val message: String) : PurgeEvent()
    data class Waiting(val millis: Long, val reason: String) : PurgeEvent()
    data class Finished(val summary: PurgeSummary, val records: List<PurgeRecord>) : PurgeEvent()
}

/**
 * Runs a deletion campaign and reports progress as a cold [Flow]. Cancelling the collector
 * stops the campaign at the next safe point.
 */
class PurgeEngine(
    private val api: DeviceDeleter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun run(targets: List<PurgeTarget>, options: PurgeOptions, runId: String = clock().toString()): Flow<PurgeEvent> = channelFlow {
        val startedAt = clock()
        send(PurgeEvent.Started(runId, targets.size, options))
        val state = RunState()
        try {
            when (options.method) {
                PurgeMethod.SEQUENTIAL -> runSequential(targets, options, state)
                PurgeMethod.PARALLEL -> runParallel(targets, options, state)
                PurgeMethod.WIPE_AND_REDISCOVER -> runWipe(targets, options, state)
            }
        } finally {
            val records = state.records.toList()
            val summary = PurgeSummary(
                runId = runId,
                method = options.method,
                startedAt = startedAt,
                finishedAt = clock(),
                total = targets.size,
                ok = records.count { it.status == PurgeStatus.OK },
                alreadyGone = records.count { it.status == PurgeStatus.ALREADY_GONE },
                failed = records.count { it.status == PurgeStatus.FAILED },
                dryRun = records.count { it.status == PurgeStatus.DRY_RUN },
                aborted = state.abortReason != null || records.size < targets.size,
                abortReason = state.abortReason ?: if (records.size < targets.size) "Interrompu" else null,
            )
            send(PurgeEvent.Finished(summary, records))
        }
    }

    private class RunState {
        val records = ArrayList<PurgeRecord>()
        val lock = Mutex()

        @Volatile
        var abortReason: String? = null

        @Volatile
        var consecutiveFailures = 0

        /** Extra pause added after a 429, decays with each success. */
        @Volatile
        var adaptiveDelayMs = 0L
    }

    private suspend fun ProducerScope<PurgeEvent>.runSequential(targets: List<PurgeTarget>, options: PurgeOptions, state: RunState) {
        for ((index, target) in targets.withIndex()) {
            if (state.abortReason != null) break
            val record = deleteOne(target, options, state)
            register(record, index, targets.size, options, state)
            if (index < targets.lastIndex && !options.dryRun) sleep(options.delayMs + state.adaptiveDelayMs)
        }
    }

    private suspend fun ProducerScope<PurgeEvent>.runParallel(targets: List<PurgeTarget>, options: PurgeOptions, state: RunState) {
        val permits = options.parallelism.coerceIn(1, 8)
        val semaphore = Semaphore(permits)
        coroutineScope {
            targets.mapIndexed { index, target ->
                async {
                    semaphore.withPermit {
                        if (state.abortReason != null) return@withPermit
                        val record = deleteOne(target, options, state)
                        register(record, index, targets.size, options, state)
                        if (!options.dryRun) sleep(options.delayMs + state.adaptiveDelayMs)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun ProducerScope<PurgeEvent>.runWipe(targets: List<PurgeTarget>, options: PurgeOptions, state: RunState) {
        send(PurgeEvent.Info("Suppression de TOUS les appareils connectés du compte…"))
        val now = clock()
        if (options.dryRun) {
            targets.forEachIndexed { i, t ->
                register(PurgeRecord(t, PurgeStatus.DRY_RUN, "Simulation : oubli global", 0, now), i, targets.size, options, state)
            }
            send(PurgeEvent.Info("Simulation : DELETE /api/phoenix puis découverte n'ont pas été appelés."))
            return
        }
        val outcome = withRetry(options, state, "oubli global") { api.deleteAllAppliances() }
        val ok = outcome is DeleteOutcome.Ok || outcome is DeleteOutcome.AlreadyGone
        val status = if (ok) PurgeStatus.OK else PurgeStatus.FAILED
        val message = if (ok) "Supprimé via l'oubli global" else outcome.message
        targets.forEachIndexed { i, t -> register(PurgeRecord(t, status, message, 1, clock()), i, targets.size, options, state) }
        if (!ok) {
            state.abortReason = "L'oubli global a échoué : ${outcome.message}"
            return
        }
        if (options.rediscoverAfterWipe) {
            send(PurgeEvent.Info("Lancement de la découverte des appareils…"))
            sleep(2000)
            val disc = withRetry(options, state, "découverte") { api.startDiscovery() }
            send(
                PurgeEvent.Info(
                    if (disc is DeleteOutcome.Ok) "Découverte lancée. Les appareils joignables réapparaîtront dans quelques minutes."
                    else "La découverte n'a pas pu être lancée (${disc.message}). Lancez-la depuis l'application Alexa.",
                ),
            )
        }
    }

    private suspend fun ProducerScope<PurgeEvent>.register(record: PurgeRecord, index: Int, total: Int, options: PurgeOptions, state: RunState) {
        state.lock.withLock {
            state.records.add(record)
            if (record.status == PurgeStatus.FAILED) {
                state.consecutiveFailures++
                if (options.abortAfterConsecutiveFailures > 0 && state.consecutiveFailures >= options.abortAfterConsecutiveFailures && state.abortReason == null) {
                    state.abortReason = "${state.consecutiveFailures} échecs consécutifs, arrêt par sécurité"
                }
                if (record.message.contains("Session expirée") && state.abortReason == null) {
                    state.abortReason = "Session Amazon expirée, reconnectez-vous"
                }
                if (record.message.contains("point d'API retiré") && state.abortReason == null) {
                    state.abortReason = "Amazon a retiré ce point d'API de suppression (HTTP 299) ; lancez le diagnostic dans les réglages"
                }
            } else {
                state.consecutiveFailures = 0
                state.adaptiveDelayMs = max(0L, state.adaptiveDelayMs - 250L)
            }
        }
        send(PurgeEvent.Item(index, total, record))
        state.abortReason?.let { send(PurgeEvent.Info("Arrêt : $it")) }
    }

    private suspend fun ProducerScope<PurgeEvent>.deleteOne(target: PurgeTarget, options: PurgeOptions, state: RunState): PurgeRecord {
        if (options.dryRun) return PurgeRecord(target, PurgeStatus.DRY_RUN, "Simulation : aucune suppression", 0, clock())
        var attempts = 0
        val outcome = withRetry(options, state, target.friendlyName, onAttempt = { attempts = it }) { api.deleteAppliance(target.applianceId) }
        val status = when (outcome) {
            is DeleteOutcome.Ok -> PurgeStatus.OK
            is DeleteOutcome.AlreadyGone -> PurgeStatus.ALREADY_GONE
            else -> PurgeStatus.FAILED
        }
        return PurgeRecord(target, status, outcome.message, attempts, clock())
    }

    private suspend fun ProducerScope<PurgeEvent>.withRetry(
        options: PurgeOptions,
        state: RunState,
        label: String,
        onAttempt: (Int) -> Unit = {},
        block: suspend () -> DeleteOutcome,
    ): DeleteOutcome {
        var attempt = 0
        var authRetried = false
        while (true) {
            attempt++
            onAttempt(attempt)
            val outcome = block()
            when (outcome) {
                is DeleteOutcome.Ok, is DeleteOutcome.AlreadyGone -> return outcome
                is DeleteOutcome.AuthFailed -> {
                    if (authRetried) return outcome
                    authRetried = true
                    send(PurgeEvent.Waiting(2000, "Session à renouveler"))
                    sleep(2000)
                }
                is DeleteOutcome.RateLimited -> {
                    state.adaptiveDelayMs = min(10_000L, state.adaptiveDelayMs + 1_000L)
                    if (attempt > options.maxRetries) return outcome
                    val wait = max(outcome.retryAfterMs ?: 0L, backoff(attempt))
                    send(PurgeEvent.Waiting(wait, "Limite de débit Amazon ($label)"))
                    sleep(wait)
                }
                is DeleteOutcome.ServerError, is DeleteOutcome.NetworkError -> {
                    if (!outcome.isRetryable || attempt > options.maxRetries) return outcome
                    val wait = backoff(attempt)
                    send(PurgeEvent.Waiting(wait, "${outcome.message} ($label), nouvel essai"))
                    sleep(wait)
                }
            }
        }
    }

    private fun backoff(attempt: Int): Long = min(30_000L, 1_500L * (1L shl (attempt - 1).coerceIn(0, 5)))
}
