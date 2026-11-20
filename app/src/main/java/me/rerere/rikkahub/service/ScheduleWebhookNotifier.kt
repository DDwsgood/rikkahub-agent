package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryConfig
import me.rerere.rikkahub.data.preferences.ScheduleWebhookPreset
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScheduleWebhookPayload(
    val deliveryId: String,
    val jobId: String,
    val jobName: String,
    val mode: String,
    val outcome: String,
    val summary: String,
    val timestampMs: Long,
)

data class WebhookRequestSpec(
    val url: String,
    val jsonBody: String,
    val headers: Map<String, String>,
)

data class WebhookDeliveryResult(
    val attempted: Boolean,
    val success: Boolean,
    val statusCode: Int? = null,
    /** Whether a caller (e.g. WorkManager outbox worker) should schedule a deferred retry. */
    val retryable: Boolean = false,
    /**
     * Leak-free machine summary safe for diagnostics and logs — NEVER contains webhook URL,
     * tokens/secrets, raw response bodies, or raw exception messages.
     */
    val summary: String,
)

/**
 * Decoupled external Webhook result delivery for scheduled jobs (WeCom / Feishu / DingTalk /
 * ServerChan / PushPlus / WxPusher / OneBot QQ / Custom JSON).
 *
 * Security & reliability invariants:
 * - Uses a dedicated private [OkHttpClient] with ZERO logging/AI interceptors so webhook URLs,
 *   Bearer tokens, and body tokens never reach `RequestLoggingInterceptor` or `HttpLoggingInterceptor`.
 * - Disables HTTP redirects and OkHttp implicit retries, enforces an explicit per-attempt
 *   `callTimeout`, reads at most [MAX_RESPONSE_BYTES] bytes, and supports cooperative cancellation.
 * - Parses per-preset 2xx JSON business error codes so silent provider rejections are reported honestly.
 * - Note: `X-RikkaHub-Delivery-Id` is sent as a best-effort tracing/idempotency hint, but callers
 *   must NOT assume third-party endpoints deduplicate on this header.
 */
object ScheduleWebhookNotifier {

    private const val TAG = "ScheduleWebhookNotifier"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L)
    const val DEFAULT_MAX_ATTEMPTS = 3
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    /** Dedicated zero-logging HTTP client isolated from app-wide AI/debug logging interceptors. */
    private val dedicatedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Pure request builder so every preset's payload format & headers are directly unit-testable
     * without making network calls.
     */
    fun buildRequestSpec(
        config: ScheduleDeliveryConfig,
        payload: ScheduleWebhookPayload,
    ): WebhookRequestSpec {
        val url = config.webhookUrl.trim()
        val token = config.webhookSecretOrToken.trim()
        val title = "[RikkaHub] ${payload.jobName} (${payload.outcome})"
        val bodyText = buildString {
            appendLine(title)
            appendLine("Mode: ${payload.mode}")
            if (payload.summary.isNotBlank()) {
                appendLine()
                append(payload.summary.take(3000))
            }
        }.trimEnd()

        val headers = linkedMapOf(
            "X-RikkaHub-Delivery-Id" to payload.deliveryId,
        )

        val jsonBody = when (config.webhookPreset) {
            ScheduleWebhookPreset.WECOM -> buildJsonObject {
                put("msgtype", "markdown")
                put("markdown", buildJsonObject {
                    put("content", "**$title**\n\n${payload.summary.take(3500)}")
                })
            }.toString()

            ScheduleWebhookPreset.FEISHU -> buildJsonObject {
                put("msg_type", "text")
                put("content", buildJsonObject {
                    put("text", bodyText)
                })
            }.toString()

            ScheduleWebhookPreset.DINGTALK -> buildJsonObject {
                put("msgtype", "markdown")
                put("markdown", buildJsonObject {
                    put("title", title)
                    put("text", "### $title\n\n${payload.summary.take(3500)}")
                })
            }.toString()

            ScheduleWebhookPreset.SERVERCHAN -> buildJsonObject {
                put("title", title.take(100))
                put("desp", payload.summary.take(3500).ifBlank { "Outcome: ${payload.outcome}" })
            }.toString()

            ScheduleWebhookPreset.PUSHPLUS -> buildJsonObject {
                if (token.isNotEmpty()) put("token", token)
                put("title", title.take(100))
                put("content", payload.summary.take(3500).ifBlank { "Outcome: ${payload.outcome}" })
                put("template", "markdown")
            }.toString()

            ScheduleWebhookPreset.WXPUSHER -> buildJsonObject {
                if (token.isNotEmpty()) put("appToken", token)
                put("summary", title.take(100))
                put("content", bodyText)
                put("contentType", 1)
                val target = config.webhookTargetId.trim()
                if (target.isNotEmpty()) {
                    put(
                        "uids",
                        buildJsonArray {
                            target.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                                add(kotlinx.serialization.json.JsonPrimitive(it))
                            }
                        },
                    )
                }
            }.toString()

            ScheduleWebhookPreset.ONEBOT_QQ -> {
                if (token.isNotEmpty()) {
                    headers["Authorization"] = "Bearer $token"
                }
                val idLong = config.webhookTargetId.trim().toLongOrNull()
                buildJsonObject {
                    if (config.webhookTargetType == "group") {
                        if (idLong != null) put("group_id", idLong) else put("group_id", config.webhookTargetId.trim())
                    } else {
                        if (idLong != null) put("user_id", idLong) else put("user_id", config.webhookTargetId.trim())
                    }
                    put("message", bodyText)
                }.toString()
            }

            ScheduleWebhookPreset.CUSTOM -> {
                if (token.isNotEmpty()) {
                    headers["Authorization"] = if (token.contains(' ')) token else "Bearer $token"
                }
                buildJsonObject {
                    put("delivery_id", payload.deliveryId)
                    put("job_id", payload.jobId)
                    put("job_name", payload.jobName)
                    put("mode", payload.mode)
                    put("outcome", payload.outcome)
                    put("summary", payload.summary.take(3500))
                    put("timestamp_ms", payload.timestampMs)
                }.toString()
            }
        }

        return WebhookRequestSpec(url = url, jsonBody = jsonBody, headers = headers)
    }

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * Strictly parses per-preset JSON business status codes returned inside an HTTP 2xx body.
     *
     * Except for [ScheduleWebhookPreset.CUSTOM] (where arbitrary 2xx payloads are accepted),
     * every built-in preset requires a well-formed JSON object containing an explicit success
     * indicator: blank bodies, truncated/non-JSON payloads, missing status fields, or non-
     * primitive field types are reported as protocol errors rather than false successes.
     *
     * Never throws; returns `null` when explicitly accepted or a stable non-sensitive error code.
     */
    internal fun parseBusinessError(preset: ScheduleWebhookPreset, responseBody: String): String? {
        if (preset == ScheduleWebhookPreset.CUSTOM) return null
        if (responseBody.isBlank()) return "protocol_empty_body"
        val obj = runCatching { Json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
            ?: return "protocol_invalid_json"
        return when (preset) {
            ScheduleWebhookPreset.WECOM, ScheduleWebhookPreset.DINGTALK -> {
                val errcode = obj.intField("errcode") ?: return "protocol_missing_status"
                if (errcode != 0) "biz_$errcode" else null
            }
            ScheduleWebhookPreset.FEISHU -> {
                val code = obj.intField("code")
                val statusCode = obj.intField("StatusCode")
                when {
                    code != null -> if (code != 0) "biz_$code" else null
                    statusCode != null -> if (statusCode != 0) "biz_$statusCode" else null
                    else -> "protocol_missing_status"
                }
            }
            ScheduleWebhookPreset.SERVERCHAN -> {
                val code = obj.intField("code") ?: return "protocol_missing_status"
                if (code != 0) "biz_$code" else null
            }
            ScheduleWebhookPreset.PUSHPLUS -> {
                val code = obj.intField("code") ?: return "protocol_missing_status"
                if (code != 200) "biz_$code" else null
            }
            ScheduleWebhookPreset.WXPUSHER -> {
                val code = obj.intField("code")
                val ok = obj.booleanField("success")
                when {
                    code != null && code != 1000 -> "biz_$code"
                    ok == false -> "biz_rejected"
                    code == 1000 || ok == true -> null
                    else -> "protocol_missing_status"
                }
            }
            ScheduleWebhookPreset.ONEBOT_QQ -> {
                val status = obj.stringField("status")?.lowercase()
                val retcode = obj.intField("retcode")
                when {
                    status == "failed" -> "biz_${retcode ?: "failed"}"
                    retcode != null && retcode != 0 -> "biz_$retcode"
                    status == "ok" || status == "async" || retcode == 0 -> null
                    else -> "protocol_missing_status"
                }
            }
            ScheduleWebhookPreset.CUSTOM -> null
        }
    }

    /**
     * Delivers [payload] using the dedicated zero-logging HTTP client.
     *
     * Pass [maxAttempts] = `1` when an outer durable queue (such as [ScheduleWebhookWorker]) owns
     * the retry budget so total POST attempts never multiply across layers. Pass [clientOverride]
     * ONLY in unit tests (e.g. a clean mock client with no logging interceptors).
     */
    suspend fun deliver(
        config: ScheduleDeliveryConfig,
        payload: ScheduleWebhookPayload,
        clientOverride: OkHttpClient? = null,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): WebhookDeliveryResult {
        if (!config.webhookEnabled) {
            return WebhookDeliveryResult(attempted = false, success = false, summary = "webhook:disabled")
        }
        val urlErr = config.webhookUrlValidationError
        if (urlErr != null) {
            return WebhookDeliveryResult(attempted = false, success = false, summary = "webhook:$urlErr")
        }
        val isSuccess = payload.outcome == "success"
        if (isSuccess && !config.webhookNotifyOnSuccess) {
            return WebhookDeliveryResult(attempted = false, success = false, summary = "webhook:filtered_success")
        }
        if (!isSuccess && !config.webhookNotifyOnFailure) {
            return WebhookDeliveryResult(attempted = false, success = false, summary = "webhook:filtered_failure")
        }

        val request = try {
            val spec = buildRequestSpec(config, payload)
            val reqBuilder = Request.Builder()
                .url(spec.url)
                .post(spec.jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            spec.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            reqBuilder.build()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // Malformed header (e.g. newline/non-ASCII in token) or URL build error — never leak token/URL.
            return WebhookDeliveryResult(
                attempted = true,
                success = false,
                retryable = false,
                summary = "webhook:config_error",
            )
        }

        val webhookClient = clientOverride?.newBuilder()
            ?.followRedirects(false)
            ?.followSslRedirects(false)
            ?.retryOnConnectionFailure(false)
            ?.build()
            ?: dedicatedClient

        var lastStatus: Int? = null
        var lastErrorCode = "unknown"
        val attempts = maxAttempts.coerceAtLeast(1)

        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                delay(RETRY_BACKOFF_MS.getOrElse(attempt - 1) { RETRY_BACKOFF_MS.last() })
            }
            val call = webhookClient.newCall(request)

            val outcome = try {
                executeCancellableBounded(call)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastErrorCode = classifyExceptionCode(t)
                // Log ONLY non-sensitive metadata (deliveryId + stable error code; never URL/body/token).
                runCatching {
                    Log.w(TAG, "Webhook delivery attempt ${attempt + 1}/$attempts failed: id=${payload.deliveryId} err=$lastErrorCode")
                }
                null
            }

            if (outcome != null) {
                val (code, bodyText) = outcome
                lastStatus = code
                if (code in 200..299) {
                    val bizErr = try {
                        parseBusinessError(config.webhookPreset, bodyText)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        "protocol_parse_error"
                    }
                    if (bizErr == null) {
                        return WebhookDeliveryResult(
                            attempted = true,
                            success = true,
                            statusCode = code,
                            retryable = false,
                            summary = "webhook:ok($code)",
                        )
                    }
                    // Business or protocol error on 2xx — do not retry blindly.
                    return WebhookDeliveryResult(
                        attempted = true,
                        success = false,
                        statusCode = code,
                        retryable = false,
                        summary = "webhook:$bizErr",
                    )
                }
                if (code in 300..399) {
                    return WebhookDeliveryResult(
                        attempted = true,
                        success = false,
                        statusCode = code,
                        retryable = false,
                        summary = "webhook:redirect_blocked($code)",
                    )
                }
                if (code in 400..499) {
                    return WebhookDeliveryResult(
                        attempted = true,
                        success = false,
                        statusCode = code,
                        retryable = false,
                        summary = "webhook:http_$code",
                    )
                }
                lastErrorCode = "http_$code"
            }
        }

        return WebhookDeliveryResult(
            attempted = true,
            success = false,
            statusCode = lastStatus,
            retryable = true,
            summary = "webhook:failed($lastErrorCode)",
        )
    }

    private suspend fun executeCancellableBounded(call: Call): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { resp ->
                                val code = resp.code
                                val limited = readBoundedUtf8(resp, MAX_RESPONSE_BYTES)
                                if (cont.isActive) cont.resume(code to limited)
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                })
            }
        }

    private fun readBoundedUtf8(response: Response, maxBytes: Int): String {
        val stream = response.body.byteStream()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        var remaining = maxBytes
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun classifyExceptionCode(t: Throwable): String = when {
        t is java.io.InterruptedIOException -> "timeout"
        t is javax.net.ssl.SSLException -> "tls_error"
        t is java.net.UnknownHostException -> "dns_error"
        t is java.net.ConnectException -> "connect_error"
        t is IOException && t.message?.contains("Canceled", ignoreCase = true) == true -> "timeout"
        t is IOException -> "io_error"
        else -> "error"
    }
}
