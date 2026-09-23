package com.jbd.bmsmonitor.protocol

import com.jbd.bmsmonitor.model.BmsSettings
import com.jbd.bmsmonitor.model.BmsTelemetry
import java.nio.charset.StandardCharsets
import java.util.UUID

object JbdProtocol {
    val SERVICE_UUID: UUID = uuid16(0xFF00)
    val NOTIFY_UUID: UUID = uuid16(0xFF01)
    val WRITE_UUID: UUID = uuid16(0xFF02)
    val CCCD_UUID: UUID = uuid16(0x2902)

    const val BASIC_INFO = 0x03
    const val CELL_INFO = 0x04
    const val HARDWARE_VERSION = 0x05
    const val ENTER_FACTORY = 0x00
    const val EXIT_FACTORY = 0x01
    const val USE_PASSWORD = 0x06

    val SETTINGS_REGISTERS = listOf(
        0x10, // design capacity
        0x11, // cycle capacity
        0x12, // 100% cell voltage
        0x13, // 0% cell voltage
        0x18, // charge over-temperature
        0x1A, // charge under-temperature
        0x1C, // discharge over-temperature
        0x1E, // discharge under-temperature
        0x20, // pack overvoltage
        0x22, // pack undervoltage
        0x24, // cell overvoltage
        0x26, // cell undervoltage
        0x2A, // balance start voltage
        0x2B, // balance start delta
        0x2F, // configured cell count
    )

    fun read(register: Int): ByteArray = command(0xA5, register, byteArrayOf())

    /** Changes only the volatile access mode. It never commits EEPROM settings. */
    fun enterFactoryMode(): ByteArray = command(0x5A, ENTER_FACTORY, byteArrayOf(0x56, 0x78))

    /** Authenticates for the current connection without changing the stored password. */
    fun usePassword(password: String): ByteArray {
        require(password.length == 6 && password.all { it.code in 0x20..0x7E })
        val passwordBytes = password.toByteArray(StandardCharsets.US_ASCII)
        return command(0x5A, USE_PASSWORD, byteArrayOf(passwordBytes.size.toByte()) + passwordBytes)
    }

    /** 00 00 exits without saving or resetting error counters. */
    fun exitFactoryMode(): ByteArray = command(0x5A, EXIT_FACTORY, byteArrayOf(0x00, 0x00))

    private fun command(mode: Int, register: Int, payload: ByteArray): ByteArray {
        val result = ByteArray(payload.size + 7)
        result[0] = 0xDD.toByte()
        result[1] = mode.toByte()
        result[2] = register.toByte()
        result[3] = payload.size.toByte()
        payload.copyInto(result, destinationOffset = 4)
        val checksum = checksum(result, 2, payload.size + 2)
        result[result.lastIndex - 2] = (checksum ushr 8).toByte()
        result[result.lastIndex - 1] = checksum.toByte()
        result[result.lastIndex] = 0x77
        return result
    }

    fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        for (index in offset until offset + length) sum += bytes[index].toInt() and 0xFF
        return (-sum) and 0xFFFF
    }

    private fun uuid16(value: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
}

data class JbdFrame(
    val register: Int,
    val status: Int,
    val payload: ByteArray,
)

/** Reassembles JBD frames split over several ATT notifications. */
class JbdFrameAssembler {
    private val buffer = mutableListOf<Byte>()

    fun append(fragment: ByteArray): List<JbdFrame> {
        buffer.addAll(fragment.toList())
        val frames = mutableListOf<JbdFrame>()

        while (true) {
            while (buffer.isNotEmpty() && buffer.first().toInt() and 0xFF != 0xDD) buffer.removeAt(0)
            if (buffer.size < 4) break
            val payloadLength = buffer[3].toInt() and 0xFF
            val frameLength = payloadLength + 7
            if (frameLength > 263) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < frameLength) break
            val raw = ByteArray(frameLength) { buffer[it] }
            repeat(frameLength) { buffer.removeAt(0) }
            if (raw.last().toInt() and 0xFF != 0x77) continue
            val receivedChecksum = ((raw[frameLength - 3].toInt() and 0xFF) shl 8) or
                (raw[frameLength - 2].toInt() and 0xFF)
            val checksumValid = JbdProtocol.checksum(raw, 2, payloadLength + 2) == receivedChecksum
            // Some JBD firmware sends a nonstandard checksum for empty command acknowledgements.
            // Never relax validation for telemetry or EEPROM data.
            val register = raw[1].toInt() and 0xFF
            val controlAcknowledgement = payloadLength == 0 &&
                register in setOf(JbdProtocol.ENTER_FACTORY, JbdProtocol.EXIT_FACTORY, JbdProtocol.USE_PASSWORD)
            if (!checksumValid && !controlAcknowledgement) continue
            frames += JbdFrame(
                register = register,
                status = raw[2].toInt() and 0xFF,
                payload = raw.copyOfRange(4, 4 + payloadLength),
            )
        }
        return frames
    }
}

object JbdParser {
    fun parseBasicInfo(data: ByteArray, previous: BmsTelemetry = BmsTelemetry()): BmsTelemetry? {
        if (data.size < 23) return null
        val cellCount = u8(data, 21)
        val ntcCount = u8(data, 22).coerceAtMost((data.size - 23) / 2)
        val balanceBits = u16(data, 12).toLong() or (u16(data, 14).toLong() shl 16)
        return previous.copy(
            packVoltageV = u16(data, 0) / 100.0,
            currentA = s16(data, 2) / 100.0,
            remainingCapacityAh = u16(data, 4) / 100.0,
            fullCapacityAh = u16(data, 6) / 100.0,
            cycleCount = u16(data, 8),
            balancingCells = (0 until cellCount).filterTo(mutableSetOf()) {
                (balanceBits and (1L shl it)) != 0L
            },
            protectionFlags = u16(data, 16),
            stateOfChargePercent = u8(data, 19),
            chargeMosfetOn = u8(data, 20) and 0x01 != 0,
            dischargeMosfetOn = u8(data, 20) and 0x02 != 0,
            cellCount = cellCount,
            temperaturesC = List(ntcCount) { index -> u16(data, 23 + index * 2) / 10.0 - 273.15 },
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun parseCells(data: ByteArray, previous: BmsTelemetry): BmsTelemetry? {
        if (data.isEmpty() || data.size % 2 != 0) return null
        val count = (data.size / 2).coerceAtMost(32)
        return previous.copy(
            cellVoltagesV = List(count) { u16(data, it * 2) / 1000.0 },
            cellCount = if (previous.cellCount == 0) count else previous.cellCount,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    fun parseHardwareVersion(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val bytes = if (u8(data, 0) == data.size - 1) data.copyOfRange(1, data.size) else data
        return String(bytes, StandardCharsets.US_ASCII).trim('\u0000', ' ').ifBlank { null }
    }

    fun applySetting(register: Int, data: ByteArray, old: BmsSettings): BmsSettings {
        if (data.size < 2) return old
        val value = u16(data, 0)
        return when (register) {
            0x10 -> old.copy(designCapacityAh = value / 100.0)
            0x11 -> old.copy(cycleCapacityAh = value / 100.0)
            0x12 -> old.copy(fullCellVoltageV = value / 1000.0)
            0x13 -> old.copy(emptyCellVoltageV = value / 1000.0)
            0x18 -> old.copy(chargeOverTemperatureC = kelvinToC(value))
            0x1A -> old.copy(chargeUnderTemperatureC = kelvinToC(value))
            0x1C -> old.copy(dischargeOverTemperatureC = kelvinToC(value))
            0x1E -> old.copy(dischargeUnderTemperatureC = kelvinToC(value))
            0x20 -> old.copy(packOvervoltageV = value / 100.0)
            0x22 -> old.copy(packUndervoltageV = value / 100.0)
            0x24 -> old.copy(cellOvervoltageV = value / 1000.0)
            0x26 -> old.copy(cellUndervoltageV = value / 1000.0)
            0x2A -> old.copy(balanceStartVoltageV = value / 1000.0)
            0x2B -> old.copy(balanceStartDeltaV = value / 1000.0)
            0x2F -> old.copy(configuredCellCount = value)
            else -> old
        }
    }

    private fun kelvinToC(value: Int) = value / 10.0 - 273.15
    private fun u8(data: ByteArray, offset: Int) = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int) = (u8(data, offset) shl 8) or u8(data, offset + 1)
    private fun s16(data: ByteArray, offset: Int) = u16(data, offset).toShort().toInt()
}
