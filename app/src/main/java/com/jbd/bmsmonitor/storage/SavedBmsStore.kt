package com.jbd.bmsmonitor.storage

import android.content.Context
import com.jbd.bmsmonitor.model.BmsDeviceState
import com.jbd.bmsmonitor.model.BmsSettings
import com.jbd.bmsmonitor.model.BmsTelemetry
import com.jbd.bmsmonitor.model.ConnectionStatus
import org.json.JSONArray
import org.json.JSONObject

/** Stores only device identity and the last successfully parsed read-only snapshot. */
class SavedBmsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<BmsDeviceState> {
        val raw = preferences.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching {
            val devices = JSONObject(raw).optJSONArray("devices") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until devices.length()) {
                    decodeDevice(devices.optJSONObject(index) ?: continue)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(devices: Collection<BmsDeviceState>) {
        val array = JSONArray()
        devices.sortedByDescending { it.lastConnectedAtMillis }.forEach { array.put(encodeDevice(it)) }
        val root = JSONObject().put("version", VERSION).put("devices", array)
        preferences.edit().putString(KEY_DEVICES, root.toString()).apply()
    }

    private fun encodeDevice(device: BmsDeviceState) = JSONObject().apply {
        put("address", device.address)
        put("name", device.name)
        put("rssi", device.rssi)
        put("lastConnectedAt", device.lastConnectedAtMillis)
        put("telemetry", encodeTelemetry(device.telemetry))
        put("settings", encodeSettings(device.settings))
    }

    private fun decodeDevice(json: JSONObject): BmsDeviceState? {
        val address = json.optString("address").takeIf { it.isNotBlank() } ?: return null
        return BmsDeviceState(
            address = address,
            name = json.optString("name", "Saved JBD BMS"),
            rssi = json.optInt("rssi", 0),
            connectionStatus = ConnectionStatus.DISCONNECTED,
            telemetry = decodeTelemetry(json.optJSONObject("telemetry")),
            settings = decodeSettings(json.optJSONObject("settings")),
            lastConnectedAtMillis = json.optLong("lastConnectedAt", 0),
        )
    }

    private fun encodeTelemetry(value: BmsTelemetry) = JSONObject().apply {
        put("packVoltageV", value.packVoltageV)
        put("currentA", value.currentA)
        put("remainingCapacityAh", value.remainingCapacityAh)
        put("fullCapacityAh", value.fullCapacityAh)
        put("soc", value.stateOfChargePercent)
        put("cycleCount", value.cycleCount)
        put("chargeMosfetOn", value.chargeMosfetOn)
        put("dischargeMosfetOn", value.dischargeMosfetOn)
        put("balancingCells", JSONArray(value.balancingCells.sorted()))
        put("protectionFlags", value.protectionFlags)
        put("cellCount", value.cellCount)
        put("temperaturesC", JSONArray(value.temperaturesC))
        put("cellVoltagesV", JSONArray(value.cellVoltagesV))
        put("updatedAt", value.updatedAtMillis)
    }

    private fun decodeTelemetry(json: JSONObject?): BmsTelemetry {
        if (json == null) return BmsTelemetry()
        return BmsTelemetry(
            packVoltageV = json.optDouble("packVoltageV", 0.0),
            currentA = json.optDouble("currentA", 0.0),
            remainingCapacityAh = json.optDouble("remainingCapacityAh", 0.0),
            fullCapacityAh = json.optDouble("fullCapacityAh", 0.0),
            stateOfChargePercent = json.optInt("soc", 0),
            cycleCount = json.optInt("cycleCount", 0),
            chargeMosfetOn = json.optBoolean("chargeMosfetOn", false),
            dischargeMosfetOn = json.optBoolean("dischargeMosfetOn", false),
            balancingCells = json.optJSONArray("balancingCells").toIntSet(),
            protectionFlags = json.optInt("protectionFlags", 0),
            cellCount = json.optInt("cellCount", 0),
            temperaturesC = json.optJSONArray("temperaturesC").toDoubleList(),
            cellVoltagesV = json.optJSONArray("cellVoltagesV").toDoubleList(),
            updatedAtMillis = json.optLong("updatedAt", 0),
        )
    }

    private fun encodeSettings(value: BmsSettings) = JSONObject().apply {
        putNullable("hardwareVersion", value.hardwareVersion)
        putNullable("designCapacityAh", value.designCapacityAh)
        putNullable("cycleCapacityAh", value.cycleCapacityAh)
        putNullable("fullCellVoltageV", value.fullCellVoltageV)
        putNullable("emptyCellVoltageV", value.emptyCellVoltageV)
        putNullable("cellOvervoltageV", value.cellOvervoltageV)
        putNullable("cellUndervoltageV", value.cellUndervoltageV)
        putNullable("packOvervoltageV", value.packOvervoltageV)
        putNullable("packUndervoltageV", value.packUndervoltageV)
        putNullable("balanceStartVoltageV", value.balanceStartVoltageV)
        putNullable("balanceStartDeltaV", value.balanceStartDeltaV)
        putNullable("configuredCellCount", value.configuredCellCount)
        putNullable("chargeOverTemperatureC", value.chargeOverTemperatureC)
        putNullable("chargeUnderTemperatureC", value.chargeUnderTemperatureC)
        putNullable("dischargeOverTemperatureC", value.dischargeOverTemperatureC)
        putNullable("dischargeUnderTemperatureC", value.dischargeUnderTemperatureC)
        put("loaded", value.loaded)
    }

    private fun decodeSettings(json: JSONObject?): BmsSettings {
        if (json == null) return BmsSettings()
        return BmsSettings(
            hardwareVersion = json.optNullableString("hardwareVersion"),
            designCapacityAh = json.optNullableDouble("designCapacityAh"),
            cycleCapacityAh = json.optNullableDouble("cycleCapacityAh"),
            fullCellVoltageV = json.optNullableDouble("fullCellVoltageV"),
            emptyCellVoltageV = json.optNullableDouble("emptyCellVoltageV"),
            cellOvervoltageV = json.optNullableDouble("cellOvervoltageV"),
            cellUndervoltageV = json.optNullableDouble("cellUndervoltageV"),
            packOvervoltageV = json.optNullableDouble("packOvervoltageV"),
            packUndervoltageV = json.optNullableDouble("packUndervoltageV"),
            balanceStartVoltageV = json.optNullableDouble("balanceStartVoltageV"),
            balanceStartDeltaV = json.optNullableDouble("balanceStartDeltaV"),
            configuredCellCount = json.optNullableInt("configuredCellCount"),
            chargeOverTemperatureC = json.optNullableDouble("chargeOverTemperatureC"),
            chargeUnderTemperatureC = json.optNullableDouble("chargeUnderTemperatureC"),
            dischargeOverTemperatureC = json.optNullableDouble("dischargeOverTemperatureC"),
            dischargeUnderTemperatureC = json.optNullableDouble("dischargeUnderTemperatureC"),
            loaded = json.optBoolean("loaded", false),
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONArray?.toDoubleList(): List<Double> {
        if (this == null) return emptyList()
        return List(length()) { optDouble(it) }
    }

    private fun JSONArray?.toIntSet(): Set<Int> {
        if (this == null) return emptySet()
        return buildSet { for (index in 0 until length()) add(optInt(index)) }
    }

    companion object {
        private const val PREFERENCES_NAME = "saved_bms_devices"
        private const val KEY_DEVICES = "devices_json"
        private const val VERSION = 1
    }
}
