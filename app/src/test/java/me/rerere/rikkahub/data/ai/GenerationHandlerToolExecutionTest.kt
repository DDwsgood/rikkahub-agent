package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerToolExecutionTest {

    @Test
    fun maxSteps_defaultIs500() {
        assertEquals(500, TermuxDefaults.DEFAULT_MAX_TOOL_STEPS)
        assertEquals(TermuxDefaults.DEFAULT_MAX_TOOL_STEPS, ToolRuntimeLimits.maxToolSteps)
    }

    @Test
    fun maxSteps_zeroMeansUnlimited() {
        assertTrue(hasToolStepBudgetRemaining(stepIndex = 0, maxSteps = 0))
        assertTrue(hasToolStepBudgetRemaining(stepIndex = Int.MAX_VALUE, maxSteps = 0))
    }

    @Test
    fun maxSteps_positiveValueStillBoundsTheTurn() {
        assertTrue(hasToolStepBudgetRemaining(stepIndex = 499, maxSteps = 500))
        assertFalse(hasToolStepBudgetRemaining(stepIndex = 500, maxSteps = 500))
    }
}
