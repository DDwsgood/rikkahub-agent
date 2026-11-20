package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAppendChunkTest {

    private fun seedMessages(): List<UIMessage> = listOf(
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("generate an image")),
        )
    )

    private fun imageChunk(url: String) = MessageChunk(
        id = "chunk-1",
        model = "gemini-image",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Image(url = url)),
                ),
                message = null,
                finishReason = null,
            )
        ),
    )

    @Test
    fun preservesFullDataUriWithoutDoublingPrefix() {
        val fullUri = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
        val result = seedMessages().handleMessageChunk(imageChunk(fullUri))
        val img = result.last().parts.single() as UIMessagePart.Image
        assertEquals(fullUri, img.url)
    }

    @Test
    fun concatenatesRawBase64FragmentsIntoSingleImagePart() {
        val afterFirst = seedMessages().handleMessageChunk(imageChunk("iVBORw0K"))
        val afterSecond = afterFirst.handleMessageChunk(imageChunk("GgoAAAANS"))
        val parts = afterSecond.last().parts
        assertEquals(1, parts.size)
        val img = parts.single() as UIMessagePart.Image
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANS", img.url)
    }

    @Test
    fun separatesTwoConsecutiveFullUriImagesIntoDistinctParts() {
        val uri1 = "data:image/png;base64,AAA="
        val uri2 = "data:image/png;base64,BBB="
        val result = seedMessages()
            .handleMessageChunk(imageChunk(uri1))
            .handleMessageChunk(imageChunk(uri2))
        val parts = result.last().parts
        assertEquals(2, parts.size)
        assertEquals(uri1, (parts[0] as UIMessagePart.Image).url)
        assertEquals(uri2, (parts[1] as UIMessagePart.Image).url)
    }
}
