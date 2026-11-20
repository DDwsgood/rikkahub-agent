package me.rerere.rikkahub.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.preferences.OutboxStageResult
import me.rerere.rikkahub.data.preferences.PendingWebhookDelivery
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryConfig
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences
import me.rerere.rikkahub.data.preferences.ScheduleWebhookPreset
import me.rerere.rikkahub.data.preferences.WebhookDeliveryState
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScheduleWebhookNotifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val payload = ScheduleWebhookPayload(
        deliveryId = "job-1-1700000000000",
        jobId = "job-1",
        jobName = "Morning Brief",
        mode = "llm",
        outcome = "success",
        summary = "All checks green",
        timestampMs = 1700000000000L,
    )

    private fun startMockServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { ex -> handler(ex) }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/hook"
    }

    @Test
    fun urlValidationEnforcesHttpsByDefaultAndAllowsOptInLocalCleartext() {
        assertNull(ScheduleDeliveryConfig.validateWebhookUrl("https://example.com/hook", allowCleartextLocal = false))
        assertEquals("https_required", ScheduleDeliveryConfig.validateWebhookUrl("http://127.0.0.1:3000/hook", allowCleartextLocal = false))
        assertNull(ScheduleDeliveryConfig.validateWebhookUrl("http://127.0.0.1:3000/hook", allowCleartextLocal = true))
        assertNull(ScheduleDeliveryConfig.validateWebhookUrl("http://192.168.1.50:8080/hook", allowCleartextLocal = true))
        assertEquals("cleartext_non_local", ScheduleDeliveryConfig.validateWebhookUrl("http://example.com/hook", allowCleartextLocal = true))
        assertEquals("invalid_url", ScheduleDeliveryConfig.validateWebhookUrl("https://user:pass@example.com/hook", allowCleartextLocal = false))
    }

    @Test
    fun buildsAllPresetPayloadsWithDeliveryIdHeader() {
        val wecom = ScheduleWebhookNotifier.buildRequestSpec(
            ScheduleDeliveryConfig(webhookEnabled = true, webhookPreset = ScheduleWebhookPreset.WECOM, webhookUrl = "https://qyapi.weixin.qq.com/test"),
            payload,
        )
        assertEquals("job-1-1700000000000", wecom.headers["X-RikkaHub-Delivery-Id"])
        assertEquals("markdown", Json.parseToJsonElement(wecom.jsonBody).jsonObject["msgtype"]?.jsonPrimitive?.content)

        val wx = ScheduleWebhookNotifier.buildRequestSpec(
            ScheduleDeliveryConfig(
                webhookEnabled = true,
                webhookPreset = ScheduleWebhookPreset.WXPUSHER,
                webhookUrl = "https://wxpusher.zjiecode.com/api/send/message",
                webhookSecretOrToken = "AT_test",
                webhookTargetId = "UID_1, UID_2",
            ),
            payload,
        )
        assertEquals(2, Json.parseToJsonElement(wx.jsonBody).jsonObject["uids"]!!.jsonArray.size)

        val qq = ScheduleWebhookNotifier.buildRequestSpec(
            ScheduleDeliveryConfig(
                webhookEnabled = true,
                webhookPreset = ScheduleWebhookPreset.ONEBOT_QQ,
                webhookUrl = "http://127.0.0.1:3000/send_group_msg",
                webhookSecretOrToken = "onebot-secret",
                webhookTargetId = "12345678",
                webhookTargetType = "group",
                webhookAllowCleartextLocal = true,
            ),
            payload,
        )
        assertEquals("Bearer onebot-secret", qq.headers["Authorization"])
        assertEquals("12345678", Json.parseToJsonElement(qq.jsonBody).jsonObject["group_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun detects2xxBusinessErrorWithoutRetryingAndNeverLeaksTokenOrBody() = runBlocking {
        val attempts = AtomicInteger(0)
        val (server, url) = startMockServer { ex ->
            attempts.incrementAndGet()
            val body = """{"errcode":93000,"errmsg":"SECRET_BODY_LEAK invalid key"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        try {
            val res = ScheduleWebhookNotifier.deliver(
                config = ScheduleDeliveryConfig(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.WECOM,
                    webhookUrl = url,
                    webhookSecretOrToken = "SUPER_SECRET_TOKEN",
                    webhookAllowCleartextLocal = true,
                ),
                payload = payload,
            )
            assertTrue(res.attempted)
            assertFalse(res.success)
            assertEquals(1, attempts.get())
            assertEquals("webhook:biz_93000", res.summary)
            assertFalse(res.summary.contains("SECRET_BODY_LEAK"))
            assertFalse(res.summary.contains("SUPER_SECRET_TOKEN"))
            assertFalse(res.summary.contains(url))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun blocks3xxRedirectAnd4xxWithoutRetryWhileRetrying5xxExactlyTwice() = runBlocking {
        val attemptsRedirect = AtomicInteger(0)
        val (redirectServer, redirectUrl) = startMockServer { ex ->
            attemptsRedirect.incrementAndGet()
            ex.responseHeaders.add("Location", "https://evil.example/steal")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        val attempts401 = AtomicInteger(0)
        val (authServer, authUrl) = startMockServer { ex ->
            attempts401.incrementAndGet()
            val body = "UNAUTHORIZED_SECRET_DETAIL".toByteArray()
            ex.sendResponseHeaders(401, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val attempts503 = AtomicInteger(0)
        val (errServer, errUrl) = startMockServer { ex ->
            attempts503.incrementAndGet()
            ex.sendResponseHeaders(503, -1)
            ex.close()
        }
        try {
            val r302 = ScheduleWebhookNotifier.deliver(
                config = ScheduleDeliveryConfig(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = redirectUrl,
                    webhookAllowCleartextLocal = true,
                ),
                payload = payload,
            )
            assertFalse(r302.success)
            assertEquals(1, attemptsRedirect.get())
            assertEquals("webhook:redirect_blocked(302)", r302.summary)

            val r401 = ScheduleWebhookNotifier.deliver(
                config = ScheduleDeliveryConfig(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = authUrl,
                    webhookAllowCleartextLocal = true,
                ),
                payload = payload,
            )
            assertFalse(r401.success)
            assertEquals(1, attempts401.get())
            assertEquals("webhook:http_401", r401.summary)
            assertFalse(r401.summary.contains("UNAUTHORIZED_SECRET_DETAIL"))

            val r503 = ScheduleWebhookNotifier.deliver(
                config = ScheduleDeliveryConfig(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = errUrl,
                    webhookAllowCleartextLocal = true,
                ),
                payload = payload,
            )
            assertFalse(r503.success)
            assertEquals(3, attempts503.get())
            assertEquals("webhook:failed(http_503)", r503.summary)
        } finally {
            redirectServer.stop(0)
            authServer.stop(0)
            errServer.stop(0)
        }
    }

    @Test
    fun timeoutAndCancellationReturnCleanlyWithoutHanging() = runBlocking {
        val (slowServer, slowUrl) = startMockServer { ex ->
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
            }
            runCatching {
                ex.sendResponseHeaders(200, -1)
                ex.close()
            }
        }
        try {
            // 1. Short call timeout
            val shortClient = OkHttpClient.Builder()
                .callTimeout(250, TimeUnit.MILLISECONDS)
                .build()
            val timedOut = ScheduleWebhookNotifier.deliver(
                config = ScheduleDeliveryConfig(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = slowUrl,
                    webhookAllowCleartextLocal = true,
                ),
                payload = payload,
                clientOverride = shortClient,
            )
            assertFalse(timedOut.success)
            assertTrue("Expected timeout error code, got ${timedOut.summary}", timedOut.summary.contains("timeout"))

            // 2. Cooperative cancellation propagates CancellationException promptly
            val job = async {
                ScheduleWebhookNotifier.deliver(
                    config = ScheduleDeliveryConfig(
                        webhookEnabled = true,
                        webhookPreset = ScheduleWebhookPreset.CUSTOM,
                        webhookUrl = slowUrl,
                        webhookAllowCleartextLocal = true,
                    ),
                    payload = payload,
                )
            }
            delay(100)
            job.cancel()
            var cancelled = false
            try {
                job.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue("Cancellation must propagate cleanly", cancelled)
        } finally {
            slowServer.stop(0)
        }
    }

    @Test
    fun diagnosticsPerJobQuotaPreventsHighFrequencyJobFromEvictingOtherJobs() {
        val traces = buildList {
            repeat(20) { i ->
                add(
                    ScheduleRunTrace(
                        runId = "hf-$i",
                        jobId = "high-freq",
                        jobName = "Every Minute",
                        mode = "direct",
                        triggerSource = "exact_alarm",
                        scheduledAtMs = 1000L + i,
                        workerStartedAtMs = 1005L + i,
                        foregroundResult = "promoted",
                    )
                )
            }
            add(
                ScheduleRunTrace(
                    runId = "daily-1",
                    jobId = "daily-job",
                    jobName = "Daily Brief",
                    mode = "llm",
                    triggerSource = "exact_alarm",
                    scheduledAtMs = 500L,
                    workerStartedAtMs = 510L,
                    foregroundResult = "promoted",
                )
            )
        }
        val retained = ScheduleRunDiagnosticsStore.retainPerJobQuota(traces, maxPerJob = 5, maxTotal = 20)
        assertEquals(5, retained.count { it.jobId == "high-freq" })
        assertEquals(1, retained.count { it.jobId == "daily-job" })
    }

    @Test
    fun preset2xxParsingRejectsMalformedBodiesAndNonPrimitiveFieldsWithoutThrowing() {
        // Empty body, non-JSON, truncated JSON, missing status field -> protocol error (never false success)
        assertEquals("protocol_empty_body", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.DINGTALK, ""))
        assertEquals("protocol_invalid_json", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.WECOM, "<html>ok</html>"))
        assertEquals("protocol_invalid_json", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.FEISHU, "{\"code\":0"))
        assertEquals("protocol_missing_status", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.PUSHPLUS, "{\"msg\":\"ok\"}"))

        // Non-primitive JSON object/array in status field must NOT throw IllegalArgumentException
        assertEquals("protocol_missing_status", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.DINGTALK, "{\"errcode\":{\"nested\":0}}"))
        assertEquals("protocol_missing_status", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.FEISHU, "{\"code\":[0]}"))
        assertEquals("protocol_missing_status", ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.ONEBOT_QQ, "{\"status\":{\"ok\":true},\"retcode\":[0]}"))

        // Explicit success responses pass cleanly for each built-in preset
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.DINGTALK, "{\"errcode\":0,\"errmsg\":\"ok\"}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.WECOM, "{\"errcode\":0}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.FEISHU, "{\"code\":0}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.FEISHU, "{\"StatusCode\":0}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.SERVERCHAN, "{\"code\":0}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.PUSHPLUS, "{\"code\":200}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.WXPUSHER, "{\"code\":1000,\"success\":true}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.ONEBOT_QQ, "{\"status\":\"ok\",\"retcode\":0}"))
        assertEquals(null, ScheduleWebhookNotifier.parseBusinessError(ScheduleWebhookPreset.CUSTOM, ""))
    }

    @Test
    fun malformedHeaderTokenReturnsConfigErrorCleanlyWithoutLeakingToken() = runBlocking {
        val result = ScheduleWebhookNotifier.deliver(
            config = ScheduleDeliveryConfig(
                webhookEnabled = true,
                webhookPreset = ScheduleWebhookPreset.CUSTOM,
                webhookUrl = "https://hooks.example.com/notify",
                webhookSecretOrToken = "SECRET_TOKEN_LINE1\r\nX-Injected: evil",
            ),
            payload = payload,
        )
        assertTrue(result.attempted)
        assertFalse(result.success)
        assertFalse(result.retryable)
        assertEquals("webhook:config_error", result.summary)
        assertFalse(result.summary.contains("SECRET_TOKEN"))
    }

    @Test
    fun webhookOutboxInputDataExcludesSecretsAndMergeDeliveryPreservesLocalNotif() {
        val cfg = ScheduleDeliveryConfig(
            webhookEnabled = true,
            webhookPreset = ScheduleWebhookPreset.CUSTOM,
            webhookUrl = "https://secret.example.com/hook/PRIVATE_PATH",
            webhookSecretOrToken = "TOP_SECRET_BEARER",
            webhookDestinationVersion = "ver-1",
        )
        val binding = cfg.destinationBinding()
        val data = ScheduleWebhookWorker.buildInputData(
            runId = "run-99",
            payload = payload,
            destinationBinding = binding,
        )
        assertEquals("run-99", data.getString(ScheduleWebhookWorker.KEY_RUN_ID))
        assertEquals(payload.deliveryId, data.getString(ScheduleWebhookWorker.KEY_DELIVERY_ID))
        assertEquals(binding, data.getString(ScheduleWebhookWorker.KEY_DESTINATION_BINDING))
        // Ensure zero secret keys or values exist in WorkManager SQLite Data
        assertFalse(data.keyValueMap.keys.any { it.contains("url", ignoreCase = true) || it.contains("token", ignoreCase = true) })
        val allValues = data.keyValueMap.values.joinToString("|")
        assertFalse(allValues.contains("PRIVATE_PATH"))
        assertFalse(allValues.contains("TOP_SECRET_BEARER"))

        val merged = ScheduleRunDiagnosticsStore.mergeDeliverySummary(
            existing = "local_failure_notif, webhook:queued",
            webhookSummary = "webhook:ok(200)",
        )
        assertEquals("local_failure_notif, webhook:ok(200)", merged)
    }

    @Test
    fun recipientChangeCancelsQueuedDeliveryWithZeroRequestsToNewEndpointAndNonReusableVersion() = runBlocking {
        val hitsA = AtomicInteger(0)
        val (serverA, urlA) = startMockServer { ex ->
            hitsA.incrementAndGet()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val hitsB = AtomicInteger(0)
        val (serverB, urlB) = startMockServer { ex ->
            hitsB.incrementAndGet()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("prefs_recipient"), "delivery.preferences_pb")
            }
        )
        try {
            // 1. Configure Channel A
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = urlA,
                    webhookSecretOrToken = "token-A",
                    webhookAllowCleartextLocal = true,
                )
            }
            val cfgA1 = prefs.current()
            val bindingA1 = cfgA1.destinationBinding()
            assertTrue(cfgA1.webhookDestinationVersion.isNotBlank())

            // 2. Unrelated toggle preserves version & binding
            prefs.update { it.copy(keepaliveWakeLockEnabled = true) }
            assertEquals(bindingA1, prefs.current().destinationBinding())

            // 3. Switch to Channel B -> queued item for A must be cancelled with 0 HTTP hits to B
            prefs.update {
                it.copy(
                    webhookUrl = urlB,
                    webhookSecretOrToken = "token-B",
                )
            }
            val cfgB = prefs.current()
            assertNotEquals(bindingA1, cfgB.destinationBinding())

            val attemptToB = ScheduleWebhookWorker.executeDeliveryAttempt(
                config = cfgB,
                expectedBinding = bindingA1,
                payload = payload,
                runAttemptCount = 0,
            )
            assertFalse(attemptToB.sentHttpRequest)
            assertFalse(attemptToB.shouldRetry)
            assertEquals("webhook:cancelled_recipient_changed", attemptToB.diagnosticSummary)
            assertEquals("Channel B must receive ZERO requests for queued Channel A payload", 0, hitsB.get())

            // 4. Switch BACK to Channel A -> destination version must NOT be reused
            prefs.update {
                it.copy(
                    webhookUrl = urlA,
                    webhookSecretOrToken = "token-A",
                )
            }
            val cfgA2 = prefs.current()
            assertNotEquals(cfgA1.webhookDestinationVersion, cfgA2.webhookDestinationVersion)
            assertNotEquals(bindingA1, cfgA2.destinationBinding())

            val replayOldAfterSwitchBack = ScheduleWebhookWorker.executeDeliveryAttempt(
                config = cfgA2,
                expectedBinding = bindingA1,
                payload = payload,
                runAttemptCount = 1,
            )
            assertFalse(replayOldAfterSwitchBack.sentHttpRequest)
            assertEquals("webhook:cancelled_recipient_changed", replayOldAfterSwitchBack.diagnosticSummary)
            assertEquals(0, hitsA.get())
        } finally {
            scope.cancel()
            serverA.stop(0)
            serverB.stop(0)
        }
    }

    @Test
    fun webhookWorkerSingleRetryLayerCapsTotalHttpPostsAtThreeOnContinuous5xx() = runBlocking {
        val totalPosts = AtomicInteger(0)
        val (server, url) = startMockServer { ex ->
            totalPosts.incrementAndGet()
            ex.sendResponseHeaders(503, -1)
            ex.close()
        }
        try {
            val cfg = ScheduleDeliveryConfig(
                webhookEnabled = true,
                webhookPreset = ScheduleWebhookPreset.CUSTOM,
                webhookUrl = url,
                webhookAllowCleartextLocal = true,
                webhookDestinationVersion = "ver-5xx",
            )
            val binding = cfg.destinationBinding()
            var runAttempt = 0
            while (true) {
                val res = ScheduleWebhookWorker.executeDeliveryAttempt(
                    config = cfg,
                    expectedBinding = binding,
                    payload = payload,
                    runAttemptCount = runAttempt,
                )
                if (!res.shouldRetry) break
                runAttempt++
                assertTrue("Retry loop must terminate within MAX_TOTAL_ATTEMPTS", runAttempt < 10)
            }
            assertEquals(
                "Total HTTP POSTs across all WorkManager runs must be capped at 3 (not 9)",
                ScheduleWebhookWorker.MAX_TOTAL_ATTEMPTS,
                totalPosts.get(),
            )
            assertEquals(3, totalPosts.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun diagnosticsStoreRecordMonotonicallyPreservesFastWebhookSettlement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ScheduleRunDiagnosticsStore.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("diag_mono"), "diag.preferences_pb")
            }
        )
        try {
            // 1. Initial running trace recorded
            store.record(
                ScheduleRunTrace(
                    runId = "run-fast",
                    jobId = "job-1",
                    jobName = "Fast Job",
                    mode = "direct",
                    triggerSource = "exact_alarm",
                    scheduledAtMs = 1000L,
                    workerStartedAtMs = 1010L,
                    foregroundResult = "promoted",
                    outcome = "running",
                )
            )
            // 2. Fast Webhook worker settles delivery BEFORE finishedTrace write arrives
            store.updateDelivery(
                runId = "run-fast",
                deliveryFinishedAtMs = 2000L,
                deliveryDurationMs = 45L,
                webhookSummary = "webhook:ok(200)",
            )
            // 3. Delayed CronJobWorker writes finishedTrace carrying `webhook:queued`
            store.record(
                ScheduleRunTrace(
                    runId = "run-fast",
                    jobId = "job-1",
                    jobName = "Fast Job",
                    mode = "direct",
                    triggerSource = "exact_alarm",
                    scheduledAtMs = 1000L,
                    workerStartedAtMs = 1010L,
                    foregroundResult = "promoted",
                    finishedAtMs = 1500L,
                    executionDurationMs = 490L,
                    outcome = "failed",
                    deliveryResult = "local_failure_notif, webhook:queued",
                )
            )
            val saved = store.recent(10).single { it.runId == "run-fast" }
            assertEquals("failed", saved.outcome)
            assertEquals(1500L, saved.finishedAtMs)
            assertEquals("Fast delivery timestamp must not be cleared", 2000L, saved.deliveryFinishedAtMs)
            assertEquals("Fast delivery duration must not be cleared", 45L, saved.deliveryDurationMs)
            assertEquals(
                "Final webhook status must not regress to webhook:queued",
                "local_failure_notif, webhook:ok(200)",
                saved.deliveryResult,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun outboxReconciliationHandlesCommittedUncommittedInFlightAndRecipientChange() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_reconcile"), "outbox.preferences_pb")
            }
        )
        try {
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = "https://hooks.example.com/live",
                )
            }
            val liveCfg = prefs.current()
            val liveBinding = liveCfg.destinationBinding()
            val oldBinding = liveCfg.copy(webhookDestinationVersion = "ver-old").destinationBinding()

            fun pendingItem(id: String, runId: String, binding: String) = PendingWebhookDelivery(
                deliveryId = id,
                runId = runId,
                jobId = "job-1",
                jobName = "Test",
                mode = "direct",
                outcome = "success",
                summary = "done",
                timestampMs = 5000L,
                destinationBinding = binding,
                createdAtMs = 5000L,
            )

            // 1. STAGED + committed Room row -> promoted to COMMITTED & enqueued
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(pendingItem("d-staged-committed", "run-committed", liveBinding)))
            // 2. Already COMMITTED + trimmed Room row (lookupRun == null!) -> MUST STILL ENQUEUE (immune to runRepo.trim!)
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(pendingItem("d-trimmed-committed", "run-trimmed", liveBinding)))
            assertTrue(prefs.markOutboxCommitted("d-trimmed-committed", nowMs = 5001L, expectedRunId = "run-trimmed"))
            // 3. Committed + changed recipient -> marked TERMINAL(cancelled_recipient_changed), not enqueued
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(pendingItem("d-changed", "run-changed", oldBinding)))
            assertTrue(prefs.markOutboxCommitted("d-changed", nowMs = 5002L, expectedRunId = "run-changed"))
            // 4. STAGED + crashed pre-commit Room row -> marked TERMINAL(uncommitted_discarded), not enqueued
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(pendingItem("d-crashed", "run-crashed", liveBinding)))
            // 5. STAGED + still-running Room row -> stays STAGED, neither enqueued nor discarded
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(pendingItem("d-inflight", "run-inflight", liveBinding)))
            // 6. A deleted run has no Room row to confirm this staged result.
            val deleted = pendingItem("d-deleted", "run-deleted", liveBinding)
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(deleted))
            val competingRun = deleted.copy(runId = "run-other")
            assertEquals(OutboxStageResult.ALREADY_PRESENT, prefs.stagePendingWebhook(competingRun))
            assertFalse(prefs.markOutboxCommitted(deleted.deliveryId, expectedRunId = competingRun.runId))
            assertFalse(prefs.markOutboxTerminal(
                deliveryId = deleted.deliveryId,
                summary = "webhook:uncommitted_discarded",
                onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                expectedRunId = competingRun.runId,
            ))
            assertEquals(WebhookDeliveryState.STAGED, prefs.getOutboxRecord(deleted.deliveryId)?.state)

            val rows = mapOf(
                "run-committed" to ScheduledJobRunEntity("run-committed", "job-1", "direct", 1000L, 1005L, 5000L, "success"),
                "run-changed" to ScheduledJobRunEntity("run-changed", "job-1", "direct", 2000L, 2005L, 5000L, "success"),
                "run-crashed" to ScheduledJobRunEntity("run-crashed", "job-1", "direct", 3000L, 3005L, 4000L, "process_killed_replay"),
                "run-inflight" to ScheduledJobRunEntity("run-inflight", "job-1", "direct", 4000L, 4005L, null, "running"),
                // Note: "run-trimmed" intentionally absent to simulate runRepo.trim(keep = 100)!
            )

            val enqueued = mutableListOf<String>()
            val diagUpdates = mutableMapOf<String, String>()

            ScheduleWebhookWorker.reconcilePendingOutboxWith(
                deliveryPrefs = prefs,
                lookupRun = { rows[it] },
                onEnqueue = { enqueued += it.deliveryId },
                onUpdateDiagnostics = { runId, summary -> diagUpdates[runId] = summary },
                nowMsProvider = { 5_500L },
                isRunActiveInCurrentProcess = { it == "run-inflight" },
            )

            assertEquals(setOf("d-staged-committed", "d-trimmed-committed"), enqueued.toSet())
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord("d-changed")?.state)
            assertEquals("webhook:cancelled_recipient_changed", diagUpdates["run-changed"])
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord("d-crashed")?.state)
            val discarded = prefs.getOutboxRecord(deleted.deliveryId)
            assertEquals(WebhookDeliveryState.TERMINAL, discarded?.state)
            assertEquals("webhook:uncommitted_discarded", discarded?.terminalSummary)
            assertEquals(WebhookDeliveryState.STAGED, prefs.getOutboxRecord("d-inflight")?.state)
            assertFalse("In-flight run must not enqueue yet", "d-inflight" in enqueued)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistedStateMachinePreventsStaleReconcileDuplicateDeliveryAndCapsPersistentBudgetAcrossRestarts() = runBlocking {
        val hitsSuccess = AtomicInteger(0)
        val (okServer, okUrl) = startMockServer { ex ->
            hitsSuccess.incrementAndGet()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val hits503 = AtomicInteger(0)
        val (errServer, errUrl) = startMockServer { ex ->
            hits503.incrementAndGet()
            ex.sendResponseHeaders(503, -1)
            ex.close()
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_persisted_sm"), "delivery.preferences_pb")
            }
        )
        val diagStore = ScheduleRunDiagnosticsStore.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("diag_persisted_sm"), "diag.preferences_pb")
            }
        )

        try {
            // --- Scenario A: Stale reconcile snapshot races with completed Worker -> ZERO duplicate POST ---
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = okUrl,
                    webhookAllowCleartextLocal = true,
                )
            }
            val okBinding = prefs.current().destinationBinding()
            val d1 = PendingWebhookDelivery(
                deliveryId = "job-ok-1",
                runId = "run-ok-1",
                jobId = "job-ok",
                jobName = "OK Job",
                mode = "direct",
                outcome = "success",
                summary = "done",
                timestampMs = 1000L,
                destinationBinding = okBinding,
                createdAtMs = 1000L,
            )
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(d1))
            assertTrue(prefs.markOutboxCommitted(d1.deliveryId, nowMs = 1001L, expectedRunId = d1.runId))

            // Worker completes delivery #1 -> HTTP 200 -> TERMINAL tombstone
            val first = ScheduleWebhookWorker.executePersistedDeliveryAttempt(
                deliveryPrefs = prefs,
                diagnosticsStore = diagStore,
                deliveryId = d1.deliveryId,
                fallbackRunId = d1.runId,
                claimId = "worker-run-1",
            )
            assertTrue(first.sentHttpRequest)
            assertTrue(first.isTerminal)
            assertEquals(1, hitsSuccess.get())
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord(d1.deliveryId)?.state)

            // Even if a stale reconcile re-enqueued a brand-new WorkRequest for the same deliveryId,
            // atomic claim sees TERMINAL tombstone and sends ZERO HTTP requests!
            val duplicate = ScheduleWebhookWorker.executePersistedDeliveryAttempt(
                deliveryPrefs = prefs,
                diagnosticsStore = diagStore,
                deliveryId = d1.deliveryId,
                fallbackRunId = d1.runId,
                claimId = "stale-reenqueue-worker",
            )
            assertFalse("Stale re-enqueue must NOT send duplicate HTTP request", duplicate.sentHttpRequest)
            assertEquals(1, hitsSuccess.get())

            // --- Scenario B: Persistent 3-attempt cap across crash + interim vs final diagnostics (P2-1) ---
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = errUrl,
                    webhookAllowCleartextLocal = true,
                )
            }
            val errBinding = prefs.current().destinationBinding()
            val d2 = PendingWebhookDelivery(
                deliveryId = "job-err-2",
                runId = "run-err-2",
                jobId = "job-err",
                jobName = "Err Job",
                mode = "direct",
                outcome = "success",
                summary = "done",
                timestampMs = 2000L,
                destinationBinding = errBinding,
                createdAtMs = 2000L,
            )
            diagStore.record(
                ScheduleRunTrace(
                    runId = d2.runId,
                    jobId = d2.jobId,
                    jobName = d2.jobName,
                    mode = d2.mode,
                    triggerSource = "exact_alarm",
                    scheduledAtMs = 2000L,
                    workerStartedAtMs = 2001L,
                    foregroundResult = "promoted",
                    finishedAtMs = 2050L,
                    outcome = "success",
                    deliveryResult = "webhook:queued",
                )
            )
            assertEquals(OutboxStageResult.STAGED, prefs.stagePendingWebhook(d2))
            assertTrue(prefs.markOutboxCommitted(d2.deliveryId, nowMs = 2001L, expectedRunId = d2.runId))

            // Attempt 1: HTTP 503 -> RETRY_WAIT; diagnostics must NOT stamp deliveryFinishedAtMs yet!
            val a1 = ScheduleWebhookWorker.executePersistedDeliveryAttempt(
                deliveryPrefs = prefs,
                diagnosticsStore = diagStore,
                deliveryId = d2.deliveryId,
                fallbackRunId = d2.runId,
                claimId = "c1",
                currentBootId = "boot-1",
                currentProcessId = "proc-1",
                nowMsProvider = { 10_000L },
                startElapsedMs = 10_000L,
                elapsedMsProvider = { 10_050L },
            )
            assertTrue(a1.shouldRetry)
            assertFalse(a1.isTerminal)
            val traceAfter1 = diagStore.recent(10).single { it.runId == d2.runId }
            assertNull("Transient retry must leave deliveryFinishedAtMs null", traceAfter1.deliveryFinishedAtMs)
            assertEquals(10_000L, traceAfter1.lastDeliveryAttemptAtMs)
            assertTrue(traceAfter1.deliveryResult.orEmpty().contains("webhook:retrying(1/3:"))

            // Simulate Attempt 2 claiming and crashing BEFORE settling (monotonic IN_FLIGHT lease later expires):
            val claim2 = prefs.claimDeliveryAttempt(
                deliveryId = d2.deliveryId,
                claimId = "c2-crashed",
                nowMs = 20_000L,
                currentBootId = "boot-1",
                currentProcessId = "proc-1",
                currentElapsedMs = 20_000L,
                maxTotalAttempts = ScheduleWebhookWorker.MAX_TOTAL_ATTEMPTS,
                inFlightLeaseMs = ScheduleWebhookWorker.IN_FLIGHT_LEASE_MS,
                recipientGate = ScheduleWebhookWorker::evaluateRecipientGate,
            )
            assertTrue(claim2 is me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed)
            assertEquals(2, prefs.getOutboxRecord(d2.deliveryId)?.attemptsUsed)

            // Attempt 3 after monotonic lease expiry (elapsed = 20_000 + 70_000): uses 3rd & final persistent attempt!
            val a3 = ScheduleWebhookWorker.executePersistedDeliveryAttempt(
                deliveryPrefs = prefs,
                diagnosticsStore = diagStore,
                deliveryId = d2.deliveryId,
                fallbackRunId = d2.runId,
                claimId = "c3-final",
                currentBootId = "boot-1",
                currentProcessId = "proc-1",
                nowMsProvider = { 90_000L },
                startElapsedMs = 90_000L,
                elapsedMsProvider = { 90_050L },
            )
            assertFalse("3rd persistent attempt must exhaust budget", a3.shouldRetry)
            assertTrue(a3.isTerminal)
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord(d2.deliveryId)?.state)
            assertEquals(3, prefs.getOutboxRecord(d2.deliveryId)?.attemptsUsed)
            val traceAfter3 = diagStore.recent(10).single { it.runId == d2.runId }
            assertEquals("Final exhausted attempt must stamp deliveryFinishedAtMs", 90_000L, traceAfter3.deliveryFinishedAtMs)
            assertEquals("webhook:failed(http_503)", traceAfter3.deliveryResult)

            // Even a brand-new WorkRequest after re-enqueue makes ZERO extra HTTP requests (total = 2 real POSTs + 1 simulated crash claim = 3 budget)
            val a4 = ScheduleWebhookWorker.executePersistedDeliveryAttempt(
                deliveryPrefs = prefs,
                diagnosticsStore = diagStore,
                deliveryId = d2.deliveryId,
                fallbackRunId = d2.runId,
                claimId = "c4-extra",
                currentBootId = "boot-1",
                currentProcessId = "proc-1",
                nowMsProvider = { 100_000L },
                startElapsedMs = 100_000L,
                elapsedMsProvider = { 100_050L },
            )
            assertFalse(a4.sentHttpRequest)
            assertEquals(2, hits503.get())
        } finally {
            scope.cancel()
            okServer.stop(0)
            errServer.stop(0)
        }
    }

    @Test
    fun outboxCapacityLimitRejectsExplicitlyWithoutSilentlyEvictingActiveDeliveries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_cap"), "cap.preferences_pb")
            }
        )
        try {
            repeat(ScheduleDeliveryPreferences.MAX_ACTIVE_OUTBOX) { i ->
                val res = prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "cap-$i",
                        runId = "run-$i",
                        jobId = "job",
                        jobName = "Cap",
                        mode = "direct",
                        outcome = "success",
                        summary = "ok",
                        timestampMs = 1000L + i,
                        destinationBinding = "b",
                        createdAtMs = 1000L + i,
                    )
                )
                assertEquals(OutboxStageResult.STAGED, res)
            }
            val overflow = prefs.stagePendingWebhook(
                PendingWebhookDelivery(
                    deliveryId = "cap-overflow",
                    runId = "run-overflow",
                    jobId = "job",
                    jobName = "Cap",
                    mode = "direct",
                    outcome = "success",
                    summary = "ok",
                    timestampMs = 9999L,
                    destinationBinding = "b",
                    createdAtMs = 9999L,
                )
            )
            assertEquals(OutboxStageResult.CAPACITY_EXCEEDED, overflow)
            assertEquals(ScheduleDeliveryPreferences.MAX_ACTIVE_OUTBOX, prefs.allPendingWebhooks().size)
            assertTrue("Oldest active delivery must NOT be silently evicted", prefs.getOutboxRecord("cap-0") != null)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reconcileRejectsStagedSuccessWhenNormalCommitFailedAndCatchWroteFailedRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_p13"), "p13.preferences_pb")
            }
        )
        try {
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = "https://hooks.example.com/live",
                    webhookNotifyOnSuccess = true,
                    webhookNotifyOnFailure = false,
                )
            }
            val binding = prefs.current().destinationBinding()
            // Case A: STAGED success, normal Room update threw, catch handler persisted `failed`
            assertEquals(
                OutboxStageResult.STAGED,
                prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "d-mismatch-success",
                        runId = "run-mismatch-1",
                        jobId = "job-1",
                        jobName = "Brief",
                        mode = "llm",
                        outcome = "success",
                        summary = "Everything succeeded!",
                        timestampMs = 50_000L,
                        destinationBinding = binding,
                        createdAtMs = 50_000L,
                        commitVerificationEnabled = true,
                        expectedConversationId = "conv-123",
                        expectedErrorMessage = null,
                    )
                ),
            )
            // Case B: STAGED normal failure, normal Room update threw, catch handler wrote crash error in SAME ms
            assertEquals(
                OutboxStageResult.STAGED,
                prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "d-mismatch-same-ms",
                        runId = "run-mismatch-2",
                        jobId = "job-1",
                        jobName = "Brief",
                        mode = "direct",
                        outcome = "failed",
                        summary = "action http failed",
                        timestampMs = 60_000L,
                        destinationBinding = binding,
                        createdAtMs = 60_000L,
                        commitVerificationEnabled = true,
                        expectedConversationId = null,
                        expectedErrorMessage = "action http: 500",
                    )
                ),
            )

            val rows = mapOf(
                "run-mismatch-1" to ScheduledJobRunEntity(
                    id = "run-mismatch-1",
                    jobId = "job-1",
                    mode = "llm",
                    scheduledAtMs = 49_000L,
                    startedAtMs = 49_100L,
                    finishedAtMs = 50_010L,
                    outcome = "failed",
                    conversationId = null,
                    errorMessage = "worker crashed: SQLiteException: disk full",
                ),
                "run-mismatch-2" to ScheduledJobRunEntity(
                    id = "run-mismatch-2",
                    jobId = "job-1",
                    mode = "direct",
                    scheduledAtMs = 59_000L,
                    startedAtMs = 59_100L,
                    finishedAtMs = 60_000L, // identical ms!
                    outcome = "failed",     // identical outcome!
                    conversationId = null,
                    errorMessage = "worker crashed: SQLiteException: commit failed",
                ),
            )

            val enqueued = mutableListOf<String>()
            ScheduleWebhookWorker.reconcilePendingOutboxWith(
                deliveryPrefs = prefs,
                lookupRun = { rows[it] },
                onEnqueue = { enqueued += it.deliveryId },
                onUpdateDiagnostics = { _, _ -> },
            )

            assertTrue("Contradictory STAGED entries must NEVER be promoted or enqueued", enqueued.isEmpty())
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord("d-mismatch-success")?.state)
            assertEquals("webhook:commit_mismatch_discarded", prefs.getOutboxRecord("d-mismatch-success")?.terminalSummary)
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord("d-mismatch-same-ms")?.state)
            assertEquals("webhook:commit_mismatch_discarded", prefs.getOutboxRecord("d-mismatch-same-ms")?.terminalSummary)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun periodicReconcileReclaimsOrphanedStagedAfterProcessDeathWithoutRebootWhilePreservingLiveRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_p14"), "p14.preferences_pb")
            }
        )
        try {
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = "https://hooks.example.com/live",
                )
            }
            val binding = prefs.current().destinationBinding()
            // 1. Orphaned STAGED from a dead process on the SAME boot (no device reboot, only 5s old!)
            assertEquals(
                OutboxStageResult.STAGED,
                prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "d-dead-proc",
                        runId = "run-dead-proc",
                        jobId = "job-1",
                        jobName = "Dead Owner",
                        mode = "direct",
                        outcome = "success",
                        summary = "ok",
                        timestampMs = 10_000L,
                        destinationBinding = binding,
                        createdAtMs = 10_000L,
                        ownerProcessId = "proc-dead-1",
                        ownerBootId = "boot-42",
                        stagedElapsedMs = 100_000L,
                    )
                ),
            )
            // 2. Live STAGED still actively owned in the CURRENT process (must NOT be touched!)
            assertEquals(
                OutboxStageResult.STAGED,
                prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "d-live-proc",
                        runId = "run-live-proc",
                        jobId = "job-2",
                        jobName = "Live Owner",
                        mode = "llm",
                        outcome = "success",
                        summary = "ok",
                        timestampMs = 10_000L,
                        destinationBinding = binding,
                        createdAtMs = 10_000L,
                        ownerProcessId = "proc-current-2",
                        ownerBootId = "boot-42",
                        stagedElapsedMs = 100_000L,
                    )
                ),
            )

            val rows = mapOf(
                "run-dead-proc" to ScheduledJobRunEntity("run-dead-proc", "job-1", "direct", 9_000L, 9_100L, null, "running"),
                "run-live-proc" to ScheduledJobRunEntity("run-live-proc", "job-2", "llm", 9_000L, 9_100L, null, "running"),
            )
            val enqueued = mutableListOf<String>()
            val strandedMarked = mutableListOf<String>()

            ScheduleWebhookWorker.reconcilePendingOutboxWith(
                deliveryPrefs = prefs,
                lookupRun = { rows[it] },
                onEnqueue = { enqueued += it.deliveryId },
                onUpdateDiagnostics = { _, _ -> },
                currentBootId = "boot-42",             // SAME boot (no device reboot!)
                currentProcessId = "proc-current-2",   // New OS process after proc-dead-1 died
                nowMsProvider = { 15_000L },           // Only 5s later (well under 30m)
                elapsedMsProvider = { 105_000L },
                isRunActiveInCurrentProcess = { it == "run-live-proc" },
                onMarkOrphanedRunStranded = { row, _ -> strandedMarked += row.id },
            )

            assertTrue(enqueued.isEmpty())
            assertEquals(listOf("run-dead-proc"), strandedMarked)
            assertEquals(WebhookDeliveryState.TERMINAL, prefs.getOutboxRecord("d-dead-proc")?.state)
            assertEquals("webhook:owner_process_lost", prefs.getOutboxRecord("d-dead-proc")?.terminalSummary)
            assertEquals("Live running job's STAGED entry must NOT be evicted", WebhookDeliveryState.STAGED, prefs.getOutboxRecord("d-live-proc")?.state)

            // Once the live run completes and settles, zero active outbox items remain (periodic reconcile can stop!)
            assertTrue(prefs.markOutboxTerminal("d-live-proc", "webhook:ok(200)", nowMs = 16_000L))
            assertFalse("No active outbox items should remain to pin periodic reconcile", prefs.hasActivePendingWebhooks())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun monotonicBootProcessLeaseIsImmuneToWallClockJumpsAndRecoversAcrossRebootOrProcessDeath() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = ScheduleDeliveryPreferences.createForTest(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tempFolder.newFolder("outbox_p2_lease"), "lease.preferences_pb")
            }
        )
        try {
            prefs.update {
                it.copy(
                    webhookEnabled = true,
                    webhookPreset = ScheduleWebhookPreset.CUSTOM,
                    webhookUrl = "https://hooks.example.com/live",
                )
            }
            val binding = prefs.current().destinationBinding()
            assertEquals(
                OutboxStageResult.STAGED,
                prefs.stagePendingWebhook(
                    PendingWebhookDelivery(
                        deliveryId = "d-lease",
                        runId = "run-lease",
                        jobId = "job-1",
                        jobName = "Lease",
                        mode = "direct",
                        outcome = "success",
                        summary = "ok",
                        timestampMs = 1_000_000L,
                        destinationBinding = binding,
                        createdAtMs = 1_000_000L,
                    )
                ),
            )
            assertTrue(prefs.markOutboxCommitted("d-lease", nowMs = 1_000_001L, expectedRunId = "run-lease"))

            // 1. Claim attempt 1 at wall=1_000_000, elapsed=50_000 in (boot-10, proc-A)
            val c1 = prefs.claimDeliveryAttempt(
                deliveryId = "d-lease",
                claimId = "c1",
                nowMs = 1_000_000L,
                currentBootId = "boot-10",
                currentProcessId = "proc-A",
                currentElapsedMs = 50_000L,
                maxTotalAttempts = 3,
                inFlightLeaseMs = 60_000L,
                recipientGate = ScheduleWebhookWorker::evaluateRecipientGate,
            )
            assertTrue(c1 is me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed)

            // 2. Wall clock jumps FORWARD +5 hours while monotonic elapsed only advanced 10s -> lease STILL ACTIVE!
            val forwardJump = prefs.claimDeliveryAttempt(
                deliveryId = "d-lease",
                claimId = "c-steal",
                nowMs = 19_000_000L,
                currentBootId = "boot-10",
                currentProcessId = "proc-A",
                currentElapsedMs = 60_000L,
                maxTotalAttempts = 3,
                inFlightLeaseMs = 60_000L,
                recipientGate = ScheduleWebhookWorker::evaluateRecipientGate,
            )
            assertTrue(
                "Forward wall-clock jump must NOT prematurely expire active in-flight lease",
                forwardJump is me.rerere.rikkahub.data.preferences.OutboxClaimResult.InFlightElsewhere,
            )

            // 3. Wall clock jumps BACKWARD -5 hours while monotonic elapsed passed 70s (> 60s) -> lease EXPIRES cleanly!
            val backwardJump = prefs.claimDeliveryAttempt(
                deliveryId = "d-lease",
                claimId = "c2",
                nowMs = 100L,
                currentBootId = "boot-10",
                currentProcessId = "proc-A",
                currentElapsedMs = 120_000L,
                maxTotalAttempts = 3,
                inFlightLeaseMs = 60_000L,
                recipientGate = ScheduleWebhookWorker::evaluateRecipientGate,
            )
            assertTrue(
                "Backward wall-clock jump must NOT pin expired monotonic lease",
                backwardJump is me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed,
            )
            assertEquals(2, (backwardJump as me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed).attemptNumber)

            // 4. Device reboots (boot-11, elapsed resets to 2_000 < 120_000) -> old lease from boot-10 immediately recovers!
            val afterReboot = prefs.claimDeliveryAttempt(
                deliveryId = "d-lease",
                claimId = "c3-after-reboot",
                nowMs = 200L,
                currentBootId = "boot-11",
                currentProcessId = "proc-B",
                currentElapsedMs = 2_000L,
                maxTotalAttempts = 3,
                inFlightLeaseMs = 60_000L,
                recipientGate = ScheduleWebhookWorker::evaluateRecipientGate,
            )
            assertTrue(
                "Cross-boot recovery must immediately release old boot's IN_FLIGHT lease",
                afterReboot is me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed,
            )
            assertEquals(3, (afterReboot as me.rerere.rikkahub.data.preferences.OutboxClaimResult.Claimed).attemptNumber)
        } finally {
            scope.cancel()
        }
    }
}
