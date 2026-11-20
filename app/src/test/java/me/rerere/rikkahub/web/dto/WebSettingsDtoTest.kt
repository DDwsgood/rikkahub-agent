package me.rerere.rikkahub.web.dto

import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSettingsDtoTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun stripsAllSecretsLocalFontPathsAndHeavyToolSchemas() {
        val modelWithOverwrite = Model(
            modelId = "gpt-test",
            displayName = "GPT Test",
            customHeaders = listOf(CustomHeader(name = "Authorization", value = "Bearer SECRET_CUSTOM_HEADER")),
            providerOverwrite = ProviderSetting.OpenAI(
                name = "Overwrite",
                apiKey = "sk-SECRET_OVERWRITE_KEY",
            ),
        )
        val openAiProvider = ProviderSetting.OpenAI(
            name = "My OpenAI",
            apiKey = "sk-SECRET_PROVIDER_KEY",
            models = listOf(modelWithOverwrite),
        )
        val codexProvider = ProviderSetting.Codex(name = "My Codex")
        val stdioMcp = McpServerConfig.StdioTransportServer(
            commonOptions = McpCommonOptions(
                enable = true,
                name = "Private Stdio MCP",
                headers = listOf("X-Api-Key" to "SECRET_MCP_HEADER"),
                tools = listOf(
                    McpTool(
                        enable = true,
                        name = "secret_tool",
                        description = "Test tool",
                        inputSchema = InputSchema.Obj(
                            properties = kotlinx.serialization.json.buildJsonObject {
                                put("SECRET_SCHEMA_PROP", kotlinx.serialization.json.JsonPrimitive("string"))
                            },
                        ),
                    )
                ),
                oauth = McpOAuthState(
                    enabled = true,
                    clientSecret = "SECRET_OAUTH_CLIENT",
                    accessToken = "SECRET_OAUTH_ACCESS",
                    refreshToken = "SECRET_OAUTH_REFRESH",
                ),
            ),
            env = mapOf("GITHUB_TOKEN" to "SECRET_MCP_ENV_TOKEN"),
        )
        val remoteMcp = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(enable = true, name = "Remote MCP"),
            url = "https://mcp.internal.example/sse?token=SECRET_REMOTE_MCP_URL_TOKEN",
        )
        val settings = Settings(
            displaySetting = DisplaySetting(
                userNickname = "Alice",
                chatCustomFontPath = "/data/user/0/secret/custom_font.ttf",
                chatCustomFontName = "SECRET_FONT_NAME",
            ),
            providers = listOf(openAiProvider, codexProvider),
            mcpServers = listOf(stdioMcp, remoteMcp),
            searchServices = listOf(SearchServiceOptions.TavilyOptions(apiKey = "tvly-SECRET_SEARCH_KEY")),
            webDavConfig = WebDavConfig(
                url = "https://dav.example.com",
                username = "alice",
                password = "SECRET_WEBDAV_PASSWORD",
            ),
            s3Config = S3Config(
                endpoint = "https://s3.example.com",
                accessKeyId = "AKIA_SECRET_S3_KEY_ID",
                secretAccessKey = "SECRET_S3_ACCESS_KEY",
                bucket = "private-bucket",
            ),
            ttsProviders = listOf(TTSProviderSetting.OpenAI(apiKey = "SECRET_TTS_KEY")),
            asrProviders = listOf(ASRProviderSetting.OpenAIRealtime(apiKey = "SECRET_ASR_KEY")),
            webServerAccessPassword = "SECRET_WEB_PASSWORD",
        )

        val dto = settings.toWebSettingsDto()
        val encoded = json.encodeToString(WebSettingsDto.serializer(), dto)

        assertEquals(2, dto.providers.size)
        assertEquals("My OpenAI", dto.providers[0].name)
        assertEquals("My Codex", dto.providers[1].name)
        assertEquals("gpt-test", dto.providers[0].models.single().modelId)
        assertEquals("Private Stdio MCP", dto.mcpServers[0].commonOptions.name)
        assertEquals("Remote MCP", dto.mcpServers[1].commonOptions.name)
        assertEquals("tavily", dto.searchServices.single().type)
        assertEquals("Alice", dto.displaySetting.userNickname)

        val forbidden = listOf(
            "SECRET_PROVIDER_KEY",
            "SECRET_OVERWRITE_KEY",
            "SECRET_CUSTOM_HEADER",
            "SECRET_MCP_HEADER",
            "SECRET_OAUTH_CLIENT",
            "SECRET_OAUTH_ACCESS",
            "SECRET_OAUTH_REFRESH",
            "SECRET_MCP_ENV_TOKEN",
            "SECRET_REMOTE_MCP_URL_TOKEN",
            "SECRET_SCHEMA_PROP",
            "SECRET_SEARCH_KEY",
            "SECRET_WEBDAV_PASSWORD",
            "AKIA_SECRET_S3_KEY_ID",
            "SECRET_S3_ACCESS_KEY",
            "SECRET_TTS_KEY",
            "SECRET_ASR_KEY",
            "SECRET_WEB_PASSWORD",
            "custom_font.ttf",
            "SECRET_FONT_NAME",
        )
        for (secret in forbidden) {
            assertFalse("Encoded WebSettingsDto must not leak $secret", encoded.contains(secret))
        }
        assertTrue(encoded.contains("My OpenAI"))
        assertTrue(encoded.contains("GPT Test"))
    }

    @Test
    fun credentialOnlyChangesProduceEqualProjectionForSseDedup() {
        val baseProvider = ProviderSetting.OpenAI(name = "OpenAI", apiKey = "sk-old")
        val s1 = Settings(
            providers = listOf(baseProvider),
            webServerAccessPassword = "pw1",
            s3Config = S3Config(secretAccessKey = "s3-1"),
        )
        val s2 = s1.copy(
            providers = listOf(baseProvider.copy(apiKey = "sk-new-rotated")),
            webServerAccessPassword = "pw2",
            s3Config = S3Config(secretAccessKey = "s3-2"),
        )
        assertEquals(
            "Credential-only mutations must project identically so distinctUntilChanged suppresses re-emit",
            s1.toWebSettingsDto(),
            s2.toWebSettingsDto(),
        )
    }
}
