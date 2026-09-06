package com.orphybel.alexacleaner.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.orphybel.alexacleaner.core.auth.AlexaSession
import com.orphybel.alexacleaner.core.auth.SessionStore
import com.orphybel.alexacleaner.core.data.TextStore
import com.orphybel.alexacleaner.core.data.storeJson
import com.orphybel.alexacleaner.core.model.DeviceSnapshot
import com.orphybel.alexacleaner.core.model.SmartHomeDevice
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain files under the app's private directory. */
class FileTextStore(private val dir: File) : TextStore {
    init {
        dir.mkdirs()
    }

    private fun file(key: String) = File(dir, key)

    @Synchronized
    override fun read(key: String): String? = file(key).takeIf { it.exists() }?.readText()

    @Synchronized
    override fun write(key: String, value: String) {
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(file(key))) {
            file(key).writeText(value)
            tmp.delete()
        }
    }

    @Synchronized
    override fun append(key: String, line: String) {
        file(key).appendText(line + "\n")
    }

    @Synchronized
    override fun delete(key: String) {
        file(key).delete()
    }
}

/**
 * Stores the session (refresh token + cookies) in EncryptedSharedPreferences. Falls back to
 * regular private preferences when the keystore is unusable (some ROMs), which is still
 * app-private storage.
 */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "session.enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Throwable) {
        context.getSharedPreferences("session.plain", Context.MODE_PRIVATE)
    }

    override fun load(): AlexaSession? = prefs.getString(KEY, null)?.let {
        runCatching { storeJson.decodeFromString<AlexaSession>(it) }.getOrNull()
    }

    override fun save(session: AlexaSession?) {
        prefs.edit().apply {
            if (session == null) remove(KEY) else putString(KEY, storeJson.encodeToString(session))
        }.apply()
    }

    companion object {
        private const val KEY = "session"
    }
}

/** Writes exports/backups and shares them through the FileProvider. */
class Exporter(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "exports").also { it.mkdirs() }
    private val stamp: String get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun writeJson(name: String, devices: List<SmartHomeDevice>): File {
        val f = File(dir, "$name-$stamp.json")
        f.writeText(storeJson.encodeToString(devices))
        return f
    }

    fun writeSnapshotJson(snapshot: DeviceSnapshot): File {
        val f = File(dir, "alexa-devices-$stamp.json")
        f.writeText(storeJson.encodeToString(snapshot))
        return f
    }

    fun writeCsv(devices: List<SmartHomeDevice>): File {
        val f = File(dir, "alexa-devices-$stamp.csv")
        val sb = StringBuilder()
        sb.append("applianceId;friendlyName;source;manufacturer;types;reachability;enabled;description;model;createdAt;lastSeenAt;entityId\n")
        for (d in devices) {
            sb.append(
                listOf(
                    d.applianceId, d.friendlyName, d.source, d.manufacturerName ?: "", d.applianceTypes.joinToString("|"),
                    d.reachability.name, d.isEnabled.toString(), d.friendlyDescription ?: "", d.modelName ?: "",
                    d.createdAt?.toString() ?: "", d.lastSeenAt?.toString() ?: "", d.entityId ?: "",
                ).joinToString(";") { csv(it) },
            ).append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    fun writeText(name: String, content: String): File {
        val f = File(dir, "$name-$stamp.txt")
        f.writeText(content)
        return f
    }

    fun listFiles(): List<File> = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = when (file.extension.lowercase()) {
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "text/plain"
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, file.name) }
    }

    /** Copies a file into the public Downloads folder (Android 10+). Returns false when unsupported. */
    fun saveToDownloads(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "csv") "text/csv" else "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AlexaCleaner")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
        return true
    }

    private fun csv(v: String): String = if (v.any { it == ';' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
