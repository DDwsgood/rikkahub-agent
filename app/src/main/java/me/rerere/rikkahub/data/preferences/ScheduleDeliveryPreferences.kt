package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

private val Context.scheduleDeliveryDataStore by preferencesDataStore(name = "schedule_delivery_prefs")

@Serializable
enum class ScheduleWebhookPreset {
    WECOM,
    FEISHU,
    DINGTALK,
    SERVERCHAN,
    PUSHPLUS,
    WXPUSHER,
    ONEBOT_QQ,
    CUSTOM,
}

@Serializable
data class ScheduleDeliveryConfig(
    /**
     * Opt-in bounded partial WakeLock for [me.rerere.rikkahub.service.AgentKeepaliveService].
     * Defaults to `false`: the specialUse FGS notification keeps foreground priority without
     * holding a continuous screen-off CPU lock that can trigger OEM abnormal-power kills.
     */
    val keepaliveWakeLockEnabled: Boolean = false,
    /**
     * Optional system Calendar reminder mirror (requires runtime READ/WRITE_CALENDAR grant).
     * Mirrors upcoming scheduled jobs into the device's Calendar app as backup reminders
     * (does not run local AI code if the app process is stopped).
     */
    val calendarMirrorEnabled: Boolean = false,
    val calendarReminderMinutesBefore: Int = 0,
    /**
     * Optional external Webhook result delivery (decoupled from local alarm wake-up).
     */
    val webhookEnabled: Boolean = false,
    val webhookPreset: ScheduleWebhookPreset = ScheduleWebhookPreset.WECOM,
    val webhookUrl: String = "",
    val webhookSecretOrToken: String = "",
    val webhookTargetId: String = "",
    val webhookTargetType: String = "private",
    val webhookNotifyOnSuccess: Boolean = true,
    val webhookNotifyOnFailure: Boolean = true,
    /**
     * Explicit opt-in allowing cleartext `http://` ONLY for loopback/LAN private endpoints
     * (e.g. local OneBot QQ bridge on `127.0.0.1` or `192.168.x.x`). Public endpoints MUST use HTTPS.
     */
    val webhookAllowCleartextLocal: Boolean = false,
    /**
     * Non-reusable opaque version minted every time the Webhook destination changes (preset, URL,
     * secret/token, target ID/type, cleartext flag, or disabling). Bound via SHA-256 digest onto
     * queued deliveries so switching recipients (even A -> B -> A) can never misdeliver queued
     * payloads to a different recipient.
     */
    val webhookDestinationVersion: String = "",
) {
    val webhookUrlValidationError: String?
        get() = validateWebhookUrl(webhookUrl, webhookAllowCleartextLocal)

    val isWebhookUsable: Boolean
        get() = webhookEnabled && webhookUrlValidationError == null

    /**
     * Opaque, non-reversible SHA-256 binding digest identifying the current Webhook recipient
     * configuration and non-reusable [webhookDestinationVersion]. Safe to store in WorkManager
     * `Data` and outbox records without exposing raw URLs, tokens, or target IDs.
     */
    fun destinationBinding(): String {
        val secretHash = sha256Hex(webhookSecretOrToken.trim())
        val canonical = buildString {
            append(webhookDestinationVersion).append('|')
            append(webhookPreset.name).append('|')
            append(webhookUrl.trim()).append('|')
            append(webhookTargetId.trim()).append('|')
            append(webhookTargetType.trim()).append('|')
            append(webhookAllowCleartextLocal).append('|')
            append(secretHash)
        }
        return sha256Hex(canonical)
    }

    companion object {
        internal fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        internal fun hasDestinationChanged(
            old: ScheduleDeliveryConfig,
            next: ScheduleDeliveryConfig,
        ): Boolean =
            old.webhookPreset != next.webhookPreset ||
                old.webhookUrl.trim() != next.webhookUrl.trim() ||
                old.webhookSecretOrToken.trim() != next.webhookSecretOrToken.trim() ||
                old.webhookTargetId.trim() != next.webhookTargetId.trim() ||
                old.webhookTargetType.trim() != next.webhookTargetType.trim() ||
                old.webhookAllowCleartextLocal != next.webhookAllowCleartextLocal ||
                (old.webhookEnabled && !next.webhookEnabled)

        /**
         * Preserves [ScheduleDeliveryConfig.webhookDestinationVersion] when only unrelated flags
         * change, and mints a fresh non-reusable version whenever any destination field changes,
         * webhook is disabled, or an enabled destination has no version yet.
         */
        internal fun normalizeUpdatedConfig(
            old: ScheduleDeliveryConfig,
            next: ScheduleDeliveryConfig,
            newVersionSupplier: () -> String = { UUID.randomUUID().toString() },
        ): ScheduleDeliveryConfig = when {
            hasDestinationChanged(old, next) -> next.copy(webhookDestinationVersion = newVersionSupplier())
            next.webhookEnabled && old.webhookDestinationVersion.isBlank() && next.webhookDestinationVersion.isBlank() ->
                next.copy(webhookDestinationVersion = newVersionSupplier())
            else -> next.copy(webhookDestinationVersion = old.webhookDestinationVersion)
        }
        /**
         * Validates a webhook URL strictly via OkHttp's `HttpUrl` parser.
         * Returns `null` when valid, or a stable machine error code otherwise.
         */
        fun validateWebhookUrl(rawUrl: String, allowCleartextLocal: Boolean): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return "invalid_url"
            val parsed = trimmed.toHttpUrlOrNull() ?: return "invalid_url"
            val host = parsed.host.trim().lowercase()
            if (host.isEmpty()) return "invalid_url"
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "invalid_url"
            return when (parsed.scheme.lowercase()) {
                "https" -> null
                "http" -> {
                    if (!allowCleartextLocal) return "https_required"
                    if (isLocalOrPrivateHost(host)) null else "cleartext_non_local"
                }
                else -> "invalid_url"
            }
        }

        internal fun isLocalOrPrivateHost(host: String): Boolean {
            val cleaned = host.removePrefix("[").removeSuffix("]").lowercase()
            if (cleaned == "localhost" || cleaned == "127.0.0.1" || cleaned == "::1") return true
            // Check numeric IP literals without triggering blocking DNS resolution.
            val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
            val ipv4Match = ipv4Regex.matchEntire(cleaned)
            if (ipv4Match != null) {
                val octets = ipv4Match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
                if (octets.size == 4 && octets.all { it in 0..255 }) {
                    val (a, b) = octets[0] to octets[1]
                    return a == 127 ||
                        a == 10 ||
                        (a == 172 && b in 16..31) ||
                        (a == 192 && b == 168) ||
                        (a == 169 && b == 254)
                }
                return false
            }
            if (cleaned.contains(':')) {
                return runCatching {
                    val addr = InetAddress.getByName(cleaned)
                    addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
                }.getOrDefault(false)
            }
            return cleaned.endsWith(".local") || cleaned.endsWith(".lan")
        }
    }
}

@Serializable
enum class WebhookDeliveryState {
    /** Written BEFORE Room terminal commit; awaiting Room commit confirmation. */
    STAGED,
    /** Confirmed committed (decoupled from trimmable Room run history); eligible for worker claim. */
    COMMITTED,
    /** Currently claimed by a worker attempt (attempt budget already durably incremented). */
    IN_FLIGHT,
    /** Retryable failure settled; awaiting next WorkManager retry within persistent budget. */
    RETRY_WAIT,
    /** Terminal state (delivered, non-retryable failure, cancelled, exhausted, or uncommitted crash). */
    TERMINAL,
}

enum class OutboxStageResult {
    STAGED,
    ALREADY_PRESENT,
    CAPACITY_EXCEEDED,
}

sealed interface OutboxClaimResult {
    data class Claimed(
        val record: PendingWebhookDelivery,
        val liveConfig: ScheduleDeliveryConfig,
        val attemptNumber: Int,
        val maxAttempts: Int,
    ) : OutboxClaimResult

    data class AlreadySettled(val terminalSummary: String?) : OutboxClaimResult
    data object NotCommitted : OutboxClaimResult
    data object InFlightElsewhere : OutboxClaimResult
    data class Rejected(val reason: String) : OutboxClaimResult
    data class BudgetExhausted(val attemptsUsed: Int) : OutboxClaimResult
}

/**
 * Durable per-`deliveryId` state record for a scheduled-job Webhook delivery.
 *
 * Lifecycle (every transition is an atomic conditional update inside `dataStore.edit`):
 * 1. `STAGED` — written BEFORE Room commits the terminal run row.
 * 2. `COMMITTED` — promoted immediately AFTER Room commits the terminal run row (or by
 *    reconciliation after crash recovery). Once `COMMITTED`, delivery eligibility is independent
 *    of `scheduled_job_runs` history trimming (`runRepo.trim`).
 * 3. `IN_FLIGHT` — claimed atomically with `attemptsUsed` incremented BEFORE any HTTP socket opens,
 *    making the 3-attempt cap persistent across process kills, retries, and stale reconcile passes.
 * 4. `RETRY_WAIT` — transient failure with remaining persistent budget.
 * 5. `TERMINAL` — immutable tombstone preventing stale reconcile snapshots or re-enqueued workers
 *    from ever re-claiming or duplicating a settled `deliveryId`.
 *
 * Contains ZERO URLs, tokens, or secrets (only the opaque SHA-256 [destinationBinding]).
 */
@Serializable
data class PendingWebhookDelivery(
    val deliveryId: String,
    val runId: String,
    val jobId: String,
    val jobName: String,
    val mode: String,
    val outcome: String,
    val summary: String,
    val timestampMs: Long,
    val destinationBinding: String,
    val createdAtMs: Long,
    val state: WebhookDeliveryState = WebhookDeliveryState.STAGED,
    val attemptsUsed: Int = 0,
    val lastClaimId: String? = null,
    val lastClaimAtMs: Long = 0L,
    val terminalSummary: String? = null,
    val updatedAtMs: Long = createdAtMs,
    /** Expected Room run fields for exact-commit verification of STAGED records (P1-3). */
    val commitVerificationEnabled: Boolean = false,
    val expectedConversationId: String? = null,
    val expectedErrorMessage: String? = null,
    /** Owner process & boot identity + monotonic staging time for reboot-free orphan detection (P1-4). */
    val ownerProcessId: String? = null,
    val ownerBootId: String? = null,
    val stagedElapsedMs: Long = 0L,
    /** Boot/process identity & monotonic uptime of active IN_FLIGHT claim for clock-jump-safe leases (P2). */
    val lastClaimBootId: String? = null,
    val lastClaimProcessId: String? = null,
    val lastClaimElapsedMs: Long = 0L,
) {
    companion object {
        /**
         * Determines whether an `IN_FLIGHT` claim lease is still active using boot identity,
         * process identity, and monotonic `elapsedRealtime()` — completely immune to wall-clock
         * forward/backward jumps and immediate across process deaths or device reboots.
         */
        internal fun isInFlightLeaseActive(
            record: PendingWebhookDelivery,
            currentBootId: String,
            currentProcessId: String,
            currentElapsedMs: Long,
            leaseMs: Long,
        ): Boolean {
            if (record.state != WebhookDeliveryState.IN_FLIGHT) return false
            if (!record.lastClaimBootId.isNullOrBlank() && record.lastClaimBootId != currentBootId) {
                return false
            }
            if (!record.lastClaimProcessId.isNullOrBlank() && record.lastClaimProcessId != currentProcessId) {
                return false
            }
            // Legacy records without monotonic claim metadata degrade safely to expired on unknown owner.
            if (record.lastClaimProcessId.isNullOrBlank() && record.lastClaimElapsedMs == 0L) {
                return false
            }
            val elapsedDelta = currentElapsedMs - record.lastClaimElapsedMs
            return elapsedDelta in 0L until leaseMs
        }
    }
}

/**
 * Dedicated preferences store for scheduled-job WakeLock control, Calendar reminder mirror,
 * and external Webhook result delivery. Stored outside `Settings` so webhook URLs/tokens are
 * never serialized into Web UI `/api/events` streams and require no Room schema bump.
 */
class ScheduleDeliveryPreferences private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.scheduleDeliveryDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val flow: Flow<ScheduleDeliveryConfig> = dataStore.data.map { prefs ->
        decodeConfig(prefs[KEY_CONFIG])
    }

    val pendingCalendarDeleteCountFlow: Flow<Int> = dataStore.data.map { prefs ->
        decodePendingDeletes(prefs[KEY_PENDING_CALENDAR_DELETES]).size
    }

    suspend fun current(): ScheduleDeliveryConfig = flow.first()

    suspend fun update(transform: (ScheduleDeliveryConfig) -> ScheduleDeliveryConfig) {
        dataStore.edit { prefs ->
            val old = decodeConfig(prefs[KEY_CONFIG])
            val next = ScheduleDeliveryConfig.normalizeUpdatedConfig(old, transform(old))
            prefs[KEY_CONFIG] = json.encodeToString(ScheduleDeliveryConfig.serializer(), next)
        }
    }

    /**
     * Stages a new outbox entry (`STAGED`) BEFORE Room commits the terminal run row.
     *
     * - Never overwrites an existing entry with the same `deliveryId` (returns [OutboxStageResult.ALREADY_PRESENT]).
     * - Never silently evicts active non-terminal entries; if active entries are at [MAX_ACTIVE_OUTBOX],
     *   returns [OutboxStageResult.CAPACITY_EXCEEDED] so callers record an explicit diagnostic error.
     */
    suspend fun stagePendingWebhook(entry: PendingWebhookDelivery): OutboxStageResult {
        var result = OutboxStageResult.STAGED
        dataStore.edit { prefs ->
            val current = decodePendingWebhooks(prefs[KEY_PENDING_WEBHOOKS])
            if (current.any { it.deliveryId == entry.deliveryId }) {
                result = OutboxStageResult.ALREADY_PRESENT
                return@edit
            }
            val active = current.filter { it.state != WebhookDeliveryState.TERMINAL }
            if (active.size >= MAX_ACTIVE_OUTBOX) {
                result = OutboxStageResult.CAPACITY_EXCEEDED
                return@edit
            }
            val tombstones = pruneTombstones(current.filter { it.state == WebhookDeliveryState.TERMINAL })
            val staged = entry.copy(
                state = WebhookDeliveryState.STAGED,
                attemptsUsed = 0,
                lastClaimId = null,
                lastClaimAtMs = 0L,
                terminalSummary = null,
                updatedAtMs = entry.createdAtMs,
            )
            prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(active + staged + tombstones)
            result = OutboxStageResult.STAGED
        }
        return result
    }

    /**
     * Atomically promotes a `STAGED` record to `COMMITTED` after Room terminal commit,
     * permanently decoupling delivery eligibility from trimmable run history rows.
     */
    suspend fun markOutboxCommitted(
        deliveryId: String,
        nowMs: Long = System.currentTimeMillis(),
        expectedRunId: String,
    ): Boolean {
        var committed = false
        dataStore.edit { prefs ->
            val current = decodePendingWebhooks(prefs[KEY_PENDING_WEBHOOKS]).toMutableList()
            val idx = current.indexOfFirst { it.deliveryId == deliveryId }
            if (idx < 0) return@edit
            val existing = current[idx]
            if (existing.runId != expectedRunId) return@edit
            when (existing.state) {
                WebhookDeliveryState.STAGED -> {
                    current[idx] = existing.copy(
                        state = WebhookDeliveryState.COMMITTED,
                        updatedAtMs = nowMs,
                    )
                    prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(current)
                    committed = true
                }
                WebhookDeliveryState.COMMITTED,
                WebhookDeliveryState.IN_FLIGHT,
                WebhookDeliveryState.RETRY_WAIT -> {
                    committed = true
                }
                WebhookDeliveryState.TERMINAL -> {
                    committed = false
                }
            }
        }
        return committed
    }

    /**
     * Atomically transitions [deliveryId] to `TERMINAL` (if not already `TERMINAL` and matching
     * [onlyIfStateIn] when specified). Retains bounded tombstones so stale reconcile snapshots can
     * never re-claim a completed delivery.
     */
    suspend fun markOutboxTerminal(
        deliveryId: String,
        summary: String,
        nowMs: Long = System.currentTimeMillis(),
        onlyIfStateIn: Set<WebhookDeliveryState>? = null,
        expectedRunId: String? = null,
    ): Boolean {
        var transitioned = false
        dataStore.edit { prefs ->
            val current = decodePendingWebhooks(prefs[KEY_PENDING_WEBHOOKS]).toMutableList()
            val idx = current.indexOfFirst { it.deliveryId == deliveryId }
            if (idx < 0) return@edit
            val existing = current[idx]
            if (expectedRunId != null && existing.runId != expectedRunId) return@edit
            if (existing.state == WebhookDeliveryState.TERMINAL) return@edit
            if (onlyIfStateIn != null && existing.state !in onlyIfStateIn) return@edit
            current[idx] = existing.copy(
                state = WebhookDeliveryState.TERMINAL,
                terminalSummary = summary,
                updatedAtMs = nowMs,
            )
            val active = current.filter { it.state != WebhookDeliveryState.TERMINAL }
            val tombstones = pruneTombstones(current.filter { it.state == WebhookDeliveryState.TERMINAL })
            prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(active + tombstones)
            transitioned = true
        }
        return transitioned
    }

    /**
     * Atomically checks live recipient binding, state, monotonic boot/process lease, and persistent
     * total attempt budget, transitioning the record to `IN_FLIGHT` and incrementing `attemptsUsed`
     * BEFORE any HTTP call.
     */
    suspend fun claimDeliveryAttempt(
        deliveryId: String,
        claimId: String,
        nowMs: Long,
        currentBootId: String,
        currentProcessId: String,
        currentElapsedMs: Long,
        maxTotalAttempts: Int,
        inFlightLeaseMs: Long,
        recipientGate: (ScheduleDeliveryConfig, String) -> String?,
    ): OutboxClaimResult {
        var result: OutboxClaimResult = OutboxClaimResult.AlreadySettled(null)
        dataStore.edit { prefs ->
            val liveConfig = decodeConfig(prefs[KEY_CONFIG])
            val current = decodePendingWebhooks(prefs[KEY_PENDING_WEBHOOKS]).toMutableList()
            val idx = current.indexOfFirst { it.deliveryId == deliveryId }
            if (idx < 0) {
                result = OutboxClaimResult.AlreadySettled(null)
                return@edit
            }
            val record = current[idx]
            if (record.state == WebhookDeliveryState.TERMINAL) {
                result = OutboxClaimResult.AlreadySettled(record.terminalSummary)
                return@edit
            }
            if (record.state == WebhookDeliveryState.STAGED) {
                result = OutboxClaimResult.NotCommitted
                return@edit
            }
            val rejection = recipientGate(liveConfig, record.destinationBinding)
            if (rejection != null) {
                current[idx] = record.copy(
                    state = WebhookDeliveryState.TERMINAL,
                    terminalSummary = rejection,
                    updatedAtMs = nowMs,
                )
                val active = current.filter { it.state != WebhookDeliveryState.TERMINAL }
                val tombstones = pruneTombstones(current.filter { it.state == WebhookDeliveryState.TERMINAL })
                prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(active + tombstones)
                result = OutboxClaimResult.Rejected(rejection)
                return@edit
            }
            if (
                PendingWebhookDelivery.isInFlightLeaseActive(
                    record = record,
                    currentBootId = currentBootId,
                    currentProcessId = currentProcessId,
                    currentElapsedMs = currentElapsedMs,
                    leaseMs = inFlightLeaseMs,
                )
            ) {
                result = OutboxClaimResult.InFlightElsewhere
                return@edit
            }
            if (record.attemptsUsed >= maxTotalAttempts) {
                current[idx] = record.copy(
                    state = WebhookDeliveryState.TERMINAL,
                    terminalSummary = "webhook:attempts_exhausted",
                    updatedAtMs = nowMs,
                )
                val active = current.filter { it.state != WebhookDeliveryState.TERMINAL }
                val tombstones = pruneTombstones(current.filter { it.state == WebhookDeliveryState.TERMINAL })
                prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(active + tombstones)
                result = OutboxClaimResult.BudgetExhausted(record.attemptsUsed)
                return@edit
            }
            val claimed = record.copy(
                state = WebhookDeliveryState.IN_FLIGHT,
                attemptsUsed = record.attemptsUsed + 1,
                lastClaimId = claimId,
                lastClaimAtMs = nowMs,
                lastClaimBootId = currentBootId,
                lastClaimProcessId = currentProcessId,
                lastClaimElapsedMs = currentElapsedMs,
                updatedAtMs = nowMs,
            )
            current[idx] = claimed
            prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(current)
            result = OutboxClaimResult.Claimed(
                record = claimed,
                liveConfig = liveConfig,
                attemptNumber = claimed.attemptsUsed,
                maxAttempts = maxTotalAttempts,
            )
        }
        return result
    }

    /**
     * Atomically settles an in-flight delivery attempt into either `TERMINAL` (returns `true`)
     * or `RETRY_WAIT` (returns `false`) based on outcome and persistent `attemptsUsed`.
     */
    suspend fun settleDeliveryAttempt(
        deliveryId: String,
        claimId: String,
        success: Boolean,
        retryable: Boolean,
        summary: String,
        nowMs: Long,
        maxTotalAttempts: Int,
    ): Boolean {
        var settledTerminal = true
        dataStore.edit { prefs ->
            val current = decodePendingWebhooks(prefs[KEY_PENDING_WEBHOOKS]).toMutableList()
            val idx = current.indexOfFirst { it.deliveryId == deliveryId }
            if (idx < 0) {
                settledTerminal = true
                return@edit
            }
            val record = current[idx]
            if (record.state == WebhookDeliveryState.TERMINAL) {
                settledTerminal = true
                return@edit
            }
            if (record.lastClaimId != null && record.lastClaimId != claimId) {
                settledTerminal = record.attemptsUsed >= maxTotalAttempts
                return@edit
            }
            val isTerminal = success || !retryable || record.attemptsUsed >= maxTotalAttempts
            current[idx] = if (isTerminal) {
                record.copy(
                    state = WebhookDeliveryState.TERMINAL,
                    terminalSummary = summary,
                    updatedAtMs = nowMs,
                )
            } else {
                record.copy(
                    state = WebhookDeliveryState.RETRY_WAIT,
                    updatedAtMs = nowMs,
                )
            }
            val active = current.filter { it.state != WebhookDeliveryState.TERMINAL }
            val tombstones = pruneTombstones(current.filter { it.state == WebhookDeliveryState.TERMINAL })
            prefs[KEY_PENDING_WEBHOOKS] = encodeOutbox(active + tombstones)
            settledTerminal = isTerminal
        }
        return settledTerminal
    }

    suspend fun allOutboxRecords(): List<PendingWebhookDelivery> =
        decodePendingWebhooks(dataStore.data.first()[KEY_PENDING_WEBHOOKS])

    suspend fun allPendingWebhooks(): List<PendingWebhookDelivery> =
        allOutboxRecords().filter { it.state != WebhookDeliveryState.TERMINAL }

    suspend fun getOutboxRecord(deliveryId: String): PendingWebhookDelivery? =
        allOutboxRecords().firstOrNull { it.deliveryId == deliveryId }

    suspend fun hasActivePendingWebhooks(): Boolean =
        allOutboxRecords().any { it.state != WebhookDeliveryState.TERMINAL }

    private fun pruneTombstones(tombstones: List<PendingWebhookDelivery>): List<PendingWebhookDelivery> =
        if (tombstones.size > MAX_TERMINAL_TOMBSTONES) {
            tombstones.sortedBy { it.updatedAtMs }.takeLast(MAX_TERMINAL_TOMBSTONES)
        } else {
            tombstones
        }

    private fun encodeOutbox(records: List<PendingWebhookDelivery>): String =
        json.encodeToString(ListSerializer(PendingWebhookDelivery.serializer()), records)

    suspend fun calendarEventIdFor(jobId: String): Long? {
        val map = decodeCalendarMap(dataStore.data.first()[KEY_CALENDAR_EVENTS])
        return map[jobId]
    }

    suspend fun allCalendarEvents(): Map<String, Long> =
        decodeCalendarMap(dataStore.data.first()[KEY_CALENDAR_EVENTS])

    suspend fun putCalendarEventId(jobId: String, eventId: Long) {
        dataStore.edit { prefs ->
            val map = decodeCalendarMap(prefs[KEY_CALENDAR_EVENTS]).toMutableMap()
            map[jobId] = eventId
            prefs[KEY_CALENDAR_EVENTS] = json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                map,
            )
        }
    }

    suspend fun removeCalendarEventId(jobId: String): Long? {
        var removed: Long? = null
        dataStore.edit { prefs ->
            val map = decodeCalendarMap(prefs[KEY_CALENDAR_EVENTS]).toMutableMap()
            removed = map.remove(jobId)
            if (map.isEmpty()) {
                prefs.remove(KEY_CALENDAR_EVENTS)
            } else {
                prefs[KEY_CALENDAR_EVENTS] = json.encodeToString(
                    MapSerializer(String.serializer(), Long.serializer()),
                    map,
                )
            }
        }
        return removed
    }

    suspend fun pendingCalendarDeletes(): Set<Long> =
        decodePendingDeletes(dataStore.data.first()[KEY_PENDING_CALENDAR_DELETES])

    suspend fun addPendingCalendarDelete(eventId: Long) {
        dataStore.edit { prefs ->
            val next = decodePendingDeletes(prefs[KEY_PENDING_CALENDAR_DELETES]) + eventId
            prefs[KEY_PENDING_CALENDAR_DELETES] = json.encodeToString(SetSerializer(Long.serializer()), next)
        }
    }

    suspend fun removePendingCalendarDelete(eventId: Long) {
        dataStore.edit { prefs ->
            val next = decodePendingDeletes(prefs[KEY_PENDING_CALENDAR_DELETES]) - eventId
            if (next.isEmpty()) {
                prefs.remove(KEY_PENDING_CALENDAR_DELETES)
            } else {
                prefs[KEY_PENDING_CALENDAR_DELETES] = json.encodeToString(SetSerializer(Long.serializer()), next)
            }
            // Also prune any stale jobId -> eventId mapping pointing to this deleted event ID.
            val currentMap = decodeCalendarMap(prefs[KEY_CALENDAR_EVENTS])
            if (currentMap.values.any { it == eventId }) {
                val pruned = currentMap.filterValues { it != eventId }
                if (pruned.isEmpty()) {
                    prefs.remove(KEY_CALENDAR_EVENTS)
                } else {
                    prefs[KEY_CALENDAR_EVENTS] = json.encodeToString(
                        MapSerializer(String.serializer(), Long.serializer()),
                        pruned,
                    )
                }
            }
        }
    }

    private fun decodeConfig(raw: String?): ScheduleDeliveryConfig {
        if (raw.isNullOrBlank()) return ScheduleDeliveryConfig()
        return runCatching {
            json.decodeFromString(ScheduleDeliveryConfig.serializer(), raw)
        }.getOrDefault(ScheduleDeliveryConfig())
    }

    private fun decodePendingWebhooks(raw: String?): List<PendingWebhookDelivery> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(PendingWebhookDelivery.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeCalendarMap(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), raw)
        }.getOrDefault(emptyMap())
    }

    private fun decodePendingDeletes(raw: String?): Set<Long> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            json.decodeFromString(SetSerializer(Long.serializer()), raw)
        }.getOrDefault(emptySet())
    }

    companion object {
        private val KEY_CONFIG = stringPreferencesKey("config_json")
        private val KEY_CALENDAR_EVENTS = stringPreferencesKey("calendar_event_ids_json")
        private val KEY_PENDING_CALENDAR_DELETES = stringPreferencesKey("pending_calendar_deletes_json")
        private val KEY_PENDING_WEBHOOKS = stringPreferencesKey("pending_webhooks_json")
        internal const val MAX_ACTIVE_OUTBOX = 200
        internal const val MAX_TERMINAL_TOMBSTONES = 128

        internal fun createForTest(dataStore: DataStore<Preferences>): ScheduleDeliveryPreferences =
            ScheduleDeliveryPreferences(dataStore)
    }
}
