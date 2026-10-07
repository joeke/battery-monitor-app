package com.jbd.bmsmonitor.background

import org.junit.Assert.*
import org.junit.Test

class BackgroundDisconnectTest {
    private var now = 0L
    private var scheduled: Runnable? = null
    private var scheduledDelay = 0L
    private val events = mutableListOf<String>()
    private val countdown = BackgroundDisconnect(
        elapsedRealtime = { now },
        scheduleTimeout = { timeout, delay -> scheduled = timeout; scheduledDelay = delay },
        cancelTimeout = { scheduled = null },
        disconnectDevices = { events += "disconnect" },
    )

    @Test
    fun `switching apps preserves connections until timeout and then disconnects once`() {
        countdown.backgrounded(60)
        assertEquals(60_000L, scheduledDelay)
        assertTrue(events.isEmpty())
        now = 59_999L
        countdown.disconnectIfDue()
        assertTrue(events.isEmpty())
        now = 60_000L
        scheduled!!.run()
        assertEquals(listOf("disconnect"), events)
        assertNull(scheduled)
        countdown.disconnectIfDue()
        assertEquals(1, events.size)
    }

    @Test
    fun `returning before timeout cancels disconnection and background uploads`() {
        countdown.backgrounded(30)
        val oldTimeout = scheduled!!
        now = 10_000L
        countdown.foregrounded()
        assertNull(scheduled)
        now = 30_000L
        oldTimeout.run()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `leaving again starts a full new countdown`() {
        countdown.backgrounded(10)
        now = 5_000L
        countdown.foregrounded()
        countdown.backgrounded(10)
        now = 10_000L
        countdown.disconnectIfDue()
        assertTrue(events.isEmpty())
        now = 15_000L
        countdown.disconnectIfDue()
        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `returning after a suspended timeout disconnects without starting background uploads`() {
        countdown.backgrounded(10)
        val timeout = scheduled!!
        now = 60_000L
        countdown.foregrounded()
        timeout.run()
        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `background worker finishes a suspended timeout and a late handler does nothing`() {
        countdown.backgrounded(10)
        val timeout = scheduled!!
        now = 90_000L
        countdown.disconnectIfDue()
        assertEquals(listOf("disconnect"), events)
        assertNull(scheduled)
        timeout.run()
        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `handler waking after screen off disconnects immediately and only once`() {
        countdown.backgrounded(10)
        val timeout = scheduled!!
        now = 90_000L
        timeout.run()
        assertEquals(listOf("disconnect"), events)
        assertNull(scheduled)
        countdown.disconnectIfDue()
        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `old long and never settings migrate to one minute while supported settings stay intact`() {
        listOf(0, 300, 1_800).forEach { assertEquals(60, BackgroundDisconnect.normalizeSeconds(it)) }
        BackgroundDisconnect.OPTIONS_SECONDS.forEach { assertEquals(it, BackgroundDisconnect.normalizeSeconds(it)) }
        assertEquals(10, BackgroundDisconnect.normalizeSeconds(-1))
    }
}
