package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.utils.RomUtils
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.net.InetAddress
import java.io.File

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> = setOf(
        LocalToolOption.Notification,        // post_notification tool
        LocalToolOption.TelegramBot,         // FGS notification
        LocalToolOption.CronJobs,            // CronJobWorker FGS notification
        LocalToolOption.Workflows,           // WorkflowTimeCronWorker FGS notification
    )
    val FineLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Location,            // get_location, geocode tools
        LocalToolOption.WifiInfo,            // SSID/BSSID on Android 10+
        LocalToolOption.Workflows,           // geofence_enter / geofence_exit triggers
    )
    val NotificationListener: Set<LocalToolOption> = setOf(
        LocalToolOption.NotificationListener,
        LocalToolOption.Workflows,           // notification_received trigger
    )
    val Accessibility: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // swipe, click_at, scroll, gesture
    )
    val Termux: Set<LocalToolOption> = setOf(
        LocalToolOption.Termux,
        LocalToolOption.SpeechToText,        // transcribe_audio_file uses Termux + whisper.cpp
        LocalToolOption.Ssh,                 // ssh_exec calls into termux ssh
    )
    val BatteryWhitelist: Set<LocalToolOption> = setOf(
        LocalToolOption.TelegramBot,         // long-poll loop
        LocalToolOption.CronJobs,            // worker fires
        LocalToolOption.Workflows,           // trigger receivers + cron worker
    )
    val AllFiles: Set<LocalToolOption> = setOf(
        LocalToolOption.Files,               // file_read / file_write to arbitrary paths
    )
    val Browser: Set<LocalToolOption> = setOf(
        LocalToolOption.Browser,             // 17 browser tools (in-app WebView)
    )
    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> = setOf(
        LocalToolOption.SmsSend,
    )
    val Nfc: Set<LocalToolOption> = setOf(
        LocalToolOption.Nfc,
    )
    // Permissions that previously had no Doctor check at all. Each is gated on the tool that
    // actually needs it, so a denied perm only WARNs when its feature is enabled (opt-in) and
    // stays INFO otherwise. Closes the "Doctor reported all-clear while overlay etc. were denied"
    // gap.
    val Overlay: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // "agent is working" overlay during automation
    )
    val WriteSettings: Set<LocalToolOption> = setOf(
        LocalToolOption.Brightness,          // set_brightness writes Settings.System
    )
    val BluetoothConnect: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // workflow Bluetooth triggers read paired-device state
    )
    val NearbyWifi: Set<LocalToolOption> = setOf(
        LocalToolOption.WifiInfo,            // WiFi scan/info on Android 13+
    )
    val BackgroundLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // geofence triggers fire while the app is closed
    )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(context: Context): String = when (this) {
    LocalToolOption.Location -> context.getString(R.string.doctor_tool_location)
    LocalToolOption.WifiInfo -> context.getString(R.string.doctor_tool_wifi_info)
    LocalToolOption.NotificationListener -> context.getString(R.string.doctor_tool_notification_listener)
    LocalToolOption.ScreenAutomation -> context.getString(R.string.doctor_tool_screen_automation)
    LocalToolOption.Termux -> "Termux"
    LocalToolOption.SpeechToText -> context.getString(R.string.doctor_tool_speech_to_text)
    LocalToolOption.Ssh -> "SSH"
    LocalToolOption.TelegramBot -> context.getString(R.string.doctor_tool_telegram_bot)
    LocalToolOption.CronJobs -> context.getString(R.string.doctor_tool_cron_jobs)
    LocalToolOption.Workflows -> context.getString(R.string.doctor_tool_workflows)
    LocalToolOption.Notification -> context.getString(R.string.doctor_tool_notification)
    LocalToolOption.Files -> context.getString(R.string.doctor_tool_files)
    LocalToolOption.Browser -> context.getString(R.string.doctor_tool_browser)
    LocalToolOption.SmsSend -> context.getString(R.string.doctor_tool_sms_send)
    LocalToolOption.ShareFile -> context.getString(R.string.doctor_tool_share_file)
    LocalToolOption.Wallpaper -> context.getString(R.string.doctor_tool_wallpaper)
    LocalToolOption.Keystore -> context.getString(R.string.doctor_tool_keystore)
    LocalToolOption.Nfc -> context.getString(R.string.doctor_tool_nfc)
    LocalToolOption.ExternalStorage -> context.getString(R.string.doctor_tool_external_storage)
    LocalToolOption.Archive -> context.getString(R.string.doctor_tool_archive)
    else -> this::class.simpleName ?: "?"
}

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
) {
    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * Row rendered when [runAll] itself throws (a bug in a check, not a device problem).
     * Lives here rather than in the ViewModel so the message can be localised.
     */
    fun errorRow(t: Throwable): DoctorCheck = DoctorCheck(
        id = "doctor.error",
        category = DoctorCategory.Diagnostics,
        label = str(R.string.doctor_error_label),
        detail = str(
            R.string.doctor_error_detail,
            t::class.simpleName ?: "?",
            t.message ?: str(R.string.doctor_error_no_message),
        ),
        severity = Severity.FAIL,
    )

    suspend fun runAll(): List<DoctorCheck> = withContext(Dispatchers.IO) {
        // Aggregate enabled tools across every assistant. A tool is "in use" if at least
        // one assistant has its LocalToolOption switched on. The Doctor uses this to
        // decide whether a missing capability is actually a problem worth flagging.
        val enabled: Set<LocalToolOption> = runCatching {
            settingsStore.settingsFlow.first().assistants.flatMap { it.localTools }.toSet()
        }.getOrDefault(emptySet())

        buildList {
            addAll(permissionChecks(enabled))
            addAll(serviceChecks(enabled))
            addAll(reliabilityChecks(enabled))
            addAll(assistantChecks())
            addAll(databaseChecks(enabled))
            addAll(networkChecks())
            addAll(termuxChecks(enabled))
            addAll(browserChecks(enabled))
            addAll(maintenanceChecks())
            addAll(diagnosticsChecks(enabled))
        }
    }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(cap: Set<LocalToolOption>, enabled: Set<LocalToolOption>): List<LocalToolOption> =
        cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        add(
            capabilityRow(
                id = "perm.notifications",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_post_notifications),
                cap = Capability.Notifications,
                enabled = enabled,
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                grantedDetail = str(R.string.doctor_detail_granted),
                missingDetail = str(R.string.doctor_detail_notifications_required),
                fix = FixAction.OpenAppRoute(
                    str(R.string.doctor_fix_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.location",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_fine_location),
                cap = Capability.FineLocation,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                grantedDetail = str(R.string.doctor_detail_granted),
                missingDetail = str(R.string.doctor_detail_location_required),
                fix = FixAction.OpenAppRoute(
                    str(R.string.doctor_fix_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.battery_opt",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_battery_whitelist),
                cap = Capability.BatteryWhitelist,
                enabled = enabled,
                granted = PermissionHelper.ignoresBatteryOptimizations(context),
                grantedDetail = str(R.string.doctor_detail_battery_granted),
                missingDetail = str(R.string.doctor_detail_battery_missing),
                fix = FixAction.OpenIntent(
                    label = str(R.string.doctor_fix_request_whitelist),
                    intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.notification_listener",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_notification_listener),
                cap = Capability.NotificationListener,
                enabled = enabled,
                granted = PermissionHelper.hasNotificationListener(context),
                grantedDetail = str(R.string.doctor_detail_listener_granted),
                missingDetail = str(R.string.doctor_detail_listener_missing),
                fix = FixAction.OpenIntent(
                    label = str(R.string.doctor_fix_open_settings),
                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.accessibility",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_accessibility_service),
                cap = Capability.Accessibility,
                enabled = enabled,
                granted = PermissionHelper.hasAccessibilityService(context),
                grantedDetail = str(R.string.doctor_detail_accessibility_granted),
                missingDetail = str(R.string.doctor_detail_accessibility_missing),
                fix = FixAction.OpenIntent(
                    label = str(R.string.doctor_fix_open_settings),
                    intent = PermissionHelper.accessibilitySettingsIntent(),
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                capabilityRow(
                    id = "perm.all_files",
                    category = DoctorCategory.Permissions,
                    label = str(R.string.doctor_label_all_files),
                    cap = Capability.AllFiles,
                    enabled = enabled,
                    granted = PermissionHelper.hasAllFilesAccess(context),
                    grantedDetail = str(R.string.doctor_detail_all_files_granted),
                    missingDetail = str(R.string.doctor_detail_all_files_missing),
                    fix = FixAction.OpenIntent(
                        label = str(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.allFilesAccessIntent(context),
                    ),
                )
            )
        }
        // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
        add(
            capabilityRow(
                id = "perm.send_sms",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_send_sms),
                cap = Capability.SendSms,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                grantedDetail = str(R.string.doctor_detail_granted),
                missingDetail = str(R.string.doctor_detail_sms_missing),
                fix = FixAction.OpenAppRoute(
                    str(R.string.doctor_fix_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        // Previously-unchecked permissions, now covered. Each is tool-aware: it only WARNs when
        // the feature that needs it is enabled, so the opt-in philosophy holds (a denied perm for
        // a disabled tool stays INFO). This is what fixes the "Doctor said all-clear while
        // Display-over-other-apps etc. were ungranted" report.
        add(
            capabilityRow(
                id = "perm.overlay",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_overlay),
                cap = Capability.Overlay,
                enabled = enabled,
                granted = android.provider.Settings.canDrawOverlays(context),
                grantedDetail = str(R.string.doctor_detail_granted),
                missingDetail = str(R.string.doctor_detail_overlay_missing),
                fix = FixAction.OpenAppRoute(
                    str(R.string.doctor_fix_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.write_settings",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_write_settings),
                cap = Capability.WriteSettings,
                enabled = enabled,
                granted = PermissionHelper.hasWriteSettings(context),
                grantedDetail = str(R.string.doctor_detail_granted),
                missingDetail = str(R.string.doctor_detail_write_settings_missing),
                fix = FixAction.OpenAppRoute(
                    str(R.string.doctor_fix_open_app_permissions),
                    AppRouteKey.SettingPermissions,
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                capabilityRow(
                    id = "perm.bluetooth_connect",
                    category = DoctorCategory.Permissions,
                    label = str(R.string.doctor_label_bluetooth_connect),
                    cap = Capability.BluetoothConnect,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)),
                    grantedDetail = str(R.string.doctor_detail_granted),
                    missingDetail = str(R.string.doctor_detail_bluetooth_missing),
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_app_permissions),
                        AppRouteKey.SettingPermissions,
                    ),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                capabilityRow(
                    id = "perm.nearby_wifi",
                    category = DoctorCategory.Permissions,
                    label = str(R.string.doctor_label_nearby_wifi),
                    cap = Capability.NearbyWifi,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
                    grantedDetail = str(R.string.doctor_detail_granted),
                    missingDetail = str(R.string.doctor_detail_nearby_wifi_missing),
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_app_permissions),
                        AppRouteKey.SettingPermissions,
                    ),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                capabilityRow(
                    id = "perm.background_location",
                    category = DoctorCategory.Permissions,
                    label = str(R.string.doctor_label_background_location),
                    cap = Capability.BackgroundLocation,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                    grantedDetail = str(R.string.doctor_detail_granted),
                    missingDetail = str(R.string.doctor_detail_background_location_missing),
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_app_permissions),
                        AppRouteKey.SettingPermissions,
                    ),
                )
            )
        }
        // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
        // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
        run {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
            val nfcNeeders = requirersOf(Capability.Nfc, enabled)
            when {
                adapter == null -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = str(R.string.doctor_label_nfc),
                        detail = str(R.string.doctor_detail_nfc_no_hardware),
                        severity = Severity.INFO,
                    )
                )
                !adapter.isEnabled -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = str(R.string.doctor_label_nfc),
                        detail = if (nfcNeeders.isEmpty())
                            str(R.string.doctor_detail_nfc_off_not_required)
                        else
                            str(
                                R.string.doctor_detail_nfc_off_needed,
                                nfcNeeders.joinToString(", ") { it.shortName(context) },
                            ),
                        severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                        fix = if (nfcNeeders.isEmpty()) null else FixAction.OpenIntent(
                            label = str(R.string.doctor_fix_open_nfc_settings),
                            intent = android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    )
                )
                else -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = str(R.string.doctor_label_nfc),
                        detail = str(R.string.doctor_detail_nfc_ok),
                        severity = Severity.OK,
                    )
                )
            }
        }
    }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        label: String,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity = when {
            granted -> Severity.OK
            needers.isEmpty() -> Severity.INFO
            else -> Severity.WARN
        }
        val detail = when {
            granted -> grantedDetail
            needers.isEmpty() -> str(R.string.doctor_detail_not_required)
            else -> str(
                R.string.doctor_detail_needed_by,
                missingDetail,
                needers.joinToString(", ") { it.shortName(context) },
            )
        }
        return DoctorCheck(
            id = id,
            category = category,
            label = label,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val tg = telegramPrefs.current()
        // Telegram bot: token, enabled flag, FGS state should agree.
        if (tg.enabled) {
            add(
                DoctorCheck(
                    id = "service.telegram_token",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_telegram_token),
                    // Don't render any portion of the token — Telegram bot tokens are
                    // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                    // bot id, which an attacker could use to enumerate bot endpoints.
                    detail = if (tg.token.isNotBlank()) str(
                        R.string.doctor_detail_telegram_token_configured,
                        tg.token.length,
                    )
                    else str(R.string.doctor_detail_telegram_no_token),
                    severity = if (tg.token.isNotBlank()) Severity.OK else Severity.FAIL,
                    fix = if (tg.token.isBlank())
                        FixAction.OpenAppRoute(
                            str(R.string.doctor_fix_open_telegram_settings),
                            AppRouteKey.SettingTelegram,
                        )
                    else null,
                )
            )
            add(
                DoctorCheck(
                    id = "service.telegram_running",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_telegram_service),
                    detail = if (TelegramBotService.isRunning) str(R.string.doctor_detail_telegram_running)
                    else str(R.string.doctor_detail_telegram_stopped),
                    severity = when {
                        TelegramBotService.isRunning -> Severity.OK
                        tg.token.isBlank() -> Severity.INFO  // token issue covers this
                        else -> Severity.FAIL
                    },
                )
            )
        } else {
            add(
                DoctorCheck(
                    id = "service.telegram_off",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_telegram_bot),
                    detail = str(R.string.doctor_detail_telegram_disabled),
                    severity = Severity.INFO,
                )
            )
        }
        // AccessibilityService binding — only flagged if a tool that needs it is enabled.
        val accNeeders = requirersOf(Capability.Accessibility, enabled)
        if (accNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.accessibility_bound",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_accessibility_bound),
                    detail = if (AccessibilityServiceHandle.isRunning())
                        str(
                            R.string.doctor_detail_accessibility_bound_ok,
                            accNeeders.joinToString(", ") { it.shortName(context) },
                        )
                    else if (PermissionHelper.hasAccessibilityService(context))
                        str(R.string.doctor_detail_accessibility_bound_stale)
                    else
                        str(
                            R.string.doctor_detail_accessibility_bound_missing,
                            accNeeders.joinToString(", ") { it.shortName(context) },
                        ),
                    severity = when {
                        AccessibilityServiceHandle.isRunning() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!AccessibilityServiceHandle.isRunning()) FixAction.OpenIntent(
                        label = str(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.accessibilitySettingsIntent(),
                    ) else null,
                )
            )
        }
        // NotificationListener binding — same logic.
        val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
        if (nlNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.notification_listener_bound",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_notification_listener_bound),
                    detail = if (NotificationListenerHandle.isBound())
                        str(
                            R.string.doctor_detail_listener_bound_ok,
                            nlNeeders.joinToString(", ") { it.shortName(context) },
                        )
                    else if (PermissionHelper.hasNotificationListener(context))
                        str(R.string.doctor_detail_listener_bound_stale)
                    else
                        str(
                            R.string.doctor_detail_listener_bound_missing,
                            nlNeeders.joinToString(", ") { it.shortName(context) },
                        ),
                    severity = when {
                        NotificationListenerHandle.isBound() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!NotificationListenerHandle.isBound()) FixAction.OpenIntent(
                        label = str(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.notificationListenerSettingsIntent(),
                    ) else null,
                )
            )
        }
    }

    // ----- OEM reliability -------------------------------------------------------------

    /**
     * OEM background-kill surface: ROM aggressiveness, last process-death cause
     * (ApplicationExitInfo — OEM cleaners leave REASON_OTHER / SIGNALED-SIGKILL /
     * EXCESSIVE_RESOURCE_USAGE / FREEZER), an honest "can't detect autostart" row, and the
     * Android 12+ phantom-process advisory (only when a Termux-using tool is enabled —
     * long-running shell children are what the killer actually hits).
     *
     * None of these rows pretend to verify state that isn't readable: autostart and the
     * per-ROM battery toggles have no query API, so the row asks the user to confirm
     * instead of faking a pass/fail.
     */
    private fun reliabilityChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val rom = RomUtils.detect()
        add(
            DoctorCheck(
                id = "service.rom",
                category = DoctorCategory.Services,
                label = str(R.string.doctor_label_device_rom),
                detail = str(
                    R.string.doctor_detail_rom,
                    rom.displayName + if (rom.version.isNotEmpty()) " ${rom.version}" else "",
                    rom.aggressiveness.name.lowercase(),
                ) + if (rom.needsWarning) " " + str(R.string.doctor_detail_rom_warning) else "",
                severity = when {
                    rom.needsWarning -> Severity.WARN
                    rom.aggressiveness == RomUtils.Aggressiveness.MODERATE -> Severity.INFO
                    else -> Severity.OK
                },
                fix = if (rom.needsWarning)
                    FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_background_settings),
                        AppRouteKey.SettingReliability,
                    )
                else null,
            )
        )

        // Autostart state is not readable on any ROM — show the manual-confirmation hint
        // only where the concept actually exists (aggressive OEMs).
        if (rom.needsWarning) {
            add(
                DoctorCheck(
                    id = "service.autostart",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_autostart),
                    detail = str(R.string.doctor_detail_autostart),
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_autostart_settings),
                        AppRouteKey.SettingReliability,
                    ),
                )
            )
        }

        // Last process-exit cause. OEM force-kills surface as REASON_OTHER, REASON_SIGNALED
        // with SIGKILL, REASON_EXCESSIVE_RESOURCE_USAGE, or REASON_FREEZER.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val last = runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                    ?.firstOrNull()
            }.getOrNull()
            if (last != null) {
                val at = java.text.DateFormat.getDateTimeInstance()
                    .format(java.util.Date(last.timestamp))
                val sigkill = last.reason == ApplicationExitInfo.REASON_SIGNALED && last.status == 9
                val killed = last.reason == ApplicationExitInfo.REASON_OTHER ||
                    last.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                    last.reason == ApplicationExitInfo.REASON_FREEZER ||
                    sigkill
                val userStop = last.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                    last.reason == ApplicationExitInfo.REASON_USER_STOPPED
                val reasonName = exitReasonName(last.reason)
                add(
                    DoctorCheck(
                        id = "service.last_exit",
                        category = DoctorCategory.Services,
                        label = str(R.string.doctor_label_last_exit),
                        detail = when {
                            killed -> str(
                                R.string.doctor_detail_last_exit_killed,
                                reasonName,
                                if (sigkill) str(R.string.doctor_detail_last_exit_sigkill) else "",
                                at,
                            )
                            userStop -> str(R.string.doctor_detail_last_exit_user, reasonName, at)
                            else -> str(R.string.doctor_detail_last_exit_other, reasonName, at)
                        },
                        severity = if (killed) Severity.WARN else Severity.INFO,
                        fix = if (killed)
                            FixAction.OpenAppRoute(
                                str(R.string.doctor_fix_open_background_settings),
                                AppRouteKey.SettingReliability,
                            )
                        else null,
                    )
                )
            }
        }

        // Android 12+ phantom process killer — only matters when the built-in Termux /
        // terminal tooling is enabled (long-running shell children get reaped).
        val termuxNeeders = requirersOf(Capability.Termux, enabled)
        if (termuxNeeders.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                DoctorCheck(
                    id = "service.phantom_procs",
                    category = DoctorCategory.Services,
                    label = str(R.string.doctor_label_phantom_process),
                    detail = str(
                        R.string.doctor_detail_phantom_process,
                        termuxNeeders.joinToString(", ") { it.shortName(context) },
                    ),
                    severity = Severity.WARN,
                )
            )
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants
            val defaultAssistant = settings.getCurrentAssistant()

            // Row 1: default assistant name + id
            add(
                DoctorCheck(
                    id = "assistant.default",
                    category = DoctorCategory.AssistantInfo,
                    label = str(R.string.doctor_label_default_assistant),
                    detail = if (assistants.isEmpty())
                        str(R.string.doctor_detail_no_assistants)
                    else
                        str(
                            R.string.doctor_detail_default_assistant,
                            defaultAssistant.name.ifBlank { str(R.string.doctor_detail_unnamed) },
                            defaultAssistant.id.toString().take(8),
                        ),
                    severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_assistants),
                        AppRouteKey.Assistant,
                    ),
                )
            )

            // Row 2: total assistant count
            add(
                DoctorCheck(
                    id = "assistant.count",
                    category = DoctorCategory.AssistantInfo,
                    label = str(R.string.doctor_label_assistant_count),
                    detail = str(R.string.doctor_detail_assistant_count, assistants.size),
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_assistants),
                        AppRouteKey.Assistant,
                    ),
                )
            )

            // Row 3: Telegram-bot assistant override (if set)
            val tg = telegramPrefs.current()
            if (tg.enabled && tg.assistantId != null) {
                val tgAssistant = tg.assistantId.let { id ->
                    runCatching {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        assistants.find { it.id == uuid }
                    }.getOrNull()
                }
                add(
                    DoctorCheck(
                        id = "assistant.telegram_override",
                        category = DoctorCategory.AssistantInfo,
                        label = str(R.string.doctor_label_telegram_override),
                        detail = when {
                            tgAssistant != null ->
                                str(
                                    R.string.doctor_detail_telegram_override_ok,
                                    tgAssistant.name.ifBlank { str(R.string.doctor_detail_unnamed) },
                                    tgAssistant.id.toString().take(8),
                                )
                            else ->
                                str(
                                    R.string.doctor_detail_telegram_override_stale,
                                    tg.assistantId.take(8),
                                )
                        },
                        severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                        fix = if (tgAssistant == null)
                            FixAction.OpenAppRoute(
                                str(R.string.doctor_fix_open_telegram_settings),
                                AppRouteKey.SettingTelegram,
                            )
                        else null,
                    )
                )
            }
        }
    }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        // Migration version
        val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(
            DoctorCheck(
                id = "db.version",
                category = DoctorCategory.Database,
                label = str(R.string.doctor_label_db_version),
                // Room refuses to open the DB unless the stored version matches the compiled schema;
                // if we got here, version is the live schema version (migrations ran successfully).
                detail = if (version > 0) str(R.string.doctor_detail_db_version_ok, version)
                else str(R.string.doctor_detail_db_version_failed),
                severity = if (version > 0) Severity.OK else Severity.WARN,
            )
        )
        // Integrity check
        val integrity = runCatching {
            withTimeoutOrNull(5_000L) {
                database.openHelper.readableDatabase
                    .query("PRAGMA integrity_check;")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
        // Offer an AutoFix only when the corruption mentions message_fts — that's the one
        // we know how to repair (DROP + recreate + reindex from the messages table). For
        // any other integrity failure, surface the message and let the user decide; we
        // don't blanket-rebuild things we don't know are safe.
        val mentionsFts = integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
        add(
            DoctorCheck(
                id = "db.integrity",
                category = DoctorCategory.Database,
                label = str(R.string.doctor_label_db_integrity),
                detail = when (integrity) {
                    null -> str(R.string.doctor_detail_db_integrity_timeout)
                    "ok" -> str(R.string.doctor_detail_db_integrity_ok)
                    else -> str(R.string.doctor_detail_db_integrity_result, integrity)
                },
                severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                fix = if (mentionsFts) FixAction.AutoFix(
                    label = str(R.string.doctor_fix_rebuild_index),
                    run = {
                        runCatching {
                            val n = conversationRepository.repairAndRebuildIndexes()
                            AutoFixResult(
                                ok = true,
                                message = str(R.string.doctor_detail_rebuild_done, n),
                            )
                        }.getOrElse {
                            AutoFixResult(
                                ok = false,
                                message = str(
                                    R.string.doctor_detail_repair_failed,
                                    it::class.simpleName ?: "?",
                                    it.message ?: "?",
                                ),
                            )
                        }
                    },
                ) else null,
            )
        )
        // Workflows summary
        runCatching {
            val all = workflowRepository.observeAll().first()
            val enabled = all.count { it.entity.enabled }
            add(
                DoctorCheck(
                    id = "db.workflows",
                    category = DoctorCategory.Database,
                    label = str(R.string.doctor_label_workflows),
                    detail = str(R.string.doctor_detail_counts, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(
                            str(R.string.doctor_fix_open_workflows),
                            AppRouteKey.SettingWorkflows,
                        )
                    else null,
                )
            )
        }
        // Scheduled jobs summary
        runCatching {
            val all = scheduledJobRepository.getAll()
            val enabled = all.count { it.enabled }
            add(
                DoctorCheck(
                    id = "db.scheduled_jobs",
                    category = DoctorCategory.Database,
                    label = str(R.string.doctor_label_scheduled_jobs),
                    detail = str(R.string.doctor_detail_counts, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(
                            str(R.string.doctor_fix_open_scheduled_jobs),
                            AppRouteKey.SettingScheduledJobs,
                        )
                    else null,
                )
            )
        }
        // Stranded run rows (started but never finished — process killed mid-run)
        runCatching {
            val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
            add(
                DoctorCheck(
                    id = "db.stranded_runs",
                    category = DoctorCategory.Database,
                    label = str(R.string.doctor_label_stranded_runs),
                    detail = if (stranded.isEmpty())
                        str(R.string.doctor_detail_stranded_none)
                    else
                        str(R.string.doctor_detail_stranded_some, stranded.size),
                    severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                )
            )
        }
        // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
        // Reconciles against the OS persisted-permission list so revoked grants drop off.
        val store = storageVolumeGrantStore
        if (store != null) {
            runCatching {
                val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                val grants = store.reconcile()
                add(
                    DoctorCheck(
                        id = "storage.granted_directories",
                        category = DoctorCategory.Database,
                        label = str(R.string.doctor_label_granted_dirs),
                        detail = when {
                            !externalStorageEnabled && grants.isEmpty() ->
                                str(R.string.doctor_detail_granted_dirs_not_required)
                            grants.isEmpty() ->
                                str(R.string.doctor_detail_granted_dirs_none)
                            else ->
                                str(
                                    R.string.doctor_detail_granted_dirs_some,
                                    grants.size,
                                    grants.joinToString(", ") { it.displayName },
                                )
                        },
                        severity = if (externalStorageEnabled && grants.isNotEmpty())
                            Severity.OK else Severity.INFO,
                    )
                )
            }
        }
    }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val provs = settings.providers
            val configured = provs.count { p ->
                when (p) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled  // on-device, no API key
                    // Local provider (LiteRT): usable when enabled AND at least one model has
                    // been loaded/downloaded. A disabled provider with no models is the factory
                    // default — don't count it.
                    is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()
                    // Local provider (llama.cpp): usable when enabled AND at least one model
                    // has been loaded, same criterion as LiteRT above.
                    is me.rerere.ai.provider.ProviderSetting.LlamaCppLocal -> p.enabled && p.models.isNotEmpty()
                    is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.Grok -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.GeminiOAuth -> p.enabled  // OAuth, no API key
                }
            }
            add(
                DoctorCheck(
                    id = "net.providers",
                    category = DoctorCategory.Network,
                    label = str(R.string.doctor_label_llm_providers),
                    detail = str(R.string.doctor_detail_providers, configured, provs.size),
                    severity = if (configured > 0) Severity.OK else Severity.WARN,
                    fix = FixAction.OpenAppRoute(
                        str(R.string.doctor_fix_open_providers),
                        AppRouteKey.SettingProvider,
                    ),
                )
            )
        }
        // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
        // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
        // "CreateSharedMemoryManager is not implemented" on some Adreno drivers), the
        // model loads on CPU and the user has no UI indication. LiteRtProvider now
        // persists the actually-chosen accelerator after every load; surface that here
        // so the user can confirm GPU is engaged.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                val detail = when {
                    accel == null -> str(R.string.doctor_detail_accel_not_probed)
                    forceCpu && accel == "CPU" -> str(R.string.doctor_detail_accel_cpu_forced)
                    accel == "CPU" -> str(R.string.doctor_detail_accel_cpu_fallback)
                    accel == "GPU" -> str(R.string.doctor_detail_accel_gpu)
                    accel == "QNN" || accel == "NPU" -> str(R.string.doctor_detail_accel_npu)
                    accel == "NNAPI" -> "NNAPI"
                    else -> str(R.string.doctor_detail_accel_label, accel)
                }
                val severity = when {
                    accel == null -> Severity.INFO
                    accel == "CPU" && !forceCpu -> Severity.WARN  // unexpected fallback
                    else -> Severity.OK
                }
                add(
                    DoctorCheck(
                        id = "net.litert_accel",
                        category = DoctorCategory.Network,
                        label = str(R.string.doctor_label_litert_accel),
                        detail = detail,
                        severity = severity,
                        fix = FixAction.OpenAppRoute(
                            str(R.string.doctor_fix_open_litert),
                            AppRouteKey.SettingProvider,
                        ),
                    )
                )
                // Performance telemetry — surface the last-known prefill/decode tok/s for
                // each model so the user (and the support team triaging a slow report)
                // can see at a glance whether the runtime is hitting expected rates. We
                // INFO when present; WARN never (the model could legitimately be slow on a
                // weak device — the user knows their hardware better than we do).
                val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (perfMap.isNotEmpty()) {
                    val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                    val detail = rows.joinToString("\n") { s ->
                        val spec = if (s.specDecodingEngaged) str(R.string.doctor_detail_mtp_on) else ""
                        str(
                            R.string.doctor_detail_litert_perf_row,
                            s.modelId,
                            "%.1f".format(s.prefillTps),
                            "%.1f".format(s.decodeTps),
                            spec,
                        )
                    }
                    add(
                        DoctorCheck(
                            id = "net.litert_perf",
                            category = DoctorCategory.Network,
                            label = str(R.string.doctor_label_litert_perf),
                            detail = str(R.string.doctor_detail_litert_perf, detail),
                            severity = Severity.INFO,
                            fix = FixAction.OpenAppRoute(
                                str(R.string.doctor_fix_open_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
                // Vision-encoder availability — surface any models the runtime had to drop
                // to text-only on this device's GPU. The provider's vision-CPU fallback
                // means a multimodal model still works for chat, but the user has lost
                // image input on this chip. Most common cause: Adreno 7xx + restrictive
                // OEM linker namespace (One UI / OriginOS) hitting upstream LiteRT-LM
                // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                val visionUnavailable = prefs
                    .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (visionUnavailable.isNotEmpty()) {
                    add(
                        DoctorCheck(
                            id = "net.litert_vision",
                            category = DoctorCategory.Network,
                            label = str(R.string.doctor_label_litert_vision),
                            detail = str(
                                R.string.doctor_detail_litert_vision,
                                visionUnavailable.joinToString(", "),
                            ),
                            severity = Severity.WARN,
                            fix = FixAction.OpenAppRoute(
                                str(R.string.doctor_fix_open_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
            }
        }
        // llama.cpp installed-model check. Unlike LiteRT, this runtime is CPU-only with no
        // vision encoder and nothing to probe for an accelerator, so there is no analogue
        // to net.litert_accel/_perf/_vision here — those would be reporting on things that
        // cannot vary on this build. The one thing that genuinely can go wrong: a model
        // registered in prefs whose backing file was moved, deleted, or lives on a volume
        // that got unmounted. Own id (net.llamacpp_models) so it can't collide with the
        // net.litert_* rows above.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val installed = prefs.installedModels(me.rerere.locallm.LocalRuntime.LlamaCpp)
                val status = llamaCppModelStatus(installed)
                val detail = when {
                    status.total == 0 -> str(R.string.doctor_detail_llamacpp_none)
                    status.missing.isEmpty() -> str(R.string.doctor_detail_llamacpp_ok, status.total)
                    else -> str(
                        R.string.doctor_detail_llamacpp_missing,
                        status.missing.size,
                        status.total,
                        status.missing.joinToString(", "),
                    )
                }
                add(
                    DoctorCheck(
                        id = "net.llamacpp_models",
                        category = DoctorCategory.Network,
                        label = str(R.string.doctor_label_llamacpp_models),
                        detail = detail,
                        severity = when {
                            status.total == 0 -> Severity.INFO
                            status.missing.isEmpty() -> Severity.OK
                            else -> Severity.WARN
                        },
                        fix = if (status.missing.isNotEmpty()) FixAction.OpenAppRoute(
                            str(R.string.doctor_fix_open_llamacpp),
                            AppRouteKey.SettingProvider,
                        ) else null,
                    )
                )
            }
        }
        // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
        val dnsOk = withTimeoutOrNull(2_500L) {
            runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
        } == true
        add(
            DoctorCheck(
                id = "net.dns",
                category = DoctorCategory.Network,
                label = str(R.string.doctor_label_dns),
                detail = if (dnsOk) str(R.string.doctor_detail_dns_ok)
                else str(R.string.doctor_detail_dns_failed),
                severity = if (dnsOk) Severity.OK else Severity.WARN,
            )
        )
    }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Termux, enabled)
        // Skip the entire category when no Termux-using tool is enabled — keeps the
        // Doctor screen focused on what the user actually configured.
        if (needers.isEmpty()) return@buildList

        val pm = context.packageManager
        val termuxInstalled = runCatching { pm.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
        add(
            DoctorCheck(
                id = "termux.installed",
                category = DoctorCategory.Termux,
                label = str(R.string.doctor_label_termux_installed),
                detail = if (termuxInstalled) str(R.string.doctor_detail_termux_installed)
                else str(
                    R.string.doctor_detail_termux_missing,
                    needers.joinToString(", ") { it.shortName(context) },
                ),
                severity = if (termuxInstalled) Severity.OK else Severity.WARN,
            )
        )
        if (termuxInstalled) {
            val runCommandPerm = runCatching {
                val perm = "com.termux.permission.RUN_COMMAND"
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.run_command",
                    category = DoctorCategory.Termux,
                    label = str(R.string.doctor_label_termux_run_command),
                    detail = if (runCommandPerm) str(R.string.doctor_detail_termux_perm_granted)
                    else str(R.string.doctor_detail_termux_perm_missing),
                    severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                )
            )
        }
    }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("Permissions / Services").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Browser, enabled)
        val browserNeeded = needers.isNotEmpty()

        // Row 1: profile dir writable (with AutoFix to mkdirs).
        val profileDir = File(context.filesDir, "browser-profile")
        val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
        val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
        val ok = exists && writable
        add(
            DoctorCheck(
                id = "browser.profile_dir_writable",
                category = DoctorCategory.Permissions,
                label = str(R.string.doctor_label_browser_profile_dir),
                detail = when {
                    ok && browserNeeded -> str(R.string.doctor_detail_browser_dir_ok, profileDir.absolutePath)
                    ok -> str(R.string.doctor_detail_browser_dir_ok_not_required, profileDir.absolutePath)
                    !exists && browserNeeded -> str(R.string.doctor_detail_browser_dir_missing_needed)
                    !exists -> str(R.string.doctor_detail_browser_dir_missing)
                    !writable && browserNeeded -> str(R.string.doctor_detail_browser_dir_readonly_needed)
                    else -> str(R.string.doctor_detail_browser_dir_readonly)
                },
                severity = when {
                    ok -> Severity.OK
                    browserNeeded -> Severity.WARN
                    else -> Severity.INFO
                },
                fix = if (!ok && browserNeeded) FixAction.AutoFix(
                    label = str(R.string.doctor_fix_create_directory),
                    run = {
                        val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                        val nowOk = profileDir.exists() && profileDir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = when {
                                nowOk -> str(R.string.doctor_detail_browser_dir_created, profileDir.absolutePath)
                                created -> str(R.string.doctor_detail_browser_dir_not_writable)
                                else -> str(R.string.doctor_detail_browser_dir_mkdir_failed)
                            },
                        )
                    },
                ) else null,
            )
        )

        // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
        // wasn't injected — the row is purely informational and the test harness paths
        // that don't construct prefs shouldn't fail.
        val prefs = browserPreferences
        if (prefs != null) {
            val snapshot = runCatching { prefs.snapshotBlocking() }.getOrDefault(BrowserToolDefaults.DEFAULT_ENABLED)
            val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
            val detail = if (onWriteTools.isEmpty())
                str(R.string.doctor_detail_browser_write_none)
            else
                str(
                    R.string.doctor_detail_browser_write_some,
                    onWriteTools.size,
                    onWriteTools.joinToString(", ") { it.removePrefix("browser_") },
                )
            add(
                DoctorCheck(
                    id = "browser.write_tools_status",
                    category = DoctorCategory.Permissions,
                    label = str(R.string.doctor_label_browser_write_tools),
                    detail = detail,
                    severity = Severity.INFO,
                )
            )
        }
    }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> = buildList {
        // Cache size on disk
        val cacheBytes = directorySize(context.cacheDir)
        add(
            DoctorCheck(
                id = "maint.cache_size",
                category = DoctorCategory.Maintenance,
                label = str(R.string.doctor_label_cache_size),
                detail = str(
                    R.string.doctor_detail_cache_size,
                    humanBytes(cacheBytes),
                    if (cacheBytes > 200L * 1024 * 1024) str(R.string.doctor_detail_cache_over)
                    else str(R.string.doctor_detail_cache_ok),
                ),
                severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                fix = FixAction.AutoFix(
                    label = str(R.string.doctor_fix_clear_cache),
                    run = {
                        val freed = clearDirectoryContents(context.cacheDir)
                        AutoFixResult(ok = true, message = str(R.string.doctor_detail_cache_freed, humanBytes(freed)))
                    },
                ),
            )
        )
    }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = listOf(
        DoctorCheck(
            id = "diag.app",
            category = DoctorCategory.Diagnostics,
            label = str(R.string.doctor_label_app_build),
            detail = "RikkaHub-agent ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — debug=${BuildConfig.DEBUG}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.android",
            category = DoctorCategory.Diagnostics,
            label = str(R.string.doctor_label_android),
            detail = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) on ${Build.MANUFACTURER} ${Build.MODEL}",
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.runtime",
            category = DoctorCategory.Diagnostics,
            label = str(R.string.doctor_label_runtime),
            detail = run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val totalMb = rt.totalMemory() / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                str(R.string.doctor_detail_heap, freeMb, totalMb, maxMb)
            },
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.enabled_tools",
            category = DoctorCategory.Diagnostics,
            label = str(R.string.doctor_label_enabled_tools),
            detail = if (enabled.isEmpty()) str(R.string.doctor_detail_tools_none)
            else str(R.string.doctor_detail_tools_some, enabled.size),
            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
        ),
    )

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}

/**
 * Pure decision logic backing the "net.llamacpp_models" row: given the filename ->
 * absolute-path map from [me.rerere.locallm.LocalRuntimePreferences.installedModels],
 * report the total installed count and which filenames' backing file is no longer on
 * disk. Extracted to a top-level function (rather than left inline) so it's unit-testable
 * on the JVM without an Android Context — [DoctorChecks] itself needs one for every other
 * check, which rules out constructing it directly in a plain JUnit test.
 */
internal data class LlamaCppModelStatus(val total: Int, val missing: List<String>)

internal fun llamaCppModelStatus(installed: Map<String, String>): LlamaCppModelStatus =
    LlamaCppModelStatus(
        total = installed.size,
        missing = installed.filterValues { path -> !File(path).exists() }.keys.sorted(),
    )
