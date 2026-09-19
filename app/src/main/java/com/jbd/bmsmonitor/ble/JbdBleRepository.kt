package com.jbd.bmsmonitor.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jbd.bmsmonitor.model.BmsDeviceState
import com.jbd.bmsmonitor.model.BmsSettings
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.protocol.JbdFrame
import com.jbd.bmsmonitor.protocol.JbdFrameAssembler
import com.jbd.bmsmonitor.protocol.JbdParser
import com.jbd.bmsmonitor.protocol.JbdProtocol
import com.jbd.bmsmonitor.storage.SavedBmsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JbdBleRepository(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val savedBmsStore = SavedBmsStore(context)
    private val found = linkedMapOf<String, DiscoveredBms>()
    private val connections = mutableMapOf<String, BmsConnection>()
    private var persistenceScheduled = false

    private val _discovered = MutableStateFlow<List<DiscoveredBms>>(emptyList())
    val discovered: StateFlow<List<DiscoveredBms>> = _discovered.asStateFlow()

    private val _devices = MutableStateFlow(
        savedBmsStore.load().associateBy { it.address },
    )
    val devices: StateFlow<Map<String, BmsDeviceState>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val bluetoothAvailable: Boolean get() = adapter != null
    val bluetoothEnabled: Boolean
        @SuppressLint("MissingPermission") get() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val advertisedName = result.scanRecord?.deviceName
            val name = advertisedName ?: result.device.name ?: "Unnamed BLE device"
            val services = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val looksLikeJbd = JbdProtocol.SERVICE_UUID in services ||
                listOf("jbd", "xiaoxiang", "overkill", "bms", "lt-").any {
                    name.contains(it, ignoreCase = true)
                }
            if (!looksLikeJbd) return
            found[address] = DiscoveredBms(address, name, result.rssi)
            _discovered.value = found.values.sortedByDescending { it.rssi }
            val old = _devices.value[address]
            if (old != null) updateDevice(address) { it.copy(name = name, rssi = result.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission() || !bluetoothEnabled) return
        found.clear()
        _discovered.value = emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        _scanning.value = true
        mainHandler.removeCallbacks(stopScanRunnable)
        mainHandler.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasScanPermission()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _scanning.value = false
        mainHandler.removeCallbacks(stopScanRunnable)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: DiscoveredBms) {
        if (!hasConnectPermission()) return
        stopScan()
        connections.remove(device.address)?.close()
        val initial = (_devices.value[device.address] ?: BmsDeviceState(
            address = device.address,
            name = device.name,
            rssi = device.rssi,
        )).copy(
            name = device.name,
            rssi = device.rssi,
            connectionStatus = ConnectionStatus.CONNECTING,
            error = null,
        )
        _devices.value = _devices.value + (device.address to initial)
        val connection = BmsConnection(initial)
        connections[device.address] = connection
        val gatt = adapter?.getRemoteDevice(device.address)
            ?.connectGatt(context, false, connection, BluetoothDeviceTransport.LE)
        if (gatt == null) {
            connections.remove(device.address)
            updateDevice(device.address) {
                it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, error = "Could not start BLE connection")
            }
        } else {
            connection.gatt = gatt
        }
    }

    fun disconnect(address: String) {
        connections.remove(address)?.close()
        updateDevice(address) { it.copy(connectionStatus = ConnectionStatus.DISCONNECTED) }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach(::disconnect)
    }

    fun reconnect(address: String) {
        val state = _devices.value[address] ?: return
        connect(DiscoveredBms(address, state.name, state.rssi))
    }

    fun close() {
        stopScan()
        connections.values.toList().forEach { it.close() }
        connections.clear()
        mainHandler.removeCallbacks(persistDevicesRunnable)
        persistenceScheduled = false
        savedBmsStore.save(_devices.value.values)
    }

    @Synchronized
    private fun updateDevice(address: String, transform: (BmsDeviceState) -> BmsDeviceState) {
        val current = _devices.value[address] ?: return
        _devices.value = _devices.value + (address to transform(current))
        if (!persistenceScheduled) {
            persistenceScheduled = true
            mainHandler.postDelayed(persistDevicesRunnable, PERSIST_INTERVAL_MS)
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }
    private val persistDevicesRunnable = Runnable {
        savedBmsStore.save(_devices.value.values)
        persistenceScheduled = false
    }

    private fun hasScanPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private inner class BmsConnection(initial: BmsDeviceState) : BluetoothGattCallback() {
        lateinit var gatt: BluetoothGatt
        private val address = initial.address
        private val assembler = JbdFrameAssembler()
        private var writeCharacteristic: BluetoothGattCharacteristic? = null
        private var phase = Phase.STARTING
        private var settingsIndex = 0
        private var defaultPasswordAttempted = false
        private var expectedRegister: Int? = null
        private var closed = false

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateDevice(address) {
                        it.copy(connectionStatus = ConnectionStatus.DISCOVERING, error = null)
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!closed) {
                        updateDevice(address) {
                            it.copy(
                                connectionStatus = ConnectionStatus.DISCONNECTED,
                                error = if (status == BluetoothGatt.GATT_SUCCESS) null else "BLE disconnected (status $status)",
                            )
                        }
                    }
                    mainHandler.removeCallbacksAndMessages(this)
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service: BluetoothGattService? = gatt.getService(JbdProtocol.SERVICE_UUID)
            val notify = service?.getCharacteristic(JbdProtocol.NOTIFY_UUID)
            writeCharacteristic = service?.getCharacteristic(JbdProtocol.WRITE_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || notify == null || writeCharacteristic == null) {
                fail("This device does not expose the JBD FF00/FF01/FF02 service")
                return
            }
            gatt.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(JbdProtocol.CCCD_UUID)
            if (descriptor == null) {
                fail("JBD notification descriptor is missing")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not enable JBD notifications (status $status)")
                return
            }
            updateDevice(address) {
                it.copy(
                    connectionStatus = ConnectionStatus.CONNECTED,
                    lastConnectedAtMillis = System.currentTimeMillis(),
                )
            }
            phase = Phase.INITIAL_BASIC
            sendRead(JbdProtocol.BASIC_INFO)
        }

        @Deprecated("Kept for Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onBytes(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBytes(value)
        }

        private fun onBytes(bytes: ByteArray) {
            assembler.append(bytes).forEach(::handleFrame)
        }

        private fun handleFrame(frame: JbdFrame) {
            if (frame.register != expectedRegister) return
            mainHandler.removeCallbacksAndMessages(this)
            expectedRegister = null

            if (frame.status != 0) {
                handleRegisterError(frame.register, frame.status)
                return
            }

            when (phase) {
                Phase.INITIAL_BASIC, Phase.POLL_BASIC -> {
                    updateDevice(address) { current ->
                        JbdParser.parseBasicInfo(frame.payload, current.telemetry)
                            ?.let { current.copy(telemetry = it, error = null) }
                            ?: current.copy(error = "Invalid basic-info frame")
                    }
                    phase = if (phase == Phase.INITIAL_BASIC) Phase.INITIAL_CELLS else Phase.POLL_CELLS
                    sendRead(JbdProtocol.CELL_INFO)
                }
                Phase.INITIAL_CELLS, Phase.POLL_CELLS -> {
                    updateDevice(address) { current ->
                        JbdParser.parseCells(frame.payload, current.telemetry)
                            ?.let { current.copy(telemetry = it, error = null) }
                            ?: current.copy(error = "Invalid cell-voltage frame")
                    }
                    if (phase == Phase.INITIAL_CELLS) {
                        phase = Phase.HARDWARE
                        sendRead(JbdProtocol.HARDWARE_VERSION)
                    } else {
                        scheduleNextPoll()
                    }
                }
                Phase.HARDWARE -> {
                    val version = JbdParser.parseHardwareVersion(frame.payload)
                    updateDevice(address) { it.copy(settings = it.settings.copy(hardwareVersion = version)) }
                    phase = Phase.ENTER_FACTORY
                    send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY)
                }
                Phase.ENTER_FACTORY -> {
                    phase = Phase.SETTINGS
                    settingsIndex = 0
                    readNextSetting()
                }
                Phase.AUTHENTICATE -> {
                    phase = Phase.ENTER_FACTORY
                    send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY)
                }
                Phase.SETTINGS -> {
                    updateDevice(address) {
                        it.copy(settings = JbdParser.applySetting(frame.register, frame.payload, it.settings))
                    }
                    settingsIndex++
                    readNextSetting()
                }
                Phase.EXIT_FACTORY -> {
                    updateDevice(address) {
                        it.copy(settings = it.settings.copy(loaded = true, unavailableReason = null))
                    }
                    scheduleNextPoll()
                }
                Phase.STARTING -> Unit
            }
        }

        private fun handleRegisterError(register: Int, status: Int) {
            when (phase) {
                Phase.ENTER_FACTORY -> {
                    authenticateWithDefaultPasswordOrFail(
                        "Settings access denied by BMS (status $status)",
                    )
                }
                Phase.AUTHENTICATE -> {
                    settingsUnavailable("Default BMS password was rejected (status $status)")
                }
                Phase.SETTINGS -> {
                    settingsIndex++
                    readNextSetting()
                }
                Phase.HARDWARE -> {
                    phase = Phase.ENTER_FACTORY
                    send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY)
                }
                else -> {
                    updateDevice(address) { it.copy(error = "BMS rejected register 0x${register.toString(16)}") }
                    scheduleNextPoll()
                }
            }
        }

        private fun readNextSetting() {
            if (settingsIndex >= JbdProtocol.SETTINGS_REGISTERS.size) {
                phase = Phase.EXIT_FACTORY
                send(JbdProtocol.exitFactoryMode(), JbdProtocol.EXIT_FACTORY)
            } else {
                sendRead(JbdProtocol.SETTINGS_REGISTERS[settingsIndex])
            }
        }

        private fun authenticateWithDefaultPasswordOrFail(failureReason: String) {
            if (defaultPasswordAttempted) {
                settingsUnavailable(failureReason)
                return
            }
            defaultPasswordAttempted = true
            phase = Phase.AUTHENTICATE
            send(JbdProtocol.usePassword(DEFAULT_CONFIGURATION_PASSWORD), JbdProtocol.USE_PASSWORD)
        }

        private fun settingsUnavailable(reason: String) {
            updateDevice(address) {
                it.copy(settings = it.settings.copy(loaded = false, unavailableReason = reason))
            }
            scheduleNextPoll()
        }

        private fun scheduleNextPoll() {
            phase = Phase.POLL_BASIC
            mainHandler.postAtTime({
                if (!closed) sendRead(JbdProtocol.BASIC_INFO)
            }, this, android.os.SystemClock.uptimeMillis() + POLL_INTERVAL_MS)
        }

        private fun sendRead(register: Int) = send(JbdProtocol.read(register), register)

        private fun send(value: ByteArray, responseRegister: Int) {
            val characteristic = writeCharacteristic ?: return
            expectedRegister = responseRegister
            val noResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            val writeType = if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!initiated) {
                fail("Android could not send a BLE request")
                return
            }
            mainHandler.postAtTime({ onRequestTimeout(responseRegister) }, this, android.os.SystemClock.uptimeMillis() + REQUEST_TIMEOUT_MS)
        }

        private fun onRequestTimeout(register: Int) {
            if (expectedRegister != register || closed) return
            expectedRegister = null
            when (phase) {
                Phase.SETTINGS -> {
                    settingsIndex++
                    readNextSetting()
                }
                Phase.HARDWARE -> {
                    phase = Phase.ENTER_FACTORY
                    send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY)
                }
                Phase.ENTER_FACTORY -> {
                    authenticateWithDefaultPasswordOrFail("Settings are not supported by this BMS")
                }
                Phase.AUTHENTICATE -> {
                    settingsUnavailable("The BMS did not accept the default configuration password")
                }
                Phase.EXIT_FACTORY -> {
                    settingsUnavailable("The BMS did not confirm that it exited configuration mode")
                }
                else -> {
                    updateDevice(address) { it.copy(error = "BMS did not respond") }
                    scheduleNextPoll()
                }
            }
        }

        private fun fail(message: String) {
            updateDevice(address) {
                it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, error = message)
            }
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            mainHandler.removeCallbacksAndMessages(this)
            if (::gatt.isInitialized) {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    private enum class Phase {
        STARTING,
        INITIAL_BASIC,
        INITIAL_CELLS,
        HARDWARE,
        ENTER_FACTORY,
        AUTHENTICATE,
        SETTINGS,
        EXIT_FACTORY,
        POLL_BASIC,
        POLL_CELLS,
    }

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    companion object {
        private const val SCAN_DURATION_MS = 12_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val REQUEST_TIMEOUT_MS = 2_500L
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val DEFAULT_CONFIGURATION_PASSWORD = "123456"
    }
}
