package com.jbd.bmsmonitor

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jbd.bmsmonitor.ble.JbdBleRepository
import com.jbd.bmsmonitor.model.BmsTelemetry
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.model.ServerUploadConfig
import com.jbd.bmsmonitor.network.BatteryDataUploader
import com.jbd.bmsmonitor.network.ServerConnectionCheckResult
import com.jbd.bmsmonitor.update.AppUpdateManager
import com.jbd.bmsmonitor.update.UpdateCheckResult
import com.jbd.bmsmonitor.update.UpdateDownloadResult
import com.jbd.bmsmonitor.update.UpdateRelease
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = JbdBleRepository(application)
    private val preferences = application.getSharedPreferences(APP_PREFERENCES, Application.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batteryDataUploader = BatteryDataUploader(application)
    private val appUpdateManager = AppUpdateManager(application)
    private var backgroundedAtRealtime: Long? = null
    private var appIsForegrounded = false
    private var uploadJob: Job? = null
    private var connectionCheckJob: Job? = null
    private var appUpdateJob: Job? = null
    private val lastUploadAttemptAt = mutableMapOf<String, Long>()
    private val lastUploadedReading = mutableMapOf<String, BmsTelemetry>()
    private val pendingImmediateUploads = mutableSetOf<String>()

    init {
        repository.onInitialReading = { address ->
            mainHandler.post {
                if (appIsForegrounded && _serverUploadConfig.value.isConfigured) {
                    val reading = repository.devices.value[address]?.telemetry
                    if (reading != null && lastUploadedReading[address] !== reading) {
                        pendingImmediateUploads += address
                        requestUploadCheck()
                    }
                }
            }
        }
    }

    private val _backgroundDisconnectSeconds = MutableStateFlow(loadBackgroundTimeoutSeconds())
    val backgroundDisconnectSeconds = _backgroundDisconnectSeconds.asStateFlow()

    private val _serverUploadConfig = MutableStateFlow(
        ServerUploadConfig(
            enabled = preferences.getBoolean(KEY_SERVER_UPLOAD_ENABLED, false),
            serverUrl = preferences.getString(KEY_SERVER_URL, "").orEmpty(),
            apiKey = preferences.getString(KEY_SERVER_API_KEY, "").orEmpty(),
        ),
    )
    val serverUploadConfig = _serverUploadConfig.asStateFlow()

    private val _serverConnectionCheck = MutableStateFlow<ServerConnectionCheckState>(ServerConnectionCheckState.Idle)
    val serverConnectionCheck = _serverConnectionCheck.asStateFlow()

    private val _appUpdateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val appUpdateState = _appUpdateState.asStateFlow()

    val discovered = repository.discovered
    val devices = repository.devices
    val scanning = repository.scanning
    val bluetoothAvailable get() = repository.bluetoothAvailable
    val bluetoothEnabled get() = repository.bluetoothEnabled

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(device: DiscoveredBms) = repository.connect(device)
    fun disconnect(address: String) = repository.disconnect(address)
    fun reconnect(address: String) = repository.reconnect(address)

    fun setBackgroundDisconnectSeconds(seconds: Int) {
        require(seconds in BACKGROUND_TIMEOUT_OPTIONS_SECONDS)
        _backgroundDisconnectSeconds.value = seconds
        preferences.edit()
            .putInt(KEY_BACKGROUND_TIMEOUT_SECONDS, seconds)
            .remove(LEGACY_KEY_BACKGROUND_TIMEOUT_MINUTES)
            .apply()
    }

    fun setServerUploadEnabled(enabled: Boolean) {
        if (!enabled) {
            connectionCheckJob?.cancel()
            _serverConnectionCheck.value = ServerConnectionCheckState.Idle
            pendingImmediateUploads.clear()
        }
        _serverUploadConfig.value = _serverUploadConfig.value.copy(enabled = enabled)
        preferences.edit().putBoolean(KEY_SERVER_UPLOAD_ENABLED, enabled).apply()
        requestUploadCheck()
    }

    fun saveServerUploadConfiguration(serverUrl: String, apiKey: String) {
        val normalizedUrl = serverUrl.trim()
        val normalizedApiKey = apiKey.trim()
        _serverUploadConfig.value = _serverUploadConfig.value.copy(
            serverUrl = normalizedUrl,
            apiKey = normalizedApiKey,
        )
        preferences.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_SERVER_API_KEY, normalizedApiKey)
            .apply()
        checkServerConnection(normalizedUrl, normalizedApiKey)
        requestUploadCheck()
    }

    fun clearServerConnectionCheck() {
        connectionCheckJob?.cancel()
        _serverConnectionCheck.value = ServerConnectionCheckState.Idle
    }

    fun checkForAppUpdate() {
        if (
            appUpdateJob?.isActive == true ||
            _appUpdateState.value is AppUpdateState.Available ||
            _appUpdateState.value is AppUpdateState.ReadyToInstall
        ) return
        _appUpdateState.value = AppUpdateState.Checking
        appUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            _appUpdateState.value = when (val result = appUpdateManager.checkForUpdate()) {
                is UpdateCheckResult.Available -> AppUpdateState.Available(result.release)
                is UpdateCheckResult.UpToDate -> AppUpdateState.UpToDate(result.latestVersion)
                is UpdateCheckResult.Failed -> AppUpdateState.Error(result.message)
            }
        }
    }

    fun downloadAppUpdate() {
        val release = (_appUpdateState.value as? AppUpdateState.Available)?.release ?: return
        if (appUpdateJob?.isActive == true) return
        _appUpdateState.value = AppUpdateState.Downloading(release)
        appUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            _appUpdateState.value = when (val result = appUpdateManager.downloadAndValidate(release)) {
                is UpdateDownloadResult.Ready -> AppUpdateState.ReadyToInstall(release, result.file)
                is UpdateDownloadResult.Failed -> AppUpdateState.Error(result.message)
            }
        }
    }

    fun installDownloadedUpdate() {
        val ready = _appUpdateState.value as? AppUpdateState.ReadyToInstall ?: return
        runCatching {
            getApplication<Application>().startActivity(appUpdateManager.createInstallIntent(ready.file))
        }.onFailure {
            _appUpdateState.value = AppUpdateState.Error("Android could not open the package installer.")
        }
    }

    private fun checkServerConnection(serverUrl: String, apiKey: String) {
        connectionCheckJob?.cancel()
        _serverConnectionCheck.value = ServerConnectionCheckState.Checking
        connectionCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val result = batteryDataUploader.checkConnection(serverUrl, apiKey)
            if (isActive) {
                _serverConnectionCheck.value = ServerConnectionCheckState.Complete(result)
            }
        }
    }

    fun onAppBackgrounded() {
        appIsForegrounded = false
        mainHandler.removeCallbacks(uploadCheck)
        mainHandler.removeCallbacks(disconnectAfterBackgroundTimeout)
        val seconds = _backgroundDisconnectSeconds.value
        if (seconds == NEVER_DISCONNECT) {
            backgroundedAtRealtime = null
            return
        }
        backgroundedAtRealtime = SystemClock.elapsedRealtime()
        mainHandler.postDelayed(disconnectAfterBackgroundTimeout, seconds * 1_000L)
    }

    fun onAppForegrounded() {
        appIsForegrounded = true
        requestUploadCheck()
        val backgroundedAt = backgroundedAtRealtime
        val timeoutSeconds = _backgroundDisconnectSeconds.value
        mainHandler.removeCallbacks(disconnectAfterBackgroundTimeout)
        backgroundedAtRealtime = null
        if (
            backgroundedAt != null &&
            timeoutSeconds != NEVER_DISCONNECT &&
            SystemClock.elapsedRealtime() - backgroundedAt >= timeoutSeconds * 1_000L
        ) {
            repository.disconnectAll()
        }
    }

    private val disconnectAfterBackgroundTimeout = Runnable {
        backgroundedAtRealtime = null
        repository.disconnectAll()
    }

    private val uploadCheck = object : Runnable {
        override fun run() {
            if (!appIsForegrounded) return
            uploadCurrentReadings()
            mainHandler.postDelayed(this, UPLOAD_CHECK_INTERVAL_MS)
        }
    }

    private fun requestUploadCheck() {
        if (!appIsForegrounded) return
        mainHandler.removeCallbacks(uploadCheck)
        mainHandler.post(uploadCheck)
    }

    private fun uploadCurrentReadings() {
        val config = _serverUploadConfig.value
        if (!config.isConfigured || uploadJob?.isActive == true) return

        val now = System.currentTimeMillis()
        pendingImmediateUploads.retainAll(repository.devices.value.values
            .filter { it.connectionStatus == ConnectionStatus.CONNECTED && repository.hasCompleteReading(it.address) }
            .map { it.address }.toSet())
        val eligibleDevices = repository.devices.value.values.filter { device ->
            val lastAttempt = lastUploadAttemptAt[device.address] ?: 0L
            val lastSuccessfulUpload = preferences.getLong(lastUploadKey(device.address), 0L)
            device.connectionStatus == ConnectionStatus.CONNECTED &&
                repository.hasCompleteReading(device.address) &&
                device.telemetry.updatedAtMillis > 0L &&
                (device.address in pendingImmediateUploads ||
                    now - maxOf(lastAttempt, lastSuccessfulUpload) >= UPLOAD_INTERVAL_MS)
        }
        if (eligibleDevices.isEmpty()) return

        eligibleDevices.forEach {
            lastUploadAttemptAt[it.address] = now
            lastUploadedReading[it.address] = it.telemetry
        }
        pendingImmediateUploads.removeAll(eligibleDevices.map { it.address }.toSet())
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            eligibleDevices.forEach { device ->
                if (batteryDataUploader.upload(config, device)) {
                    preferences.edit().putLong(lastUploadKey(device.address), System.currentTimeMillis()).apply()
                }
            }
        }
    }

    private fun lastUploadKey(address: String) = "$KEY_LAST_SERVER_UPLOAD_PREFIX$address"

    private fun loadBackgroundTimeoutSeconds(): Int {
        val stored = when {
            preferences.contains(KEY_BACKGROUND_TIMEOUT_SECONDS) ->
                preferences.getInt(KEY_BACKGROUND_TIMEOUT_SECONDS, DEFAULT_BACKGROUND_TIMEOUT_SECONDS)
            preferences.contains(LEGACY_KEY_BACKGROUND_TIMEOUT_MINUTES) ->
                preferences.getInt(LEGACY_KEY_BACKGROUND_TIMEOUT_MINUTES, 5) * 60
            else -> DEFAULT_BACKGROUND_TIMEOUT_SECONDS
        }
        return stored.takeIf { it in BACKGROUND_TIMEOUT_OPTIONS_SECONDS }
            ?: DEFAULT_BACKGROUND_TIMEOUT_SECONDS
    }

    override fun onCleared() {
        appIsForegrounded = false
        mainHandler.removeCallbacks(uploadCheck)
        mainHandler.removeCallbacks(disconnectAfterBackgroundTimeout)
        repository.close()
    }

    sealed interface ServerConnectionCheckState {
        data object Idle : ServerConnectionCheckState
        data object Checking : ServerConnectionCheckState
        data class Complete(val result: ServerConnectionCheckResult) : ServerConnectionCheckState
    }

    sealed interface AppUpdateState {
        data object Idle : AppUpdateState
        data object Checking : AppUpdateState
        data class UpToDate(val latestVersion: String) : AppUpdateState
        data class Available(val release: UpdateRelease) : AppUpdateState
        data class Downloading(val release: UpdateRelease) : AppUpdateState
        data class ReadyToInstall(val release: UpdateRelease, val file: File) : AppUpdateState
        data class Error(val message: String) : AppUpdateState
    }

    companion object {
        val BACKGROUND_TIMEOUT_OPTIONS_SECONDS = listOf(5, 10, 30, 60, 300, 1_800, NEVER_DISCONNECT)
        const val NEVER_DISCONNECT = 0
        private const val DEFAULT_BACKGROUND_TIMEOUT_SECONDS = 10
        private const val APP_PREFERENCES = "app_preferences"
        private const val KEY_BACKGROUND_TIMEOUT_SECONDS = "background_disconnect_seconds"
        private const val LEGACY_KEY_BACKGROUND_TIMEOUT_MINUTES = "background_disconnect_minutes"
        private const val KEY_SERVER_UPLOAD_ENABLED = "server_upload_enabled"
        private const val KEY_SERVER_URL = "server_upload_url"
        private const val KEY_SERVER_API_KEY = "server_upload_api_key"
        private const val KEY_LAST_SERVER_UPLOAD_PREFIX = "server_last_upload_at_"
        private const val UPLOAD_INTERVAL_MS = 30_000L
        private const val UPLOAD_CHECK_INTERVAL_MS = 5_000L
    }
}
