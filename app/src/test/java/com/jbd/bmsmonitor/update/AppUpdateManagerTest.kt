package com.jbd.bmsmonitor.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun `newer semantic versions are recognized`() {
        assertTrue(isNewerVersion("0.4.4", "0.4.3"))
        assertTrue(isNewerVersion("v0.10.0", "0.9.9"))
        assertTrue(isNewerVersion("1.0", "0.99.99"))
    }

    @Test
    fun `equal older and malformed versions are rejected`() {
        assertFalse(isNewerVersion("0.4.3", "0.4.3"))
        assertFalse(isNewerVersion("0.4.2", "0.4.3"))
        assertFalse(isNewerVersion("latest", "0.4.3"))
    }
}
