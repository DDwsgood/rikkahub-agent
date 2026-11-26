package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentParserTest {
    @Test
    fun `parses mixed markdown, kpi-grid, media-card, gallery, link-card, video-card and rikka-ui`() {
        val input = """
            先看结论 [citation,example.com](abc123)。

            <kpi-grid>
              <kpi label="2024众筹" value="127.5万美元" sub="8,224人" />
              <kpi label="2026特别版" value="210.5万美元" sub="6,501人" />
            </kpi-grid>

            <media-card img="https://covers.openlibrary.org/b/id/1-L.jpg" ratio="2:3" title="Cradle" subtitle="Will Wight" href="https://willwight.com" source="willwight.com">
            东方修炼体系改编代表作 <badge tone="ok">神作</badge>。
            </media-card>

            <link-card url="https://www.royalroad.com" title="Royal Road" desc="西方网文平台" />
            <video-card url="https://www.youtube.com/watch?v=dQw4w9WgXcQ" title="Intro Video" source="YouTube" />

            ```rikka-ui
            <div class="w-card">Hello</div>
            <script>console.log(1)</script>
            ```
        """.trimIndent()

        val blocks = RichContentParser.parse(input, streaming = false)
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is RichBlock.Markdown)
        val kpi = blocks[1] as RichBlock.KpiGrid
        assertEquals(2, kpi.items.size)
        assertEquals("127.5万美元", kpi.items[0].value)

        val media = blocks[2] as RichBlock.MediaCard
        assertEquals("Cradle", media.title)
        assertEquals("2:3", media.ratio)
        assertTrue(media.body.contains("<badge tone=\"ok\">"))

        val link = blocks[3] as RichBlock.LinkCard
        assertEquals("https://www.royalroad.com", link.url)

        val video = blocks[4] as RichBlock.VideoCard
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", video.thumb)

        val widget = blocks[5] as RichBlock.Widget
        assertTrue(widget.complete)
        assertTrue(widget.html.contains("w-card"))
    }

    @Test
    fun `handles unclosed streaming rikka-ui and hides incomplete media-card`() {
        val streaming = """
            正在生成：
            ```rikka-ui
            <div class="w-card">partial
        """.trimIndent()
        val blocks = RichContentParser.parse(streaming, streaming = true)
        assertEquals(2, blocks.size)
        val widget = blocks[1] as RichBlock.Widget
        assertFalse(widget.complete)
    }

    @Test
    fun `unwraps accidental citation tags and rejects non-http urls`() {
        val unwrapped = RichContentParser.unwrapCitationWrappers("结果<citation>([citation,exa.ai](a1b2c3))</citation>完成")
        assertEquals("结果[citation,exa.ai](a1b2c3)完成", unwrapped)
        assertEquals("", RichContentParser.normalizeExternalUrl("javascript:alert(1)"))
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/abc.jpg@672w_378h_1c.webp",
            RichContentParser.normalizeExternalUrl("https://i0.hdslb.com/bfs/archive/abc.jpg@100w_100h_1c.png"),
        )
    }

    @Test
    fun `tolerates embedded quotes inside CJK attribute values`() {
        val attrs = RichContentParser.parseAttrs("""src="https://a.com/x.jpg" caption="证明"经营"也能火" source="a.com"""")
        assertEquals("https://a.com/x.jpg", attrs["src"])
        assertEquals("证明\"经营\"也能火", attrs["caption"])
        assertEquals("a.com", attrs["source"])
    }
}
