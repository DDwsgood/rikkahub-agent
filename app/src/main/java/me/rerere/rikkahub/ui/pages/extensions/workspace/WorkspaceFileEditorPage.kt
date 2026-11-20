package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.WorkspacePublicExport
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        runCatching {
            repository.readTextForPreview(id, area, path)
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure {
            loadError = it.message ?: context.getString(R.string.workspace_file_editor_read_failed)
            loading = false
        }
    }

    var pendingSafExport by remember { mutableStateOf(false) }
    val safExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val wasPending = pendingSafExport
        pendingSafExport = false
        if (!wasPending || uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open target URI")
                repository.exportFile(id, area, path, output)
            }.onSuccess {
                toaster.show(
                    context.getString(R.string.workspace_file_saved_to_downloads, fileName),
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

    fun exportToDownloads() {
        scope.launch {
            runCatching {
                repository.exportFileToDownloads(id, area, path)
            }.onSuccess { result ->
                toaster.show(
                    context.getString(R.string.workspace_file_saved_to_downloads, result.displayPath),
                    type = ToastType.Success,
                )
            }.onFailure { err ->
                if (err is me.rerere.rikkahub.data.files.DownloadExportException &&
                    err.code == me.rerere.rikkahub.data.files.ExportErrorCode.PERMISSION_REQUIRED
                ) {
                    pendingSafExport = true
                    safExportLauncher.launch(fileName)
                } else {
                    toaster.show(
                        err.message ?: context.getString(R.string.workspace_file_save_to_downloads_failed),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }

    fun openExternally() {
        scope.launch {
            runCatching {
                WorkspacePublicExport.stageToRestrictedShareCacheSuspend(
                    context = context,
                    fileName = fileName,
                ) { out ->
                    repository.exportFile(id, area, path, out)
                }
            }.onSuccess { staged ->
                if (!WorkspacePublicExport.openCachedFileExternally(context, staged)) {
                    toaster.show(
                        context.getString(R.string.workspace_file_open_externally_failed),
                        type = ToastType.Warning,
                    )
                }
            }.onFailure {
                toaster.show(
                    it.message ?: context.getString(R.string.workspace_file_open_externally_failed),
                    type = ToastType.Error,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = ::exportToDownloads) {
                        Icon(
                            imageVector = HugeIcons.Download01,
                            contentDescription = stringResource(R.string.workspace_file_save_to_downloads),
                        )
                    }
                    IconButton(onClick = ::openExternally) {
                        Icon(
                            imageVector = HugeIcons.Link01,
                            contentDescription = stringResource(R.string.workspace_file_open_externally),
                        )
                    }
                    if (editable && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            id = id,
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        toaster.show(
                                            context.getString(R.string.workspace_file_editor_saved),
                                            type = ToastType.Success,
                                        )
                                    }.onFailure {
                                        toaster.show(
                                            it.message ?: context.getString(R.string.workspace_file_editor_save_failed),
                                            type = ToastType.Error,
                                        )
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = ::openExternally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_file_open_externally))
                    }
                    OutlinedButton(
                        onClick = ::exportToDownloads,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.workspace_file_save_to_downloads))
                    }
                }
            }

            else -> TextField(
                state = textState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                readOnly = !editable,
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = JetbrainsMono,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}
