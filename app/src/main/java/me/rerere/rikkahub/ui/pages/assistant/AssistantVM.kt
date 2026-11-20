package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(transform: (Settings) -> Settings): Job =
        viewModelScope.launch {
            settingsStore.update(transform)
        }

    fun reorderAssistant(fromId: Uuid, toId: Uuid): Job =
        viewModelScope.launch {
            applyReorderAssistant(settingsStore, fromId, toId)
        }

    fun reorderAssistantTag(fromId: Uuid, toId: Uuid): Job =
        viewModelScope.launch {
            applyReorderAssistantTag(settingsStore, fromId, toId)
        }

    fun addAssistant(assistant: Assistant): Job =
        viewModelScope.launch {
            applyAddAssistant(settingsStore, assistant)
        }

    fun removeAssistant(assistant: Assistant): Job =
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            applyRemoveAssistant(settingsStore, assistant.id)
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant): Job =
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if (assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update { latest ->
                latest.copy(
                    assistants = latest.assistants + copiedAssistant
                )
            }
        }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }

    companion object {
        internal suspend fun applyReorderAssistant(
            settingsStore: SettingsStore,
            fromId: Uuid,
            toId: Uuid,
        ) {
            settingsStore.update { latest ->
                val list = latest.assistants.toMutableList()
                val fromIdx = list.indexOfFirst { it.id == fromId }
                val toIdx = list.indexOfFirst { it.id == toId }
                if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@update latest
                list.add(toIdx, list.removeAt(fromIdx))
                latest.copy(assistants = list)
            }
        }

        internal suspend fun applyReorderAssistantTag(
            settingsStore: SettingsStore,
            fromId: Uuid,
            toId: Uuid,
        ) {
            settingsStore.update { latest ->
                val list = latest.assistantTags.toMutableList()
                val fromIdx = list.indexOfFirst { it.id == fromId }
                val toIdx = list.indexOfFirst { it.id == toId }
                if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@update latest
                list.add(toIdx, list.removeAt(fromIdx))
                latest.copy(assistantTags = list)
            }
        }

        internal suspend fun applyAddAssistant(
            settingsStore: SettingsStore,
            assistant: Assistant,
        ) {
            settingsStore.update { latest ->
                if (latest.assistants.any { it.id == assistant.id }) {
                    latest
                } else {
                    latest.copy(assistants = latest.assistants + assistant)
                }
            }
        }

        internal suspend fun applyRemoveAssistant(
            settingsStore: SettingsStore,
            assistantId: Uuid,
        ) {
            settingsStore.update { latest ->
                latest.copy(
                    assistants = latest.assistants.filter { it.id != assistantId }
                )
            }
        }
    }
}
