package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object PermissionHelper {
    fun hasRuntime(ctx: Context, perms: List<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun hasWriteSettings(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun hasDndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun writeSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${ctx.packageName}".toUri())

    fun dndAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS is @hide/@SystemApi; use literal action string.
            Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
                .setData("package:${ctx.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }

    /**
     * True iff the app is exempted from Doze battery optimizations. Without this exemption,
     * OEM-aggressive ROMs (Xiaomi/OPPO/OnePlus/Vivo) cut network for foreground services when
     * the screen turns off, which makes the Telegram bot's long-poll go silent.
     */
    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

    /**
     * Direct prompt to whitelist the app from Doze. Required-permission per Play Store policy
     * is `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; manifest declares it.
     */
    fun requestIgnoreBatteryOptimizationsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${ctx.packageName}".toUri())

    /** Fallback page for users who declined the prompt; shows the system-wide list. */
    fun batteryOptimizationsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** True iff the app can schedule exact alarms (Android 12+ SCHEDULE_EXACT_ALARM). */
    fun canScheduleExactAlarms(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactAlarmAccessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData("package:${ctx.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasAccessibilityService(ctx: Context): Boolean =
        AccessibilityServiceHandle.isEnabledInSettings(ctx)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationListener(ctx: Context): Boolean =
        NotificationListenerHandle.isEnabledInSettings(ctx)

    fun notificationListenerSettingsIntent(): Intent =
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Direct shared-storage File access capability (gates `/sdcard` and `/shared` bind mounts):
     * - Android 11+ (API 30+): requires `MANAGE_EXTERNAL_STORAGE` (`Environment.isExternalStorageManager()`).
     * - Android 10 (API 29): scoped storage blocks raw File writes to `/sdcard/Documents/...` unless
     *   `Environment.isExternalStorageLegacy()` is true AND `WRITE_EXTERNAL_STORAGE` is granted.
     * - Android 8–9 (API 26–28): requires runtime `WRITE_EXTERNAL_STORAGE` grant.
     */
    fun hasAllFilesAccess(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
            Environment.isExternalStorageLegacy() &&
                ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        else -> {
            ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun allFilesAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * True iff the app may post full-screen intents. USE_FULL_SCREEN_INTENT is granted at
     * install, but Android 14+ (API 34) lets the user revoke it from app settings — a
     * revoked grant silently degrades setFullScreenIntent to a normal heads-up
     * notification, which breaks the "wake screen on fire" feature without any error.
     * Pre-34 there is no revocation surface, so it's always true there.
     */
    fun canUseFullScreenIntent(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Settings page where the user can re-grant full-screen intent (API 34+). */
    fun fullScreenIntentSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData("package:${ctx.packageName}".toUri())
        } else {
            // Pre-34 has no dedicated page — fall back to app notification settings.
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        }
}
