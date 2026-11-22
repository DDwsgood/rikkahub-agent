package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.preferences.availableApprovalDecisionModel
import me.rerere.rikkahub.data.preferences.supportsApprovalReview
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Lists every tool the user has granted "Always Allow" to (across the whole app, persistent
 * to disk) and lets them revoke individual entries or all of them at once.
 *
 * Also surfaces the "I AM STUPID" YOLO toggle: when enabled, every tool call auto-approves
 * across every conversation and every surface. HARDLINE patterns (rm -rf /, mkfs, …) still
 * block; nothing else does. Behind a confirm dialog because flipping it on is a live
 * foot-gun and the user should own the choice deliberately.
 *
 * "Allow for this chat" grants are NOT shown here — they live in process memory only and
 * vanish on /new or app restart. There's no useful surface for them in Settings.
 */
@Composable
fun SettingToolApprovalsPage() {
    val prefs: ToolApprovalPreferences = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val granted by prefs.alwaysAllowFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val yolo by prefs.globalYoloFlow.collectAsStateWithLifecycle(initialValue = false)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var pendingYoloEnable by remember { mutableStateOf(false) }
    if (pendingYoloEnable) {
        AlertDialog(
            onDismissRequest = { pendingYoloEnable = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_yolo_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(stringResource(R.string.setting_page_tool_approvals_yolo_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingYoloEnable = false
                    scope.launch { prefs.setYolo(true) }
                }) {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_yolo_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingYoloEnable = false }) {
                    Text(stringResource(R.string.setting_page_tool_approvals_yolo_dialog_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tool_approvals)) },
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
            // YOLO toggle. Top of the page so the user always sees its state when they
            // come here. Background goes red when the toggle is on so it's visually
            // unmistakable that approvals are off.
            item {
                val containerColor =
                    if (yolo) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                val onContainer =
                    if (yolo) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_tool_approvals_yolo_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onContainer,
                        )
                        Text(
                            text = stringResource(
                                if (yolo) R.string.setting_page_tool_approvals_yolo_on
                                else R.string.setting_page_tool_approvals_yolo_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = onContainer,
                        )
                    }
                    Switch(
                        checked = yolo,
                        onCheckedChange = { wantOn ->
                            if (wantOn) {
                                pendingYoloEnable = true
                            } else {
                                scope.launch { prefs.setYolo(false) }
                            }
                        },
                        colors = if (yolo) SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.error,
                            checkedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                        ) else SwitchDefaults.colors(),
                    )
                }
            }

            item {
                val decisionModel = settings.availableApprovalDecisionModel()
                val decisionProviders = remember(settings.providers) {
                    settings.providers.filter { it.enabled }.map { provider ->
                        provider.copyProvider(models = provider.models.filter { it.supportsApprovalReview() })
                    }
                }
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_tool_approvals_decision_model))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.setting_page_tool_approvals_decision_model_desc))
                                ModelSelector(
                                    modelId = decisionModel?.id,
                                    providers = decisionProviders,
                                    type = ModelType.CHAT,
                                    allowClear = true,
                                    onSelect = { model ->
                                        scope.launch {
                                            settingsStore.update {
                                                it.copy(approvalDecisionModelId = model.id.takeIf { model.modelId.isNotBlank() })
                                            }
                                        }
                                    },
                                )
                                val provider = decisionModel?.findProvider(settings.providers, checkOverwrite = false)
                                if (provider != null) {
                                    Text(provider.name, style = MaterialTheme.typography.bodySmall)
                                } else if (settings.approvalDecisionModelId != null) {
                                    Text(stringResource(R.string.permission_mode_model_missing))
                                    TextButton(onClick = {
                                        scope.launch {
                                            settingsStore.update { it.copy(approvalDecisionModelId = null) }
                                        }
                                    }) { Text(stringResource(R.string.common_clear)) }
                                }
                            }
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_page_tool_approvals_header),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (granted.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.setting_page_tool_approvals_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                item {
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        granted.sorted().forEach { toolName ->
                            item(
                                headlineContent = { Text(toolName) },
                                trailingContent = {
                                    IconButton(onClick = {
                                        scope.launch { prefs.revoke(toolName) }
                                    }) {
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = stringResource(
                                                R.string.setting_page_tool_approvals_revoke
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { scope.launch { prefs.revokeAll() } },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.setting_page_tool_approvals_revoke_all))
                    }
                }
            }
        }
    }
}
