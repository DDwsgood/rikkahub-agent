package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [parseAdbDevices] and the derived [AdbDevice] properties.
 *
 * The parser feeds both `adb_shell` target resolution and the Doctor connectivity
 * check, so its handling of header lines, daemon chatter, and missing state
 * columns is pinned here.
 */
class AdbToolsParseTest {

    @Test
    fun `parses a typical devices listing`() {
        val devices = parseAdbDevices(
            """
            List of devices attached
            emulator-5554	device
            127.0.0.1:39213	device

            """.trimIndent()
        )
        assertEquals(2, devices.size)
        assertEquals("emulator-5554", devices[0].serial)
        assertEquals("device", devices[0].state)
        assertEquals("127.0.0.1:39213", devices[1].serial)
    }

    @Test
    fun `skips daemon log lines and blank lines`() {
        val devices = parseAdbDevices(
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached


            emulator-5554	device
            """.trimIndent()
        )
        assertEquals(listOf("emulator-5554"), devices.map { it.serial })
    }

    @Test
    fun `empty output yields no devices`() {
        assertTrue(parseAdbDevices("").isEmpty())
        assertTrue(parseAdbDevices("List of devices attached\n").isEmpty())
    }

    @Test
    fun `captures unauthorized and offline states`() {
        val devices = parseAdbDevices(
            """
            List of devices attached
            127.0.0.1:41001	unauthorized
            127.0.0.1:41002	offline
            """.trimIndent()
        )
        assertEquals("unauthorized", devices[0].state)
        assertEquals("offline", devices[1].state)
        assertFalse(devices[0].authorized)
        assertFalse(devices[1].authorized)
    }

    @Test
    fun `state is lowercased`() {
        val devices = parseAdbDevices("emulator-5554\tDEVICE\n")
        assertEquals("device", devices[0].state)
        assertTrue(devices[0].authorized)
    }

    @Test
    fun `row without a state column gets empty state`() {
        val devices = parseAdbDevices("emulator-5554\n")
        assertEquals(1, devices.size)
        assertEquals("", devices[0].state)
        assertFalse(devices[0].authorized)
    }

    @Test
    fun `tolerates irregular whitespace between columns`() {
        val devices = parseAdbDevices("  emulator-5554     device  \n")
        assertEquals("emulator-5554", devices[0].serial)
        assertEquals("device", devices[0].state)
    }

    @Test
    fun `verbose listing keeps second column as state`() {
        // `adb devices -l` appends product/model/device columns; state stays tokens[1].
        val devices = parseAdbDevices(
            "emulator-5554  device product:sdk model:Pixel device:emu64a\n"
        )
        assertEquals("device", devices[0].state)
        assertTrue(devices[0].authorized)
    }

    // -- AdbDevice derived properties -----------------------------------------

    @Test
    fun `authorized is true only for the device state`() {
        assertTrue(AdbDevice("s", "device").authorized)
        assertFalse(AdbDevice("s", "unauthorized").authorized)
        assertFalse(AdbDevice("s", "offline").authorized)
        assertFalse(AdbDevice("s", "").authorized)
    }

    @Test
    fun `isLocal recognises loopback serials only`() {
        assertTrue(AdbDevice("127.0.0.1:5555", "device").isLocal)
        assertTrue(AdbDevice("localhost:5555", "device").isLocal)
        assertFalse(AdbDevice("192.168.1.5:5555", "device").isLocal)
        assertFalse(AdbDevice("emulator-5554", "device").isLocal)
        assertFalse(AdbDevice("10.0.0.1:5555", "device").isLocal)
    }

    @Test
    fun `port extracts the trailing serial segment`() {
        assertEquals(39213, AdbDevice("127.0.0.1:39213", "device").port)
        assertEquals(5555, AdbDevice("192.168.1.5:5555", "device").port)
    }

    @Test
    fun `port is null when serial has no numeric port`() {
        assertNull(AdbDevice("emulator-5554", "device").port)
        assertNull(AdbDevice("localhost:notaport", "device").port)
    }
}
