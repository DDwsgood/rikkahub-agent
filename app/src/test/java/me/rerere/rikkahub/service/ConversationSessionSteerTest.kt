package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Covers the ConversationSession steer queue used by "send while generating": the
 * transcript is the source of truth, the queue is only a delivery hint, and drainSteer
 * is the atomic claim that decides who (in-loop boundary vs post-turn continuation)
 * consumes a leftover.
 */
class ConversationSessionSteerTest {

    private fun newSession(id: Uuid = Uuid.random()): ConversationSession =
        ConversationSession(
            id = id,
            initial = Conversation.ofId(id = id, assistantId = Uuid.random()),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onIdle = {},
        )

    @Test
    fun `drainSteer returns messages in FIFO order and empties the queue`() {
        val session = newSession()
        val first = UIMessage.user("first")
        val second = UIMessage.user("second")
        session.enqueueSteer(first)
        session.enqueueSteer(second)

        assertEquals(listOf(first, second), session.drainSteer())
        assertTrue(session.drainSteer().isEmpty())
    }

    @Test
    fun `clearSteerQueue drops stranded entries`() {
        val session = newSession()
        session.enqueueSteer(UIMessage.user("abandoned by a cancelled turn"))

        session.clearSteerQueue()

        assertTrue(session.drainSteer().isEmpty())
    }

    @Test
    fun `concurrent drainers never double-claim an entry`() = runBlocking {
        val session = newSession()
        repeat(20) { session.enqueueSteer(UIMessage.user("m$it")) }

        val drained = mutableListOf<UIMessage>()
        // Hammer the queue from several coroutines on a shared mutable list; every
        // claimed message must appear exactly once across all drains.
        kotlinx.coroutines.coroutineScope {
            repeat(4) {
                launch(Dispatchers.Default) {
                    repeat(10) {
                        synchronized(drained) { drained += session.drainSteer() }
                    }
                }
            }
        }

        assertEquals(20, drained.size)
        assertEquals(20, drained.map { it.id }.toSet().size)
    }
}
