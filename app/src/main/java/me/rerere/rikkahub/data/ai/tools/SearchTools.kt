package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.richtext.MediaQueryResolver
import me.rerere.rikkahub.ui.components.richtext.RichContentParser
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.time.LocalDate
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information. Use focused
                    keywords; run multiple searches if needed. Today is ${LocalDate.now().toLocalString(true)}.
                    Response: items[].id/title/url/text, images[] (may be empty).
                    Citations: append `[citation,domain](id)` after sentences that use
                    results; omit if none cited. Images: embed 2-4 relevant images[]
                    urls with Markdown `![](url)`; never fabricate urls.
                    Example: The capital of France is Paris. [citation,example.com](abc123)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        add(
            Tool(
                name = "search_media",
                description = """
                    Search verified real images (book covers, scenery, products) or YouTube/Bilibili videos.
                    Returns real URLs and thumbnails for <media-card>, <gallery>, and <video-card>.
                    Never fabricate image or video URLs.
                """.trimIndent(),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Focused search keywords (English for books/western topics, native keywords for landmarks)")
                            })
                            put("kind", buildJsonObject {
                                put("type", "string")
                                put("description", "Media type: image or video")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("image"))
                                    add(JsonPrimitive("video"))
                                })
                            })
                        },
                        required = listOf("query", "kind"),
                    )
                },
                execute = { input ->
                    val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val kind = input.jsonObject["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "image"
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT },
                    )
                    val service = SearchService.getService(options)
                    val items = mutableListOf<JsonObject>()
                    val seen = mutableSetOf<String>()

                    if (kind == "video") {
                        val searchQuery = if (query.contains("youtube", true) || query.contains("bilibili", true)) {
                            query
                        } else {
                            "$query youtube OR bilibili"
                        }
                        val res = runCatching {
                            service.search(
                                params = buildJsonObject { put("query", JsonPrimitive(searchQuery)) },
                                commonOptions = SearchCommonOptions(resultSize = 6),
                                serviceOptions = options,
                            ).getOrNull()
                        }.getOrNull()
                        res?.items?.forEachIndexed { idx, item ->
                            val url = RichContentParser.normalizeExternalUrl(item.url)
                            val lower = url.lowercase()
                            val isVideo = lower.contains("youtube.com/watch") ||
                                lower.contains("youtu.be/") ||
                                lower.contains("bilibili.com/video/")
                            if (isVideo && seen.add(url)) {
                                val rawThumb = res.images.getOrNull(idx)
                                val thumb = RichContentParser.resolveVideoThumb(url, rawThumb)
                                val source = when {
                                    lower.contains("bilibili.com") -> "Bilibili"
                                    lower.contains("youtu") -> "YouTube"
                                    else -> RichContentParser.extractDomain(url)
                                }
                                items.add(buildJsonObject {
                                    put("title", JsonPrimitive(item.title))
                                    put("url", JsonPrimitive(url))
                                    put("source", JsonPrimitive(source))
                                    if (thumb.isNotEmpty()) put("thumb", JsonPrimitive(thumb))
                                })
                            }
                        }
                    } else {
                        // 1. Keyless high-res covers / encyclopedia photos (Open Library + Wikipedia)
                        val keyless = runCatching { MediaQueryResolver.fetchKeylessImage(query) }.getOrNull()
                        if (!keyless.isNullOrEmpty() && seen.add(keyless)) {
                            items.add(buildJsonObject {
                                put("url", JsonPrimitive(keyless))
                                put("title", JsonPrimitive(query))
                                put("source", JsonPrimitive(RichContentParser.extractDomain(keyless)))
                            })
                        }
                        // 2. Configured SearchService images (Exa / Tavily / etc.)
                        val res = runCatching {
                            service.search(
                                params = buildJsonObject { put("query", JsonPrimitive(query)) },
                                commonOptions = SearchCommonOptions(resultSize = 6),
                                serviceOptions = options,
                            ).getOrNull()
                        }.getOrNull()
                        res?.images?.forEach { rawImg ->
                            val img = RichContentParser.normalizeExternalUrl(rawImg)
                            if (img.isNotEmpty() && !img.endsWith(".svg", true) && seen.add(img)) {
                                items.add(buildJsonObject {
                                    put("url", JsonPrimitive(img))
                                    put("title", JsonPrimitive(query))
                                    put("source", JsonPrimitive(RichContentParser.extractDomain(img)))
                                })
                            }
                        }
                    }

                    val payload = buildJsonObject {
                        put("kind", JsonPrimitive(kind))
                        put("query", JsonPrimitive(query))
                        put("items", JsonArray(items.take(6)))
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                },
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}
