package me.rerere.rikkahub.utils

import me.rerere.rikkahub.utils.RomUtils.Aggressiveness
import me.rerere.rikkahub.utils.RomUtils.Rom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure part of [RomUtils] — [RomUtils.RomInfo.needsWarning].
 *
 * `RomUtils.detect()` itself is NOT covered: it reads real system properties via
 * SystemProperties reflection / /system/build.prop / a `getprop` subprocess with
 * no injectable seam, and on the JVM every lookup yields "" until the
 * `Build.MANUFACTURER` check, which is null under the unit-test android.jar.
 * Covering it would require Robolectric or refactoring prod code — out of scope.
 */
class RomUtilsTest {

    private fun info(aggressiveness: Aggressiveness) =
        RomUtils.RomInfo(Rom.OTHER, "Test ROM", "", aggressiveness)

    @Test
    fun `needsWarning is false for NONE and MODERATE`() {
        assertFalse(info(Aggressiveness.NONE).needsWarning)
        assertFalse(info(Aggressiveness.MODERATE).needsWarning)
    }

    @Test
    fun `needsWarning is true for HIGH and SEVERE`() {
        assertTrue(info(Aggressiveness.HIGH).needsWarning)
        assertTrue(info(Aggressiveness.SEVERE).needsWarning)
    }

    @Test
    fun `aggressiveness ordering matches the warning threshold`() {
        // needsWarning relies on enum ordinal order; pin that HIGH and SEVERE sit
        // above MODERATE so a future reorder can't silently flip the gate.
        assertTrue(Aggressiveness.HIGH > Aggressiveness.MODERATE)
        assertTrue(Aggressiveness.SEVERE > Aggressiveness.HIGH)
    }
}
