package me.rerere.rikkahub.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

/**
 * Provides stable per-process identity, per-boot identity, and monotonic uptime milliseconds
 * for crash-safe lease/orphan recovery without relying on wall-clock time (`currentTimeMillis`),
 * which can jump backward or forward on NTP/timezone/manual clock adjustments.
 */
internal object ProcessRuntimeIdentity {

    /** Unique ID minted once per OS process lifetime; changes across any app process kill/restart. */
    val processInstanceId: String = UUID.randomUUID().toString()

    /**
     * Stable identifier for the current device boot session (Android `Settings.Global.BOOT_COUNT`,
     * incremented monotonically by the OS on every reboot since API 24).
     */
    fun currentBootId(context: Context): String = runCatching {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (count >= 0) "boot-$count" else "boot-unknown"
    }.getOrDefault("boot-unknown")

    /** Monotonic milliseconds since device boot (never jumps on wall-clock edits). */
    fun monotonicNowMs(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
}
