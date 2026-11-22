package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationRepositoryCancellationTest {
    @Test
    fun `cancellation escapes the shared page and single row reader without recovery`() {
        val cancellation = CancellationException("stop loading nodes")

        val caught = assertThrows(CancellationException::class.java) {
            readMessageNodePageOrNull<Unit>(
                readPage = { throw cancellation },
                onUnreadable = { error("Cancellation must not trigger recovery") },
            )
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `unreadable cursor still signals recovery`() {
        val cursorFailure = IllegalStateException("bad cursor")
        var reported: RuntimeException? = null

        val result = readMessageNodePageOrNull<Unit>(
            readPage = { throw cursorFailure },
            onUnreadable = { reported = it },
        )

        assertNull(result)
        assertSame(cursorFailure, reported)
    }
}
