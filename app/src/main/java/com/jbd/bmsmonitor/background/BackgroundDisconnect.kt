package com.jbd.bmsmonitor.background

/**
 * Application-owned BLE countdown. Main thread only.
 * Upload work is scheduled separately when leaving the app; a late timeout must not restart it.
 */
class BackgroundDisconnect(
    private val elapsedRealtime: () -> Long,
    private val scheduleTimeout: (Runnable, Long) -> Unit,
    private val cancelTimeout: (Runnable) -> Unit,
    private val disconnectDevices: () -> Unit,
) {
    private var deadline: Long? = null
    private val timeout = Runnable { disconnectIfDue() }

    fun backgrounded(seconds: Int) {
        require(seconds in OPTIONS_SECONDS)
        cancelTimeout(timeout)
        deadline = elapsedRealtime() + seconds * 1_000L
        scheduleTimeout(timeout, seconds * 1_000L)
    }

    fun foregrounded() {
        cancelTimeout(timeout)
        val expired = deadline?.let { elapsedRealtime() >= it } == true
        deadline = null
        if (expired) disconnectDevices()
    }

    fun disconnectIfDue() {
        val disconnectAt = deadline ?: return
        if (elapsedRealtime() < disconnectAt) return
        deadline = null
        cancelTimeout(timeout)
        disconnectDevices()
    }

    companion object {
        val OPTIONS_SECONDS = listOf(5, 10, 30, 60)
        const val DEFAULT_SECONDS = 10

        fun normalizeSeconds(stored: Int): Int = when {
            stored in OPTIONS_SECONDS -> stored
            stored == 0 || stored > OPTIONS_SECONDS.last() -> OPTIONS_SECONDS.last()
            else -> DEFAULT_SECONDS
        }
    }
}
