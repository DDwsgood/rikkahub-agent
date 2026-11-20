package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.pages.assistant.AssistantVM
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.setting.mergeProviderConfigChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/**
 * Regression tests for P0-3:
 * 1. Field-level 3-way provider merge preserves concurrent model updates and untouched fields.
 * 2. Real DataStore-backed [SettingsStore.update] interleaving independent setting changes
 *    with provider/MCP list mutations never clobbers unrelated fields or concurrent additions.
 */
class SettingsMergeInterleaveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun mergeProviderConfigChangesPreservesConcurrentModelsAndUntouchedFields() {
        val providerId = Uuid.random()
        val m1 = Model(modelId = "gpt-4o", displayName = "GPT-4o")
        val m2 = Model(modelId = "gpt-4.1", displayName = "GPT-4.1")

        val base = ProviderSetting.OpenAI(
            id = providerId,
            name = "Old Name",
            apiKey = "sk-old",
            baseUrl = "https://api.openai.com/v1",
            promptCaching = true,
            models = listOf(m1),
        )
        // Meanwhile another path updated models and rotated apiKey in DataStore:
        val latest = base.copy(
            apiKey = "sk-rotated-bg",
            models = listOf(m1, m2),
        )
        // User ONLY edited `name` and `baseUrl` in the config draft (captured from `base`):
        val draft = base.copy(
            name = "Renamed Provider",
            baseUrl = "https://proxy.example.com/v1",
        )

        val merged = mergeProviderConfigChanges(latest = latest, base = base, draft = draft) as ProviderSetting.OpenAI
        assertEquals("Renamed Provider", merged.name)
        assertEquals("https://proxy.example.com/v1", merged.baseUrl)
        assertEquals("Untouched apiKey must keep latest value", "sk-rotated-bg", merged.apiKey)
        assertEquals("Config save must NEVER overwrite latest models", listOf(m1, m2), merged.models)
    }

    @Test
    fun concurrentInterleavedListAndScalarUpdatesPreserveAllChangesInRealDataStore() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(tempFolder.newFolder("ds"), "settings_interleave.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
        val store = SettingsStore(
            dataStore = dataStore,
            scope = scope,
            settingsFlowRawOverride = null,
        )

        try {
            val initialProvider = ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "Initial",
                apiKey = "sk-1",
                models = listOf(Model(modelId = "m0", displayName = "M0")),
            )
            // Seed non-dummy settings
            store.update { latest ->
                latest.copy(
                    dynamicColor = false,
                    providers = listOf(initialProvider),
                    mcpServers = emptyList(),
                )
            }

            val addedProvider = ProviderSetting.Claude(id = Uuid.random(), name = "Added Claude", apiKey = "sk-c")
            val addedModel = Model(modelId = "m-concurrent", displayName = "Concurrent Model")
            val addedMcp = McpServerConfig.SseTransportServer(
                id = Uuid.random(),
                commonOptions = McpCommonOptions(name = "Concurrent MCP", enable = true),
                url = "https://mcp.example.com/sse",
            )

            // Fire 4 independent concurrent transactional updates simultaneously
            val jobs = listOf(
                async(Dispatchers.IO) {
                    store.update { latest -> latest.copy(dynamicColor = true) }
                },
                async(Dispatchers.IO) {
                    store.update { latest -> latest.copy(providers = listOf(addedProvider) + latest.providers) }
                },
                async(Dispatchers.IO) {
                    store.update { latest ->
                        latest.copy(
                            providers = latest.providers.map { p ->
                                if (p.id == initialProvider.id) p.addModel(addedModel) else p
                            }
                        )
                    }
                },
                async(Dispatchers.IO) {
                    store.update { latest -> latest.copy(mcpServers = latest.mcpServers + addedMcp) }
                },
            )
            jobs.awaitAll()

            // 1. Immediately after all transactional updates complete, DataStore's raw source
            //    must reflect EVERY concurrent mutation with zero clobbering.
            val persisted = store.settingsFlowRaw.first()
            assertTrue("Independent scalar toggle must survive", persisted.dynamicColor)
            assertTrue(
                "Added provider must survive concurrent edits",
                persisted.providers.any { it.id == addedProvider.id },
            )
            val updatedInitial = persisted.providers.firstOrNull { it.id == initialProvider.id }
            assertTrue(
                "Model added concurrently to initial provider must survive",
                updatedInitial?.models?.any { it.modelId == "m-concurrent" } == true,
            )
            assertTrue(
                "Added MCP server must survive concurrent provider/scalar edits",
                persisted.mcpServers.any { it.id == addedMcp.id },
            )

            // 2. The UI StateFlow published solely from DataStore converges to the exact same state.
            val converged = withTimeout(2_000L) {
                store.settingsFlow.first { s ->
                    !s.init &&
                        s.dynamicColor &&
                        s.providers.any { it.id == addedProvider.id } &&
                        s.providers.firstOrNull { it.id == initialProvider.id }?.models?.any { it.modelId == "m-concurrent" } == true &&
                        s.mcpServers.any { it.id == addedMcp.id }
                }
            }
            assertEquals(persisted.dynamicColor, converged.dynamicColor)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun realViewModelAssistantAndBackupPathsNeverClobberConcurrentProviderOrMcpEdits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dsFile = File(tempFolder.newFolder("ds_vm"), "settings_vm_interleave.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dsFile }
        val store = SettingsStore(
            dataStore = dataStore,
            scope = scope,
            settingsFlowRawOverride = null,
        )

        try {
            val a1 = Assistant(id = Uuid.random(), name = "Alpha")
            val a2 = Assistant(id = Uuid.random(), name = "Beta")
            val a3 = Assistant(id = Uuid.random(), name = "Gamma")
            val t1 = Tag(id = Uuid.random(), name = "Work")
            val t2 = Tag(id = Uuid.random(), name = "Personal")
            val pKeep = ProviderSetting.OpenAI(id = Uuid.random(), name = "OAuth Provider", apiKey = "token-v1")
            val pDelete = ProviderSetting.Claude(id = Uuid.random(), name = "To Be Deleted", apiKey = "sk-del")
            val addedMcp = McpServerConfig.SseTransportServer(
                id = Uuid.random(),
                commonOptions = McpCommonOptions(name = "Bg MCP", enable = true),
                url = "https://mcp.concurrent.example/sse",
            )

            store.update { latest ->
                latest.copy(
                    assistants = listOf(a1, a2),
                    assistantTags = listOf(t1, t2),
                    providers = listOf(pKeep, pDelete),
                    mcpServers = emptyList(),
                )
            }

            // Concurrently invoke the real ViewModel transactional helpers (AssistantVM + BackupVM)
            // interleaved with background OAuth token refresh, provider deletion, and MCP addition.
            val jobs = listOf(
                async(Dispatchers.IO) { AssistantVM.applyReorderAssistant(store, a1.id, a2.id) },
                async(Dispatchers.IO) { AssistantVM.applyReorderAssistantTag(store, t1.id, t2.id) },
                async(Dispatchers.IO) { AssistantVM.applyAddAssistant(store, a3) },
                async(Dispatchers.IO) {
                    BackupVM.applyS3Config(store, S3Config(endpoint = "https://s3.example.com", bucket = "prod-backup"))
                },
                async(Dispatchers.IO) {
                    BackupVM.applyWebDavConfig(store, WebDavConfig(url = "https://dav.example.com/backup"))
                },
                async(Dispatchers.IO) {
                    // Simulated background OAuth token refresh on pKeep
                    store.update { latest ->
                        latest.copy(
                            providers = latest.providers.map { p ->
                                if (p.id == pKeep.id && p is ProviderSetting.OpenAI) p.copy(apiKey = "token-v2-refreshed") else p
                            }
                        )
                    }
                },
                async(Dispatchers.IO) {
                    // Simulated provider deletion
                    store.update { latest ->
                        latest.copy(providers = latest.providers.filterNot { it.id == pDelete.id })
                    }
                },
                async(Dispatchers.IO) {
                    store.update { latest -> latest.copy(mcpServers = latest.mcpServers + addedMcp) }
                },
            )
            jobs.awaitAll()

            val persisted = store.settingsFlowRaw.first()
            assertEquals("prod-backup", persisted.s3Config.bucket)
            assertEquals("https://dav.example.com/backup", persisted.webDavConfig.url)
            assertTrue("New assistant a3 must survive concurrent reorder + backup save", persisted.assistants.any { it.id == a3.id })
            assertTrue(
                "Reorder of a1 after a2 must be reflected",
                persisted.assistants.indexOfFirst { it.id == a2.id } < persisted.assistants.indexOfFirst { it.id == a1.id },
            )
            assertEquals(listOf(t2.id, t1.id), persisted.assistantTags.map { it.id })
            val refreshed = persisted.providers.firstOrNull { it.id == pKeep.id } as? ProviderSetting.OpenAI
            assertEquals("Background OAuth refresh must survive UI saves", "token-v2-refreshed", refreshed?.apiKey)
            assertFalse("Deleted provider must NEVER be resurrected by Assistant/Backup UI saves", persisted.providers.any { it.id == pDelete.id })
            assertTrue("Concurrent MCP addition must survive", persisted.mcpServers.any { it.id == addedMcp.id })
        } finally {
            scope.cancel()
        }
    }
}
