package me.rerere.rikkahub.data.preferences

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.workspaceSharedDirDataStore by preferencesDataStore(name = "workspace_shared_dir_prefs")

/**
 * Per-workspace opt-in toggle for mounting a dedicated user-visible public directory
 * (`/sdcard/Documents/RikkaHub/<workspace>/`) into PRoot at `/shared`.
 *
 * Stored in a dedicated DataStore keyed by workspace ID so we avoid bumping the Room
 * schema (preserving clean migration compatibility with upstream).
 */
class WorkspaceSharedDirPreferences(private val context: Context) {

    fun isEnabledFlow(workspaceId: String): Flow<Boolean> =
        context.workspaceSharedDirDataStore.data.map { prefs ->
            workspaceId in (prefs[ENABLED_IDS_KEY] ?: emptySet())
        }

    suspend fun isEnabled(workspaceId: String): Boolean = isEnabledFlow(workspaceId).first()

    suspend fun setEnabled(workspaceId: String, enabled: Boolean) {
        context.workspaceSharedDirDataStore.edit { prefs ->
            val current = (prefs[ENABLED_IDS_KEY] ?: emptySet()).toMutableSet()
            if (enabled) current.add(workspaceId) else current.remove(workspaceId)
            if (current.isEmpty()) {
                prefs.remove(ENABLED_IDS_KEY)
            } else {
                prefs[ENABLED_IDS_KEY] = current
            }
        }
    }

    suspend fun removeWorkspace(workspaceId: String) {
        setEnabled(workspaceId, false)
    }

    companion object {
        const val MOUNT_TARGET = "/shared"
        private val ENABLED_IDS_KEY = stringSetPreferencesKey("shared_dir_enabled_ids")

        /**
         * Resolves the dedicated, workspace-isolated public output folder on shared storage:
         * `/storage/emulated/0/Documents/RikkaHub/ws-<stableId>/`.
         *
         * Keyed strictly by stable workspace ID (never by display name) so two workspaces with
         * identical or colliding sanitized names cannot share a folder and renaming a workspace
         * never shifts the mount point.
         */
        @Suppress("DEPRECATION")
        fun hostDir(@Suppress("UNUSED_PARAMETER") workspaceName: String, workspaceId: String): File {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val safeFolder = stableFolderName(workspaceId)
            return File(File(docs, "RikkaHub"), safeFolder)
        }

        fun displayPath(@Suppress("UNUSED_PARAMETER") workspaceName: String, workspaceId: String): String {
            val safeFolder = stableFolderName(workspaceId)
            return "Documents/RikkaHub/$safeFolder"
        }

        internal fun stableFolderName(workspaceId: String): String {
            val cleaned = workspaceId
                .replace(Regex("[^a-zA-Z0-9_-]"), "")
                .take(48)
            val fallback = workspaceId.hashCode().toUInt().toString(16)
            return "ws-${cleaned.ifBlank { fallback }}"
        }
    }
}
