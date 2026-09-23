package com.jbd.bmsmonitor.protocol

import com.jbd.bmsmonitor.model.BmsDeviceState

/** One connection's read sequence. The transport serializes replies and timeouts. */
class JbdReadSession(
    private val updateDevice: ((BmsDeviceState) -> BmsDeviceState) -> Unit,
    private val onInitialReading: () -> Unit,
    private val dispatch: (ByteArray, Int, Long) -> Unit,
) {
    private var phase = Phase.STARTING
    private var settingsIndex = 0
    private var settingsReadCount = 0
    private var defaultPasswordAttempted = false
    private var lastRequest: Pair<ByteArray, Int>? = null
    private var retried = false
    private var basicInfoReceived = false
    var initialReadingComplete = false
        private set

    fun start() {
        updateDevice { it.copy(settings = it.settings.copy(loaded = false, unavailableReason = null)) }
        phase = Phase.INITIAL_BASIC
        sendRead(JbdProtocol.BASIC_INFO)
    }

    fun handleFrame(frame: JbdFrame) {
        if (frame.status != 0) {
            handleRegisterError(frame.register, frame.status)
            return
        }

        when (phase) {
            Phase.INITIAL_BASIC, Phase.POLL_BASIC -> {
                updateDevice { current ->
                    JbdParser.parseBasicInfo(frame.payload, current.telemetry)
                        ?.let {
                            basicInfoReceived = true
                            current.copy(telemetry = it, error = null)
                        }
                        ?: current.copy(error = "Invalid basic-info frame")
                }
                phase = if (phase == Phase.INITIAL_BASIC) Phase.INITIAL_CELLS else Phase.POLL_CELLS
                sendRead(JbdProtocol.CELL_INFO)
            }
            Phase.INITIAL_CELLS, Phase.POLL_CELLS -> {
                val initialReading = phase == Phase.INITIAL_CELLS
                var readingComplete = false
                updateDevice { current ->
                    JbdParser.parseCells(frame.payload, current.telemetry)
                        ?.let {
                            readingComplete = true
                            current.copy(telemetry = it, error = null)
                        }
                        ?: current.copy(error = "Invalid cell-voltage frame")
                }
                if (!initialReadingComplete && basicInfoReceived && readingComplete) {
                    initialReadingComplete = true
                    onInitialReading()
                }
                if (initialReading) {
                    phase = Phase.HARDWARE
                    sendRead(JbdProtocol.HARDWARE_VERSION)
                } else {
                    scheduleNextPoll()
                }
            }
            Phase.HARDWARE -> {
                val version = JbdParser.parseHardwareVersion(frame.payload)
                updateDevice { it.copy(settings = it.settings.copy(hardwareVersion = version)) }
                phase = Phase.ENTER_FACTORY
                send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY, 100L)
            }
            Phase.ENTER_FACTORY -> {
                phase = Phase.SETTINGS
                settingsIndex = 0
                settingsReadCount = 0
                send(JbdProtocol.read(JbdProtocol.SETTINGS_REGISTERS.first()), JbdProtocol.SETTINGS_REGISTERS.first(), 300L)
            }
            Phase.AUTHENTICATE -> {
                phase = Phase.ENTER_FACTORY
                send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY, 100L)
            }
            Phase.SETTINGS -> {
                if (frame.payload.size >= 2) {
                    settingsReadCount++
                    updateDevice {
                        it.copy(settings = JbdParser.applySetting(frame.register, frame.payload, it.settings))
                    }
                }
                settingsIndex++
                readNextSetting()
            }
            Phase.EXIT_FACTORY -> {
                finishSettings(exitConfirmed = true)
            }
            Phase.STARTING -> Unit
        }
    }

    private fun handleRegisterError(register: Int, status: Int) {
        when (phase) {
            Phase.INITIAL_BASIC, Phase.INITIAL_CELLS -> advanceInitialReading()
            Phase.EXIT_FACTORY -> finishSettings(exitConfirmed = false)
            Phase.ENTER_FACTORY -> {
                authenticateWithDefaultPasswordOrFail(
                    "Settings access denied by BMS (status $status)",
                )
            }
            Phase.AUTHENTICATE -> {
                settingsUnavailable("Default BMS password was rejected (status $status)")
            }
            Phase.SETTINGS -> {
                if (retryRequest()) return
                settingsIndex++
                readNextSetting()
            }
            Phase.HARDWARE -> {
                phase = Phase.ENTER_FACTORY
                send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY, 100L)
            }
            else -> {
                updateDevice { it.copy(error = "BMS rejected register 0x${register.toString(16)}") }
                scheduleNextPoll()
            }
        }
    }

    private fun readNextSetting() {
        if (settingsIndex >= JbdProtocol.SETTINGS_REGISTERS.size) {
            phase = Phase.EXIT_FACTORY
            send(JbdProtocol.exitFactoryMode(), JbdProtocol.EXIT_FACTORY, 100L)
        } else {
            sendRead(JbdProtocol.SETTINGS_REGISTERS[settingsIndex])
        }
    }

    private fun authenticateWithDefaultPasswordOrFail(failureReason: String) {
        if (defaultPasswordAttempted) {
            settingsUnavailable(failureReason)
            return
        }
        defaultPasswordAttempted = true
        phase = Phase.AUTHENTICATE
        send(JbdProtocol.usePassword("123456"), JbdProtocol.USE_PASSWORD, 100L)
    }

    private fun settingsUnavailable(reason: String) {
        updateDevice {
            it.copy(settings = it.settings.copy(loaded = false, unavailableReason = reason))
        }
        scheduleNextPoll()
    }

    private fun finishSettings(exitConfirmed: Boolean) {
        // Try authentication once if entry succeeded but no EEPROM values were returned.
        if (settingsReadCount == 0 && !defaultPasswordAttempted && exitConfirmed) {
            authenticateWithDefaultPasswordOrFail("The BMS did not return configuration values")
            return
        }
        val total = JbdProtocol.SETTINGS_REGISTERS.size
        val readIssue = when {
            settingsReadCount == 0 -> "The BMS did not return any configuration values"
            settingsReadCount < total -> "Read $settingsReadCount of $total configuration values"
            else -> null
        }
        val exitIssue = if (exitConfirmed) null else "Could not confirm exit from configuration mode"
        updateDevice {
            it.copy(settings = it.settings.copy(
                loaded = settingsReadCount > 0,
                unavailableReason = listOfNotNull(readIssue, exitIssue).joinToString(". ").ifBlank { null },
            ))
        }
        scheduleNextPoll()
    }

    private fun scheduleNextPoll() {
        phase = Phase.POLL_BASIC
        send(JbdProtocol.read(JbdProtocol.BASIC_INFO), JbdProtocol.BASIC_INFO, 2_000L)
    }

    private fun sendRead(register: Int) = send(JbdProtocol.read(register), register, 100L)

    private fun send(bytes: ByteArray, register: Int, delayMillis: Long) {
        retried = false
        lastRequest = bytes to register
        dispatch(bytes, register, delayMillis)
    }

    private fun retryRequest(): Boolean {
        val request = lastRequest ?: return false
        if (retried) return false
        retried = true
        dispatch(request.first, request.second, 300L)
        return true
    }

    private fun advanceInitialReading() {
        // Settings discovery must not depend on the first telemetry exchange succeeding.
        if (phase == Phase.INITIAL_BASIC) {
            phase = Phase.INITIAL_CELLS
            sendRead(JbdProtocol.CELL_INFO)
        } else {
            phase = Phase.HARDWARE
            sendRead(JbdProtocol.HARDWARE_VERSION)
        }
    }

    fun onRequestTimeout() {
        if (retryRequest()) return
        when (phase) {
            Phase.INITIAL_BASIC, Phase.INITIAL_CELLS -> advanceInitialReading()
            Phase.SETTINGS -> {
                settingsIndex++
                readNextSetting()
            }
            Phase.HARDWARE -> {
                phase = Phase.ENTER_FACTORY
                send(JbdProtocol.enterFactoryMode(), JbdProtocol.ENTER_FACTORY, 100L)
            }
            Phase.ENTER_FACTORY -> {
                authenticateWithDefaultPasswordOrFail("Settings are not supported by this BMS")
            }
            Phase.AUTHENTICATE -> {
                settingsUnavailable("The BMS did not accept the default configuration password")
            }
            Phase.EXIT_FACTORY -> {
                finishSettings(exitConfirmed = false)
            }
            else -> {
                updateDevice { it.copy(error = "BMS did not respond") }
                scheduleNextPoll()
            }
        }
    }

    private enum class Phase {
        STARTING,
        INITIAL_BASIC,
        INITIAL_CELLS,
        HARDWARE,
        ENTER_FACTORY,
        AUTHENTICATE,
        SETTINGS,
        EXIT_FACTORY,
        POLL_BASIC,
        POLL_CELLS,
    }
}
