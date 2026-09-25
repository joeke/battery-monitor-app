package com.jbd.bmsmonitor.background

import com.jbd.bmsmonitor.model.DiscoveredBms
import org.junit.Assert.*
import org.junit.Test

class BackgroundConnectionsTest {
    private val connected = mutableListOf<String>()
    private val disconnected = mutableListOf<String>()
    private var busy = false
    private val connections = BackgroundConnections(
        hasActiveConnections = { busy },
        connectDevice = { connected += it.address },
        disconnectDevice = { disconnected += it },
    )
    private val device = DiscoveredBms("AA:BB:CC:DD:EE:FF", "Battery", 0)

    @Test
    fun `foreground and existing UI connections prevent a background session`() {
        connections.setForeground(true)
        assertNull(connections.begin())
        connections.setForeground(false)
        busy = true
        assertNull(connections.begin())
        busy = false
        assertNotNull(connections.begin())
    }

    @Test
    fun `foreground handoff releases BLE and later cleanup cannot disconnect a new connection`() {
        val first = connections.begin()!!
        first.connect(device)
        connections.setForeground(true)
        assertTrue(first.closed)
        assertEquals(listOf(device.address), disconnected)
        connections.setForeground(false)
        val second = connections.begin()!!
        second.connect(device)
        first.disconnect()
        first.close()
        assertEquals(1, disconnected.size)
        second.close()
        assertEquals(2, disconnected.size)
    }

    @Test
    fun `only one worker session owns BLE and cancellation cleanup is idempotent`() {
        val session = connections.begin()!!
        session.connect(device)
        assertNull(connections.begin())
        connections.stop()
        session.close()
        assertEquals(listOf(device.address), connected)
        assertEquals(listOf(device.address), disconnected)
        assertNotNull(connections.begin())
    }

    @Test
    fun `each BMS can be released before upload and the next BMS attempted`() {
        val session = connections.begin()!!
        session.connect(device)
        session.disconnect()
        session.connect(device.copy(address = "11:22:33:44:55:66"))
        session.close()
        assertEquals(connected, disconnected)
        assertEquals(2, disconnected.size)
    }
}
