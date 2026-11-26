package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.KNOWN_CONFIGURABLE_TOOLS
import me.rerere.rikkahub.data.ai.tools.ToolRegistry
import me.rerere.rikkahub.data.ai.tools.effectiveInitialExposedTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.message.tools.toolDisplayName
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private data class ConfigurableToolItem(
    val name: String,
    val category: String,
)

@Composable
fun SettingInitialToolsPage() {
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var query by remember { mutableStateOf("") }

    val selectedTools = remember(settings.initialExposedTools) {
        effectiveInitialExposedTools(settings.initialExposedTools)
    }
    val allTools = remember {
        val map = linkedMapOf<String, String>()
        KNOWN_CONFIGURABLE_TOOLS.forEach { (name, cat) -> map[name] = cat }
        ToolRegistry.getAll()
            .sortedBy { it.name }
            .forEach { entry -> map.putIfAbsent(entry.name, entry.category) }
        map.entries.map { (name, cat) -> ConfigurableToolItem(name, cat) }
    }

    val context = LocalContext.current
    val localizedNames = remember(allTools, context) {
        allTools.associate { it.name to toolDisplayName(context, it.name) }
    }

    val filtered = remember(allTools, query, localizedNames) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allTools
        else allTools.filter {
            it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (localizedNames[it.name]?.lowercase()?.contains(q) == true)
        }
    }

    val grouped = remember(filtered) {
        val order = listOf("shell", "workspace", "file", "misc")
        filtered.groupBy { it.category }.toSortedMap(
            compareBy<String> {
                val idx = order.indexOf(it)
                if (idx >= 0) idx else order.size
            }.thenBy { it }
        )
    }

    fun toggleTool(name: String, checked: Boolean) {
        scope.launch {
            settingsStore.update { latest ->
                val current = effectiveInitialExposedTools(latest.initialExposedTools).toMutableSet()
                if (checked) current.add(name) else current.remove(name)
                latest.copy(initialExposedTools = current)
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_initial_tools)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (settings.initialExposedTools != null) {
                        TextButton(onClick = {
                            scope.launch {
                                settingsStore.update { it.copy(initialExposedTools = null) }
                            }
                        }) {
                            Text(stringResource(R.string.setting_page_initial_tools_reset))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_page_initial_tools_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(R.string.setting_page_initial_tools_search_hint))
                        },
                    )
                }
            }

            grouped.forEach { (category, items) ->
                item(key = "cat_$category") {
                    Text(
                        text = initialToolsCategoryLabel(category),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
                item(key = "group_$category") {
                    CardGroup {
                        items.forEach { tool ->
                            val isChecked = tool.name in selectedTools
                            item(
                                headlineContent = {
                                    Text(localizedNames[tool.name] ?: toolDisplayName(tool.name))
                                },
                                trailingContent = {
                                    Switch(
                                        checked = isChecked,
                                        onCheckedChange = { toggleTool(tool.name, it) },
                                    )
                                },
                                onClick = { toggleTool(tool.name, !isChecked) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun initialToolsCategoryLabel(category: String): String = when (category) {
    "shell" -> stringResource(R.string.setting_page_initial_tools_cat_shell)
    "workspace" -> stringResource(R.string.setting_page_initial_tools_cat_workspace)
    "file" -> stringResource(R.string.setting_page_initial_tools_cat_file)
    "misc" -> stringResource(R.string.setting_page_initial_tools_cat_core)
    else -> category.replaceFirstChar { it.uppercase() }
}
