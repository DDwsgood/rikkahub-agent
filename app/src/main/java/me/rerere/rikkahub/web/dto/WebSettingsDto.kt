package me.rerere.rikkahub.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.search.SearchServiceOptions

/**
 * Redacted, allowlist projection of [Settings] for the Web UI (`/api/events`).
 *
 * Never serialize raw [Settings] to web clients: raw [Settings] carries plaintext provider
 * API keys, `model.providerOverwrite` / `customHeaders`, `webServerAccessPassword`,
 * WebDAV/S3 credentials, MCP OAuth/header/env secrets, search API keys, and TTS/ASR keys.
 */
@Serializable
data class WebSettingsDto(
    val dynamicColor: Boolean,
    val themeId: String,
    val developerMode: Boolean,
    val displaySetting: WebDisplaySettingDto,
    val favoriteModels: List<String>,
    val chatModelId: String,
    val assistantId: String,
    val webServerJwtEnabled: Boolean,
    val providers: List<WebProviderProfileDto>,
    val assistants: List<WebAssistantProfileDto>,
    val assistantTags: List<Tag>,
    val modeInjections: List<WebModeInjectionDto>,
    val lorebooks: List<WebLorebookDto>,
    val mcpServers: List<WebMcpServerDto>,
    val searchServices: List<WebSearchServiceDto>,
    val quickMessages: List<QuickMessage>,
    val searchServiceSelected: Int,
)

/**
 * Minimized display-settings projection for the Web UI — omits device-local paths such as
 * `chatCustomFontPath` / `chatCustomFontName` and mobile-only notification/haptic toggles.
 */
@Serializable
data class WebDisplaySettingDto(
    val userAvatar: Avatar,
    val userNickname: String,
    val showUserAvatar: Boolean,
    val showModelIcon: Boolean,
    val showModelName: Boolean,
    val showTokenUsage: Boolean,
    val showThinkingContent: Boolean,
    val autoCloseThinking: Boolean,
    val codeBlockAutoWrap: Boolean,
    val codeBlockAutoCollapse: Boolean,
    val showLineNumbers: Boolean,
    val sendOnEnter: Boolean,
    val enableAutoScroll: Boolean,
    val fontSizeRatio: Float,
    val pasteLongTextAsFile: Boolean,
    val pasteLongTextThreshold: Int,
)

@Serializable
data class WebProviderProfileDto(
    val id: String,
    val enabled: Boolean,
    val name: String,
    val models: List<WebProviderModelDto>,
)

@Serializable
data class WebProviderModelDto(
    val id: String,
    val modelId: String,
    val displayName: String,
    val type: ModelType,
    val inputModalities: List<Modality>,
    val outputModalities: List<Modality>,
    val abilities: List<ModelAbility>,
    val tools: Set<BuiltInTools>,
)

@Serializable
data class WebAssistantProfileDto(
    val id: String,
    val chatModelId: String? = null,
    val reasoningLevel: ReasoningLevel,
    val enableWebSearch: Boolean,
    val mcpServers: List<String>,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val allowConversationPromptInjection: Boolean,
    val allowConversationSystemPrompt: Boolean,
    val name: String,
    val avatar: Avatar,
    val useAssistantAvatar: Boolean,
    val tags: List<String>,
    val quickMessageIds: List<String>,
)

@Serializable
data class WebModeInjectionDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

@Serializable
data class WebLorebookDto(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

@Serializable
data class WebMcpServerDto(
    val id: String,
    val type: String,
    val commonOptions: WebMcpCommonOptionsDto,
)

@Serializable
data class WebMcpCommonOptionsDto(
    val enable: Boolean,
    val name: String,
    val tools: List<WebMcpToolDto>,
)

@Serializable
data class WebMcpToolDto(
    val enable: Boolean,
    val name: String,
    val description: String? = null,
    val needsApproval: Boolean = false,
)

@Serializable
data class WebSearchServiceDto(
    val id: String,
    val type: String,
)

fun Settings.toWebSettingsDto(): WebSettingsDto = WebSettingsDto(
    dynamicColor = dynamicColor,
    themeId = themeId,
    developerMode = developerMode,
    displaySetting = displaySetting.toWebDto(),
    favoriteModels = favoriteModels.map { it.toString() },
    chatModelId = chatModelId.toString(),
    assistantId = assistantId.toString(),
    webServerJwtEnabled = webServerJwtEnabled,
    providers = providers.map { it.toWebDto() },
    assistants = assistants.map { it.toWebDto() },
    assistantTags = assistantTags,
    modeInjections = modeInjections.map { it.toWebDto() },
    lorebooks = lorebooks.map { it.toWebDto() },
    mcpServers = mcpServers.map { it.toWebDto() },
    searchServices = searchServices.map { it.toWebDto() },
    quickMessages = quickMessages,
    searchServiceSelected = searchServiceSelected,
)

private fun DisplaySetting.toWebDto(): WebDisplaySettingDto = WebDisplaySettingDto(
    userAvatar = userAvatar,
    userNickname = userNickname,
    showUserAvatar = showUserAvatar,
    showModelIcon = showModelIcon,
    showModelName = showModelName,
    showTokenUsage = showTokenUsage,
    showThinkingContent = showThinkingContent,
    autoCloseThinking = autoCloseThinking,
    codeBlockAutoWrap = codeBlockAutoWrap,
    codeBlockAutoCollapse = codeBlockAutoCollapse,
    showLineNumbers = showLineNumbers,
    sendOnEnter = sendOnEnter,
    enableAutoScroll = enableAutoScroll,
    fontSizeRatio = fontSizeRatio,
    pasteLongTextAsFile = pasteLongTextAsFile,
    pasteLongTextThreshold = pasteLongTextThreshold,
)

private fun ProviderSetting.toWebDto(): WebProviderProfileDto = WebProviderProfileDto(
    id = id.toString(),
    enabled = enabled,
    name = name,
    models = models.map { it.toWebDto() },
)

private fun Model.toWebDto(): WebProviderModelDto = WebProviderModelDto(
    id = id.toString(),
    modelId = modelId,
    displayName = displayName,
    type = type,
    inputModalities = inputModalities,
    outputModalities = outputModalities,
    abilities = abilities,
    tools = tools,
)

private fun Assistant.toWebDto(): WebAssistantProfileDto = WebAssistantProfileDto(
    id = id.toString(),
    chatModelId = chatModelId?.toString(),
    reasoningLevel = reasoningLevel,
    enableWebSearch = enableWebSearch,
    mcpServers = mcpServers.map { it.toString() },
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    allowConversationPromptInjection = allowConversationPromptInjection,
    allowConversationSystemPrompt = allowConversationSystemPrompt,
    name = name,
    avatar = avatar,
    useAssistantAvatar = useAssistantAvatar,
    tags = tags.map { it.toString() },
    quickMessageIds = quickMessageIds.map { it.toString() },
)

private fun PromptInjection.ModeInjection.toWebDto(): WebModeInjectionDto = WebModeInjectionDto(
    id = id.toString(),
    name = name,
    enabled = enabled,
)

private fun Lorebook.toWebDto(): WebLorebookDto = WebLorebookDto(
    id = id.toString(),
    name = name,
    description = description,
    enabled = enabled,
)

private fun McpServerConfig.toWebDto(): WebMcpServerDto = WebMcpServerDto(
    id = id.toString(),
    type = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
        is McpServerConfig.StdioTransportServer -> "stdio"
    },
    commonOptions = commonOptions.toWebDto(),
)

private fun McpCommonOptions.toWebDto(): WebMcpCommonOptionsDto = WebMcpCommonOptionsDto(
    enable = enable,
    name = name,
    tools = tools.map { it.toWebDto() },
)

private fun McpTool.toWebDto(): WebMcpToolDto = WebMcpToolDto(
    enable = enable,
    name = name,
    description = description,
    needsApproval = needsApproval,
)

private fun SearchServiceOptions.toWebDto(): WebSearchServiceDto {
    val serialName = this::class.annotations
        .filterIsInstance<SerialName>()
        .firstOrNull()?.value
        ?: when (this) {
            is SearchServiceOptions.BingLocalOptions -> "bing_local"
            is SearchServiceOptions.DuckDuckGoOptions -> "duckduckgo"
            is SearchServiceOptions.ZhipuOptions -> "zhipu"
            is SearchServiceOptions.TavilyOptions -> "tavily"
            is SearchServiceOptions.ExaOptions -> "exa"
            is SearchServiceOptions.SearXNGOptions -> "searxng"
            is SearchServiceOptions.LinkUpOptions -> "linkup"
            is SearchServiceOptions.BraveOptions -> "brave"
            is SearchServiceOptions.MetasoOptions -> "metaso"
            is SearchServiceOptions.OllamaOptions -> "ollama"
            is SearchServiceOptions.PerplexityOptions -> "perplexity"
            is SearchServiceOptions.FirecrawlOptions -> "firecrawl"
            is SearchServiceOptions.JinaOptions -> "jina"
            is SearchServiceOptions.BochaOptions -> "bocha"
            is SearchServiceOptions.RikkaHubOptions -> "rikkahub"
            is SearchServiceOptions.GrokOptions -> "grok"
            is SearchServiceOptions.TinyfishOptions -> "tinyfish"
            is SearchServiceOptions.SerperOptions -> "serper"
            is SearchServiceOptions.CustomJsOptions -> "custom_js"
        }
    return WebSearchServiceDto(
        id = id.toString(),
        type = serialName,
    )
}
