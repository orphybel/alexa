package com.orphybel.alexacleaner.core.data

import com.orphybel.alexacleaner.core.domain.AppSettings
import com.orphybel.alexacleaner.core.domain.HistoryDb
import com.orphybel.alexacleaner.core.domain.PurgeRecord
import com.orphybel.alexacleaner.core.domain.PurgeSummary
import com.orphybel.alexacleaner.core.model.DeviceSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Minimal key → text storage; the Android app implements it with files in the private directory. */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun append(key: String, line: String)
    fun delete(key: String)
}

class InMemoryTextStore : TextStore {
    private val map = HashMap<String, StringBuilder>()
    override fun read(key: String): String? = map[key]?.toString()
    override fun write(key: String, value: String) { map[key] = StringBuilder(value) }
    override fun append(key: String, line: String) { map.getOrPut(key) { StringBuilder() }.append(line).append('\n') }
    override fun delete(key: String) { map.remove(key) }
}

val storeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

class HistoryRepository(private val store: TextStore) {
    fun load(): HistoryDb = store.read(KEY)?.let { runCatching { storeJson.decodeFromString<HistoryDb>(it) }.getOrNull() } ?: HistoryDb()
    fun save(db: HistoryDb) = store.write(KEY, storeJson.encodeToString(db))
    fun clear() = store.delete(KEY)

    companion object { const val KEY = "history.json" }
}

class SettingsRepository(private val store: TextStore) {
    fun load(): AppSettings = store.read(KEY)?.let { runCatching { storeJson.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    fun save(s: AppSettings) = store.write(KEY, storeJson.encodeToString(s))

    companion object { const val KEY = "settings.json" }
}

class SnapshotRepository(private val store: TextStore) {
    fun load(): DeviceSnapshot? = store.read(KEY)?.let { runCatching { storeJson.decodeFromString<DeviceSnapshot>(it) }.getOrNull() }
    fun save(s: DeviceSnapshot) = store.write(KEY, storeJson.encodeToString(s))
    fun clear() = store.delete(KEY)

    companion object { const val KEY = "snapshot.json" }
}

@Serializable
data class PurgeLogLine(
    val runId: String,
    val record: PurgeRecord? = null,
    val summary: PurgeSummary? = null,
)

/** Append-only journal of every deletion attempt (JSON lines). */
class PurgeLogRepository(private val store: TextStore) {
    fun append(line: PurgeLogLine) = store.append(KEY, storeJson.encodeToString(line))

    fun readAll(): List<PurgeLogLine> = store.read(KEY)
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { runCatching { storeJson.decodeFromString<PurgeLogLine>(it) }.getOrNull() }
        ?.toList()
        ?: emptyList()

    fun clear() = store.delete(KEY)

    /** Groups the journal per run, most recent first. */
    fun runs(): List<PurgeRun> = readAll()
        .groupBy { it.runId }
        .map { (runId, lines) ->
            PurgeRun(
                runId = runId,
                summary = lines.firstNotNullOfOrNull { it.summary },
                records = lines.mapNotNull { it.record },
            )
        }
        .sortedByDescending { it.summary?.startedAt ?: it.records.firstOrNull()?.at ?: 0L }

    companion object { const val KEY = "purge-log.jsonl" }
}

data class PurgeRun(val runId: String, val summary: PurgeSummary?, val records: List<PurgeRecord>)
