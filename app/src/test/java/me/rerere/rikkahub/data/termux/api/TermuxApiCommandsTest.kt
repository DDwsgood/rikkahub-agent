package me.rerere.rikkahub.data.termux.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure argument parsers behind the termux-* shim commands
 * ([parseNotificationArgs], [parseToastArgs], [parseVibrateArgs], [parseTorchArgs])
 * and the battery-status string mappers.
 *
 * The parsers aim to mirror official Termux:API CLI behaviour: option values are
 * consumed even when they look like flags, unsupported-but-official options are
 * accepted and ignored for compatibility, and malformed input is an Invalid
 * result rather than a crash.
 */
class TermuxApiCommandsTest {

    // -- termux-notification -------------------------------------------------

    private fun notification(args: List<String>): NotificationOptions =
        (parseNotificationArgs(args) as ParsedArgs.Ok).value

    @Test
    fun `notification empty args yields defaults`() {
        val opts = notification(emptyList())
        assertNull(opts.title)
        assertNull(opts.content)
        assertNull(opts.id)
        assertEquals("default", opts.priority)
        assertFalse(opts.ongoing)
        assertFalse(opts.alertOnce)
        assertFalse(opts.sound)
        assertNull(opts.vibratePattern)
        assertNull(opts.group)
        assertNull(opts.channel)
    }

    @Test
    fun `notification short and long option forms`() {
        val opts = notification(
            listOf("-t", "Title", "--content", "Body", "-i", "42", "--group", "g", "--channel", "ch")
        )
        assertEquals("Title", opts.title)
        assertEquals("Body", opts.content)
        assertEquals("42", opts.id)
        assertEquals("g", opts.group)
        assertEquals("ch", opts.channel)
    }

    @Test
    fun `notification boolean flags`() {
        val opts = notification(listOf("--sound", "--alert-once", "--id", "x", "--ongoing"))
        assertTrue(opts.sound)
        assertTrue(opts.alertOnce)
        assertTrue(opts.ongoing)
    }

    @Test
    fun `notification accepts every documented priority`() {
        for (p in listOf("min", "low", "default", "high", "max")) {
            assertEquals(p, notification(listOf("--priority", p)).priority)
        }
    }

    @Test
    fun `notification rejects unknown priority`() {
        val r = parseNotificationArgs(listOf("--priority", "urgent"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals(
            "invalid priority 'urgent' (min/low/default/high/max)",
            (r as ParsedArgs.Invalid).message,
        )
    }

    @Test
    fun `notification vibrate pattern parses to long array`() {
        val opts = notification(listOf("--vibrate", "500,1000, 200"))
        assertArrayEquals(longArrayOf(500L, 1000L, 200L), opts.vibratePattern)
    }

    @Test
    fun `notification vibrate rejects non-numeric element`() {
        assertTrue(
            parseNotificationArgs(listOf("--vibrate", "500,buzz")) is ParsedArgs.Invalid
        )
    }

    @Test
    fun `notification ongoing without id is invalid`() {
        val r = parseNotificationArgs(listOf("--ongoing"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals(
            "ongoing notifications without an ID are not removable; set --id",
            (r as ParsedArgs.Invalid).message,
        )
    }

    @Test
    fun `notification missing option value is invalid`() {
        val r = parseNotificationArgs(listOf("-t"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals("option '-t' requires an argument", (r as ParsedArgs.Invalid).message)
    }

    @Test
    fun `notification consumes officially supported but ignored options`() {
        // Phase 1 ignores --action/--icon/etc but must still consume their values
        // so they don't surface as "illegal option".
        val opts = notification(
            listOf("--action", "a", "--icon", "i.png", "--button1", "OK", "--led-off", "500", "-t", "T")
        )
        assertEquals("T", opts.title)
    }

    @Test
    fun `notification ignored option missing value is invalid`() {
        assertTrue(
            parseNotificationArgs(listOf("--icon")) is ParsedArgs.Invalid
        )
    }

    @Test
    fun `notification unknown option is invalid`() {
        val r = parseNotificationArgs(listOf("--frobnicate"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals("illegal option --frobnicate", (r as ParsedArgs.Invalid).message)
    }

    @Test
    fun `notification bare positional arg is invalid`() {
        // termux-notification takes no positional args — content comes via -c or stdin.
        assertTrue(parseNotificationArgs(listOf("hello")) is ParsedArgs.Invalid)
    }

    @Test
    fun `notification help variants return Help`() {
        assertTrue(parseNotificationArgs(listOf("-h")) is ParsedArgs.Help)
        assertTrue(parseNotificationArgs(listOf("--help")) is ParsedArgs.Help)
        assertTrue(parseNotificationArgs(listOf("--help-actions")) is ParsedArgs.Help)
    }

    // -- termux-toast --------------------------------------------------------

    private fun toast(args: List<String>): ToastOptions =
        (parseToastArgs(args) as ParsedArgs.Ok).value

    @Test
    fun `toast joins positional text with spaces`() {
        val opts = toast(listOf("hello", "big", "world"))
        assertEquals("hello big world", opts.text)
        assertFalse(opts.short)
        assertNull(opts.gravity)
    }

    @Test
    fun `toast empty args yields empty text`() {
        val opts = toast(emptyList())
        assertEquals("", opts.text)
    }

    @Test
    fun `toast -s sets short duration`() {
        assertTrue(toast(listOf("-s", "hi")).short)
    }

    @Test
    fun `toast accepts documented gravities`() {
        for (g in listOf("top", "middle", "bottom")) {
            assertEquals(g, toast(listOf("-g", g)).gravity)
        }
    }

    @Test
    fun `toast rejects unknown gravity`() {
        assertTrue(parseToastArgs(listOf("-g", "left")) is ParsedArgs.Invalid)
    }

    @Test
    fun `toast -b and -c consume a value but are ignored`() {
        // Colour options are accepted for CLI compat even though Android 12+ toasts
        // can't be styled.
        val opts = toast(listOf("-b", "red", "-c", "blue", "text"))
        assertEquals("text", opts.text)
    }

    @Test
    fun `toast -b missing value is invalid`() {
        assertTrue(parseToastArgs(listOf("-b")) is ParsedArgs.Invalid)
    }

    @Test
    fun `toast double dash ends option parsing`() {
        val opts = toast(listOf("--", "-s", "-g"))
        assertEquals("-s -g", opts.text)
        assertFalse(opts.short)
        assertNull(opts.gravity)
    }

    @Test
    fun `toast single dash is treated as text`() {
        assertEquals("-", toast(listOf("-")).text)
    }

    @Test
    fun `toast unknown option is invalid`() {
        assertTrue(parseToastArgs(listOf("-z")) is ParsedArgs.Invalid)
    }

    @Test
    fun `toast help returns Help`() {
        assertTrue(parseToastArgs(listOf("-h")) is ParsedArgs.Help)
    }

    // -- termux-vibrate ------------------------------------------------------

    private fun vibrate(args: List<String>): VibrateOptions =
        (parseVibrateArgs(args) as ParsedArgs.Ok).value

    @Test
    fun `vibrate defaults to 1000ms no force`() {
        val opts = vibrate(emptyList())
        assertEquals(1000, opts.durationMs)
        assertFalse(opts.force)
    }

    @Test
    fun `vibrate parses duration and force`() {
        val opts = vibrate(listOf("-d", "250", "-f"))
        assertEquals(250, opts.durationMs)
        assertTrue(opts.force)
    }

    @Test
    fun `vibrate rejects non-numeric duration`() {
        val r = parseVibrateArgs(listOf("-d", "soon"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals("invalid duration 'soon'", (r as ParsedArgs.Invalid).message)
    }

    @Test
    fun `vibrate missing duration value is invalid`() {
        assertTrue(parseVibrateArgs(listOf("-d")) is ParsedArgs.Invalid)
    }

    @Test
    fun `vibrate accepts negative duration at parse time`() {
        // Parsing only checks it's an int; the handler clamps to >= 1ms.
        assertEquals(-5, vibrate(listOf("-d", "-5")).durationMs)
    }

    @Test
    fun `vibrate unknown option is invalid`() {
        assertTrue(parseVibrateArgs(listOf("--loud")) is ParsedArgs.Invalid)
    }

    @Test
    fun `vibrate help returns Help`() {
        assertTrue(parseVibrateArgs(listOf("-h")) is ParsedArgs.Help)
    }

    // -- termux-torch --------------------------------------------------------

    @Test
    fun `torch on and off parse to booleans`() {
        assertEquals(true, (parseTorchArgs(listOf("on")) as ParsedArgs.Ok).value)
        assertEquals(false, (parseTorchArgs(listOf("off")) as ParsedArgs.Ok).value)
    }

    @Test
    fun `torch help variants return Help`() {
        assertTrue(parseTorchArgs(listOf("-h")) is ParsedArgs.Help)
        assertTrue(parseTorchArgs(listOf("--help")) is ParsedArgs.Help)
    }

    @Test
    fun `torch unknown single arg is invalid`() {
        val r = parseTorchArgs(listOf("blink"))
        assertTrue(r is ParsedArgs.Invalid)
        assertEquals("illegal parameter: blink", (r as ParsedArgs.Invalid).message)
    }

    @Test
    fun `torch is case sensitive`() {
        assertTrue(parseTorchArgs(listOf("ON")) is ParsedArgs.Invalid)
    }

    @Test
    fun `torch wrong arg count is invalid`() {
        val empty = parseTorchArgs(emptyList())
        assertTrue(empty is ParsedArgs.Invalid)
        assertEquals("illegal param count", (empty as ParsedArgs.Invalid).message)
        assertTrue(parseTorchArgs(listOf("on", "off")) is ParsedArgs.Invalid)
    }

    // -- termux-battery-status string mappers ---------------------------------
    // BatteryManager values (inlined constants): health GOOD=2 OVERHEAT=3 DEAD=4
    // OVER_VOLTAGE=5 UNSPECIFIED_FAILURE=6 COLD=7 UNKNOWN=1; plugged AC=1 USB=2
    // WIRELESS=4 DOCK=8; status CHARGING=2 DISCHARGING=3 NOT_CHARGING=4 FULL=5.

    @Test
    fun `battery health maps every known constant`() {
        val cases = mapOf(
            7 to "COLD",
            4 to "DEAD",
            2 to "GOOD",
            3 to "OVERHEAT",
            5 to "OVER_VOLTAGE",
            1 to "UNKNOWN",
            6 to "UNSPECIFIED_FAILURE",
        )
        for ((code, expected) in cases) {
            assertEquals(expected, batteryHealthString(code))
        }
    }

    @Test
    fun `battery health unknown code falls back to the raw number`() {
        assertEquals("99", batteryHealthString(99))
    }

    @Test
    fun `battery plugged maps every known constant`() {
        val cases = mapOf(
            0 to "UNPLUGGED",
            1 to "PLUGGED_AC",
            8 to "PLUGGED_DOCK",
            2 to "PLUGGED_USB",
            4 to "PLUGGED_WIRELESS",
        )
        for ((code, expected) in cases) {
            assertEquals(expected, batteryPluggedString(code))
        }
    }

    @Test
    fun `battery plugged unknown code keeps the number`() {
        assertEquals("PLUGGED_16", batteryPluggedString(16))
    }

    @Test
    fun `battery status maps every known constant`() {
        val cases = mapOf(
            2 to "CHARGING",
            3 to "DISCHARGING",
            5 to "FULL",
            4 to "NOT_CHARGING",
        )
        for ((code, expected) in cases) {
            assertEquals(expected, batteryStatusString(code))
        }
    }

    @Test
    fun `battery status unknown code reports UNKNOWN`() {
        assertEquals("UNKNOWN", batteryStatusString(1))
        assertEquals("UNKNOWN", batteryStatusString(-1))
    }

    // -- 请求协议: 空参数哨兵 --------------------------------------------------
    // 协议行为见 parseApiRequestLine / TermuxApiShims.scriptFor:
    // base64("") 是空串会被字段过滤吞掉，shim 端因此把空参数编码为 "-"。

    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `request line decodes base64 args`() {
        val req = parseApiRequestLine("tok termux-toast ${b64("hello world")}")
        assertEquals("tok", req!!.token)
        assertEquals("termux-toast", req.command)
        assertEquals(listOf("hello world"), req.args)
    }

    @Test
    fun `request line restores sentinel to empty string arg`() {
        // termux-notification -t "" -c "body": 空标题必须保留在位，
        // 否则 -c 的值会前移成标题。
        val req = parseApiRequestLine(
            "tok termux-notification ${b64("-t")} - ${b64("-c")} ${b64("body")}"
        )
        assertEquals(listOf("-t", "", "-c", "body"), req!!.args)
    }

    @Test
    fun `request line keeps real dash arg distinct from sentinel`() {
        // 真实的 "-" 参数被 shim 编码成 "LQ=="，不会与哨兵混淆。
        val req = parseApiRequestLine("tok termux-toast ${b64("-")}")
        assertEquals(listOf("-"), req!!.args)
    }

    @Test
    fun `request line rejects invalid base64`() {
        assertNull(parseApiRequestLine("tok cmd !!!notbase64!!!"))
    }
}
