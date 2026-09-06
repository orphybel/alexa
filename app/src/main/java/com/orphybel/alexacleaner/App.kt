package com.orphybel.alexacleaner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.orphybel.alexacleaner.core.api.AlexaApi
import com.orphybel.alexacleaner.core.auth.MemoryCookieJar
import com.orphybel.alexacleaner.core.auth.SessionManager
import com.orphybel.alexacleaner.core.data.ApiHintsStore
import com.orphybel.alexacleaner.core.data.HistoryRepository
import com.orphybel.alexacleaner.core.data.PurgeLogRepository
import com.orphybel.alexacleaner.core.data.SettingsRepository
import com.orphybel.alexacleaner.core.data.SnapshotRepository
import com.orphybel.alexacleaner.core.http.Logger
import com.orphybel.alexacleaner.data.EncryptedSessionStore
import com.orphybel.alexacleaner.data.Exporter
import com.orphybel.alexacleaner.data.FileTextStore
import com.orphybel.alexacleaner.work.Scheduler
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createNotificationChannels()
        Scheduler.syncPeriodicScan(this, graph.settings.load().scanIntervalHours)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PURGE, getString(R.string.notif_channel_purge), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, getString(R.string.notif_channel_scan), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_PURGE = "purge"
        const val CHANNEL_SCAN = "scan"
        const val TAG = "AlexaCleaner"
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph

/** Keeps the last few hundred log lines so the settings screen can show what the app is doing. */
class LogBuffer(private val capacity: Int = 400) {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast("${fmt.format(Date())} $line")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

/** Manual dependency graph; small enough not to need a DI framework. */
class AppGraph(context: Context) {
    val logBuffer = LogBuffer()
    val logger = Logger { line ->
        Log.d(App.TAG, line)
        logBuffer.add(line)
    }
    val cookieJar = MemoryCookieJar()
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val textStore = FileTextStore(File(context.filesDir, "data"))
    val sessionStore = EncryptedSessionStore(context)
    val sessions = SessionManager(sessionStore, http, cookieJar, logger)
    val apiHints = ApiHintsStore(textStore)
    val api = AlexaApi(http, sessions, logger, apiHints)
    val history = HistoryRepository(textStore)
    val settings = SettingsRepository(textStore)
    val snapshots = SnapshotRepository(textStore)
    val purgeLog = PurgeLogRepository(textStore)
    val exporter = Exporter(context)
}
