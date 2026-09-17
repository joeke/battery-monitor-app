package com.jbd.bmsmonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.jbd.bmsmonitor.ble.JbdBleRepository
import com.jbd.bmsmonitor.model.DiscoveredBms

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = JbdBleRepository(application)

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

    override fun onCleared() {
        repository.close()
    }
}
