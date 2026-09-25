package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * mergeSteeredMessages pins the steer-merge contract used at GenerationHandler's
 * per-step boundary: steered user messages append after the running assistant message,
 * and a message present in both the transcript snapshot and the steer queue (the
 * clear-vs-snapshot race window) is injected exactly once.
 */
class GenerationHandlerSteerTest {

    @Test
    fun `steered messages append after the running assistant message`() {
        val user = UIMessage.user("original question")
        val assistant = UIMessage.assistant("working on it...")
        val steer = UIMessage.user("actually do X instead")

        val merged = mergeSteeredMessages(listOf(user, assistant), listOf(steer))

        assertEquals(3, merged.size)
        assertEquals(assistant, merged[1])
        assertEquals(steer, merged[2])
        assertEquals(MessageRole.USER, merged.last().role)
    }

    @Test
    fun `message already in the snapshot is not duplicated`() {
        val user = UIMessage.user("original")
        val steer = UIMessage.user("steer")
        // Snapshot read happened between the transcript append and the enqueue:
        // the same UIMessage instance/id shows up in both inputs.
        val snapshot = listOf(user, UIMessage.assistant("partial"), steer)

        val merged = mergeSteeredMessages(snapshot, listOf(steer))

        assertEquals(3, merged.size)
        assertEquals(listOf(steer.id), merged.filter { it.id == steer.id }.map { it.id })
    }

    @Test
    fun `multiple steers keep arrival order`() {
        val s1 = UIMessage.user("one")
        val s2 = UIMessage.user("two")

        val merged = mergeSteeredMessages(listOf(UIMessage.user("q")), listOf(s1, s2))

        assertEquals(listOf(s1, s2), merged.takeLast(2))
    }

    @Test
    fun `empty drain leaves messages untouched`() {
        val messages = listOf(UIMessage.user("q"), UIMessage.assistant("a"))
        assertEquals(messages, mergeSteeredMessages(messages, emptyList()))
    }
}
