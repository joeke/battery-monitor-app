package com.jbd.bmsmonitor.model

data class DiscoveredBms(
    val address: String,
    val name: String,
    val rssi: Int,
)

enum class ConnectionStatus {
    CONNECTING,
    DISCOVERING,
    CONNECTED,
    DISCONNECTED,
}

data class BmsTelemetry(
    val packVoltageV: Double = 0.0,
    val currentA: Double = 0.0,
    val remainingCapacityAh: Double = 0.0,
    val fullCapacityAh: Double = 0.0,
    val stateOfChargePercent: Int = 0,
    val cycleCount: Int = 0,
    val chargeMosfetOn: Boolean = false,
    val dischargeMosfetOn: Boolean = false,
    val balancingCells: Set<Int> = emptySet(),
    val protectionFlags: Int = 0,
    val cellCount: Int = 0,
    val temperaturesC: List<Double> = emptyList(),
    val cellVoltagesV: List<Double> = emptyList(),
    val updatedAtMillis: Long = 0,
) {
    val powerW: Double get() = packVoltageV * currentA
    val cellDeltaV: Double
        get() = if (cellVoltagesV.isEmpty()) 0.0 else cellVoltagesV.max() - cellVoltagesV.min()
    val averageCellV: Double
        get() = cellVoltagesV.takeIf { it.isNotEmpty() }?.average() ?: 0.0
}

data class BmsSettings(
    val hardwareVersion: String? = null,
    val designCapacityAh: Double? = null,
    val cycleCapacityAh: Double? = null,
    val fullCellVoltageV: Double? = null,
    val emptyCellVoltageV: Double? = null,
    val cellOvervoltageV: Double? = null,
    val cellUndervoltageV: Double? = null,
    val packOvervoltageV: Double? = null,
    val packUndervoltageV: Double? = null,
    val balanceStartVoltageV: Double? = null,
    val balanceStartDeltaV: Double? = null,
    val configuredCellCount: Int? = null,
    val chargeOverTemperatureC: Double? = null,
    val chargeUnderTemperatureC: Double? = null,
    val dischargeOverTemperatureC: Double? = null,
    val dischargeUnderTemperatureC: Double? = null,
    val loaded: Boolean = false,
    val unavailableReason: String? = null,
)

data class BmsDeviceState(
    val address: String,
    val name: String,
    val rssi: Int,
    val connectionStatus: ConnectionStatus = ConnectionStatus.CONNECTING,
    val telemetry: BmsTelemetry = BmsTelemetry(),
    val settings: BmsSettings = BmsSettings(),
    val error: String? = null,
)
