package me.rerere.rikkahub.ui.components.richtext

import java.net.URI

internal sealed interface RichBlock {
    data class Markdown(val text: String) : RichBlock
    data class Widget(val html: String, val complete: Boolean) : RichBlock
    data class KpiGrid(val items: List<KpiItem>) : RichBlock
    data class MediaCard(
        val img: String,
        val query: String,
        val ratio: String,
        val title: String,
        val subtitle: String,
        val href: String,
        val source: String,
        val body: String,
    ) : RichBlock

    data class Gallery(val photos: List<GalleryPhoto>) : RichBlock
    data class LinkCard(
        val url: String,
        val title: String,
        val desc: String,
        val img: String,
    ) : RichBlock

    data class VideoCard(
        val url: String,
        val title: String,
        val source: String,
        val thumb: String,
    ) : RichBlock
}

internal data class KpiItem(
    val label: String,
    val value: String,
    val sub: String,
)

internal data class GalleryPhoto(
    val src: String,
    val query: String,
    val caption: String,
    val source: String,
)

internal object RichContentParser {
    private val QUICK_MARKER_REGEX = Regex(
        """```rikka-ui|<kpi-grid\b|<media-card\b|<gallery\b|<link-card\b|<video-card\b|<citation\b""",
        RegexOption.IGNORE_CASE,
    )

    private val CITATION_WRAPPER_REGEX = Regex(
        """<citation>\s*\(?(\[citation,[^\]]+]\([^)]+\))\)?\s*</citation>""",
        RegexOption.IGNORE_CASE,
    )

    private val FENCE_START_REGEX = Regex("""^(`{3,}|~{3,})([^\n]*)$""")

    private val BLOCK_OPEN_REGEX = Regex(
        """^<(kpi-grid|media-card|gallery|link-card|video-card)\b([^>]*?)(/?)>(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val KPI_ITEM_REGEX = Regex("""<kpi\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val PHOTO_ITEM_REGEX = Regex("""<photo\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val YT_ID_REGEX = Regex("""(?:youtu\.be/|[?&]v=|/embed/|/shorts/)([\w-]{11})""")

    fun hasRichBlocks(text: String): Boolean = QUICK_MARKER_REGEX.containsMatchIn(text)

    fun unwrapCitationWrappers(text: String): String =
        if (text.contains("<citation", ignoreCase = true)) {
            CITATION_WRAPPER_REGEX.replace(text, "$1")
        } else {
            text
        }

    fun normalizeExternalUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return ""
        return upgradeKnownThumbnail(trimmed)
    }

    fun upgradeKnownThumbnail(url: String): String {
        if (url.contains("hdslb.com", ignoreCase = true)) {
            return url.replace(Regex("""@\d+w_\d+h(?:_1c)?\.[a-z]+$""", RegexOption.IGNORE_CASE), "@672w_378h_1c.webp")
        }
        return url
    }

    fun resolveVideoThumb(url: String, thumb: String?): String {
        val explicit = normalizeExternalUrl(thumb)
        if (explicit.isNotEmpty()) return explicit
        val ytMatch = YT_ID_REGEX.find(url)
        if (ytMatch != null) {
            return "https://i.ytimg.com/vi/${ytMatch.groupValues[1]}/hqdefault.jpg"
        }
        return ""
    }

    fun extractDomain(url: String): String {
        return runCatching {
            val host = URI(url).host?.lowercase().orEmpty()
            host.removePrefix("www.")
        }.getOrDefault("")
    }

    /**
     * Tolerant HTML attribute parser that handles normal single/double quotes and
     * survives accidental embedded ASCII quotes inside CJK attribute values.
     */
    fun parseAttrs(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val s = raw.trim()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
            if (i == nameStart) {
                i++
                continue
            }
            val key = s.substring(nameStart, i).lowercase()
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n || s[i] != '=') {
                result[key] = "true"
                continue
            }
            i++ // skip '='
            while (i < n && s[i].isWhitespace()) i++
            if (i >= n) break
            val quote = s[i]
            if (quote == '"' || quote == '\'') {
                i++
                val valStart = i
                var endIdx = -1
                var scan = i
                while (scan < n) {
                    if (s[scan] == quote) {
                        val after = scan + 1
                        if (after >= n || s[after].isWhitespace() || s[after] == '/' || s[after] == '>') {
                            // Verify what follows looks like either end or another attr
                            val rest = s.substring(after).trimStart()
                            if (rest.isEmpty() || rest.startsWith("/") || rest.startsWith(">") ||
                                Regex("""^[a-zA-Z_][\w-]*\s*=""").containsMatchIn(rest)
                            ) {
                                endIdx = scan
                                break
                            }
                        }
                    }
                    scan++
                }
                if (endIdx < 0) {
                    endIdx = s.indexOf(quote, valStart).takeIf { it >= 0 } ?: n
                }
                result[key] = decodeHtmlEntities(s.substring(valStart, endIdx).trim())
                i = (endIdx + 1).coerceAtMost(n)
            } else {
                val valStart = i
                while (i < n && !s[i].isWhitespace() && s[i] != '>') i++
                result[key] = decodeHtmlEntities(s.substring(valStart, i).trim())
            }
        }
        return result
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    fun parse(rawInput: String, streaming: Boolean = false): List<RichBlock> {
        val cleaned = unwrapCitationWrappers(rawInput)
        if (!hasRichBlocks(cleaned)) {
            return if (cleaned.isEmpty()) emptyList() else listOf(RichBlock.Markdown(cleaned))
        }

        val lines = cleaned.split("\n")
        val blocks = mutableListOf<RichBlock>()
        val mdBuf = StringBuilder()

        fun flushMarkdown() {
            val md = mdBuf.toString()
            if (md.isNotBlank()) {
                blocks.add(RichBlock.Markdown(md.trim('\n')))
            }
            mdBuf.setLength(0)
        }

        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            val trimmed = line.trim()

            // 1. Check fenced code blocks (track non-rikka-ui fences so tags inside code examples are ignored)
            val fenceMatch = FENCE_START_REGEX.matchEntire(trimmed)
            if (fenceMatch != null) {
                val fenceChars = fenceMatch.groupValues[1]
                val fenceChar = fenceChars[0]
                val fenceLen = fenceChars.length
                val info = fenceMatch.groupValues[2].trim().lowercase()

                if (info == "rikka-ui" || info.startsWith("rikka-ui ")) {
                    flushMarkdown()
                    val widgetBuf = StringBuilder()
                    var closed = false
                    idx++
                    while (idx < lines.size) {
                        val cur = lines[idx]
                        val curTrim = cur.trim()
                        if (curTrim.length >= fenceLen && curTrim.all { it == fenceChar }) {
                            closed = true
                            idx++
                            break
                        }
                        if (widgetBuf.isNotEmpty()) widgetBuf.append('\n')
                        widgetBuf.append(cur)
                        idx++
                    }
                    blocks.add(RichBlock.Widget(html = widgetBuf.toString(), complete = closed || !streaming))
                    continue
                } else {
                    // Copy regular code block verbatim into markdown buffer
                    mdBuf.append(line).append('\n')
                    idx++
                    while (idx < lines.size) {
                        val cur = lines[idx]
                        mdBuf.append(cur).append('\n')
                        val curTrim = cur.trim()
                        idx++
                        if (curTrim.length >= fenceLen && curTrim.all { it == fenceChar }) {
                            break
                        }
                    }
                    continue
                }
            }

            // 2. Check custom Layer 1 rich block tags
            val openMatch = BLOCK_OPEN_REGEX.matchEntire(trimmed)
            if (openMatch != null) {
                val tag = openMatch.groupValues[1].lowercase()
                val rawAttrs = openMatch.groupValues[2]
                val selfClosing = openMatch.groupValues[3] == "/"
                val tail = openMatch.groupValues[4]
                val attrs = parseAttrs(rawAttrs)

                if (selfClosing || tag == "link-card" || tag == "video-card") {
                    val closingTag = "</$tag>"
                    val hasInlineClose = tail.contains(closingTag, ignoreCase = true)
                    var extraIdx = idx + 1
                    if (!selfClosing && !hasInlineClose && (tag == "link-card" || tag == "video-card")) {
                        // Consume optional closing tag on next line if present
                        if (extraIdx < lines.size && lines[extraIdx].trim().equals(closingTag, ignoreCase = true)) {
                            extraIdx++
                        }
                    }
                    val block = when (tag) {
                        "link-card" -> buildLinkCard(attrs)
                        "video-card" -> buildVideoCard(attrs)
                        else -> null
                    }
                    if (block != null) {
                        flushMarkdown()
                        blocks.add(block)
                        idx = extraIdx
                        continue
                    }
                } else {
                    // Multi-line container: kpi-grid, media-card, gallery
                    val closeToken = "</$tag>"
                    val closePosInline = tail.indexOf(closeToken, ignoreCase = true)
                    val innerBuf = StringBuilder()
                    var closed = false
                    var nextIdx = idx + 1

                    if (closePosInline >= 0) {
                        innerBuf.append(tail.substring(0, closePosInline))
                        closed = true
                    } else {
                        if (tail.isNotEmpty()) innerBuf.append(tail).append('\n')
                        while (nextIdx < lines.size) {
                            val cur = lines[nextIdx]
                            val pos = cur.indexOf(closeToken, ignoreCase = true)
                            if (pos >= 0) {
                                innerBuf.append(cur.substring(0, pos))
                                closed = true
                                nextIdx++
                                break
                            }
                            innerBuf.append(cur).append('\n')
                            nextIdx++
                        }
                    }

                    if (!closed && streaming) {
                        // Hide incomplete opening rich block while streaming so raw XML doesn't flash
                        idx = nextIdx
                        continue
                    }

                    val inner = innerBuf.toString().trim()
                    val parsedBlock = when (tag) {
                        "kpi-grid" -> parseKpiGrid(inner)
                        "gallery" -> parseGallery(inner)
                        "media-card" -> buildMediaCard(attrs, inner)
                        else -> null
                    }
                    if (parsedBlock != null) {
                        flushMarkdown()
                        blocks.add(parsedBlock)
                        idx = nextIdx
                        continue
                    }
                }
            }

            mdBuf.append(line).append('\n')
            idx++
        }

        flushMarkdown()
        return blocks
    }

    private fun parseKpiGrid(inner: String): RichBlock.KpiGrid? {
        val items = KPI_ITEM_REGEX.findAll(inner).mapNotNull { match ->
            val a = parseAttrs(match.groupValues[1])
            val label = a["label"].orEmpty()
            val value = a["value"].orEmpty()
            if (label.isEmpty() && value.isEmpty()) null
            else KpiItem(label = label, value = value, sub = a["sub"].orEmpty())
        }.toList()
        return items.takeIf { it.isNotEmpty() }?.let { RichBlock.KpiGrid(it) }
    }

    private fun parseGallery(inner: String): RichBlock.Gallery? {
        val photos = PHOTO_ITEM_REGEX.findAll(inner).mapNotNull { match ->
            val a = parseAttrs(match.groupValues[1])
            val src = normalizeExternalUrl(a["src"] ?: a["img"])
            val query = a["query"].orEmpty()
            if (src.isEmpty() && query.isEmpty()) null
            else GalleryPhoto(
                src = src,
                query = query,
                caption = a["caption"] ?: a["title"].orEmpty(),
                source = a["source"].orEmpty().ifEmpty { extractDomain(src) },
            )
        }.toList()
        return photos.takeIf { it.isNotEmpty() }?.let { RichBlock.Gallery(it) }
    }

    private fun buildMediaCard(attrs: Map<String, String>, body: String): RichBlock.MediaCard? {
        val title = attrs["title"].orEmpty()
        val subtitle = attrs["subtitle"].orEmpty()
        val img = normalizeExternalUrl(attrs["img"] ?: attrs["src"])
        val query = attrs["query"].orEmpty()
        val href = normalizeExternalUrl(attrs["href"] ?: attrs["url"])
        val source = attrs["source"].orEmpty().ifEmpty { extractDomain(href) }
        val ratio = attrs["ratio"].orEmpty().ifEmpty { "4:3" }
        if (title.isEmpty() && body.isEmpty() && img.isEmpty()) return null
        return RichBlock.MediaCard(
            img = img,
            query = query,
            ratio = ratio,
            title = title,
            subtitle = subtitle,
            href = href,
            source = source,
            body = body,
        )
    }

    private fun buildLinkCard(attrs: Map<String, String>): RichBlock.LinkCard? {
        val url = normalizeExternalUrl(attrs["url"] ?: attrs["href"])
        if (url.isEmpty()) return null
        val title = attrs["title"].orEmpty().ifEmpty { extractDomain(url) }
        return RichBlock.LinkCard(
            url = url,
            title = title,
            desc = attrs["desc"] ?: attrs["description"].orEmpty(),
            img = normalizeExternalUrl(attrs["img"] ?: attrs["thumb"]),
        )
    }

    private fun buildVideoCard(attrs: Map<String, String>): RichBlock.VideoCard? {
        val url = normalizeExternalUrl(attrs["url"] ?: attrs["href"])
        if (url.isEmpty()) return null
        val title = attrs["title"].orEmpty().ifEmpty { extractDomain(url) }
        val source = attrs["source"].orEmpty().ifEmpty { extractDomain(url) }
        val thumb = resolveVideoThumb(url, attrs["thumb"] ?: attrs["img"])
        return RichBlock.VideoCard(
            url = url,
            title = title,
            source = source,
            thumb = thumb,
        )
    }
}
