package com.jbd.bmsmonitor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAdviceTest {
    @Test
    fun `pack undervoltage above 0 percent and pack overvoltage at 100 percent are flagged`() {
        val settings = BmsSettings(
            fullCellVoltageV = 4.20,
            emptyCellVoltageV = 3.00,
            cellOvervoltageV = 4.23,
            cellUndervoltageV = 3.00,
            packOvervoltageV = 42.0,
            packUndervoltageV = 31.0,
        )

        assertEquals(
            listOf(SettingsIssue.EMPTY_BELOW_PACK_CUTOFF, SettingsIssue.FULL_AT_OR_ABOVE_PACK_CUTOFF),
            settingsAdvice(settings, cellCount = 10).map { it.issue },
        )
    }

    @Test
    fun `cell undervoltage above 0 percent is flagged`() {
        val settings = BmsSettings(
            fullCellVoltageV = 4.18,
            emptyCellVoltageV = 3.00,
            cellOvervoltageV = 4.21,
            cellUndervoltageV = 3.05,
            packOvervoltageV = 42.1,
            packUndervoltageV = 30.0,
        )

        assertEquals(
            listOf(SettingsIssue.EMPTY_BELOW_CELL_CUTOFF),
            settingsAdvice(settings, cellCount = 10).map { it.issue },
        )
    }

    @Test
    fun `100 percent at or above cell overvoltage is flagged`() {
        val settings = BmsSettings(fullCellVoltageV = 4.20, cellOvervoltageV = 4.20)

        assertEquals(
            listOf(SettingsIssue.FULL_AT_OR_ABOVE_CELL_CUTOFF, SettingsIssue.FULL_AT_CHARGER_VOLTAGE),
            settingsAdvice(settings, cellCount = 10).map { it.issue },
        )
    }

    @Test
    fun `well configured pack produces no advice`() {
        val settings = BmsSettings(
            fullCellVoltageV = 4.16,
            emptyCellVoltageV = 3.10,
            cellOvervoltageV = 4.23,
            cellUndervoltageV = 3.00,
            packOvervoltageV = 42.2,
            packUndervoltageV = 30.0,
        )

        assertTrue(settingsAdvice(settings, cellCount = 10).isEmpty())
    }

    @Test
    fun `pack checks are skipped without a cell count`() {
        val settings = BmsSettings(fullCellVoltageV = 4.16, emptyCellVoltageV = 3.00, packUndervoltageV = 31.0)

        assertTrue(settingsAdvice(settings, cellCount = 0).isEmpty())
    }
}
