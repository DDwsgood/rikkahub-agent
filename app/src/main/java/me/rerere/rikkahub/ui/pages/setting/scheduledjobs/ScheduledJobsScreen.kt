package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryConfig
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences
import me.rerere.rikkahub.data.preferences.ScheduleWebhookPreset
import me.rerere.rikkahub.service.AgentKeepaliveService
import me.rerere.rikkahub.service.CalendarReminderMirror
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.service.KeepaliveEligibilityChecker
import me.rerere.rikkahub.service.ScheduleWebhookNotifier
import me.rerere.rikkahub.service.ScheduleWebhookPayload
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RelativeTimeStrings
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun ScheduledJobsScreen(vm: ScheduledJobsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val deliveryPrefs: ScheduleDeliveryPreferences = koinInject()
    val deliveryConfig by deliveryPrefs.flow.collectAsStateWithLifecycle(initialValue = ScheduleDeliveryConfig())
    val pendingCalendarDeletes by deliveryPrefs.pendingCalendarDeleteCountFlow
        .collectAsStateWithLifecycle(initialValue = 0)
    var calendarPermissionsGranted by remember {
        mutableStateOf(CalendarReminderMirror.hasCalendarPermissions(context))
    }
    var hasWritableCalendar by remember {
        mutableStateOf(CalendarReminderMirror.hasWritableCalendar(context))
    }
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val keepaliveEnabled by vm.keepaliveEnabled.collectAsStateWithLifecycle()
    val wakeOnLockScreen by vm.wakeOnLockScreen.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHowItWorks by remember { mutableStateOf(false) }
    var showWebhookDialog by remember { mutableStateOf(false) }
    var exactAlarmGranted by remember {
        mutableStateOf(PermissionHelper.canScheduleExactAlarms(context))
    }
    var eligibility by remember {
        mutableStateOf(KeepaliveEligibilityChecker.check(context))
    }
    // Set when the user asks to enable keep-alive while prerequisites are still missing;
    // when they come back from the system prompt and everything is granted we enable it
    // automatically instead of making them hunt for the switch again.
    var pendingKeepaliveEnable by remember { mutableStateOf(false) }
    var widgetPinUnsupported by remember { mutableStateOf(false) }
    // Android 14+ lets the user revoke USE_FULL_SCREEN_INTENT; a revoked grant silently
    // degrades setFullScreenIntent to a heads-up notification, so the wake-on-lock-screen
    // switch must surface that state instead of promising a full-screen alert.
    var fullScreenIntentGranted by remember {
        mutableStateOf(PermissionHelper.canUseFullScreenIntent(context))
    }

    fun enableCalendarMirrorAndSync() {
        scope.launch(Dispatchers.IO) {
            deliveryPrefs.update { it.copy(calendarMirrorEnabled = true) }
            jobs.filter { it.enabled }.forEach { job ->
                CalendarReminderMirror.syncJob(context, deliveryPrefs, job, job.nextRunAtMs)
            }
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
        if (granted || CalendarReminderMirror.hasCalendarPermissions(context)) {
            enableCalendarMirrorAndSync()
        } else {
            toaster.show(context.getString(R.string.setting_page_scheduled_jobs_calendar_permission_denied))
        }
    }

    fun refreshEligibility() {
        eligibility = KeepaliveEligibilityChecker.check(context)
        if (pendingKeepaliveEnable && eligibility.allSatisfied) {
            pendingKeepaliveEnable = false
            vm.setKeepaliveEnabled(true)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { refreshEligibility() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmGranted = PermissionHelper.canScheduleExactAlarms(context)
                if (exactAlarmGranted) vm.reconcileSchedules()
                fullScreenIntentGranted = PermissionHelper.canUseFullScreenIntent(context)
                calendarPermissionsGranted = CalendarReminderMirror.hasCalendarPermissions(context)
                hasWritableCalendar = CalendarReminderMirror.hasWritableCalendar(context)
                if (calendarPermissionsGranted) {
                    scope.launch(Dispatchers.IO) {
                        CalendarReminderMirror.retryPendingDeletes(context, deliveryPrefs)
                    }
                }
                refreshEligibility()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // WakeUp-style gate: only flip the setting when every prerequisite holds; otherwise
    // walk the user through the first missing piece (permission prompt / settings deep
    // link / widget pin sheet) rather than silently enabling a feature that can't work.
    fun onKeepaliveToggle(enabled: Boolean) {
        if (!enabled) {
            pendingKeepaliveEnable = false
            vm.setKeepaliveEnabled(false)
            return
        }
        val res = KeepaliveEligibilityChecker.check(context).also { eligibility = it }
        when {
            !res.notificationsGranted -> {
                pendingKeepaliveEnable = true
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
            }
            !res.exactAlarmsGranted -> {
                pendingKeepaliveEnable = true
                context.startActivity(PermissionHelper.exactAlarmAccessIntent(context))
            }
            !res.widgetPlaced -> {
                pendingKeepaliveEnable = true
                if (!KeepaliveEligibilityChecker.requestPinWidget(context)) {
                    // Launcher doesn't support the pin sheet — surface a hint so the user
                    // adds it via the normal widget picker.
                    widgetPinUnsupported = true
                }
            }
            else -> vm.setKeepaliveEnabled(true)
        }
    }

    if (showHowItWorks) {
        AlertDialog(
            onDismissRequest = { showHowItWorks = false },
            title = { Text(stringResource(R.string.setting_page_scheduled_jobs_how_it_works_title)) },
            text = { Text(stringResource(R.string.setting_page_scheduled_jobs_how_it_works_body)) },
            confirmButton = {
                TextButton(onClick = { showHowItWorks = false }) {
                    Text(stringResource(R.string.setting_page_scheduled_jobs_how_it_works_dismiss))
                }
            },
        )
    }

    if (showWebhookDialog) {
        WebhookDeliveryConfigDialog(
            initial = deliveryConfig,
            onDismiss = { showWebhookDialog = false },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    deliveryPrefs.update { current ->
                        current.copy(
                            webhookEnabled = updated.webhookEnabled,
                            webhookPreset = updated.webhookPreset,
                            webhookUrl = updated.webhookUrl,
                            webhookSecretOrToken = updated.webhookSecretOrToken,
                            webhookTargetId = updated.webhookTargetId,
                            webhookTargetType = updated.webhookTargetType,
                            webhookNotifyOnSuccess = updated.webhookNotifyOnSuccess,
                            webhookNotifyOnFailure = updated.webhookNotifyOnFailure,
                            webhookAllowCleartextLocal = updated.webhookAllowCleartextLocal,
                        )
                    }
                }
                showWebhookDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_scheduled_jobs)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // High-reliability background & decoupled delivery section — lives inside the
            // unified LazyColumn so small screens never lose the scheduled-job list viewport.
            item(key = "reliability_section") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    title = { Text(stringResource(R.string.setting_page_scheduled_jobs_reliability)) },
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_keepalive_title))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_keepalive_desc))
                                if (!eligibility.allSatisfied) {
                                    val missing = buildList {
                                        if (!eligibility.notificationsGranted) {
                                            add(stringResource(R.string.setting_page_scheduled_jobs_req_notifications))
                                        }
                                        if (!eligibility.exactAlarmsGranted) {
                                            add(stringResource(R.string.setting_page_scheduled_jobs_req_exact_alarms))
                                        }
                                        if (!eligibility.widgetPlaced) {
                                            add(stringResource(R.string.setting_page_scheduled_jobs_req_widget))
                                        }
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.setting_page_scheduled_jobs_reliability_missing,
                                            missing.joinToString(", "),
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = keepaliveEnabled,
                                onCheckedChange = { onKeepaliveToggle(it) },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_wakelock_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_wakelock_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = deliveryConfig.keepaliveWakeLockEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch(Dispatchers.IO) {
                                        deliveryPrefs.update { it.copy(keepaliveWakeLockEnabled = enabled) }
                                        AgentKeepaliveService.refreshWakeLockIfRunning(context)
                                    }
                                },
                            )
                        },
                    )
                    item(
                        onClick = if (eligibility.widgetPlaced) null else {
                            {
                                if (!KeepaliveEligibilityChecker.requestPinWidget(context)) {
                                    widgetPinUnsupported = true
                                }
                            }
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_widget_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (widgetPinUnsupported) {
                                        R.string.setting_page_scheduled_jobs_widget_desc_manual
                                    } else {
                                        R.string.setting_page_scheduled_jobs_widget_desc
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(
                                    if (eligibility.widgetPlaced) {
                                        R.string.setting_page_scheduled_jobs_widget_added
                                    } else {
                                        R.string.setting_page_scheduled_jobs_widget_add
                                    }
                                ),
                                color = if (eligibility.widgetPlaced) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                    item(
                        onClick = { nav.navigate(Screen.SettingReliability) },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_reliability_note))
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_open),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                    item(
                        onClick = if (!fullScreenIntentGranted && wakeOnLockScreen) {
                            {
                                runCatching {
                                    context.startActivity(
                                        PermissionHelper.fullScreenIntentSettingsIntent(context)
                                    )
                                }
                            }
                        } else null,
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_wake_lockscreen_title))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_wake_lockscreen_desc))
                                if (!fullScreenIntentGranted && wakeOnLockScreen) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_page_scheduled_jobs_wake_lockscreen_fsi_missing
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = wakeOnLockScreen,
                                onCheckedChange = { enabled ->
                                    if (enabled && !PermissionHelper.canUseFullScreenIntent(context)) {
                                        runCatching {
                                            context.startActivity(
                                                PermissionHelper.fullScreenIntentSettingsIntent(context)
                                            )
                                        }
                                        fullScreenIntentGranted = false
                                    }
                                    vm.setWakeOnLockScreen(enabled)
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_calendar_mirror_title))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_calendar_mirror_desc))
                                if (deliveryConfig.calendarMirrorEnabled && !calendarPermissionsGranted) {
                                    Text(
                                        text = stringResource(R.string.setting_page_scheduled_jobs_calendar_permission_denied),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else if (deliveryConfig.calendarMirrorEnabled && !hasWritableCalendar) {
                                    Text(
                                        text = stringResource(R.string.setting_page_scheduled_jobs_calendar_no_writable),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (pendingCalendarDeletes > 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_page_scheduled_jobs_calendar_pending_cleanup,
                                            pendingCalendarDeletes,
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = deliveryConfig.calendarMirrorEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        if (CalendarReminderMirror.hasCalendarPermissions(context)) {
                                            calendarPermissionsGranted = true
                                            hasWritableCalendar = CalendarReminderMirror.hasWritableCalendar(context)
                                            enableCalendarMirrorAndSync()
                                        } else {
                                            calendarPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.READ_CALENDAR,
                                                    Manifest.permission.WRITE_CALENDAR,
                                                )
                                            )
                                        }
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            deliveryPrefs.update { it.copy(calendarMirrorEnabled = false) }
                                            CalendarReminderMirror.clearAll(context, deliveryPrefs)
                                        }
                                    }
                                },
                            )
                        },
                    )
                    item(
                        onClick = { showWebhookDialog = true },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_title))
                        },
                        supportingContent = {
                            Text(
                                if (deliveryConfig.isWebhookUsable) {
                                    stringResource(
                                        R.string.setting_page_scheduled_jobs_webhook_desc_on,
                                        webhookPresetLabel(deliveryConfig.webhookPreset),
                                    )
                                } else {
                                    stringResource(R.string.setting_page_scheduled_jobs_webhook_desc_off)
                                }
                            )
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_scheduled_jobs_webhook_configure),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }

            if (jobs.isEmpty()) {
                item(key = "jobs_empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_scheduled_jobs_empty),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                if (!exactAlarmGranted && jobs.any { it.enabled }) {
                    item(key = "exact_alarm_banner") {
                        ListItem(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            headlineContent = {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_exact_permission_title))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_exact_permission_body))
                            },
                            trailingContent = {
                                TextButton(onClick = {
                                    context.startActivity(PermissionHelper.exactAlarmAccessIntent(context))
                                }) {
                                    Text(stringResource(R.string.setting_page_scheduled_jobs_exact_permission_grant))
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                items(jobs, key = { it.id }) { job ->
                    ScheduledJobRow(
                        job = job,
                        exactAlarmGranted = exactAlarmGranted,
                        onToggle = { enabled -> vm.setEnabled(job.id, enabled) },
                        onTap = { nav.navigate(Screen.ScheduledJobDetail(job.id)) },
                    )
                }
                item(key = "how_it_works") {
                    TextButton(
                        onClick = { showHowItWorks = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_scheduled_jobs_how_it_works))
                    }
                }
            }
        }
    }
}

private fun webhookPresetLabel(preset: ScheduleWebhookPreset): String = when (preset) {
    ScheduleWebhookPreset.WECOM -> "WeCom"
    ScheduleWebhookPreset.FEISHU -> "Feishu"
    ScheduleWebhookPreset.DINGTALK -> "DingTalk"
    ScheduleWebhookPreset.SERVERCHAN -> "ServerChan"
    ScheduleWebhookPreset.PUSHPLUS -> "PushPlus"
    ScheduleWebhookPreset.WXPUSHER -> "WxPusher"
    ScheduleWebhookPreset.ONEBOT_QQ -> "OneBot QQ"
    ScheduleWebhookPreset.CUSTOM -> "Custom JSON"
}

@Composable
private fun WebhookDeliveryConfigDialog(
    initial: ScheduleDeliveryConfig,
    onDismiss: () -> Unit,
    onSave: (ScheduleDeliveryConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep dialog-local draft unkeyed on `initial` so unrelated preference updates (e.g. WakeLock
    // or Calendar mirror toggles) never wipe in-progress Webhook edits while the dialog is open.
    var enabled by remember { mutableStateOf(initial.webhookEnabled) }
    var preset by remember { mutableStateOf(initial.webhookPreset) }
    var url by remember { mutableStateOf(initial.webhookUrl) }
    var token by remember { mutableStateOf(initial.webhookSecretOrToken) }
    var showToken by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf(initial.webhookTargetId) }
    var targetType by remember { mutableStateOf(initial.webhookTargetType) }
    var notifySuccess by remember { mutableStateOf(initial.webhookNotifyOnSuccess) }
    var notifyFailure by remember { mutableStateOf(initial.webhookNotifyOnFailure) }
    var allowCleartextLocal by remember { mutableStateOf(initial.webhookAllowCleartextLocal) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }

    fun currentDraft(): ScheduleDeliveryConfig = ScheduleDeliveryConfig(
        webhookEnabled = enabled,
        webhookPreset = preset,
        webhookUrl = url.trim(),
        webhookSecretOrToken = token.trim(),
        webhookTargetId = targetId.trim(),
        webhookTargetType = targetType,
        webhookNotifyOnSuccess = notifySuccess,
        webhookNotifyOnFailure = notifyFailure,
        webhookAllowCleartextLocal = allowCleartextLocal,
    )

    val urlError = ScheduleDeliveryConfig.validateWebhookUrl(url, allowCleartextLocal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_webhook_enable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Text(
                    stringResource(R.string.setting_page_scheduled_jobs_webhook_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ScheduleWebhookPreset.entries.forEach { item ->
                        FilterChip(
                            selected = preset == item,
                            onClick = { preset = item },
                            label = { Text(webhookPresetLabel(item)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_url)) },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_token)) },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(
                                stringResource(
                                    if (showToken) R.string.setting_page_scheduled_jobs_webhook_hide_token
                                    else R.string.setting_page_scheduled_jobs_webhook_show_token
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_webhook_allow_cleartext_local),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = allowCleartextLocal, onCheckedChange = { allowCleartextLocal = it })
                }

                if (preset == ScheduleWebhookPreset.WXPUSHER || preset == ScheduleWebhookPreset.ONEBOT_QQ) {
                    OutlinedTextField(
                        value = targetId,
                        onValueChange = { targetId = it },
                        label = { Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_target_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (preset == ScheduleWebhookPreset.ONEBOT_QQ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = targetType != "group",
                            onClick = { targetType = "private" },
                            label = {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_target_type_private))
                            },
                        )
                        FilterChip(
                            selected = targetType == "group",
                            onClick = { targetType = "group" },
                            label = {
                                Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_target_type_group))
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_webhook_notify_success),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = notifySuccess, onCheckedChange = { notifySuccess = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.setting_page_scheduled_jobs_webhook_notify_failure),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = notifyFailure, onCheckedChange = { notifyFailure = it })
                }

                Text(
                    stringResource(R.string.setting_page_scheduled_jobs_webhook_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    enabled = !testing && urlError == null,
                    onClick = {
                        testing = true
                        testStatus = null
                        val draft = currentDraft().copy(
                            webhookEnabled = true,
                            webhookNotifyOnSuccess = true,
                        )
                        scope.launch {
                            try {
                                val now = System.currentTimeMillis()
                                val res = ScheduleWebhookNotifier.deliver(
                                    config = draft,
                                    payload = ScheduleWebhookPayload(
                                        deliveryId = "test-$now",
                                        jobId = "test",
                                        jobName = "RikkaHub Test",
                                        mode = "test",
                                        outcome = "success",
                                        summary = "Scheduled-job Webhook test message from RikkaHub.",
                                        timestampMs = now,
                                    ),
                                )
                                testStatus = if (res.success) {
                                    context.getString(
                                        R.string.setting_page_scheduled_jobs_webhook_test_ok,
                                        res.summary,
                                    )
                                } else {
                                    context.getString(
                                        R.string.setting_page_scheduled_jobs_webhook_test_fail,
                                        res.summary,
                                    )
                                }
                            } catch (c: kotlinx.coroutines.CancellationException) {
                                throw c
                            } catch (_: Throwable) {
                                testStatus = context.getString(
                                    R.string.setting_page_scheduled_jobs_webhook_test_fail,
                                    "webhook:error",
                                )
                            } finally {
                                testing = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.setting_page_scheduled_jobs_webhook_test))
                }

                testStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(currentDraft()) }) {
                Text(stringResource(R.string.common_save))
            }
        },
    )
}

@Composable
private fun ScheduledJobRow(
    job: ScheduledJobEntity,
    exactAlarmGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val context = LocalContext.current
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    val schedule = remember(job.scheduleType, job.atUnixMs, job.cronExpression) {
        summariseSchedule(context, job)
    }
    val strategy = backendStrategyText(job.mode, exactAlarmGranted)
    val statusLine: String = when (job.lastRunAtMs) {
        null -> stringResource(R.string.setting_page_scheduled_jobs_subtitle_never_run)
        else -> {
            val ago = formatRelativeAgo(job.lastRunAtMs, nowMs, rel)
            stringResource(R.string.setting_page_scheduled_jobs_subtitle_ran, ago)
        }
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(job.name) },
        supportingContent = {
            Text(
                text = "${stringResource(R.string.setting_page_scheduled_jobs_subtitle_when, schedule)}\n$strategy · $statusLine",
                maxLines = 3,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(checked = job.enabled, onCheckedChange = onToggle)
        },
    )
    HorizontalDivider()
}

@Composable
internal fun relativeStrings(): RelativeTimeStrings = RelativeTimeStrings(
    justNow = stringResource(R.string.relative_time_just_now),
    secondsAgo = stringResource(R.string.relative_time_seconds_ago),
    minutesAgo = stringResource(R.string.relative_time_minutes_ago),
    hoursAgo = stringResource(R.string.relative_time_hours_ago),
    daysAgo = stringResource(R.string.relative_time_days_ago),
)

/**
 * Maps (mode, exactAlarmGranted) to a human-readable strategy label. Uses the same
 * selectBackend pure function as the scheduler so the UI always matches the actual
 * backend. Does NOT expose precision selection — the strategy is automatic.
 *
 * Distinct labels per backend so the user can tell Direct (alarm clock) from LLM (exact
 * alarm) from fallback (flexible WorkManager).
 */
@Composable
internal fun backendStrategyText(mode: String, exactAlarmGranted: Boolean): String {
    val backend = selectBackendLabel(mode, exactAlarmGranted)
    return when (backend) {
        CronJobScheduler.Backend.ALARM_CLOCK_DIRECT ->
            stringResource(R.string.setting_page_scheduled_jobs_strategy_direct_alarm_clock)
        CronJobScheduler.Backend.EXACT_ALARM_LLM ->
            stringResource(R.string.setting_page_scheduled_jobs_strategy_llm_exact_alarm)
        CronJobScheduler.Backend.WORK_MANAGER_FALLBACK ->
            stringResource(R.string.setting_page_scheduled_jobs_strategy_flexible_fallback)
        CronJobScheduler.Backend.WORK_MANAGER ->
            stringResource(R.string.setting_page_scheduled_jobs_strategy_flexible_fallback)
        CronJobScheduler.Backend.NONE ->
            stringResource(R.string.setting_page_scheduled_jobs_strategy_flexible_fallback)
    }
}

@Composable
internal fun rememberTickingNowMs(): State<Long> {
    val state = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            state.longValue = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return state
}
