package com.jbd.bmsmonitor.background

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.jbd.bmsmonitor.ble.JbdBleRepository
import com.jbd.bmsmonitor.model.BackgroundUploadInterval
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.model.ServerUploadConfig
import com.jbd.bmsmonitor.network.BatteryDataUploader
import com.jbd.bmsmonitor.storage.UploadLogStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One repository for both the UI and workers, so connections and saved snapshots cannot compete. */
class BatteryMonitorApplication : Application() {
    val repository by lazy { JbdBleRepository(this) }
    val uploadLogs by lazy { UploadLogStore.open(this) }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    val backgroundDisconnect by lazy {
        BackgroundDisconnect(
            elapsedRealtime = SystemClock::elapsedRealtime,
            scheduleTimeout = { timeout, delay -> mainHandler.postDelayed(timeout, delay); Unit },
            cancelTimeout = { mainHandler.removeCallbacks(it) },
            disconnectDevices = repository::disconnectAll,
        )
    }
    val backgroundConnections by lazy {
        BackgroundConnections(
            hasActiveConnections = {
                repository.devices.value.values.any { it.connectionStatus != ConnectionStatus.DISCONNECTED }
            },
            connectDevice = { repository.connect(it, readSettings = false) },
            disconnectDevice = repository::disconnect,
        )
    }

    override fun onCreate() {
        super.onCreate()
        BackgroundUpload.schedule(this)
    }
}

object BackgroundUpload {
    const val PREFERENCES = "app_preferences"
    const val ENABLED = "background_upload_enabled"
    const val INTERVAL_SECONDS = "background_upload_interval_seconds"
    internal const val GENERATION = "background_upload_generation"
    private const val DISCONNECT_AT = "background_disconnect_at_millis"
    const val SERVER_ENABLED = "server_upload_enabled"
    const val SERVER_URL = "server_upload_url"
    const val SERVER_API_KEY = "server_upload_api_key"
    const val LAST_UPLOAD_PREFIX = "server_last_upload_at_"
    private const val LEGACY_WORK_NAME = "background_bms_upload"
    private const val WORK_NAME = "background_bms_upload_cycles"

    fun interval(context: Context): BackgroundUploadInterval {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return BackgroundUploadInterval.fromSeconds(
            preferences.getInt(INTERVAL_SECONDS, BackgroundUploadInterval.DEFAULT.seconds),
        )
    }

    fun config(context: Context): ServerUploadConfig {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return ServerUploadConfig(
            enabled = preferences.getBoolean(SERVER_ENABLED, false),
            serverUrl = preferences.getString(SERVER_URL, "").orEmpty(),
            apiKey = preferences.getString(SERVER_API_KEY, "").orEmpty(),
        )
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false) &&
            config(context).isConfigured

    fun beginBackground(context: Context, disconnectSeconds: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(DISCONNECT_AT, System.currentTimeMillis() + disconnectSeconds * 1_000L).apply()
        // Persist the whole delay now, so Android can resume uploads even if the process dies during the countdown.
        schedule(context, restart = true)
    }

    fun endBackground(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(DISCONNECT_AT).apply()
        schedule(context)
    }

    @Synchronized
    fun schedule(context: Context, restart: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        // Migrate installations that still have the old 30-minute periodic request.
        manager.cancelUniqueWork(LEGACY_WORK_NAME)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!enabled(context) || !preferences.contains(DISCONNECT_AT)) {
            preferences.edit().remove(GENERATION).apply()
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val generation = if (restart) null else preferences.getString(GENERATION, null)
        val currentGeneration = generation ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(GENERATION, it).apply()
        }
        manager.enqueueUniqueWork(
            WORK_NAME,
            if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(context, currentGeneration),
        )
    }

    fun isCurrent(context: Context, generation: String?): Boolean =
        generation != null && enabled(context) &&
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).contains(DISCONNECT_AT) &&
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(GENERATION, null) == generation

    @Synchronized
    internal fun scheduleNext(context: Context, generation: String): Operation? {
        // A cancelled/replaced cycle must not append work to the new schedule.
        if (!isCurrent(context, generation)) return null
        return WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, generation),
        )
    }

    private fun request(context: Context, generation: String): OneTimeWorkRequest {
        val seconds = interval(context).seconds
        val remainingDisconnectMillis = (
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(DISCONNECT_AT, 0L) -
                System.currentTimeMillis()
            ).coerceAtLeast(0L)
        return OneTimeWorkRequestBuilder<BackgroundUploadWorker>()
            // Periodic work clamps intervals to 15 minutes; delayed one-time cycles support all options.
            // Keep jitter small relative to the selected interval, especially for 30 seconds.
            .setInitialDelay(
                remainingDisconnectMillis +
                    (seconds + Random.nextLong(0, minOf(30, seconds / 10) + 1L)) * 1_000L,
                TimeUnit.MILLISECONDS,
            )
            .setInputData(workDataOf(GENERATION to generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    }
}

class BackgroundUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext as BatteryMonitorApplication
        val generation = inputData.getString(BackgroundUpload.GENERATION) ?: return@withContext Result.success()
        // A suspended process may have missed its Handler timeout. Finish that disconnect first,
        // without replacing this worker: beginBackground already scheduled the full initial delay.
        if (!app.backgroundConnections.foreground) app.backgroundDisconnect.disconnectIfDue()
        if (!BackgroundUpload.isCurrent(app, generation)) return@withContext Result.success()
        app.uploadLogs.append("Background upload cycle started.")
        try {
            uploadOnce(app)
            coroutineContext.ensureActive()
            if (!isStopped) BackgroundUpload.scheduleNext(app, generation)?.await()
            app.uploadLogs.append("Background upload cycle finished.")
            Result.success()
        } catch (cancelled: CancellationException) {
            app.uploadLogs.append("Background upload cycle cancelled.")
            throw cancelled
        } catch (_: Exception) {
            app.uploadLogs.append("Background upload cycle failed; a retry is scheduled.")
            // Keep the schedule alive after a transient failure; WorkManager applies retry backoff.
            Result.retry()
        }
    }

    private suspend fun uploadOnce(app: BatteryMonitorApplication) {
        if (!BackgroundUpload.enabled(app) || app.backgroundConnections.foreground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            app.uploadLogs.append("Background upload skipped: Bluetooth permission is missing.")
            return
        }
        val repository = app.repository
        if (!runCatching { repository.bluetoothEnabled }.getOrDefault(false)) {
            app.uploadLogs.append("Background upload skipped: Bluetooth is off or unavailable.")
            return
        }
        val session = app.backgroundConnections.begin() ?: run {
            app.uploadLogs.append("Background upload skipped: another Bluetooth session is active.")
            return
        }
        try {
            val completed = withTimeoutOrNull(4 * 60_000L) {
                val devices = repository.devices.value.values.filter { it.lastConnectedAtMillis > 0L }.shuffled()
                if (devices.isEmpty()) app.uploadLogs.append("Background upload skipped: no previously connected batteries.")
                val uploader = BatteryDataUploader(app, app.uploadLogs)
                for (device in devices) {
                    if (session.closed || !BackgroundUpload.enabled(app)) break
                    try {
                        session.connect(DiscoveredBms(device.address, device.name, device.rssi))
                        val reading = withTimeoutOrNull(45_000L) reading@{
                            while (!session.closed) {
                                val state = repository.devices.value[device.address] ?: break
                                if (state.connectionStatus == ConnectionStatus.DISCONNECTED) break
                                if (repository.hasCompleteReading(device.address)) return@reading state
                                delay(100)
                            }
                            null
                        }
                        // Release BLE before network I/O, leaving it available to other phones.
                        session.disconnect()
                        if (session.closed || !BackgroundUpload.enabled(app)) break
                        if (reading != null) {
                            val config = BackgroundUpload.config(app)
                            val uploaded = withContext(Dispatchers.IO) { uploader.upload(config, reading, background = true) }
                            if (uploaded) {
                                app.getSharedPreferences(BackgroundUpload.PREFERENCES, Context.MODE_PRIVATE).edit()
                                    .putLong(BackgroundUpload.LAST_UPLOAD_PREFIX + device.address, System.currentTimeMillis())
                                    .apply()
                            }
                        } else {
                            app.uploadLogs.append(
                                "Background upload skipped for ${device.name.ifBlank { device.address }}: " +
                                    "could not obtain a complete reading; the battery may be busy, unreachable, or timed out.",
                            )
                        }
                    } catch (_: SecurityException) {
                        app.uploadLogs.append("Background upload stopped: Bluetooth access was denied.")
                        // Permission or Bluetooth access can be revoked while work is running.
                        break
                    } catch (_: IllegalArgumentException) {
                        app.uploadLogs.append("Background upload skipped for ${device.name}: invalid Bluetooth address.")
                        // Ignore an invalid saved BLE address and continue with the remaining devices.
                    } finally {
                        session.disconnect()
                    }
                }
                true
            }
            if (completed == null) app.uploadLogs.append("Background upload stopped: the cycle reached its four-minute limit.")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { session.close() }
        }
    }
}
