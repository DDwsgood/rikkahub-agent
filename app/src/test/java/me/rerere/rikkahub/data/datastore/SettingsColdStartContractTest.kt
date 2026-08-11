package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the data invariants that the cold-start fix relies on: the dummy Settings
 * (used as the MutableStateFlow placeholder before DataStore loads) must resolve to
 * the bundled DEFAULT assistants whose localTools do NOT include Notification, so a
 * cron worker that reads the dummy would miss post_notification. The fix routes
 * headless reads through [SettingsStore.awaitLoadedSettings] to avoid this placeholder.
 */
class SettingsColdStartContractTest {

    @Test
    fun `dummy settings is marked init and carries the bundled default assistants`() {
        val dummy = Settings.dummy()
        assertTrue("dummy must carry the init sentinel", dummy.init)
        assertEquals(DEFAULT_ASSISTANTS, dummy.assistants)
        assertEquals(DEFAULT_ASSISTANT_ID, dummy.assistantId)
    }

    @Test
    fun `real default settings are not marked init`() {
        // A freshly-constructed Settings (as settingsFlowRaw emits on first install /
        // IOException fallback) must NOT carry the init sentinel — only dummy() does.
        assertFalse(Settings().init)
        assertFalse(Settings(assistants = emptyList()).init)
    }

    @Test
    fun `default assistants do not opt into Notification so dummy cannot offer post_notification`() {
        for (a in Settings.dummy().assistants) {
            assertFalse(
                "default assistant ${a.id} must not enable Notification by default",
                LocalToolOption.Notification in a.localTools,
            )
        }
    }

    @Test
    fun `a persisted assistant with Notification resolves to post_notification-capable tools`() {
        val id = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val assistant = Assistant(id = id, localTools = listOf(LocalToolOption.Notification))
        val settings = Settings(assistants = listOf(assistant))

        assertFalse("persisted settings are not the dummy", settings.init)
        val resolved = settings.findAssistantById(id)
        assertNotNull(resolved)
        assertTrue(
            "the persisted assistant's localTools must contain Notification",
            LocalToolOption.Notification in resolved!!.localTools,
        )
    }

    @Test
    fun `findAssistantById returns null for an unknown assistant on dummy settings`() {
        val unknown = Uuid.parse("22222222-2222-2222-2222-222222222222")
        assertNull(Settings.dummy().findAssistantById(unknown))
    }
}