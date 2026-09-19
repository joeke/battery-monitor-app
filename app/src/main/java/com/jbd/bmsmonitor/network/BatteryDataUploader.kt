package com.jbd.bmsmonitor.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.jbd.bmsmonitor.model.BmsDeviceState
import com.jbd.bmsmonitor.model.ServerUploadConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

sealed interface ServerConnectionCheckResult {
    data object Success : ServerConnectionCheckResult
    data object InvalidUrl : ServerConnectionCheckResult
    data object InvalidApiKey : ServerConnectionCheckResult
    data object NotFound : ServerConnectionCheckResult
    data object RateLimited : ServerConnectionCheckResult
    data object NetworkError : ServerConnectionCheckResult
    data class UnexpectedResponse(val statusCode: Int) : ServerConnectionCheckResult
}

class BatteryDataUploader(context: Context) {
    private val senderId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    )
    private val senderName = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
        .take(255)

    fun upload(config: ServerUploadConfig, device: BmsDeviceState): Boolean {
        val endpoint = runCatching { URI(config.serverUrl).toURL() }.getOrNull() ?: return false
        if (endpoint.protocol != "https") return false

        val connection = (endpoint.openConnection() as? HttpURLConnection) ?: return false
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("X-Api-Key", config.apiKey)

            connection.outputStream.use { output ->
                output.write(requestBody(device).toString().toByteArray(Charsets.UTF_8))
            }

            val succeeded = connection.responseCode in 200..299
            (if (succeeded) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            succeeded
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun checkConnection(serverUrl: String, apiKey: String): ServerConnectionCheckResult {
        val endpoint = connectionCheckEndpoint(serverUrl)
            ?: return ServerConnectionCheckResult.InvalidUrl
        val connection = (runCatching { endpoint.openConnection() }.getOrNull() as? HttpURLConnection)
            ?: return ServerConnectionCheckResult.NetworkError

        return try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Api-Key", apiKey.trim())

            when (val statusCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> ServerConnectionCheckResult.Success
                HttpURLConnection.HTTP_UNAUTHORIZED -> ServerConnectionCheckResult.InvalidApiKey
                HttpURLConnection.HTTP_NOT_FOUND -> ServerConnectionCheckResult.NotFound
                429 -> ServerConnectionCheckResult.RateLimited
                else -> ServerConnectionCheckResult.UnexpectedResponse(statusCode)
            }
        } catch (_: Exception) {
            ServerConnectionCheckResult.NetworkError
        } finally {
            connection.disconnect()
        }
    }

    private fun connectionCheckEndpoint(serverUrl: String) = runCatching {
        val trimmedUrl = serverUrl.trim()
        val uri = URI(trimmedUrl)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
        val withoutFragment = trimmedUrl.substringBefore('#')
        val separator = when {
            uri.rawQuery == null -> "?"
            withoutFragment.endsWith('?') || withoutFragment.endsWith('&') -> ""
            else -> "&"
        }
        URI("$withoutFragment${separator}per_page=1").toURL()
    }.getOrNull()

    internal fun requestBody(device: BmsDeviceState): JSONObject {
        val telemetry = device.telemetry
        return JSONObject().apply {
            put("bms_id", device.address)
            put("bms_name", device.name)
            senderId?.takeIf { it.isNotBlank() }?.let { put("sender_id", it.take(100)) }
            senderName.takeIf { it.isNotBlank() }?.let { put("sender_name", it) }
            put("voltage_v", telemetry.packVoltageV)
            put("current_a", telemetry.currentA)
            put("state_of_charge_pct", telemetry.stateOfChargePercent)
            put("state", batteryState(telemetry.currentA))
            put("charge_enabled", telemetry.chargeMosfetOn)
            put("discharge_enabled", telemetry.dischargeMosfetOn)
            put("remaining_capacity_ah", telemetry.remainingCapacityAh)
            put("full_capacity_ah", telemetry.fullCapacityAh)
            put("cycle_count", telemetry.cycleCount)
            put("cells", JSONArray().apply {
                telemetry.cellVoltagesV.forEachIndexed { index, voltage ->
                    put(JSONObject().apply {
                        put("number", index + 1)
                        put("voltage_v", voltage)
                        put("balancing", index + 1 in telemetry.balancingCells)
                    })
                }
            })
            put("temperatures_c", JSONArray(telemetry.temperaturesC))
            put("faults", faults(telemetry.protectionFlags))
            put("metadata", JSONObject().apply {
                put("rssi", device.rssi)
                put("power_w", telemetry.powerW)
                put("protection_flags", telemetry.protectionFlags)
                device.settings.hardwareVersion?.let { put("hardware_version", it) }
            })
            put("measured_at", Instant.ofEpochMilli(telemetry.updatedAtMillis).toString())
        }
    }

    private fun batteryState(currentA: Double): String = when {
        currentA > IDLE_CURRENT_THRESHOLD_A -> "charging"
        currentA < -IDLE_CURRENT_THRESHOLD_A -> "discharging"
        else -> "idle"
    }

    private fun faults(flags: Int) = JSONArray().apply {
        PROTECTION_CODES.forEachIndexed { bit, code ->
            if (flags and (1 shl bit) != 0) {
                put(JSONObject().put("code", code).put("cell", JSONObject.NULL))
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val IDLE_CURRENT_THRESHOLD_A = 0.05

        val PROTECTION_CODES = listOf(
            "cell_overvoltage",
            "cell_undervoltage",
            "pack_overvoltage",
            "pack_undervoltage",
            "charge_overtemperature",
            "charge_undertemperature",
            "discharge_overtemperature",
            "discharge_undertemperature",
            "charge_overcurrent",
            "discharge_overcurrent",
            "short_circuit",
            "front_end_ic_error",
            "software_mos_lock",
        )
    }
}
