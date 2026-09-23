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
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.protocol.JbdFrame
import com.jbd.bmsmonitor.protocol.JbdFrameAssembler
import com.jbd.bmsmonitor.protocol.JbdProtocol
import com.jbd.bmsmonitor.protocol.JbdReadSession
import com.jbd.bmsmonitor.storage.SavedBmsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JbdBleRepository(private val context: Context) {
    var onInitialReading: ((String) -> Unit)? = null

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

    fun hasCompleteReading(address: String): Boolean =
        connections[address]?.initialReadingComplete == true

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
        private var expectedRegister: Int? = null
        private var requestId = 0L
        @Volatile private var closed = false
        private val session = JbdReadSession(
            updateDevice = { transform -> updateDevice(address, transform) },
            onInitialReading = { onInitialReading?.invoke(address) },
            dispatch = { value, register, delay ->
                mainHandler.postAtTime({
                    if (!closed) send(value, register)
                }, this, android.os.SystemClock.uptimeMillis() + delay)
            },
        )
        val initialReadingComplete get() = session.initialReadingComplete

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
                    closed = true
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
            mainHandler.post { if (!closed) session.start() }
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
            val fragment = bytes.copyOf()
            mainHandler.post {
                if (!closed) assembler.append(fragment).forEach(::handleFrame)
            }
        }

        private fun handleFrame(frame: JbdFrame) {
            if (closed || frame.register != expectedRegister) return
            mainHandler.removeCallbacksAndMessages(this)
            expectedRegister = null
            session.handleFrame(frame)
        }

        private fun send(value: ByteArray, responseRegister: Int) {
            val characteristic = writeCharacteristic ?: return
            expectedRegister = responseRegister
            val currentRequestId = ++requestId
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
            mainHandler.postAtTime({
                if (!closed && requestId == currentRequestId && expectedRegister == responseRegister) {
                    expectedRegister = null
                    session.onRequestTimeout()
                }
            }, this, android.os.SystemClock.uptimeMillis() + REQUEST_TIMEOUT_MS)
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

    private object BluetoothDeviceTransport {
        const val LE = 2
    }

    companion object {
        private const val SCAN_DURATION_MS = 12_000L
        private const val REQUEST_TIMEOUT_MS = 2_500L
        private const val PERSIST_INTERVAL_MS = 5_000L
    }
}
