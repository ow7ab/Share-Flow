package com.example.shareflow.core.common

import com.example.shareflow.domain.model.SettingsValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersAndValidationTest {

    @Test
    fun `formatBytes formats byte magnitudes accurately`() {
        assertEquals("0 B", ShareFlowFormatters.formatBytes(-5L))
        assertEquals("512 B", ShareFlowFormatters.formatBytes(512L))
        assertEquals("1.0 KB", ShareFlowFormatters.formatBytes(1024L))
        assertEquals("1.5 MB", ShareFlowFormatters.formatBytes((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatTransferSpeed appends per-second unit`() {
        assertEquals("0 B/s", ShareFlowFormatters.formatTransferSpeed(0L))
        assertEquals("10.0 MB/s", ShareFlowFormatters.formatTransferSpeed(10L * 1024L * 1024L))
    }

    @Test
    fun `SettingsValidator enforces device name and port boundaries`() {
        assertTrue(SettingsValidator.isValidDeviceName("Pixel 9 Pro"))
        assertEquals("Pixel 9", SettingsValidator.sanitizeDeviceName("  Pixel   9  "))
        assertFalse(SettingsValidator.isValidDeviceName("A"))
        assertFalse(SettingsValidator.isValidDeviceName("A".repeat(33)))
        assertFalse(SettingsValidator.isValidDeviceName("Bad\nName"))

        assertTrue(SettingsValidator.isValidTransferPort(1024))
        assertTrue(SettingsValidator.isValidTransferPort(48721))
        assertTrue(SettingsValidator.isValidTransferPort(65535))
        assertFalse(SettingsValidator.isValidTransferPort(1023))
        assertFalse(SettingsValidator.isValidTransferPort(65536))
    }
}
