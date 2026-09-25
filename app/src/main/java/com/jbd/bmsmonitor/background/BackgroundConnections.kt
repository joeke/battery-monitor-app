package com.jbd.bmsmonitor.background

import com.jbd.bmsmonitor.model.DiscoveredBms

/** Accessed only on the main thread. Foregrounding synchronously releases worker-owned connections. */
class BackgroundConnections(
    private val hasActiveConnections: () -> Boolean,
    private val connectDevice: (DiscoveredBms) -> Unit,
    private val disconnectDevice: (String) -> Unit,
) {
    var foreground = false
        private set
    private var current: Session? = null

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) stop()
    }

    fun stop() {
        current?.close()
        current = null
    }

    fun begin(): Session? {
        if (foreground || current?.closed == false) return null
        // Respect any foreground connection still waiting for its background timeout.
        if (hasActiveConnections()) return null
        return Session(connectDevice, disconnectDevice).also { current = it }
    }

    class Session(
        private val connectDevice: (DiscoveredBms) -> Unit,
        private val disconnectDevice: (String) -> Unit,
    ) {
        var closed = false
            private set
        private var address: String? = null

        fun connect(device: DiscoveredBms) {
            check(!closed)
            address = device.address
            connectDevice(device)
        }

        fun disconnect() {
            val connectedAddress = address
            address = null
            connectedAddress?.let(disconnectDevice)
        }

        fun close() {
            if (closed) return
            closed = true
            disconnect()
        }
    }
}

