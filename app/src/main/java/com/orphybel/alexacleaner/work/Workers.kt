package com.orphybel.alexacleaner.work

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.orphybel.alexacleaner.App
import com.orphybel.alexacleaner.R
import com.orphybel.alexacleaner.core.auth.AuthException
import com.orphybel.alexacleaner.core.data.PurgeLogLine
import com.orphybel.alexacleaner.core.data.storeJson
import com.orphybel.alexacleaner.core.domain.DeviceFilters
import com.orphybel.alexacleaner.core.domain.PurgeEngine
import com.orphybel.alexacleaner.core.domain.PurgeEvent
import com.orphybel.alexacleaner.core.domain.PurgeMethod
import com.orphybel.alexacleaner.core.domain.PurgeOptions
import com.orphybel.alexacleaner.core.domain.PurgeStatus
import com.orphybel.alexacleaner.core.domain.PurgeSummary
import com.orphybel.alexacleaner.core.domain.PurgeTarget
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import com.orphybel.alexacleaner.graph
import com.orphybel.alexacleaner.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object Scheduler {
    const val UNIQUE_SCAN = "scan-periodic"
    const val UNIQUE_SCAN_NOW = "scan-now"
    const val UNIQUE_PURGE = "purge"

    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun syncPeriodicScan(context: Context, hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(UNIQUE_SCAN)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScanWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(network())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_SCAN, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runScanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(network())
            .setInputData(workDataOf(ScanWorker.KEY_MANUAL to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_SCAN_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun enqueuePurge(context: Context, devices: List<SmartHomeDevice>, options: PurgeOptions): UUID {
        val dir = File(context.filesDir, "purge").also { it.mkdirs() }
        val file = File(dir, "targets-${System.currentTimeMillis()}.json")
        file.writeText(storeJson.encodeToString(devices))
        val request = OneTimeWorkRequestBuilder<PurgeWorker>()
            .setConstraints(network())
            .setInputData(
                workDataOf(
                    PurgeWorker.KEY_TARGETS_FILE to file.absolutePath,
                    PurgeWorker.KEY_OPTIONS to storeJson.encodeToString(options),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_PURGE, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancelPurge(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PURGE)

    fun purgeInfoFlow(context: Context): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_PURGE).map { list -> list.firstOrNull() }

    fun scanInfoFlow(context: Context): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_SCAN_NOW).map { list -> list.firstOrNull() }
}

object Notifications {
    const val ID_PURGE = 1001
    const val ID_SCAN = 1002

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun purgeProgress(context: Context, title: String, text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, App.CHANNEL_PURGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun simple(context: Context, channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()

    fun show(context: Context, id: Int, notification: Notification) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}

/** Fetches the device list, updates the local history and applies the auto-purge rule. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (!g.sessions.isLoggedIn) return Result.success()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        return try {
            val snapshot = g.api.fetchSnapshot()
            g.snapshots.save(snapshot)
            val now = System.currentTimeMillis()
            val history = g.history.load().record(snapshot.smartHome, now)
            g.history.save(history)
            val settings = g.settings.load()
            val offline = snapshot.smartHome.count { it.isOffline }
            g.logger.log("Scan: ${snapshot.smartHome.size} appareils, $offline hors ligne")

            val rule = settings.autoPurge
            if (rule.enabled) {
                val insights = DeviceFilters.insights(snapshot.smartHome, history, now)
                val targets = rule.select(insights)
                when {
                    targets.isEmpty() -> if (settings.notifyOnScan || manual) notify(
                        "Analyse terminée",
                        "${snapshot.smartHome.size} appareils, $offline hors ligne. Aucun ne remplit les critères de suppression automatique.",
                    )
                    rule.dryRun -> notify(
                        "Simulation : ${targets.size} appareil(s) seraient supprimés",
                        targets.joinToString(", ") { it.friendlyName }.take(400),
                    )
                    else -> {
                        val options = settings.purgeDefaults.copy(method = PurgeMethod.SEQUENTIAL, dryRun = false)
                        Scheduler.enqueuePurge(applicationContext, targets, options)
                        notify(
                            "Suppression automatique de ${targets.size} appareil(s)",
                            targets.joinToString(", ") { it.friendlyName }.take(400),
                        )
                    }
                }
            } else if (settings.notifyOnScan || manual) {
                notify("Analyse terminée", "${snapshot.smartHome.size} appareils connectés, $offline hors ligne.")
            }
            Result.success(workDataOf(KEY_TOTAL to snapshot.smartHome.size, KEY_OFFLINE to offline))
        } catch (e: AuthException) {
            g.logger.log("Scan: ${e.message}")
            notify("Session Amazon expirée", "Ouvrez l'application et reconnectez-vous pour reprendre les analyses.")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "auth")))
        } catch (e: IOException) {
            g.logger.log("Scan: erreur réseau ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "io")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            g.logger.log("Scan: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun notify(title: String, text: String) =
        Notifications.show(applicationContext, Notifications.ID_SCAN, Notifications.simple(applicationContext, App.CHANNEL_SCAN, title, text))

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_TOTAL = "total"
        const val KEY_OFFLINE = "offline"
        const val KEY_ERROR = "error"
    }
}

/** Runs a deletion campaign as a foreground job so it survives the app being sent to the background. */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var done = 0
    private var ok = 0
    private var failed = 0
    private var total = 0
    private var lastMessage = ""

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Suppression en cours", lastMessage)

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val path = inputData.getString(KEY_TARGETS_FILE) ?: return Result.failure(workDataOf(KEY_ERROR to "targets manquants"))
        val options = inputData.getString(KEY_OPTIONS)?.let { runCatching { storeJson.decodeFromString<PurgeOptions>(it) }.getOrNull() }
            ?: PurgeOptions()
        val file = File(path)
        val devices: List<SmartHomeDevice> = runCatching { storeJson.decodeFromString<List<SmartHomeDevice>>(file.readText()) }
            .getOrElse { return Result.failure(workDataOf(KEY_ERROR to "fichier de cibles illisible")) }
        val targets = devices.map(PurgeTarget::of)
        total = targets.size
        val runId = "run-${System.currentTimeMillis()}"

        try {
            setForeground(foregroundInfo(titleFor(options), "Préparation…"))
        } catch (e: Exception) {
            g.logger.log("Foreground refusé: ${e.message}")
        }

        if (options.backupBeforePurge && !options.dryRun) {
            runCatching { g.exporter.writeJson("sauvegarde-avant-suppression", devices) }
                .onFailure { g.logger.log("Sauvegarde impossible: ${it.message}") }
        }

        val engine = PurgeEngine(g.api)
        var summary: PurgeSummary? = null
        val startedAt = System.currentTimeMillis()
        try {
            engine.run(targets, options, runId).collect { ev ->
                when (ev) {
                    is PurgeEvent.Started -> publish("Démarrage : ${ev.total} appareil(s)")
                    is PurgeEvent.Item -> {
                        done++
                        if (ev.record.status == PurgeStatus.FAILED) failed++ else ok++
                        g.purgeLog.append(PurgeLogLine(runId, record = ev.record))
                        publish("${ev.record.target.friendlyName} : ${ev.record.status.label}")
                    }
                    is PurgeEvent.Info -> publish(ev.message)
                    is PurgeEvent.Waiting -> publish("Pause ${ev.millis / 1000}s — ${ev.reason}")
                    is PurgeEvent.Finished -> {
                        summary = ev.summary
                        g.purgeLog.append(PurgeLogLine(runId, summary = ev.summary))
                    }
                }
            }
        } catch (e: CancellationException) {
            val s = PurgeSummary(
                runId, options.method, startedAt, System.currentTimeMillis(), total,
                ok = ok, alreadyGone = 0, failed = failed, dryRun = 0, aborted = true, abortReason = "Annulé par l'utilisateur",
            )
            g.purgeLog.append(PurgeLogLine(runId, summary = s))
            Notifications.show(
                applicationContext, Notifications.ID_SCAN,
                Notifications.simple(applicationContext, App.CHANNEL_PURGE, "Suppression annulée", "$done/$total traités avant l'annulation."),
            )
            throw e
        } finally {
            file.delete()
        }

        val s = summary
        val text = if (s == null) "Terminé" else buildString {
            append("${s.succeeded}/${s.total} réussis")
            if (s.failed > 0) append(", ${s.failed} échec(s)")
            if (s.aborted) append(" — ${s.abortReason ?: "interrompu"}")
        }
        Notifications.show(
            applicationContext, Notifications.ID_SCAN,
            Notifications.simple(applicationContext, App.CHANNEL_PURGE, if (options.dryRun) "Simulation terminée" else "Suppression terminée", text),
        )
        val output = workDataOf(
            KEY_SUMMARY to (s?.let { storeJson.encodeToString(it) } ?: ""),
            KEY_DONE to done, KEY_TOTAL to total, KEY_OK to ok, KEY_FAILED to failed, KEY_LAST to text,
        )
        return if (s != null && s.aborted && s.failed > 0 && s.succeeded == 0) Result.failure(output) else Result.success(output)
    }

    private suspend fun publish(message: String) {
        lastMessage = message
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_OK to ok, KEY_FAILED to failed, KEY_LAST to message))
        if (Notifications.canNotify(applicationContext)) {
            try {
                NotificationManagerCompat.from(applicationContext).notify(
                    Notifications.ID_PURGE,
                    Notifications.purgeProgress(applicationContext, "Suppression $done/$total", message, done, total),
                )
            } catch (_: SecurityException) {
            }
        }
    }

    private fun titleFor(options: PurgeOptions) = if (options.dryRun) "Simulation de suppression" else "Suppression des appareils"

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        val notification = Notifications.purgeProgress(applicationContext, title, text, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_PURGE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_PURGE, notification)
        }
    }

    companion object {
        const val KEY_TARGETS_FILE = "targetsFile"
        const val KEY_OPTIONS = "options"
        const val KEY_SUMMARY = "summary"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_OK = "ok"
        const val KEY_FAILED = "failed"
        const val KEY_LAST = "last"
        const val KEY_ERROR = "error"

        fun progressOf(data: Data): PurgeProgress = PurgeProgress(
            done = data.getInt(KEY_DONE, 0),
            total = data.getInt(KEY_TOTAL, 0),
            ok = data.getInt(KEY_OK, 0),
            failed = data.getInt(KEY_FAILED, 0),
            last = data.getString(KEY_LAST) ?: "",
            summary = data.getString(KEY_SUMMARY)?.takeIf { it.isNotBlank() }?.let {
                runCatching { storeJson.decodeFromString<PurgeSummary>(it) }.getOrNull()
            },
        )
    }
}

data class PurgeProgress(
    val done: Int,
    val total: Int,
    val ok: Int,
    val failed: Int,
    val last: String,
    val summary: PurgeSummary? = null,
)
