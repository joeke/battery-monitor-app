package com.jbd.bmsmonitor.storage

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class UploadLogEntry(
    val timestampMillis: Long,
    val message: String,
    val id: String = UUID.randomUUID().toString(),
) {
    fun timestamp(zone: ZoneId = ZoneId.systemDefault()): String =
        FORMAT.withZone(zone).format(Instant.ofEpochMilli(timestampMillis))

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm:ss", Locale.ROOT)
    }
}

/** One application-owned store shared by foreground uploads, workers, and the Logs screen. */
class UploadLogStore(
    initialEntries: List<UploadLogEntry> = emptyList(),
    private val persist: (List<UploadLogEntry>) -> Unit,
) {
    private val _entries = MutableStateFlow(initialEntries.take(MAX_ENTRIES))
    val entries = _entries.asStateFlow()

    @Synchronized
    fun append(message: String, timestampMillis: Long = System.currentTimeMillis()) {
        update((listOf(UploadLogEntry(timestampMillis, message)) + _entries.value).take(MAX_ENTRIES))
    }

    @Synchronized
    fun clear() = update(emptyList())

    private fun update(entries: List<UploadLogEntry>) {
        // A storage failure must never prevent an upload or stop its recurring schedule.
        runCatching { persist(entries) }
        _entries.value = entries
    }

    companion object {
        const val MAX_ENTRIES = 1_000

        fun open(context: Context): UploadLogStore {
            val preferences = context.getSharedPreferences("upload_logs", Context.MODE_PRIVATE)
            val entries = runCatching {
                val array = JSONArray(preferences.getString("entries", "[]"))
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                        val json = array.optJSONObject(index) ?: continue
                        val id = json.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val message = json.optString("message").takeIf { it.isNotBlank() } ?: continue
                        add(UploadLogEntry(json.getLong("timestamp"), message, id))
                    }
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
            return UploadLogStore(entries) { saved ->
                val array = JSONArray()
                saved.forEach { entry ->
                    array.put(JSONObject().apply {
                        put("id", entry.id)
                        put("timestamp", entry.timestampMillis)
                        put("message", entry.message)
                    })
                }
                preferences.edit().putString("entries", array.toString()).apply()
            }
        }
    }
}
