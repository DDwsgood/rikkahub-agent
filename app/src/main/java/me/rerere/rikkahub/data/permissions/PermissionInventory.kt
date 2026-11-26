package me.rerere.rikkahub.data.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService

/**
 * Auto-discovered inventory of every permission this app requires, grouped by how the
 * user grants it. Reads <uses-permission> entries at runtime via PackageManager so
 * future-added perms appear automatically with localized labels/descriptions.
 */
object PermissionInventory {

    enum class Group { ServicesAndIntegrations, SpecialAccess, Runtime, AutoGranted }

    enum class Status { GRANTED, DENIED, AUTO_GRANTED }

    sealed class GrantAction {
        /** No action required — install-time / signature-level / always granted. */
        object None : GrantAction()
        /** Request via ActivityResultContracts.RequestPermission. */
        data class Runtime(val permission: String) : GrantAction()
        /** Open this Intent, user toggles in system Settings. */
        data class SystemSettings(val intent: Intent) : GrantAction()
    }

    data class Row(
        val id: String,
        val label: String,
        val description: String,
        val status: Status,
        val group: Group,
        val grant: GrantAction,
    )

    fun build(context: Context): List<Row> {
        val rows = mutableListOf<Row>()
        rows += accessibilityServiceRow(context)
        rows += notificationListenerRow(context)

        val declared = readDeclaredPermissions(context)
        for (perm in declared) {
            rows += classify(context, perm) ?: continue
        }
        return rows.sortedWith(
            compareBy({ it.group.ordinal }, { if (it.status == Status.DENIED) 0 else 1 }, { it.label })
        )
    }

    private fun readDeclaredPermissions(context: Context): List<String> {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    private fun classify(context: Context, perm: String): Row? {
        val pm = context.packageManager
        val pkgUri: Uri = ("package:" + context.packageName).toUri()

        when (perm) {
            Manifest.permission.SYSTEM_ALERT_WINDOW -> {
                val granted = Settings.canDrawOverlays(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_overlay_label),
                    description = context.getString(R.string.perm_overlay_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
                    ),
                )
            }
            Manifest.permission.WRITE_SETTINGS -> {
                val granted = Settings.System.canWrite(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_write_settings_label),
                    description = context.getString(R.string.perm_write_settings_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
                    ),
                )
            }
            Manifest.permission.ACCESS_NOTIFICATION_POLICY -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val granted = nm?.isNotificationPolicyAccessGranted == true
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_dnd_label),
                    description = context.getString(R.string.perm_dnd_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    ),
                )
            }
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> {
                val pwm = context.getSystemService(PowerManager::class.java)
                val granted = pwm?.isIgnoringBatteryOptimizations(context.packageName) == true
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_battery_opt_label),
                    description = context.getString(R.string.perm_battery_opt_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    ),
                )
            }
            Manifest.permission.SCHEDULE_EXACT_ALARM -> {
                val label = context.getString(R.string.perm_exact_alarm_label)
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(AlarmManager::class.java)
                    val granted = alarmManager?.canScheduleExactAlarms() == true
                    Row(
                        id = perm,
                        label = label,
                        description = context.getString(R.string.perm_exact_alarm_desc),
                        status = if (granted) Status.GRANTED else Status.DENIED,
                        group = Group.SpecialAccess,
                        grant = GrantAction.SystemSettings(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
                        ),
                    )
                } else {
                    autoRow(context, perm, label)
                }
            }
            Manifest.permission.MANAGE_EXTERNAL_STORAGE -> {
                val granted = PermissionHelper.hasAllFilesAccess(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_all_files_label),
                    description = context.getString(R.string.perm_all_files_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(PermissionHelper.allFilesAccessIntent(context)),
                )
            }
            Manifest.permission.PACKAGE_USAGE_STATS -> {
                val granted = hasUsageStatsAccess(context)
                return Row(
                    id = perm,
                    label = context.getString(R.string.perm_usage_stats_label),
                    description = context.getString(R.string.perm_usage_stats_desc),
                    status = if (granted) Status.GRANTED else Status.DENIED,
                    group = Group.SpecialAccess,
                    grant = GrantAction.SystemSettings(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ),
                )
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                val label = context.getString(R.string.perm_post_notifications_label)
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(context, perm) ==
                        PackageManager.PERMISSION_GRANTED
                    Row(
                        id = perm,
                        label = label,
                        description = context.getString(R.string.perm_post_notifications_desc),
                        status = if (granted) Status.GRANTED else Status.DENIED,
                        group = Group.Runtime,
                        grant = GrantAction.Runtime(perm),
                    )
                } else {
                    autoRow(context, perm, label)
                }
            }
        }

        val info: PermissionInfo? = try {
            pm.getPermissionInfo(perm, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        if (perm == "com.termux.permission.RUN_COMMAND") {
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            return Row(
                id = perm,
                label = labelOrHumanize(context, perm, info, pm),
                description = describeRuntime(context, perm),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.ServicesAndIntegrations,
                grant = GrantAction.None,
            )
        }

        if (info == null) {
            val granted = ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
            return Row(
                id = perm,
                label = labelOrHumanize(context, perm, null, pm),
                description = context.getString(R.string.perm_desc_custom_uninstalled),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        }

        val protectionBase = info.protection
        val isDangerous = protectionBase == PermissionInfo.PROTECTION_DANGEROUS
        val granted = ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED

        return if (isDangerous) {
            Row(
                id = perm,
                label = labelOrHumanize(context, perm, info, pm),
                description = describeRuntime(context, perm),
                status = if (granted) Status.GRANTED else Status.DENIED,
                group = Group.Runtime,
                grant = GrantAction.Runtime(perm),
            )
        } else {
            autoRow(context, perm, labelOrHumanize(context, perm, info, pm))
        }
    }

    private fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun autoRow(context: Context, perm: String, label: String) = Row(
        id = perm,
        label = label,
        description = context.getString(R.string.perm_desc_auto_granted),
        status = Status.AUTO_GRANTED,
        group = Group.AutoGranted,
        grant = GrantAction.None,
    )

    private fun accessibilityServiceRow(context: Context): Row {
        val component = ComponentName(context, RikkaAccessibilityService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_ACCESSIBILITY",
            label = context.getString(R.string.perm_service_accessibility_label),
            description = context.getString(R.string.perm_service_accessibility_desc),
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private fun notificationListenerRow(context: Context): Row {
        val component = ComponentName(context, RikkaNotificationListenerService::class.java)
            .flattenToString()
        val enabled = (Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: "").split(":").any { it.equals(component, ignoreCase = true) }
        return Row(
            id = "rikkahub.SERVICE_NOTIFICATION_LISTENER",
            label = context.getString(R.string.perm_service_notification_listener_label),
            description = context.getString(R.string.perm_service_notification_listener_desc),
            status = if (enabled) Status.GRANTED else Status.DENIED,
            group = Group.ServicesAndIntegrations,
            grant = GrantAction.SystemSettings(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ),
        )
    }

    private val LABEL_RES: Map<String, Int> = mapOf(
        Manifest.permission.CAMERA to R.string.perm_camera_label,
        Manifest.permission.RECORD_AUDIO to R.string.perm_record_audio_label,
        Manifest.permission.READ_PHONE_STATE to R.string.perm_phone_state_label,
        Manifest.permission.ACCESS_FINE_LOCATION to R.string.perm_fine_location_label,
        Manifest.permission.ACCESS_COARSE_LOCATION to R.string.perm_coarse_location_label,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to R.string.perm_bg_location_label,
        Manifest.permission.READ_CONTACTS to R.string.perm_read_contacts_label,
        Manifest.permission.READ_CALL_LOG to R.string.perm_read_call_log_label,
        Manifest.permission.READ_SMS to R.string.perm_read_sms_label,
        Manifest.permission.SEND_SMS to R.string.perm_send_sms_label,
        Manifest.permission.READ_CALENDAR to R.string.perm_read_calendar_label,
        Manifest.permission.WRITE_CALENDAR to R.string.perm_write_calendar_label,
        Manifest.permission.BLUETOOTH_CONNECT to R.string.perm_bluetooth_connect_label,
        Manifest.permission.POST_NOTIFICATIONS to R.string.perm_post_notifications_label,
        "android.permission.ACCESS_LOCAL_NETWORK" to R.string.perm_local_network_label,
        "com.termux.permission.RUN_COMMAND" to R.string.perm_termux_legacy_label,
        "dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API" to R.string.perm_agent_keyboard_label,
        Manifest.permission.INTERNET to R.string.perm_internet_label,
        Manifest.permission.ACCESS_NETWORK_STATE to R.string.perm_network_state_label,
        Manifest.permission.ACCESS_WIFI_STATE to R.string.perm_wifi_state_label,
        Manifest.permission.CHANGE_WIFI_MULTICAST_STATE to R.string.perm_wifi_multicast_label,
        Manifest.permission.FOREGROUND_SERVICE to R.string.perm_fg_service_label,
        Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE to R.string.perm_fg_service_special_label,
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK to R.string.perm_fg_service_media_label,
        Manifest.permission.VIBRATE to R.string.perm_vibrate_label,
        Manifest.permission.USE_BIOMETRIC to R.string.perm_biometric_label,
        Manifest.permission.RECEIVE_BOOT_COMPLETED to R.string.perm_boot_completed_label,
        Manifest.permission.WAKE_LOCK to R.string.perm_wake_lock_label,
        Manifest.permission.USE_FULL_SCREEN_INTENT to R.string.perm_full_screen_intent_label,
        Manifest.permission.QUERY_ALL_PACKAGES to R.string.perm_query_packages_label,
        Manifest.permission.SET_WALLPAPER to R.string.perm_set_wallpaper_label,
        Manifest.permission.NFC to R.string.perm_nfc_label,
        "android.permission.POST_PROMOTED_NOTIFICATIONS" to R.string.perm_promoted_notifications_label,
        Manifest.permission.WRITE_EXTERNAL_STORAGE to R.string.perm_write_external_storage_label,
    )

    private val DESC_RES: Map<String, Int> = mapOf(
        Manifest.permission.CAMERA to R.string.perm_camera_desc,
        Manifest.permission.RECORD_AUDIO to R.string.perm_record_audio_desc,
        Manifest.permission.READ_PHONE_STATE to R.string.perm_phone_state_desc,
        Manifest.permission.ACCESS_FINE_LOCATION to R.string.perm_fine_location_desc,
        Manifest.permission.ACCESS_COARSE_LOCATION to R.string.perm_coarse_location_desc,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to R.string.perm_bg_location_desc,
        Manifest.permission.READ_CONTACTS to R.string.perm_read_contacts_desc,
        Manifest.permission.READ_CALL_LOG to R.string.perm_read_call_log_desc,
        Manifest.permission.READ_SMS to R.string.perm_read_sms_desc,
        Manifest.permission.SEND_SMS to R.string.perm_send_sms_desc,
        Manifest.permission.READ_CALENDAR to R.string.perm_read_calendar_desc,
        Manifest.permission.WRITE_CALENDAR to R.string.perm_write_calendar_desc,
        Manifest.permission.BLUETOOTH_CONNECT to R.string.perm_bluetooth_connect_desc,
        Manifest.permission.POST_NOTIFICATIONS to R.string.perm_post_notifications_desc,
        "android.permission.ACCESS_LOCAL_NETWORK" to R.string.perm_local_network_desc,
        "com.termux.permission.RUN_COMMAND" to R.string.perm_termux_legacy_desc,
        "dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API" to R.string.perm_agent_keyboard_desc,
    )

    private fun labelOrHumanize(
        context: Context,
        perm: String,
        info: PermissionInfo?,
        pm: PackageManager,
    ): String {
        LABEL_RES[perm]?.let { @StringRes val resId = it; return context.getString(resId) }
        val systemLabel = info?.loadLabel(pm)?.toString()?.trim()
        if (!systemLabel.isNullOrEmpty() && !systemLabel.equals(perm, ignoreCase = true) && !systemLabel.startsWith("android.permission.")) {
            return systemLabel.replaceFirstChar { it.uppercase() }
        }
        return humanize(perm)
    }

    private fun describeRuntime(context: Context, perm: String): String {
        DESC_RES[perm]?.let { @StringRes val resId = it; return context.getString(resId) }
        return context.getString(R.string.perm_desc_runtime_generic)
    }

    private fun humanize(perm: String): String {
        val tail = perm.substringAfterLast('.')
        return tail.lowercase().split('_').joinToString(" ") {
            it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}

private fun String.toUri(): Uri = Uri.parse(this)
