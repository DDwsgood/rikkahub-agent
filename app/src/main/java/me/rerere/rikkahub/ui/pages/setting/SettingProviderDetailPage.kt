package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Share01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.MoreVertical
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ModelTypeTag
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ShareSheet
import me.rerere.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.rememberShareSheetState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.CodexProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.GeminiProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.GrokProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConnectionTester
import me.rerere.rikkahub.ui.pages.setting.components.SettingProviderBalanceOption
import me.rerere.rikkahub.ui.pages.setting.components.isUsingDefaultBaseUrl
import me.rerere.rikkahub.ui.pages.setting.components.resetBaseUrlToDefault
import me.rerere.locallm.LocalRuntime
import me.rerere.rikkahub.ui.pages.setting.locallm.SettingLocalLlmViewModel
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun SettingProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val onMutateProvider: ((ProviderSetting) -> ProviderSetting) -> Unit = { transform ->
        vm.updateSettings { latest ->
            latest.copy(
                providers = latest.providers.map { existing ->
                    if (existing.id == id) transform(existing) else existing
                }
            )
        }
    }
    val onDelete = {
        val targetId = provider.id
        val targetBuiltIn = provider.builtIn
        vm.updateSettings { latest ->
            latest.copy(
                providers = latest.providers.filter { it.id != targetId },
                deletedBuiltInProviderIds = if (targetBuiltIn) {
                    latest.deletedBuiltInProviderIds + targetId
                } else {
                    latest.deletedBuiltInProviderIds
                },
            )
        }
        navController.popBackStack()
    }
    var showDeleteProviderDialog by remember { mutableStateOf(false) }
    // Hoist the config draft AND its baseline above HorizontalPager so switching to the Models
    // tab never wipes unsaved edits AND saving applies only user-modified fields onto the live
    // provider row inside DataStore (preserving concurrent model or background updates).
    var draftBase by remember(provider.id) { mutableStateOf(provider) }
    var configDraft by remember(provider.id) { mutableStateOf(provider) }
    LaunchedEffect(provider.models) {
        if (configDraft.models != provider.models) {
            configDraft = configDraft.copyProvider(models = provider.models)
        }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutoAIIcon(provider.name, modifier = Modifier.size(22.dp))
                        Text(text = provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (
                        provider !is ProviderSetting.Codex &&
                        provider !is ProviderSetting.Grok &&
                        provider !is ProviderSetting.GeminiOAuth
                    ) {
                        val shareSheetState = rememberShareSheetState()
                        ShareSheet(shareSheetState)
                        IconButton(
                            onClick = {
                                shareSheetState.show(provider)
                            }
                        ) {
                            Icon(HugeIcons.Share01, contentDescription = stringResource(R.string.share))
                        }
                    }
                    IconButton(
                        onClick = { showDeleteProviderDialog = true }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    label = { Text(stringResource(id = R.string.setting_provider_page_configuration)) },
                    icon = { Icon(HugeIcons.Tools, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(0)
                        }
                    }
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1,
                    label = { Text(stringResource(id = R.string.setting_provider_page_models)) },
                    icon = { Icon(HugeIcons.Package01, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(1)
                        }
                    }
                )
            }
        }
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(it)
                .consumeWindowInsets(it)
        ) { page ->
            when (page) {
                0 -> {
                    SettingProviderConfigPage(
                        provider = provider,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        onEdit = { saved ->
                            val baseline = draftBase
                            configDraft = saved
                            draftBase = saved
                            onMutateProvider { latest ->
                                mergeProviderConfigChanges(latest = latest, base = baseline, draft = saved)
                            }
                            toaster.show(
                                context.getString(R.string.setting_provider_page_save_success),
                                type = ToastType.Success
                            )
                        },
                        onDelete = {
                            showDeleteProviderDialog = true
                        }
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onMutateProvider = onMutateProvider
                    )
                }
            }
        }
    }

    if (showDeleteProviderDialog) {
        val isOAuth = provider is ProviderSetting.Codex ||
            provider is ProviderSetting.Grok ||
            provider is ProviderSetting.GeminiOAuth
        AlertDialog(
            onDismissRequest = { showDeleteProviderDialog = false },
            title = { Text(stringResource(R.string.setting_provider_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (isOAuth) R.string.setting_provider_delete_oauth_note
                        else R.string.setting_provider_delete_body,
                        provider.name,
                    )
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProviderDialog = false }) {
                    Text(stringResource(R.string.setting_provider_delete_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteProviderDialog = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        )
    }
}

@Composable
private fun SettingProviderConfigPage(
    provider: ProviderSetting,
    draft: ProviderSetting,
    onDraftChange: (ProviderSetting) -> Unit,
    onEdit: (ProviderSetting) -> Unit,
    onDelete: () -> Unit
) {
    if (provider is ProviderSetting.Codex) {
        CodexProviderConfigure(
            provider = provider,
            onEdit = onEdit,
        )
        return
    }
    if (provider is ProviderSetting.Grok) {
        GrokProviderConfigure(
            provider = provider,
            onEdit = onEdit,
        )
        return
    }
    if (provider is ProviderSetting.GeminiOAuth) {
        GeminiProviderConfigure(
            provider = provider,
            onEdit = onEdit,
        )
        return
    }
    val internalProvider = draft

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProviderConfigure(
            provider = internalProvider,
            onEdit = onDraftChange
        )

        if (internalProvider is ProviderSetting.OpenAI) {
            SettingProviderBalanceOption(
                provider = internalProvider,
                balanceOption = internalProvider.balanceOption,
                onEdit = { onDraftChange(internalProvider.copyProvider(balanceOption = it)) }
            )
            ProviderBalanceText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderConnectionTester(
                internalProvider = internalProvider,
            )

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = {
                    onDelete()
                },
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            IconButton(
                onClick = {
                    onDraftChange(internalProvider.resetBaseUrlToDefault())
                },
                enabled = !internalProvider.isUsingDefaultBaseUrl(),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.setting_model_page_reset_to_default)
                )
            }

            Button(
                onClick = {
                    val trimmedName = internalProvider.name.trim()
                    val toSave = if (trimmedName.isNotEmpty()) {
                        internalProvider.copyProvider(name = trimmedName)
                    } else {
                        internalProvider
                    }
                    onDraftChange(toSave)
                    onEdit(toSave)
                }
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        }

        // 硅基流动图标
        if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
            SiliconFlowPowerByIcon(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp)
            )
        }
    }
}

/**
 * Field-level 3-way merge for provider config saves.
 *
 * Always preserves [latest.models] and applies ONLY fields the user actually changed between
 * [base] (the snapshot when editing began / last saved) and [draft], so concurrent background
 * updates (model additions/deletions, unrelated config tweaks) are never overwritten.
 */
internal fun mergeProviderConfigChanges(
    latest: ProviderSetting,
    base: ProviderSetting,
    draft: ProviderSetting,
): ProviderSetting {
    if (draft::class != base::class) {
        // Explicit provider-type conversion: keep latest models + builtIn flags intact.
        return draft.copyProvider(
            id = latest.id,
            models = latest.models,
            builtIn = latest.builtIn,
            description = latest.description,
            shortDescription = latest.shortDescription,
        )
    }
    if (latest::class != base::class) {
        // Underlying provider type changed concurrently; preserve latest rather than clobbering.
        return latest
    }
    val mergedEnabled = if (draft.enabled != base.enabled) draft.enabled else latest.enabled
    val mergedName = if (draft.name != base.name) draft.name else latest.name
    val mergedBalance = if (draft.balanceOption != base.balanceOption) draft.balanceOption else latest.balanceOption

    return when {
        latest is ProviderSetting.OpenAI && base is ProviderSetting.OpenAI && draft is ProviderSetting.OpenAI ->
            latest.copy(
                enabled = mergedEnabled,
                name = mergedName,
                balanceOption = mergedBalance,
                apiKey = if (draft.apiKey != base.apiKey) draft.apiKey else latest.apiKey,
                baseUrl = if (draft.baseUrl != base.baseUrl) draft.baseUrl else latest.baseUrl,
                chatCompletionsPath = if (draft.chatCompletionsPath != base.chatCompletionsPath) {
                    draft.chatCompletionsPath
                } else {
                    latest.chatCompletionsPath
                },
                useResponseApi = if (draft.useResponseApi != base.useResponseApi) {
                    draft.useResponseApi
                } else {
                    latest.useResponseApi
                },
                promptCaching = if (draft.promptCaching != base.promptCaching) {
                    draft.promptCaching
                } else {
                    latest.promptCaching
                },
                includeHistoryReasoning = if (draft.includeHistoryReasoning != base.includeHistoryReasoning) {
                    draft.includeHistoryReasoning
                } else {
                    latest.includeHistoryReasoning
                },
                routing = if (draft.routing != base.routing) draft.routing else latest.routing,
            )

        latest is ProviderSetting.Google && base is ProviderSetting.Google && draft is ProviderSetting.Google ->
            latest.copy(
                enabled = mergedEnabled,
                name = mergedName,
                balanceOption = mergedBalance,
                apiKey = if (draft.apiKey != base.apiKey) draft.apiKey else latest.apiKey,
                baseUrl = if (draft.baseUrl != base.baseUrl) draft.baseUrl else latest.baseUrl,
                vertexAI = if (draft.vertexAI != base.vertexAI) draft.vertexAI else latest.vertexAI,
                useServiceAccount = if (draft.useServiceAccount != base.useServiceAccount) {
                    draft.useServiceAccount
                } else {
                    latest.useServiceAccount
                },
                privateKey = if (draft.privateKey != base.privateKey) draft.privateKey else latest.privateKey,
                serviceAccountEmail = if (draft.serviceAccountEmail != base.serviceAccountEmail) {
                    draft.serviceAccountEmail
                } else {
                    latest.serviceAccountEmail
                },
                location = if (draft.location != base.location) draft.location else latest.location,
                projectId = if (draft.projectId != base.projectId) draft.projectId else latest.projectId,
            )

        latest is ProviderSetting.Claude && base is ProviderSetting.Claude && draft is ProviderSetting.Claude ->
            latest.copy(
                enabled = mergedEnabled,
                name = mergedName,
                balanceOption = mergedBalance,
                apiKey = if (draft.apiKey != base.apiKey) draft.apiKey else latest.apiKey,
                baseUrl = if (draft.baseUrl != base.baseUrl) draft.baseUrl else latest.baseUrl,
                promptCaching = if (draft.promptCaching != base.promptCaching) {
                    draft.promptCaching
                } else {
                    latest.promptCaching
                },
                promptCacheTtl = if (draft.promptCacheTtl != base.promptCacheTtl) {
                    draft.promptCacheTtl
                } else {
                    latest.promptCacheTtl
                },
            )

        latest is ProviderSetting.AICore && base is ProviderSetting.AICore && draft is ProviderSetting.AICore ->
            latest.copy(
                enabled = mergedEnabled,
                name = mergedName,
                balanceOption = mergedBalance,
                releaseStage = if (draft.releaseStage != base.releaseStage) draft.releaseStage else latest.releaseStage,
            )

        else -> latest.copyProvider(
            enabled = mergedEnabled,
            name = mergedName,
            balanceOption = mergedBalance,
            models = latest.models,
        )
    }
}

@Composable
private fun SettingProviderModelPage(
    provider: ProviderSetting,
    onMutateProvider: ((ProviderSetting) -> ProviderSetting) -> Unit,
) {
    ModelList(
        providerSetting = provider,
        onMutateProvider = onMutateProvider,
    )
}

@Composable
private fun ModelList(
    providerSetting: ProviderSetting,
    onMutateProvider: ((ProviderSetting) -> ProviderSetting) -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val toaster = LocalToaster.current
    val loadModelsFailedMsg = stringResource(R.string.setting_provider_detail_load_models_failed)
    val localRuntime = when (providerSetting) {
        is ProviderSetting.LiteRtLocal -> LocalRuntime.LiteRT
        is ProviderSetting.LlamaCppLocal -> LocalRuntime.LlamaCpp
        else -> null
    }
    val localLlmVm = localRuntime?.let { runtime ->
        koinViewModel<SettingLocalLlmViewModel>(
            key = "configure-${runtime.displayName}",
            parameters = { parametersOf(runtime) },
        )
    }
    // Key remote /models lookup on provider connection config without `models`, so adding,
    // editing, deleting, or reordering local model entries never triggers redundant HTTP fetches.
    val connectionKey = remember(providerSetting) {
        providerSetting.copyProvider(models = emptyList())
    }
    val modelList by produceState(emptyList(), connectionKey) {
        runCatching {
            value = providerManager.getProviderByType(providerSetting)
                .listModels(providerSetting)
                .sortedBy { it.modelId }
                .toList()
        }.onFailure { error ->
            // runCatching catches Throwable, which includes CancellationException
            // (e.g. when the user navigates away from the Models tab mid-fetch
            // and Compose cancels this produceState's coroutine). Re-throw so
            // we don't print a stack trace + show a toast for normal teardown.
            if (error is kotlinx.coroutines.CancellationException) throw error
            error.printStackTrace()
            // Surface real failures (missing/invalid API key, providers like
            // Minimax that return an HTTP 200 error envelope instead of a 4xx).
            toaster.show(
                error.message ?: loadModelsFailedMsg,
                type = ToastType.Error
            )
        }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val models = providerSetting.models
        if (from.index !in models.indices || to.index !in models.indices) {
            return@rememberReorderableLazyListState
        }
        val movedId = models[from.index].id
        val targetId = models[to.index].id
        if (movedId == targetId) return@rememberReorderableLazyListState
        onMutateProvider { latest ->
            val fromIdx = latest.models.indexOfFirst { it.id == movedId }
            val toIdx = latest.models.indexOfFirst { it.id == targetId }
            if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) latest else latest.moveMove(fromIdx, toIdx)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                ),
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 128.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            // 模型列表
            if (providerSetting.models.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_no_models),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.setting_provider_page_add_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                items(providerSetting.models, key = { it.id }) { item ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = item.id
                    ) { isDragging ->
                        ModelCard(
                            model = item,
                            onDelete = {
                                if (localLlmVm != null) {
                                    localLlmVm.hideModelFromList(item.modelId)
                                } else {
                                    onMutateProvider { latest -> latest.delModel(item) }
                                }
                            },
                            onDeleteWithFile = localLlmVm?.let { vm ->
                                { vm.deleteModel(item.modelId) }
                            },
                            onEdit = { editedModel ->
                                onMutateProvider { latest -> latest.editModel(editedModel) }
                            },
                            parentProvider = providerSetting,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                },
                        )
                    }
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = expanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
        ) {
            AddModelButton(
                models = modelList,
                selectedModels = providerSetting.models,
                onAddModel = { added ->
                    onMutateProvider { latest -> latest.addModel(added) }
                },
                onRemoveModel = { removed ->
                    onMutateProvider { latest -> latest.delModel(removed) }
                },
                expanded = expanded,
                parentProvider = providerSetting,
                onMutateProvider = onMutateProvider,
            )
        }
    }
}

@Composable
private fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null
) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        onModelChange(
            model.copy(
                modelId = id,
                displayName = id,
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities
            )
        )
    }

    Column {
        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_basic_settings)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_advanced_settings)) }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(2)
                    }
                },
                text = { Text(stringResource(R.string.setting_page_built_in_tools)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置页面
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = {
                                if (!isEdit) {
                                    setModelId(it.trim())
                                }
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_model_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_id_placeholder))
                                }
                            },
                            enabled = !isEdit
                        )

                        OutlinedTextField(
                            value = model.displayName,
                            onValueChange = {
                                onModelChange(model.copy(displayName = it))
                            },
                            label = { Text(stringResource(if (isEdit) R.string.setting_provider_page_model_name else R.string.setting_provider_page_model_display_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_display_name_placeholder))
                                }
                            }
                        )

                        ModelTypeSelector(
                            selectedType = model.type,
                            onTypeSelected = {
                                onModelChange(model.copy(type = it))
                            }
                        )

                        ModelModalitySelector(
                            model = model,
                            inputModalities = model.inputModalities,
                            onUpdateInputModalities = {
                                onModelChange(model.copy(inputModalities = it))
                            },
                            outputModalities = model.outputModalities,
                            onUpdateOutputModalities = {
                                onModelChange(model.copy(outputModalities = it))
                            }
                        )

                        if (model.type == ModelType.CHAT) {
                            ModalAbilitySelector(
                                abilities = model.abilities,
                                onUpdateAbilities = {
                                    onModelChange(model.copy(abilities = it))
                                }
                            )
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProviderOverrideSettings(
                            providerOverride = model.providerOverwrite,
                            onUpdateProviderOverride = { providerOverride ->
                                onModelChange(model.copy(providerOverwrite = providerOverride))
                            },
                            parentProvider = parentProvider
                        )

                        CustomHeaders(
                            headers = model.customHeaders,
                            onUpdate = { headers ->
                                onModelChange(model.copy(customHeaders = headers))
                            }
                        )

                        CustomBodies(
                            customBodies = model.customBodies,
                            onUpdate = { bodies ->
                                onModelChange(model.copy(customBodies = bodies))
                            }
                        )
                    }
                }

                2 -> {
                    // 内置工具页面
                    BuiltInToolsSettings(
                        tools = model.tools,
                        onUpdateTools = { tools ->
                            onModelChange(model.copy(tools = tools))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddModelButton(
    models: List<Model>,
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    parentProvider: ProviderSetting,
    onMutateProvider: ((ProviderSetting) -> ProviderSetting) -> Unit,
) {
    val dialogState = useEditState<Model> { onAddModel(it) }
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            models = models,
            selectedModels = selectedModels,
            onModelSelected = { model ->
                onAddModel(model.enrichCapabilities())
            },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onAllModelSelected = { selected ->
                onMutateProvider { latest ->
                    latest.copyProvider(
                        models = latest.models + selected.filter { model ->
                            latest.models.none { existing -> existing.modelId == model.modelId }
                        }.map { model -> model.enrichCapabilities() }
                    )
                }
            },
            onAllModelDeselected = { filteredModels ->
                onMutateProvider { latest ->
                    latest.copyProvider(
                        models = latest.models.filter { model ->
                            filteredModels.none { filtered -> filtered.modelId == model.modelId }
                        }
                    )
                }
            }
        )

        Button(
            onClick = {
                dialogState.open(Model())
            }
        ) {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    HugeIcons.Add01,
                    contentDescription = stringResource(R.string.setting_provider_page_add_model)
                )
                AnimatedVisibility(expanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.setting_provider_page_add_new_model),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        }
                    ) {
                        Icon(HugeIcons.ArrowDown01, null)
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = false,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                val trimmedId = modelState.modelId.trim()
                                val trimmedName = modelState.displayName.trim()
                                if (trimmedId.isNotEmpty() && trimmedName.isNotEmpty()) {
                                    dialogState.currentState = modelState.copy(
                                        modelId = trimmedId,
                                        displayName = trimmedName,
                                    )
                                    dialogState.confirm()
                                }
                            },
                            enabled = modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.setting_provider_page_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<Model>,
    selectedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onModelDeselected: (Model) -> Unit,
    onAllModelSelected: (List<Model>) -> Unit,
    onAllModelDeselected: (List<Model>) -> Unit
) {
    var showModal by remember { mutableStateOf(false) }
    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            var filterText by remember { mutableStateOf("") }
            val filterKeywords = filterText.split(" ").filter { it.isNotBlank() }
            val filteredModels = models.fastFilter {
                if (filterKeywords.isEmpty()) {
                    true
                } else {
                    filterKeywords.all { keyword ->
                        it.modelId.contains(keyword, ignoreCase = true) ||
                            it.displayName.contains(keyword, ignoreCase = true)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(8.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 标题栏和添加所有按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_avaliable_models),
                        style = MaterialTheme.typography.titleMedium
                    )

                    val unselectedCount = filteredModels.count { model ->
                        selectedModels.none { it.modelId == model.modelId }
                    }

                    TextButton(
                        onClick = {
                            if (unselectedCount > 0) {
                                onAllModelSelected(filteredModels)
                            } else {
                                onAllModelDeselected(filteredModels)
                            }
                        },
                    ) {
                        Text(
                            if (unselectedCount > 0) stringResource(
                                R.string.setting_provider_page_select_all,
                                unselectedCount
                            ) else stringResource(R.string.setting_provider_page_deselect_models)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(filteredModels, key = { it.id }) {
                        Card {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                AutoAIIcon(
                                    it.modelId,
                                    Modifier.size(32.dp)
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(
                                        4.dp
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = it.modelId,
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val modelMeta = remember(it) { it.enrichCapabilities() }
                                        ModelModalityTag(
                                            model = modelMeta,
                                        )
                                        ModelAbilityTag(
                                            model = modelMeta,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                            // 从selectedModels中计算出要删除的model，因为删除需要id匹配，而不是ModelId
                                            onModelDeselected(selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                ?: it)
                                        } else {
                                            onModelSelected(it)
                                        }
                                    }
                                ) {
                                    if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                        Icon(HugeIcons.Cancel01, null)
                                    } else {
                                        Icon(HugeIcons.Add01, null)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.setting_provider_page_filter_example))
                    },
                )
            }
        }
    }
    BadgedBox(
        badge = {
            if (models.isNotEmpty()) {
                Badge {
                    Text(models.size.toString())
                }
            }
        }
    ) {
        IconButton(
            onClick = {
                showModal = true
            }
        ) {
            Icon(HugeIcons.Package01, null)
        }
    }
}

@Composable
private fun ModelTypeSelector(
    selectedType: ModelType,
    onTypeSelected: (ModelType) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_model_type),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        ModelType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, ModelType.entries.size),
                label = {
                    Text(
                        text = stringResource(
                            when (type) {
                                ModelType.CHAT -> R.string.setting_provider_page_chat_model
                                ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                ModelType.IMAGE -> R.string.setting_provider_page_image_model
                            }
                        )
                    )
                },
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@Composable
private fun ModelModalitySelector(
    model: Model,
    inputModalities: List<Modality>,
    onUpdateInputModalities: (List<Modality>) -> Unit,
    outputModalities: List<Modality>,
    onUpdateOutputModalities: (List<Modality>) -> Unit
) {
    if (model.type == ModelType.CHAT) {
        Text(
            stringResource(R.string.setting_provider_page_input_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in inputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateInputModalities(inputModalities + modality)
                        } else {
                            onUpdateInputModalities(inputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                            }
                        )
                    )
                }
            }
        }

        Text(
            stringResource(R.string.setting_provider_page_output_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in outputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateOutputModalities(outputModalities + modality)
                        } else {
                            onUpdateOutputModalities(outputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ModalAbilitySelector(
    abilities: List<ModelAbility>,
    onUpdateAbilities: (List<ModelAbility>) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_abilities),
        style = MaterialTheme.typography.titleSmall
    )
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ModelAbility.entries.forEachIndexed { index, ability ->
            SegmentedButton(
                checked = ability in abilities,
                shape = SegmentedButtonDefaults.itemShape(index, ModelAbility.entries.size),
                onCheckedChange = {
                    if (it) {
                        onUpdateAbilities(abilities + ability)
                    } else {
                        onUpdateAbilities(abilities - ability)
                    }
                },
                label = {
                    Text(
                        text = stringResource(
                            when (ability) {
                                ModelAbility.TOOL -> R.string.setting_provider_page_tool
                                ModelAbility.REASONING -> R.string.setting_provider_page_reasoning
                            }
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: Model,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onDeleteWithFile: (() -> Unit)? = null,
    onEdit: (Model) -> Unit,
    parentProvider: ProviderSetting
) {
    val dialogState = useEditState<Model> {
        onEdit(it)
    }
    val scope = rememberCoroutineScope()
    val canDeleteModel = parentProvider !is ProviderSetting.AICore
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm && canDeleteModel) {
        val modelLabel = model.displayName.ifBlank { model.modelId }
        if (onDeleteWithFile != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.setting_local_model_delete_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setting_local_model_delete_body, modelLabel))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    showDeleteConfirm = false
                                    dialogState.dismiss()
                                    onDelete()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.setting_local_model_remove_from_list))
                            }
                            TextButton(
                                onClick = {
                                    showDeleteConfirm = false
                                    dialogState.dismiss()
                                    onDeleteWithFile()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_local_model_delete_with_file),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.setting_model_delete_title)) },
                text = { Text(stringResource(R.string.setting_model_delete_body, modelLabel)) },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            dialogState.dismiss()
                            onDelete()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { editingModel ->
            val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    dialogState.dismiss()
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.cancel))
                        }
                        Text(
                            text = stringResource(R.string.setting_provider_page_edit_model),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = editingModel,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = true,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canDeleteModel) {
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                            ) {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                val trimmedName = editingModel.displayName.trim()
                                if (trimmedName.isNotEmpty()) {
                                    dialogState.currentState = editingModel.copy(displayName = trimmedName)
                                    dialogState.confirm()
                                }
                            },
                            enabled = editingModel.displayName.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }

    OutlinedCard(
        onClick = { dialogState.open(model.copy()) },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                AutoAIIcon(
                    name = model.modelId,
                    modifier = Modifier.size(36.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (model.providerOverwrite != null) {
                        Tag(type = TagType.INFO) {
                            Text(
                                model.providerOverwrite?.javaClass?.simpleName ?: model.providerOverwrite?.name
                                ?: "ProviderOverwrite"
                            )
                        }
                    }
                    ModelTypeTag(model = model)
                    ModelModalityTag(model = model)
                    ModelAbilityTag(model = model)
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.setting_provider_page_edit_model)) },
                        leadingIcon = { Icon(HugeIcons.Tools, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            dialogState.open(model.copy())
                        },
                    )
                    if (canDeleteModel) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                showDeleteConfirm = true
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInToolsSettings(
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val availableTools = listOf(
            BuiltInTools.Search to Pair(
                stringResource(R.string.setting_page_built_in_tools_search),
                stringResource(R.string.setting_page_built_in_tools_search_desc)
            ),
            BuiltInTools.UrlContext to Pair(
                stringResource(R.string.setting_page_built_in_tools_url_context),
                stringResource(R.string.setting_page_built_in_tools_url_context_desc)
            ),
            BuiltInTools.ImageGeneration to Pair(
                stringResource(R.string.setting_page_built_in_tools_image_generation),
                stringResource(R.string.setting_page_built_in_tools_image_generation_desc)
            )
        )

        availableTools.forEach { (tool, info) ->
            val (title, description) = info
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = tool in tools,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onUpdateTools(tools + tool)
                            } else {
                                onUpdateTools(tools - tool)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_provider_override),
            style = MaterialTheme.typography.titleSmall
        )

        Text(
            text = stringResource(R.string.setting_provider_page_provider_override_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (providerOverride != null) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AutoAIIcon(
                            providerOverride.name,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.setting_provider_detail_override_name,
                                providerOverride.name,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                editingProvider = providerOverride
                                showProviderConfig = true
                            }
                        ) {
                            Icon(
                            HugeIcons.Tools,
                            contentDescription = stringResource(R.string.setting_provider_detail_edit_override),
                        )
                        }
                        IconButton(
                            onClick = {
                                onUpdateProviderOverride(null)
                            }
                        ) {
                            Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.setting_provider_detail_remove_override),
                        )
                        }
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    editingProvider = parentProvider?.copyProvider(
                        id = Uuid.random(),
                        builtIn = false,
                        models = emptyList(), // 这里必须设置为空，不然会导致循环依赖JSON
                        description = {},
                    )
                    showProviderConfig = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.setting_provider_page_add_provider_override))
            }
        }

        // Provider configuration modal
        val currentEditingProvider = editingProvider
        if (showProviderConfig && currentEditingProvider != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showProviderConfig = false
                    editingProvider = null
                },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
            ) {
                var internalProvider by remember(currentEditingProvider) { mutableStateOf(currentEditingProvider) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_configure_provider_override),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProviderConfigure(
                            provider = internalProvider,
                            onEdit = { internalProvider = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                onUpdateProviderOverride(internalProvider)
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Prefer capabilities auto-detected at fetch time (OpenRouter's /models populates
 * supportedParameters, modalities, abilities and the IMAGE model type); only fall back to
 * the static ModelRegistry lookup for providers that return bare model ids. Without this,
 * the registry lookup clobbered OpenRouter's detected image/tool/reasoning capabilities.
 */
private fun Model.enrichCapabilities(): Model =
    if (supportedParameters.isNotEmpty()) {
        this
    } else {
        copy(
            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
            abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
        )
    }
