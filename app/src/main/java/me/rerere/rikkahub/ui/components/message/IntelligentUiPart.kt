package me.rerere.rikkahub.ui.components.message

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.context.LocalNavController
import org.json.JSONObject
import java.io.ByteArrayInputStream

private const val PAGE = "https://rikka-ui.invalid/index.html"
private const val RESOURCE = "/static/intelligent-ui/index.html"

/**
 * WebView tailored for interactive cards inside a scrollable Compose chat list.
 * Releases vertical drags to the parent list by default, while honoring [captureTouch]
 * when the user drags a 3D canvas or slider (`[data-capture-touch]`).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class InlineUiView(context: Context) : WebView(context) {
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @Volatile
    var captureTouch: Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(captureTouch)
            }
            MotionEvent.ACTION_MOVE -> {
                if (captureTouch) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dy > slop && dy > kotlin.math.abs(event.x - downX)) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                captureTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}

@Keep
private class HeightBridge(private val onStatus: (String, Int) -> Unit) {
    @JavascriptInterface
    fun status(type: String, value: Int) = onStatus(type, value)
}

/**
 * Sandboxed offline interactive widget surface (`rikka-ui` & HTML interactive card preview).
 * Has no network access, file access, browser profile, or privileged JS bridges.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun RikkaWidgetCard(
    html: String,
    complete: Boolean,
    modifier: Modifier = Modifier,
    showFullscreenButton: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val lang = LocalConfiguration.current.locales[0].toLanguageTag()
    val zh = lang.startsWith("zh", ignoreCase = true)
    val context = LocalContext.current
    val navController = LocalNavController.current
    val sequence = remember { java.util.concurrent.atomic.AtomicInteger() }
    val revision = remember(html, complete, dark, lang) { sequence.incrementAndGet() }
    val payload = remember(revision) {
        JSONObject()
            .put("source", html.take(65536))
            .put("done", complete)
            .put("dark", dark)
            .put("lang", lang)
            .put("revision", revision)
            .toString()
    }

    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableIntStateOf(0) }
    var partial by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(64) }
    val density = LocalDensity.current.density

    LaunchedEffect(ready, failed) {
        if (!ready && !failed) {
            delay(10000)
            failed = true
        }
    }
    LaunchedEffect(ready, revision, acknowledged, complete, failed) {
        if (ready && complete && acknowledged != revision && !failed) {
            delay(10000)
            partial = true
        }
    }

    if (failed || showSource) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.055f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f)
                )
                .border(
                    1.dp,
                    if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.13f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (zh) "交互卡片源码" else "Widget Source",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!failed) {
                    GlassActionChip(
                        label = if (zh) "返回卡片" else "Back to card",
                        dark = dark,
                        onClick = { showSource = false },
                    )
                }
            }
            Text(
                text = html.take(4096),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height.dp),
                factory = { ctx ->
                    var created: InlineUiView? = null
                    try {
                        InlineUiView(ctx).also { created = it }.apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            addJavascriptInterface(
                                HeightBridge { type, value ->
                                    post {
                                        when (type) {
                                            "height" -> {
                                                val maxDp = (22000f / density).toInt().coerceAtLeast(1600)
                                                height = value.coerceIn(36, maxDp)
                                            }
                                            "final" -> acknowledged = value
                                            "partial" -> partial = true
                                            "failed" -> failed = true
                                            "capture-touch" -> {
                                                captureTouch = value != 0
                                                parent?.requestDisallowInterceptTouchEvent(value != 0)
                                            }
                                        }
                                    }
                                },
                                "RikkaSize",
                            )
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ) = true

                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: RenderProcessGoneDetail,
                                ): Boolean {
                                    failed = true
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse {
                                    if (request.url.toString() == PAGE && request.isForMainFrame) {
                                        val page = HeightBridge::class.java.getResourceAsStream(RESOURCE)
                                        if (page != null) {
                                            return WebResourceResponse("text/html", "UTF-8", page)
                                        }
                                    }
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        404,
                                        "Not found",
                                        emptyMap(),
                                        ByteArrayInputStream(byteArrayOf()),
                                    )
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    view.evaluateJavascript("typeof window.rikkaUpdate === 'function'") { available ->
                                        if (available == "true") ready = true else failed = true
                                    }
                                }
                            }
                            loadUrl(PAGE)
                        }
                    } catch (_: Exception) {
                        created?.destroy()
                        failed = true
                        android.view.View(ctx)
                    }
                },
                update = { view ->
                    if (view is WebView && ready && view.tag != payload) {
                        view.tag = payload
                        view.evaluateJavascript("window.rikkaUpdate($payload)") {
                            if (it != "true") failed = true
                        }
                    }
                },
                onRelease = { view ->
                    (view as? WebView)?.destroy()
                },
            )
        }

        if (complete && (showFullscreenButton || partial)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (partial) {
                    GlassActionChip(
                        label = if (zh) "查看源码" else "Source",
                        dark = dark,
                        onClick = { showSource = true },
                    )
                }
                if (showFullscreenButton) {
                    GlassActionChip(
                        label = if (zh) "全屏交互" else "Fullscreen",
                        dark = dark,
                        onClick = {
                            val standalone = buildStandaloneFullscreenHtml(html, dark)
                            val contentId = WebViewContentCache.store(context.cacheDir, standalone)
                            navController.navigate(Screen.WebView(contentId = contentId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassActionChip(
    label: String,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f)
    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
    val border = if (dark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildStandaloneFullscreenHtml(html: String, dark: Boolean): String {
    val shell = HeightBridge::class.java.getResourceAsStream(RESOURCE)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: return html
    val payload = JSONObject()
        .put("source", html.take(65536))
        .put("done", true)
        .put("dark", dark)
        .put("lang", "zh")
        .put("revision", 1)
        .toString()
        .replace("</", "<\\/")
    val bgStyle = if (dark) {
        "html,body{min-height:100vh;padding:12px;overflow:auto!important;background:radial-gradient(circle at 20% 10%,#17324f,#09101d 70%)!important;}"
    } else {
        "html,body{min-height:100vh;padding:12px;overflow:auto!important;background:#f4f7fb!important;}"
    }
    val boot = "<style>$bgStyle</style><script>window.rikkaUpdate($payload);</script></body>"
    return shell.replace("</body>", boot)
}
