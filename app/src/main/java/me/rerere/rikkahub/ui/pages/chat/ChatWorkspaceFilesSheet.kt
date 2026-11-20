package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.WorkspacePublicExport
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileType
import me.rerere.rikkahub.ui.pages.extensions.workspace.detectFileType
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * Quick bottom-sheet browser for the assistant's bound workspace (`/workspace`), accessible
 * directly from the Chat TopAppBar without navigating away from the conversation.
 */
@Composable
fun ChatWorkspaceFilesSheet(
    workspaceId: String,
    onDismissRequest: () -> Unit,
) {
    val repository = koinInject<WorkspaceRepository>()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var workspace by remember(workspaceId) { mutableStateOf<WorkspaceEntity?>(null) }
    var currentPath by remember(workspaceId) { mutableStateOf("") }
    var entries by remember(workspaceId) { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember(workspaceId) { mutableStateOf(true) }
    var error by remember(workspaceId) { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspaceId, currentPath, refreshTick) {
        loading = true
        error = null
        runCatching {
            if (workspace == null) {
                workspace = repository.getById(workspaceId)
            }
            repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, currentPath)
        }.onSuccess { list ->
            entries = list
            loading = false
        }.onFailure { err ->
            error = err.message ?: context.getString(R.string.workspace_file_editor_read_failed)
            loading = false
        }
    }

    var safExportEntry by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val safExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = safExportEntry.also { safExportEntry = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open target URI")
                repository.exportFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, out)
            }.onSuccess {
                toaster.show(
                    context.getString(R.string.workspace_file_saved_to_downloads, entry.name),
                    type = ToastType.Success,
                )
            }.onFailure { err ->
                toaster.show(
                    err.message ?: context.getString(R.string.workspace_file_save_to_downloads_failed),
                    type = ToastType.Error,
                )
            }
        }
    }

    fun stageAndRun(entry: WorkspaceFileEntry, onReady: (java.io.File) -> Unit) {
        scope.launch {
            runCatching {
                WorkspacePublicExport.stageToRestrictedShareCacheSuspend(
                    context = context,
                    fileName = entry.name,
                ) { out ->
                    repository.exportFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, out)
                }
            }.onSuccess(onReady).onFailure {
                toaster.show(
                    it.message ?: context.getString(R.string.workspace_file_open_externally_failed),
                    type = ToastType.Error,
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workspace?.name ?: stringResource(R.string.chat_workspace_files_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "/workspace/${currentPath}".trimEnd('/'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { refreshTick++ }) {
                    Icon(HugeIcons.Refresh01, contentDescription = null)
                }
                TextButton(
                    onClick = {
                        onDismissRequest()
                        navController.navigate(Screen.WorkspaceDetail(workspaceId))
                    },
                ) {
                    Text(stringResource(R.string.chat_workspace_files_open_full))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            currentPath = currentPath.substringBeforeLast('/', missingDelimiterValue = "")
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.ArrowTurnBackward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                workspace?.root?.let { wsRoot ->
                    OutlinedButton(
                        onClick = {
                            if (!WorkspacePublicExport.openWorkspaceInSystemFiles(context, wsRoot)) {
                                toaster.show(
                                    context.getString(R.string.workspace_detail_open_in_files_unavailable),
                                    type = ToastType.Warning,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.workspace_detail_open_in_files),
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                ) {
                    Text(text = error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }

                entries.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_detail_empty_directory),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        currentPath = entry.path
                                    } else {
                                        when (entry.detectFileType()) {
                                            WorkspaceFileType.TEXT -> {
                                                onDismissRequest()
                                                navController.navigate(
                                                    Screen.WorkspaceFileEditor(
                                                        workspaceId,
                                                        WorkspaceStorageArea.FILES.name,
                                                        entry.path,
                                                    )
                                                )
                                            }
                                            WorkspaceFileType.IMAGE -> stageAndRun(entry) { file ->
                                                previewImageUri = file.absolutePath
                                            }
                                            WorkspaceFileType.OTHER -> stageAndRun(entry) { file ->
                                                if (!WorkspacePublicExport.openCachedFileExternally(context, file)) {
                                                    toaster.show(
                                                        context.getString(R.string.workspace_file_open_externally_failed),
                                                        type = ToastType.Warning,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                            colors = CustomColors.cardColorsOnSurfaceContainer,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (entry.isDirectory) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (entry.isDirectory) {
                                            entry.path
                                        } else {
                                            "${entry.path} · ${entry.sizeBytes.fileSizeToString()}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!entry.isDirectory) {
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
                                                text = { Text(stringResource(R.string.workspace_file_save_to_downloads)) },
                                                leadingIcon = { Icon(HugeIcons.Download01, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    scope.launch {
                                                        runCatching {
                                                            repository.exportFileToDownloads(
                                                                workspaceId,
                                                                WorkspaceStorageArea.FILES,
                                                                entry.path,
                                                            )
                                                        }.onSuccess { result ->
                                                            toaster.show(
                                                                context.getString(
                                                                    R.string.workspace_file_saved_to_downloads,
                                                                    result.displayPath,
                                                                ),
                                                                type = ToastType.Success,
                                                            )
                                                        }.onFailure { err ->
                                                            if (err is me.rerere.rikkahub.data.files.DownloadExportException &&
                                                                err.code == me.rerere.rikkahub.data.files.ExportErrorCode.PERMISSION_REQUIRED
                                                            ) {
                                                                safExportEntry = entry
                                                                safExportLauncher.launch(entry.name)
                                                            } else {
                                                                toaster.show(
                                                                    err.message ?: context.getString(
                                                                        R.string.workspace_file_save_to_downloads_failed
                                                                    ),
                                                                    type = ToastType.Error,
                                                                )
                                                            }
                                                        }
                                                    }
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.workspace_file_open_externally)) },
                                                leadingIcon = { Icon(HugeIcons.Link01, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    stageAndRun(entry) { file ->
                                                        if (!WorkspacePublicExport.openCachedFileExternally(context, file)) {
                                                            toaster.show(
                                                                context.getString(R.string.workspace_file_open_externally_failed),
                                                                type = ToastType.Warning,
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.common_share)) },
                                                leadingIcon = { Icon(HugeIcons.Share08, contentDescription = null) },
                                                onClick = {
                                                    menuExpanded = false
                                                    stageAndRun(entry) { file ->
                                                        if (!WorkspacePublicExport.shareCachedFile(context, file)) {
                                                            toaster.show(
                                                                context.getString(R.string.workspace_file_open_externally_failed),
                                                                type = ToastType.Warning,
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }
}
