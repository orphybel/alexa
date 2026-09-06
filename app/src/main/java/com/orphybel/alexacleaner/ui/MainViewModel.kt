package com.orphybel.alexacleaner.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.orphybel.alexacleaner.core.auth.AmazonAuth
import com.orphybel.alexacleaner.core.auth.AuthException
import com.orphybel.alexacleaner.core.data.PurgeRun
import com.orphybel.alexacleaner.core.domain.AppSettings
import com.orphybel.alexacleaner.core.domain.DeviceFilters
import com.orphybel.alexacleaner.core.domain.DeviceInsight
import com.orphybel.alexacleaner.core.domain.FilterState
import com.orphybel.alexacleaner.core.domain.HistoryDb
import com.orphybel.alexacleaner.core.domain.PurgeOptions
import com.orphybel.alexacleaner.core.http.Endpoints
import com.orphybel.alexacleaner.core.model.DeviceSnapshot
import com.orphybel.alexacleaner.core.model.Region
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import com.orphybel.alexacleaner.auth.LoginActivity
import com.orphybel.alexacleaner.graph
import com.orphybel.alexacleaner.work.PurgeProgress
import com.orphybel.alexacleaner.work.PurgeWorker
import com.orphybel.alexacleaner.work.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { DEVICES, ECHOS, PURGE, LOGS, SETTINGS }

/** Everything needed to resume the login after the WebView returns (survives the activity hop). */
data class LoginRequest(
    val region: Region,
    val deviceSerial: String,
    val codeVerifier: String,
    val signInUrl: String,
    val signInHost: String,
    val regionalSignIn: Boolean,
)

data class UiState(
    val screen: Screen = Screen.DEVICES,
    val loggedIn: Boolean = false,
    val customerName: String? = null,
    val region: Region = Region.ALL.first(),
    val loading: Boolean = false,
    val loginInProgress: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val snapshot: DeviceSnapshot? = null,
    val history: HistoryDb = HistoryDb(),
    val insights: List<DeviceInsight> = emptyList(),
    val filtered: List<DeviceInsight> = emptyList(),
    val filter: FilterState = FilterState(),
    val selected: Set<String> = emptySet(),
    /** (source, device count) sorted by count. */
    val sources: List<Pair<String, Int>> = emptyList(),
    val types: List<Pair<String, Int>> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val purge: PurgeProgress? = null,
    val purgeRunning: Boolean = false,
    val runs: List<PurgeRun> = emptyList(),
    val debugLog: List<String> = emptyList(),
) {
    val offlineCount: Int get() = insights.count { it.device.isOffline }
    val unknownCount: Int get() = insights.count { it.device.reachability == com.orphybel.alexacleaner.core.model.Reachability.UNKNOWN }
    val duplicateCount: Int get() = insights.count { it.isDuplicate }
    val selectedDevices: List<SmartHomeDevice> get() = insights.filter { it.id in selected }.map { it.device }
    val selectedInFiltered: Int get() = filtered.count { it.id in selected }
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var wasRunning = false

    init {
        val session = g.sessions.session
        val settings = g.settings.load()
        _state.update {
            it.copy(
                loggedIn = session != null,
                customerName = session?.customerName,
                region = Region.byTld(session?.regionTld ?: Region.ALL.first().tld),
                settings = settings,
                snapshot = g.snapshots.load(),
                history = g.history.load(),
                filter = FilterState(sort = it.filter.sort),
                runs = g.purgeLog.runs(),
                debugLog = g.logBuffer.snapshot(),
            )
        }
        recompute()
        viewModelScope.launch {
            Scheduler.purgeInfoFlow(app).collect { info -> onPurgeInfo(info) }
        }
        viewModelScope.launch {
            Scheduler.scanInfoFlow(app).collect { info ->
                if (info != null && info.state.isFinished) reloadLocal()
            }
        }
        if (session != null && _state.value.snapshot == null) refresh()
    }

    // ---------------------------------------------------------------- navigation & messages

    fun navigate(screen: Screen) = _state.update { it.copy(screen = screen, debugLog = g.logBuffer.snapshot(), runs = if (screen == Screen.LOGS) g.purgeLog.runs() else it.runs) }

    fun dismissMessages() = _state.update { it.copy(error = null, message = null) }

    private fun fail(t: Throwable) {
        val msg = when (t) {
            is AuthException -> t.message ?: "Erreur d'authentification"
            else -> "${t.javaClass.simpleName}: ${t.message}"
        }
        g.logger.log("Erreur: $msg")
        _state.update { it.copy(error = msg, loading = false, loginInProgress = false, debugLog = g.logBuffer.snapshot()) }
    }

    // ---------------------------------------------------------------- login

    fun beginLogin(region: Region, regionalSignIn: Boolean): LoginRequest {
        val auth = AmazonAuth.create(region, g.http, g.logger, Endpoints(region, regionalSignIn))
        return LoginRequest(region, auth.deviceSerial, auth.codeVerifier, auth.signInUrl(), auth.signInHost, regionalSignIn)
    }

    fun loginIntent(request: LoginRequest): Intent = LoginActivity.intent(app, request.region, request.signInUrl, request.signInHost)

    fun completeLogin(request: LoginRequest, code: String, cookieHeader: String?, frc: String?) {
        _state.update { it.copy(loginInProgress = true, error = null) }
        viewModelScope.launch {
            try {
                val auth = AmazonAuth(
                    request.region, request.deviceSerial, request.codeVerifier, g.http, g.logger,
                    Endpoints(request.region, request.regionalSignIn),
                )
                val session = withContext(Dispatchers.IO) { g.sessions.completeLogin(auth, code, cookieHeader, frc) }
                _state.update {
                    it.copy(
                        loggedIn = true,
                        loginInProgress = false,
                        customerName = session.customerName,
                        region = request.region,
                        message = "Connecté${session.customerName?.let { n -> " en tant que $n" } ?: ""}",
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun logout() {
        g.sessions.logout()
        g.snapshots.clear()
        g.apiHints.clear()
        Scheduler.syncPeriodicScan(app, 0)
        _state.update {
            it.copy(loggedIn = false, customerName = null, snapshot = null, insights = emptyList(), filtered = emptyList(), selected = emptySet(), screen = Screen.DEVICES)
        }
    }

    // ---------------------------------------------------------------- data

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { g.api.fetchSnapshot() }
                val now = System.currentTimeMillis()
                val history = g.history.load().record(snapshot.smartHome, now)
                withContext(Dispatchers.IO) {
                    g.snapshots.save(snapshot)
                    g.history.save(history)
                }
                _state.update { it.copy(snapshot = snapshot, history = history, loading = false, debugLog = g.logBuffer.snapshot()) }
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun reloadLocal() {
        _state.update {
            it.copy(snapshot = g.snapshots.load() ?: it.snapshot, history = g.history.load(), runs = g.purgeLog.runs(), debugLog = g.logBuffer.snapshot())
        }
        recompute()
    }

    private fun recompute() {
        _state.update { s ->
            val devices = s.snapshot?.smartHome ?: emptyList()
            val insights = DeviceFilters.insights(devices, s.history, System.currentTimeMillis())
            val filtered = DeviceFilters.apply(insights, s.filter)
            val ids = devices.map { it.applianceId }.toSet()
            s.copy(
                insights = insights,
                filtered = filtered,
                selected = s.selected.filter { it in ids }.toSet(),
                sources = devices.groupingBy { it.source }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }),
                types = devices.groupingBy { it.primaryType }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }),
            )
        }
    }

    // ---------------------------------------------------------------- filters & selection

    fun updateFilter(transform: (FilterState) -> FilterState) {
        _state.update { it.copy(filter = transform(it.filter)) }
        recompute()
    }

    fun resetFilter() = updateFilter { FilterState(sort = it.sort, sortDescending = it.sortDescending) }

    fun toggleSelected(id: String) = _state.update { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun selectAllFiltered() = _state.update { s -> s.copy(selected = s.selected + s.filtered.map { it.id }) }

    fun selectOnlyFiltered() = _state.update { s -> s.copy(selected = s.filtered.map { it.id }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun invertSelectionInFiltered() = _state.update { s ->
        val ids = s.filtered.map { it.id }
        val newly = ids.filter { it !in s.selected }.toSet()
        s.copy(selected = (s.selected - ids.toSet()) + newly)
    }

    // ---------------------------------------------------------------- purge

    fun startPurge(options: PurgeOptions, targets: List<SmartHomeDevice> = _state.value.selectedDevices) {
        if (targets.isEmpty() && options.method != com.orphybel.alexacleaner.core.domain.PurgeMethod.WIPE_AND_REDISCOVER) return
        val all = _state.value.snapshot?.smartHome ?: emptyList()
        val effectiveTargets = if (options.method == com.orphybel.alexacleaner.core.domain.PurgeMethod.WIPE_AND_REDISCOVER) all else targets
        val settings = _state.value.settings.copy(purgeDefaults = options.copy(dryRun = false))
        g.settings.save(settings)
        Scheduler.enqueuePurge(app, effectiveTargets, options)
        wasRunning = true
        _state.update {
            it.copy(
                settings = settings,
                screen = Screen.PURGE,
                purgeRunning = true,
                purge = PurgeProgress(0, effectiveTargets.size, 0, 0, "En file d'attente…"),
                selected = emptySet(),
            )
        }
    }

    fun cancelPurge() = Scheduler.cancelPurge(app)

    private fun onPurgeInfo(info: WorkInfo?) {
        if (info == null) {
            _state.update { it.copy(purgeRunning = false) }
            return
        }
        val running = !info.state.isFinished
        val progress = if (running) PurgeWorker.progressOf(info.progress) else PurgeWorker.progressOf(info.outputData)
        val hasData = progress.total > 0 || progress.last.isNotBlank() || progress.summary != null
        val runs = g.purgeLog.runs()
        _state.update { s ->
            s.copy(
                purgeRunning = running,
                purge = if (hasData) progress else s.purge,
                runs = runs,
            )
        }
        if (!running && wasRunning) {
            wasRunning = false
            val out = PurgeWorker.progressOf(info.outputData)
            _state.update { it.copy(message = out.last.ifBlank { "Suppression terminée" }) }
            refresh()
        } else if (running) {
            wasRunning = true
        }
    }

    // ---------------------------------------------------------------- settings, exports, maintenance

    fun saveSettings(settings: AppSettings) {
        g.settings.save(settings)
        Scheduler.syncPeriodicScan(app, settings.scanIntervalHours)
        _state.update { it.copy(settings = settings) }
    }

    fun scanNow() {
        Scheduler.runScanNow(app)
        _state.update { it.copy(message = "Analyse lancée en arrière-plan") }
    }

    fun exportJson(): File? = _state.value.snapshot?.let { g.exporter.writeSnapshotJson(it) }

    fun exportCsv(): File? = _state.value.snapshot?.let { g.exporter.writeCsv(it.smartHome) }

    fun exportDebugLog(): File = g.exporter.writeText("alexa-cleaner-log", g.logBuffer.snapshot().joinToString("\n"))

    fun exportFiles(): List<File> = g.exporter.listFiles()

    fun share(file: File) {
        val intent = g.exporter.shareIntent(file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    fun saveToDownloads(file: File) {
        val ok = g.exporter.saveToDownloads(file)
        _state.update { it.copy(message = if (ok) "Enregistré dans Téléchargements/AlexaCleaner" else "Enregistrement non pris en charge, utilisez le partage") }
    }

    fun clearHistory() {
        g.history.clear()
        _state.update { it.copy(history = HistoryDb(), message = "Historique des analyses effacé") }
        recompute()
    }

    fun clearLogs() {
        g.purgeLog.clear()
        _state.update { it.copy(runs = emptyList(), message = "Journal effacé") }
    }

    fun refreshDebugLog() = _state.update { it.copy(debugLog = g.logBuffer.snapshot()) }

    /** Probes every Amazon endpoint the app uses and writes the results to the technical log. */
    fun runDiagnostics() {
        _state.update { it.copy(loading = true, message = "Diagnostic en cours…") }
        viewModelScope.launch {
            try {
                val sample = _state.value.snapshot?.smartHome?.firstOrNull { !it.entityId.isNullOrBlank() }?.entityId
                val lines = withContext(Dispatchers.IO) { g.api.diagnostics(sample) }
                _state.update {
                    it.copy(loading = false, debugLog = g.logBuffer.snapshot(), message = "Diagnostic terminé (${lines.size} points testés), voir le journal technique")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}
