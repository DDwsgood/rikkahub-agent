package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRequestValidationTest {

    @Test fun `blank task rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = ""))
        assertEquals("invalid_task", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `whitespace-only task rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "   "))
        assertEquals("invalid_task", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `valid request accepted with trimmed task`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "  go research X  "))
        val ok = r as SubAgentRequestValidator.Result.Ok
        assertEquals("go research X", ok.request.task)
    }

    @Test fun `timeout below 1 rejected`() {
        for (timeout in listOf(0, -1)) {
            val result = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", timeoutSeconds = timeout))
            assertEquals("invalid_timeout", (result as SubAgentRequestValidator.Result.Reject).error)
        }
    }

    @Test fun `positive timeouts above the old cap are accepted without clamping`() {
        for (timeout in listOf(1_801, Int.MAX_VALUE)) {
            val result = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", timeoutSeconds = timeout))
            assertEquals(timeout, (result as SubAgentRequestValidator.Result.Ok).request.timeoutSeconds)
        }
    }

    @Test fun `only an omitted tool timeout uses the default`() {
        assertEquals(300, SubAgentRequest(task = "x").timeoutSeconds)
        assertEquals(SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS, parseSubAgentTimeoutSeconds(null))
        assertEquals(Int.MAX_VALUE, parseSubAgentTimeoutSeconds(JsonPrimitive(Int.MAX_VALUE)))
        val invalidValues = listOf(
            JsonPrimitive(Int.MAX_VALUE.toLong() + 1),
            JsonPrimitive(1.5),
            JsonPrimitive("300"),
            JsonPrimitive(true),
            JsonNull,
            JsonArray(emptyList()),
            JsonObject(emptyMap()),
        )
        for (value in invalidValues) {
            assertNull("must reject $value instead of silently using the default", parseSubAgentTimeoutSeconds(value))
        }
    }

    @Test fun `max trips below 1 rejected`() {
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", maxTrips = 0))
        assertEquals("invalid_max_trips", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `max trips above 30 rejected`() {
        val r = SubAgentRequestValidator.validate(
            SubAgentRequest(task = "x", maxTrips = SubAgentDefaults.MAX_MAX_TRIPS + 1)
        )
        assertEquals("invalid_max_trips", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `oversize label rejected`() {
        val long = "x".repeat(SubAgentDefaults.MAX_LABEL_LENGTH + 1)
        val r = SubAgentRequestValidator.validate(SubAgentRequest(task = "x", label = long))
        assertEquals("invalid_label", (r as SubAgentRequestValidator.Result.Reject).error)
    }

    @Test fun `boundary values accepted`() {
        val ok = SubAgentRequestValidator.validate(
            SubAgentRequest(
                task = "x",
                timeoutSeconds = 1,
                maxTrips = SubAgentDefaults.MAX_MAX_TRIPS,
                label = "x".repeat(SubAgentDefaults.MAX_LABEL_LENGTH),
            )
        )
        assertTrue(ok is SubAgentRequestValidator.Result.Ok)
    }
}
