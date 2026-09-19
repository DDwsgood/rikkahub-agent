package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.RomUtils
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus

/**
 * "Background run & reliability" — the OEM-adaptation surface. Detects the vendor ROM,
 * shows a FairEmail-style red warning on aggressive systems, links to the vendor's
 * autostart manager (with a resolve-checked intent chain), reuses the battery-optimization
 * whitelist prompt, and prints the per-ROM manual checklist.
 *
 * Nothing here is auto-fixable: autostart/background state cannot be read back on any of
 * these ROMs, so the page is deliberately honest about needing manual confirmation.
 */
@Composable
fun SettingReliabilityPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val rom = remember { RomUtils.detect() }

    // Re-check battery whitelist on every resume — returning from the system prompt or the
    // settings page flips the row without a rebuild.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val whitelisted = remember(resumeTick) {
        PermissionHelper.ignoresBatteryOptimizations(context)
    }
    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* result ignored — re-checked via ON_RESUME */ }

    val guideRes = when (rom.rom) {
        RomUtils.Rom.HYPEROS, RomUtils.Rom.MIUI -> R.string.setting_page_reliability_guide_xiaomi
        RomUtils.Rom.EMUI, RomUtils.Rom.MAGICOS, RomUtils.Rom.HARMONYOS ->
            R.string.setting_page_reliability_guide_huawei
        RomUtils.Rom.REALME_UI, RomUtils.Rom.COLOROS -> R.string.setting_page_reliability_guide_oppo
        RomUtils.Rom.ORIGINOS, RomUtils.Rom.FUNTOUCH -> R.string.setting_page_reliability_guide_vivo
        RomUtils.Rom.FLYME -> R.string.setting_page_reliability_guide_meizu
        RomUtils.Rom.ONEUI -> R.string.setting_page_reliability_guide_samsung
        RomUtils.Rom.OTHER -> R.string.setting_page_reliability_guide_generic
    }
    val levelRes = when (rom.aggressiveness) {
        RomUtils.Aggressiveness.SEVERE -> R.string.setting_page_reliability_level_severe
        RomUtils.Aggressiveness.HIGH -> R.string.setting_page_reliability_level_high
        RomUtils.Aggressiveness.MODERATE -> R.string.setting_page_reliability_level_moderate
        RomUtils.Aggressiveness.NONE -> R.string.setting_page_reliability_level_normal
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_reliability)) },
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
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // FairEmail-style red warning, only on ROMs known to force-stop background apps.
            if (rom.needsWarning) {
                item {
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_reliability_rom_warning),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_reliability_detected_title)) },
                ) {
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(
                                    R.string.setting_page_reliability_detected,
                                    rom.displayName,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_aggressiveness) +
                                    " " + stringResource(levelRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (rom.needsWarning)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_guide_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(guideRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_reliability_actions_title)) },
                ) {
                    item(
                        onClick = {
                            if (!RomUtils.openAutostartSettings(context)) {
                                toaster.show(
                                    context.getString(R.string.setting_page_reliability_autostart_failed)
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_autostart),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_autostart_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        onClick = {
                            if (whitelisted) {
                                toaster.show(
                                    context.getString(R.string.setting_page_reliability_battery_already_ok)
                                )
                                return@item
                            }
                            val intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context)
                            try {
                                batteryLauncher.launch(intent)
                            } catch (_: Throwable) {
                                // Some OEMs reject ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
                                // fall back to the system-wide list page.
                                batteryLauncher.launch(PermissionHelper.batteryOptimizationsListIntent())
                            }
                        },
                        headlineContent = {
                            Text(
                                text = if (whitelisted)
                                    stringResource(R.string.setting_page_reliability_battery_ok)
                                else
                                    stringResource(R.string.setting_page_reliability_battery_needed),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (whitelisted)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_battery_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        onClick = { context.openUrl("https://dontkillmyapp.com/") },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_dontkillmyapp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_reliability_dontkillmyapp_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
