package com.jbd.bmsmonitor.protocol

import com.jbd.bmsmonitor.model.BmsDeviceState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdReadSessionTest {
    @Test
    fun `cold connection enters configuration mode and reads settings before polling`() {
        val device = Device()
        device.startSettings()
        assertArrayEquals(hex("DD 5A 00 02 56 78 FF 30 77"), device.command)
        device.reply()
        assertEquals(300L, device.delay)
        device.readSettings()
        assertArrayEquals(hex("DD 5A 01 02 00 00 FF FD 77"), device.command)
        device.reply()
        assertTrue(device.state.settings.loaded)
        assertNull(device.state.settings.unavailableReason)
        assertEquals(100.0, device.state.settings.designCapacityAh!!, 0.001)
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
        assertEquals(2_000L, device.delay)
        assertEquals(1, device.initialReadings)
    }

    @Test
    fun `initial telemetry timeouts cannot skip settings discovery`() {
        val device = Device()
        device.session.start()
        repeat(2) { device.session.onRequestTimeout() }
        assertEquals(JbdProtocol.CELL_INFO, device.register)
        repeat(2) { device.session.onRequestTimeout() }
        assertEquals(JbdProtocol.HARDWARE_VERSION, device.register)
        repeat(2) { device.session.onRequestTimeout() }
        assertEquals(JbdProtocol.ENTER_FACTORY, device.register)
        device.reply()
        device.readSettings()
        device.reply()
        assertTrue(device.state.settings.loaded)
        assertEquals(0, device.initialReadings)
        device.reply(payload = basicInfo)
        device.reply(payload = hex("0C E4"))
        assertEquals(1, device.initialReadings)
    }

    @Test
    fun `rejected initial telemetry still proceeds to settings`() {
        val device = Device()
        device.session.start()
        device.reply(status = 0x80)
        assertEquals(JbdProtocol.CELL_INFO, device.register)
        device.reply(status = 0x80)
        assertEquals(JbdProtocol.HARDWARE_VERSION, device.register)
        device.reply(status = 0x80)
        assertEquals(JbdProtocol.ENTER_FACTORY, device.register)
    }

    @Test
    fun `lost factory acknowledgement is retried before password fallback`() {
        val device = Device()
        device.startSettings()
        device.session.onRequestTimeout()
        assertEquals(JbdProtocol.ENTER_FACTORY, device.register)
        device.reply()
        assertEquals(0x10, device.register)
        device.session.onRequestTimeout()
        assertEquals(0x10, device.register)
        device.reply(payload = hex("27 10"))
        assertEquals(0x11, device.register)
    }

    @Test
    fun `password protected cold connection authenticates then reenters factory mode`() {
        val device = Device()
        device.startSettings()
        device.reply(status = 0x80)
        assertEquals(JbdProtocol.USE_PASSWORD, device.register)
        device.reply()
        assertEquals(JbdProtocol.ENTER_FACTORY, device.register)
        device.reply()
        device.readSettings()
        device.reply()
        assertTrue(device.state.settings.loaded)
    }

    @Test
    fun `rejected exit ends loading with an explanation`() {
        val device = Device()
        device.startSettings()
        device.reply()
        device.readSettings()
        device.reply(status = 0x80)
        assertTrue(device.state.settings.loaded)
        assertTrue(device.state.settings.unavailableReason!!.contains("exit"))
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
    }

    @Test
    fun `silent battery ends settings loading and resumes polling`() {
        val device = Device()
        device.session.start()
        repeat(30) {
            if (device.state.settings.unavailableReason == null) device.session.onRequestTimeout()
        }
        assertFalse(device.state.settings.loaded)
        assertNotNull(device.state.settings.unavailableReason)
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
    }

    @Test
    fun `denied settings after successful entry trigger one authentication attempt`() {
        val device = Device()
        device.startSettings()
        device.reply()
        repeat(JbdProtocol.SETTINGS_REGISTERS.size) {
            device.reply(status = 0x80)
            device.reply(status = 0x80)
        }
        assertEquals(JbdProtocol.EXIT_FACTORY, device.register)
        device.reply()
        assertEquals(JbdProtocol.USE_PASSWORD, device.register)
        device.reply()
        device.reply()
        device.readSettings()
        device.reply()
        assertTrue(device.state.settings.loaded)
    }

    @Test
    fun `missing setting and lost exit acknowledgement preserve partial results`() {
        val device = Device()
        device.startSettings()
        device.reply()
        repeat(2) { device.session.onRequestTimeout() }
        JbdProtocol.SETTINGS_REGISTERS.drop(1).forEach {
            assertEquals(it, device.register)
            device.reply(payload = hex("27 10"))
        }
        repeat(2) { device.session.onRequestTimeout() }
        assertTrue(device.state.settings.loaded)
        assertNull(device.state.settings.designCapacityAh)
        assertTrue(device.state.settings.unavailableReason!!.contains("14 of 15"))
        assertTrue(device.state.settings.unavailableReason!!.contains("exit"))
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
    }

    @Test
    fun `permanent register rejection terminates without repeating authentication`() {
        val device = Device()
        device.startSettings()
        device.reply()
        repeat(2) { attempt ->
            repeat(JbdProtocol.SETTINGS_REGISTERS.size * 2) { device.reply(status = 0x80) }
            device.reply() // Exit without saving.
            if (attempt == 0) {
                assertEquals(JbdProtocol.USE_PASSWORD, device.register)
                device.reply()
                device.reply()
            }
        }
        assertFalse(device.state.settings.loaded)
        assertNotNull(device.state.settings.unavailableReason)
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
    }

    @Test
    fun `background reading requires fresh telemetry and never enters settings mode`() {
        val device = Device(readSettings = false)
        device.state = device.state.copy(settings = device.state.settings.copy(loaded = true))
        device.session.start()
        assertTrue(device.state.settings.loaded)
        assertFalse(device.session.initialReadingComplete)
        device.reply(payload = basicInfo)
        assertFalse(device.session.initialReadingComplete)
        device.reply(payload = hex("0C E4"))
        assertTrue(device.session.initialReadingComplete)
        assertEquals(1, device.initialReadings)
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
        assertEquals(2_000L, device.delay)
    }

    @Test
    fun `background timeouts retry telemetry without configuration commands`() {
        val device = Device(readSettings = false)
        device.session.start()
        repeat(20) {
            assertTrue(device.register == JbdProtocol.BASIC_INFO || device.register == JbdProtocol.CELL_INFO)
            device.session.onRequestTimeout()
        }
        assertFalse(device.session.initialReadingComplete)
        assertEquals(0, device.initialReadings)
    }

    @Test
    fun `background malformed and rejected readings are not complete`() {
        val device = Device(readSettings = false)
        device.session.start()
        device.reply(status = 0x80)
        device.reply(payload = hex("0C E4"))
        assertFalse(device.session.initialReadingComplete)
        assertEquals(JbdProtocol.BASIC_INFO, device.register)
        device.reply(payload = basicInfo)
        device.reply(payload = byteArrayOf())
        assertFalse(device.session.initialReadingComplete)
        device.reply(payload = basicInfo)
        device.reply(payload = hex("0C E4"))
        assertTrue(device.session.initialReadingComplete)
    }

    private class Device(readSettings: Boolean = true) {
        var state = BmsDeviceState("test", "Battery", 0)
        var command = byteArrayOf()
        var register = -1
        var delay = 0L
        var initialReadings = 0
        val session = JbdReadSession(
            readSettings = readSettings,
            updateDevice = { state = it(state) },
            onInitialReading = { initialReadings++ },
            dispatch = { bytes, responseRegister, delayMillis ->
                command = bytes
                register = responseRegister
                delay = delayMillis
            },
        )

        fun reply(status: Int = 0, payload: ByteArray = byteArrayOf()) =
            session.handleFrame(JbdFrame(register, status, payload))

        fun startSettings() {
            session.start()
            reply(payload = basicInfo)
            reply(payload = hex("0C E4"))
            reply(payload = "JBD".toByteArray())
            assertEquals(JbdProtocol.ENTER_FACTORY, register)
        }

        fun readSettings() {
            JbdProtocol.SETTINGS_REGISTERS.forEach {
                assertEquals(it, register)
                reply(payload = hex("27 10"))
            }
        }
    }

    companion object {
        private val basicInfo = hex("06 17 00 00 01 F3 01 F4 00 00 2C 7C 00 00 00 00 00 00 80 64 03 01 00")
        private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }
}
