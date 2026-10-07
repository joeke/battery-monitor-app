package com.jbd.bmsmonitor.storage

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class UploadLogStoreTest {
    @Test
    fun `history survives recreation and retains only the newest entries`() {
        var persisted = emptyList<UploadLogEntry>()
        val store = UploadLogStore { persisted = it }
        repeat(UploadLogStore.MAX_ENTRIES + 10) { store.append("Request $it", it.toLong()) }

        val reopened = UploadLogStore(persisted) { persisted = it }
        assertEquals(UploadLogStore.MAX_ENTRIES, reopened.entries.value.size)
        assertEquals("Request 1009", reopened.entries.value.first().message)
        assertEquals("Request 10", reopened.entries.value.last().message)
        assertEquals(store.entries.value, reopened.entries.value)
        reopened.clear()
        assertTrue(persisted.isEmpty())
        assertTrue(UploadLogStore(persisted) {}.entries.value.isEmpty())
    }

    @Test
    fun `foreground and background writers cannot lose each others entries`() {
        var persisted = emptyList<UploadLogEntry>()
        val store = UploadLogStore { persisted = it }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writers = listOf("Foreground", "Background").map { source ->
                executor.submit { repeat(100) { store.append("$source $it", 0L) } }
            }
            writers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(200, store.entries.value.size)
            assertEquals(200, store.entries.value.map { it.id }.distinct().size)
            assertEquals(100, store.entries.value.count { it.message.startsWith("Foreground") })
            assertEquals(100, store.entries.value.count { it.message.startsWith("Background") })
            assertEquals(store.entries.value, persisted)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `storage failures do not interrupt logging or uploads`() {
        val store = UploadLogStore { throw IllegalStateException("Storage unavailable") }
        store.append("Sent battery data")
        assertEquals("Sent battery data", store.entries.value.single().message)
        store.clear()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `timestamps follow the requested format in local time including daylight saving`() {
        val winter = UploadLogEntry(Instant.parse("2026-01-01T14:00:00Z").toEpochMilli(), "Sent")
        val summer = UploadLogEntry(Instant.parse("2026-07-01T13:00:00Z").toEpochMilli(), "Sent")
        val zone = ZoneId.of("Europe/Amsterdam")
        assertEquals("2026-01-01-15:00:00", winter.timestamp(zone))
        assertEquals("2026-07-01-15:00:00", summer.timestamp(zone))
    }
}
