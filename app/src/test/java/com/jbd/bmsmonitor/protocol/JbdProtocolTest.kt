package com.jbd.bmsmonitor.protocol

import com.jbd.bmsmonitor.model.BmsSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdProtocolTest {
    @Test
    fun `basic info read command has documented bytes`() {
        assertArrayEquals(
            hex("DD A5 03 00 FF FD 77"),
            JbdProtocol.read(JbdProtocol.BASIC_INFO),
        )
    }

    @Test
    fun `fragmented basic info frame is reassembled and parsed`() {
        val raw = hex(
            "DD 03 00 1D 06 17 00 00 01 F3 01 F4 00 00 2C 7C 00 00 " +
                "00 00 00 00 80 64 03 04 03 0B 8D 0B 8C 0B 88 FA 85 77",
        )
        val assembler = JbdFrameAssembler()

        assertTrue(assembler.append(raw.copyOfRange(0, 20)).isEmpty())
        val frames = assembler.append(raw.copyOfRange(20, raw.size))

        assertEquals(1, frames.size)
        val telemetry = JbdParser.parseBasicInfo(frames.single().payload)!!
        assertEquals(15.59, telemetry.packVoltageV, 0.001)
        assertEquals(4.99, telemetry.remainingCapacityAh, 0.001)
        assertEquals(100, telemetry.stateOfChargePercent)
        assertEquals(4, telemetry.cellCount)
        assertTrue(telemetry.chargeMosfetOn)
        assertTrue(telemetry.dischargeMosfetOn)
        assertEquals(3, telemetry.temperaturesC.size)
        assertEquals(22.55, telemetry.temperaturesC.first(), 0.001)
    }

    @Test
    fun `signed discharge current and cell delta are preserved`() {
        val data = hex("06 17 FF 9C 01 F3 01 F4 00 00 2C 7C 00 00 00 00 00 00 80 32 02 04 00")
        val basic = JbdParser.parseBasicInfo(data)!!
        val withCells = JbdParser.parseCells(hex("0C E4 0C E9 0C DF 0C E1"), basic)!!

        assertEquals(-1.0, withCells.currentA, 0.001)
        assertEquals(-15.59, withCells.powerW, 0.001)
        assertFalse(withCells.chargeMosfetOn)
        assertTrue(withCells.dischargeMosfetOn)
        assertEquals(0.010, withCells.cellDeltaV, 0.0001)
    }

    @Test
    fun `settings use their documented scale`() {
        val result = JbdParser.applySetting(0x24, hex("10 68"), BmsSettings())
        assertEquals(4.2, result.cellOvervoltageV!!, 0.0001)
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
