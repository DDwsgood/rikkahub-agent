package me.rerere.rikkahub.ui.components.message

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.data.ai.IntelligentUi
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val PAGE = "https://rikka-ui.invalid/index.html"
private const val RESOURCE = "/static/intelligent-ui/index.html"

// Chromium may disallow ancestor interception even with no scroll range (e.g. over a chart).
// Leave taps and horizontal controls alone; release vertical drags to the Compose chat list.
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class InlineUiView(context: Context) : WebView(context) {
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val consumed = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = kotlin.math.abs(event.y - downY)
                if (dy > slop && dy > kotlin.math.abs(event.x - downX)) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return consumed
    }
}

@Keep
private class HeightBridge(private val onStatus: (String, Int) -> Unit) {
    @JavascriptInterface
    fun status(type: String, value: Int) = onStatus(type, value)
}

/** This WebView has no browser profile entry point, network access, tool bridge or file access. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun IntelligentUiPartOrNull(text: String, loading: Boolean): Boolean {
    val trimmed = text.trimStart()
    val marker = IntelligentUi.marker
    if (loading && "$marker\r".startsWith(trimmed)) return true
    if (!trimmed.startsWith("$marker\n") && !trimmed.startsWith("$marker\r\n")) return false

    InlineUiSurface(trimmed.substringAfter('\n'), loading)
    return true
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InlineUiSurface(source: String, loading: Boolean) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val lang = LocalConfiguration.current.locales[0].toLanguageTag()
    val sequence = remember { java.util.concurrent.atomic.AtomicInteger() }
    val revision = remember(source, loading, dark, lang) { sequence.incrementAndGet() }
    val payload = remember(revision) {
        JSONObject().put("source", source.take(65537)).put("done", !loading).put("dark", dark).put("lang", lang).put("revision", revision).toString()
    }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableIntStateOf(0) }
    var partial by remember { mutableStateOf(false) }
    LaunchedEffect(ready, failed) {
        if (!ready && !failed) { delay(10000); failed = true }
    }
    LaunchedEffect(ready, revision, acknowledged, failed) {
        if (ready && !loading && acknowledged != revision && !failed) { delay(10000); failed = true }
    }
    var height by remember { mutableIntStateOf(36) }
    var oversized by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    // Compose dimensions are packed into finite pixel constraints. Split pathological
    // answers on complete declarations, never turn a height limit into a scroll viewport.
    if (oversized) {
        val cut = remember { source.indexOf('\n', source.length / 2).takeIf { it in 1 until source.lastIndex }
            ?: source.lastIndexOf('\n', source.length / 2).takeIf { it in 1 until source.lastIndex } }
        if (cut != null && cut < source.length && source[cut] == '\n') {
            InlineUiSurface(source.substring(0, cut), false)
            InlineUiSurface(source.substring(cut + 1), loading)
        } else {
            source.chunked(2048).forEach { Text(it) }
        }
        return
    }
    if (failed) {
        Text(source)
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height.dp),
        factory = { context ->
            var created: WebView? = null
            try { InlineUiView(context).also { created = it }.apply {
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                addJavascriptInterface(HeightBridge { type, value -> post {
                    when (type) {
                        "height" -> if (value * density > 24000) oversized = true else height = value.coerceAtLeast(24)
                        "final" -> acknowledged = value
                        "partial" -> partial = true
                        "failed" -> failed = true
                    }
                } }, "RikkaSize")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        failed = true
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        if (request.url.toString() == PAGE && request.isForMainFrame) {
                            val page = HeightBridge::class.java.getResourceAsStream(RESOURCE)
                            if (page != null) return WebResourceResponse("text/html", "UTF-8", page)
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("typeof window.rikkaUpdate === 'function'") { available ->
                            if (available == "true") ready = true else failed = true
                        }
                    }
                }
                loadUrl(PAGE)
            } } catch (error: Exception) {
                created?.destroy()
                failed = true
                android.view.View(context)
            }
        },
        update = { view ->
            if (view is WebView && ready && view.tag != payload) {
                view.tag = payload
                view.evaluateJavascript("window.rikkaUpdate($payload)") { if (it != "true") failed = true }
            }
        },
        onRelease = { view ->
            (view as? WebView)?.destroy()
        },
    )
    if (partial) TextButton(onClick = { failed = true }) {
        Text(if (lang.startsWith("zh")) "查看完整原文" else "View original text")
    }
}
