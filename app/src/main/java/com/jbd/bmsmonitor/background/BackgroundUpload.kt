package com.jbd.bmsmonitor.background

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jbd.bmsmonitor.ble.JbdBleRepository
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.model.ServerUploadConfig
import com.jbd.bmsmonitor.network.BatteryDataUploader
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One repository for both the UI and workers, so connections and saved snapshots cannot compete. */
class BatteryMonitorApplication : Application() {
    val repository by lazy { JbdBleRepository(this) }
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
    const val SERVER_ENABLED = "server_upload_enabled"
    const val SERVER_URL = "server_upload_url"
    const val SERVER_API_KEY = "server_upload_api_key"
    const val LAST_UPLOAD_PREFIX = "server_last_upload_at_"
    private const val WORK_NAME = "background_bms_upload"

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

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!enabled(context)) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackgroundUploadWorker>(30, TimeUnit.MINUTES)
            // Stagger installations to reduce contention when several phones share a BMS.
            .setInitialDelay(30 * 60 + Random.nextLong(0, 121), TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class BackgroundUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val app = applicationContext as BatteryMonitorApplication
        if (!BackgroundUpload.enabled(app) || app.backgroundConnections.foreground) return@withContext Result.success()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return@withContext Result.success()
        val repository = app.repository
        if (!runCatching { repository.bluetoothEnabled }.getOrDefault(false)) return@withContext Result.success()
        // Also stagger later runs if Android batches several devices' jobs at the same time.
        delay(Random.nextLong(0, 30_001))
        if (!BackgroundUpload.enabled(app)) return@withContext Result.success()
        val session = app.backgroundConnections.begin() ?: return@withContext Result.success()
        try {
            withTimeoutOrNull(4 * 60_000L) {
                val devices = repository.devices.value.values.filter { it.lastConnectedAtMillis > 0L }.shuffled()
                val uploader = BatteryDataUploader(app)
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
                            val uploaded = withContext(Dispatchers.IO) { uploader.upload(config, reading) }
                            if (uploaded) {
                                app.getSharedPreferences(BackgroundUpload.PREFERENCES, Context.MODE_PRIVATE).edit()
                                    .putLong(BackgroundUpload.LAST_UPLOAD_PREFIX + device.address, System.currentTimeMillis())
                                    .apply()
                            }
                        }
                    } catch (_: SecurityException) {
                        // Permission or Bluetooth access can be revoked while work is running.
                        break
                    } catch (_: IllegalArgumentException) {
                        // Ignore an invalid saved BLE address and continue with the remaining devices.
                    } finally {
                        session.disconnect()
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { session.close() }
        }
        // Unavailable/busy BMSes are tried again next period, without an aggressive retry loop.
        Result.success()
    }
}
