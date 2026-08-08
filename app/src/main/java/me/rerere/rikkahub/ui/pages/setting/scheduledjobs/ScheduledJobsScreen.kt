package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.service.CronJobScheduler
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RelativeTimeStrings
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduledJobsScreen(vm: ScheduledJobsViewModel = koinViewModel()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHowItWorks by remember { mutableStateOf(false) }
    var exactAlarmGranted by remember {
        mutableStateOf(PermissionHelper.canScheduleExactAlarms(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmGranted = PermissionHelper.canScheduleExactAlarms(context)
                if (exactAlarmGranted) vm.reconcileSchedules()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setting_page_scheduled_jobs_empty),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!exactAlarmGranted && jobs.any { it.enabled }) {
                    item {
                        ListItem(
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
                item {
                    TextButton(
                        onClick = { showHowItWorks = true },
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_scheduled_jobs_how_it_works))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledJobRow(
    job: ScheduledJobEntity,
    exactAlarmGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val rel = relativeStrings()
    val nowMs by rememberTickingNowMs()
    val schedule = remember(job.scheduleType, job.atUnixMs, job.cronExpression) {
        summariseSchedule(job)
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
