package com.jbd.bmsmonitor.model

import java.util.Locale

enum class SettingsIssue {
    EMPTY_BELOW_CELL_CUTOFF,
    EMPTY_BELOW_PACK_CUTOFF,
    FULL_AT_OR_ABOVE_CELL_CUTOFF,
    FULL_AT_OR_ABOVE_PACK_CUTOFF,
    FULL_AT_CHARGER_VOLTAGE,
}

data class SettingsNote(
    val issue: SettingsIssue,
    val title: String,
    val body: String,
)

/**
 * Flags configurations that stop the BMS from recalibrating its state of charge.
 * The BMS resets its coulomb counter to 100% / 0% when cells reach the configured
 * full / empty voltages, so a protection cutoff that trips first prevents that reset.
 */
fun settingsAdvice(settings: BmsSettings, cellCount: Int): List<SettingsNote> {
    val notes = mutableListOf<SettingsNote>()
    val full = settings.fullCellVoltageV
    val empty = settings.emptyCellVoltageV
    val packOvPerCell = settings.packOvervoltageV?.takeIf { cellCount > 0 }?.div(cellCount)
    val packUvPerCell = settings.packUndervoltageV?.takeIf { cellCount > 0 }?.div(cellCount)

    if (empty != null && settings.cellUndervoltageV != null && empty < settings.cellUndervoltageV - EPSILON) {
        notes += SettingsNote(
            SettingsIssue.EMPTY_BELOW_CELL_CUTOFF,
            "0% is never reached",
            "The 0% voltage (${v(empty)}) is below the cell undervoltage cutoff " +
                "(${v(settings.cellUndervoltageV)}). The BMS switches off before cells reach 0%, so it " +
                "never recalibrates at empty and may still show charge left when it cuts off. " +
                "Set the 0% voltage to at least ${v(settings.cellUndervoltageV)}.",
        )
    }
    if (empty != null && packUvPerCell != null && empty < packUvPerCell - EPSILON) {
        notes += SettingsNote(
            SettingsIssue.EMPTY_BELOW_PACK_CUTOFF,
            "0% is never reached",
            "The pack undervoltage cutoff (${v(settings.packUndervoltageV!!, 2)}) equals " +
                "${v(packUvPerCell)} per cell, which trips before cells drop to the 0% voltage " +
                "(${v(empty)}). The BMS then never recalibrates at empty. Raise the 0% voltage to at " +
                "least ${v(packUvPerCell)}, or lower the pack undervoltage cutoff.",
        )
    }
    if (full != null && settings.cellOvervoltageV != null && full >= settings.cellOvervoltageV - EPSILON) {
        notes += SettingsNote(
            SettingsIssue.FULL_AT_OR_ABOVE_CELL_CUTOFF,
            "100% is never reached",
            "The 100% voltage (${v(full)}) is at or above the cell overvoltage cutoff " +
                "(${v(settings.cellOvervoltageV)}). Charging stops before cells reach 100%, so the BMS " +
                "never recalibrates at full. Set the 100% voltage below the cutoff.",
        )
    }
    if (full != null && packOvPerCell != null && full >= packOvPerCell - EPSILON) {
        notes += SettingsNote(
            SettingsIssue.FULL_AT_OR_ABOVE_PACK_CUTOFF,
            "100% is hard to reach",
            "The pack overvoltage cutoff (${v(settings.packOvervoltageV!!, 2)}) equals " +
                "${v(packOvPerCell)} per cell, which is not above the 100% voltage (${v(full)}). " +
                "Charging can stop before every cell reaches 100%, so the BMS rarely recalibrates at " +
                "full. Lower the 100% voltage slightly, or raise the pack overvoltage cutoff a little.",
        )
    }
    val packCutoffNoted = notes.any { it.issue == SettingsIssue.FULL_AT_OR_ABOVE_PACK_CUTOFF }
    if (full != null && full in CHARGER_END_RANGE && !packCutoffNoted) {
        notes += SettingsNote(
            SettingsIssue.FULL_AT_CHARGER_VOLTAGE,
            "100% may not be reached reliably",
            "The 100% voltage (${v(full)}) is what a Li-ion charger ends at. With cable losses and " +
                "slightly unbalanced cells, cells often stop just short of it, so the percentage drifts " +
                "between charges. A 100% voltage of 4.150–4.170 V recalibrates more reliably.",
        )
    }
    return notes
}

private const val EPSILON = 0.0005
private val CHARGER_END_RANGE = 4.195..4.25

private fun v(value: Double, decimals: Int = 3): String =
    String.format(Locale.getDefault(), "%.${decimals}f V", value)
