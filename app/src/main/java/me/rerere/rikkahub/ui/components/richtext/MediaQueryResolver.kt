package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lightweight keyless media fallback resolver for `<media-card query="...">` / `<photo query="...">`
 * when the model omits a direct image URL. Strictly gated on [allowNetwork] so offline chats
 * never initiate outbound requests, and persisted to cacheDir so reopening a conversation never
 * re-fetches or swaps images.
 */
internal object MediaQueryResolver {
    private const val CACHE_FILE = "rikka_media_query_cache.json"
    private const val MAX_CACHE_ENTRIES = 256

    private val memoryCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var diskLoaded = false

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun resolveImage(context: Context, query: String, allowNetwork: Boolean): String? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        ensureLoaded(context)
        memoryCache[key]?.let { cached ->
            return cached.ifEmpty { null }
        }
        if (!allowNetwork) return null

        val resolved = fetchKeylessImage(query)

        val value = resolved.orEmpty()
        memoryCache[key] = value
        persistAsync(context)
        return resolved
    }

    suspend fun fetchKeylessImage(query: String): String? = withContext(Dispatchers.IO) {
        fetchOpenLibraryCover(query)
            ?: fetchWikipediaThumb(query, "en")
            ?: fetchWikipediaThumb(query, "zh")
            ?: fetchWikipediaThumb(query, "ja")
    }

    private fun ensureLoaded(context: Context) {
        if (diskLoaded) return
        synchronized(this) {
            if (diskLoaded) return
            runCatching {
                val file = File(context.cacheDir, CACHE_FILE)
                if (file.isFile) {
                    val obj = JSONObject(file.readText())
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        memoryCache[k] = obj.optString(k, "")
                    }
                }
            }
            diskLoaded = true
        }
    }

    private fun persistAsync(context: Context) {
        runCatching {
            val file = File(context.cacheDir, CACHE_FILE)
            val obj = JSONObject()
            memoryCache.entries.take(MAX_CACHE_ENTRIES).forEach { (k, v) ->
                obj.put(k, v)
            }
            file.writeText(obj.toString())
        }
    }

    private fun fetchOpenLibraryCover(query: String): String? = runCatching {
        val cleaned = query
            .replace(Regex("""\b(book cover|cover art|novel cover|novel|book|series|volume \d+)\b""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { query }
        val encoded = URLEncoder.encode(cleaned, "UTF-8")
        val req = Request.Builder()
            .url("https://openlibrary.org/search.json?q=$encoded&limit=3&fields=cover_i,title")
            .header("User-Agent", "RikkaHub/2.5 (Android)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body.string()
            val docs = json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray ?: return@runCatching null
            val coverId = docs.firstNotNullOfOrNull { it.jsonObject["cover_i"]?.jsonPrimitive?.intOrNull }
                ?: return@runCatching null
            "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
        }
    }.getOrNull()

    private fun fetchWikipediaThumb(query: String, lang: String): String? = runCatching {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchReq = Request.Builder()
            .url("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=1&format=json")
            .header("User-Agent", "RikkaHub/2.5 (Android)")
            .build()
        val title = client.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val arr = json.parseToJsonElement(resp.body.string())
                .jsonObject["query"]?.jsonObject?.get("search")?.jsonArray
                ?: return@runCatching null
            arr.firstOrNull()?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        } ?: return@runCatching null

        val encodedTitle = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val summaryReq = Request.Builder()
            .url("https://$lang.wikipedia.org/api/rest_v1/page/summary/$encodedTitle")
            .header("User-Agent", "RikkaHub/2.5 (Android)")
            .build()
        client.newCall(summaryReq).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
            val url = obj["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
                ?: obj["originalimage"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
            url?.takeIf { it.startsWith("https://") && !it.endsWith(".svg", ignoreCase = true) }
        }
    }.getOrNull()
}
