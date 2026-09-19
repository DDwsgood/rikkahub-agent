package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.io.File

/**
 * OEM ROM identification + vendor settings-page intents for background-reliability setup.
 *
 * Detection reads *system properties* (not [Build.MANUFACTURER], which lies — Redmi returns
 * "redmi", Honor MagicOS returns "honor", custom ROMs rewrite it). Property table follows
 * getActivity/DeviceCompat and Blankj/RomUtils; ordering is load-bearing:
 *
 *   - HyperOS before MIUI (HyperOS keeps the ro.miui.* props)
 *   - realmeUI before ColorOS (realme keeps ro.build.version.opporom)
 *   - MagicOS before EMUI (MagicUI 6.x writes "MagicUI_x.y" into ro.build.version.emui)
 *
 * The result is cached: properties don't change at runtime and reading them falls back to
 * a `getprop` exec on API 28+ where SystemProperties reflection is restricted.
 */
object RomUtils {

    enum class Rom {
        HYPEROS, MIUI,
        REALME_UI, COLOROS,
        ORIGINOS, FUNTOUCH,
        MAGICOS, EMUI, HARMONYOS,
        FLYME, ONEUI,
        OTHER,
    }

    /**
     * How hard this ROM works to kill background work, per dontkillmyapp.com scoring and
     * the documented freeze triggers. Drives the red warning on the reliability page.
     */
    enum class Aggressiveness { NONE, MODERATE, HIGH, SEVERE }

    data class RomInfo(
        val rom: Rom,
        val displayName: String,
        val version: String,
        val aggressiveness: Aggressiveness,
    ) {
        /** True when the OEM is known to force-stop background apps on a timer. */
        val needsWarning: Boolean get() = aggressiveness >= Aggressiveness.HIGH
    }

    @Volatile
    private var cached: RomInfo? = null

    fun detect(): RomInfo = cached ?: detectInternal().also { cached = it }

    private fun detectInternal(): RomInfo {
        // HarmonyOS (EMUI-based, still runs APKs) — before EMUI.
        prop("ro.build.ohos.devicetype").let { version ->
            if (version.isNotEmpty()) return RomInfo(Rom.HARMONYOS, "HarmonyOS", version, Aggressiveness.SEVERE)
        }
        // HyperOS before MIUI — HyperOS devices still expose ro.miui.ui.version.*.
        val hyperVersion = prop("ro.mi.os.version.name").ifEmpty { prop("ro.mi.os.version.code") }
        if (hyperVersion.isNotEmpty()) return RomInfo(Rom.HYPEROS, "HyperOS", hyperVersion, Aggressiveness.SEVERE)
        prop("ro.miui.ui.version.name").let { version ->
            if (version.isNotEmpty()) return RomInfo(Rom.MIUI, "MIUI", version, Aggressiveness.SEVERE)
        }
        // realmeUI before ColorOS — realme keeps the oppo props.
        prop("ro.build.version.realmeui").let { version ->
            if (version.isNotEmpty()) return RomInfo(Rom.REALME_UI, "realme UI", version, Aggressiveness.HIGH)
        }
        val colorVersion = prop("ro.build.version.oplusrom").ifEmpty { prop("ro.build.version.opporom") }
        if (colorVersion.isNotEmpty()) return RomInfo(Rom.COLOROS, "ColorOS", colorVersion, Aggressiveness.SEVERE)
        // vivo: ro.vivo.os.version covers OriginOS + Funtouch; the display id distinguishes.
        val vivoVersion = prop("ro.vivo.os.version")
        val vivoDisplay = prop("ro.vivo.os.build.display.id").lowercase()
        if (vivoVersion.isNotEmpty() || "origin" in vivoDisplay || "funtouch" in vivoDisplay) {
            return if ("origin" in vivoDisplay) {
                RomInfo(Rom.ORIGINOS, "OriginOS", vivoVersion, Aggressiveness.HIGH)
            } else {
                RomInfo(Rom.FUNTOUCH, "Funtouch OS", vivoVersion, Aggressiveness.HIGH)
            }
        }
        // MagicOS before EMUI — MagicUI 6.x reports itself via ro.build.version.emui too.
        val magicVersion = prop("ro.magic.os.version")
            .ifEmpty { prop("msc.config.magic.version") }
            .ifEmpty { prop("ro.build.version.magic") }
        if (magicVersion.isNotEmpty()) return RomInfo(Rom.MAGICOS, "MagicOS", magicVersion, Aggressiveness.SEVERE)
        prop("ro.build.version.emui").let { version ->
            if (version.isNotEmpty() && !version.startsWith("MagicUI", ignoreCase = true)) {
                return RomInfo(Rom.EMUI, "EMUI", version, Aggressiveness.SEVERE)
            }
        }
        val flymeVersion = prop("ro.flyme.version.id")
        if (flymeVersion.isNotEmpty() || prop("ro.flyme.published") == "true" ||
            "flyme" in prop("ro.build.display.id").lowercase()
        ) {
            return RomInfo(Rom.FLYME, "Flyme", flymeVersion, Aggressiveness.HIGH)
        }
        val oneuiVersion = prop("ro.build.version.oneui")
        if (oneuiVersion.isNotEmpty() || prop("ro.build.PDA").isNotEmpty() ||
            Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        ) {
            return RomInfo(Rom.ONEUI, "One UI", oneuiVersion, Aggressiveness.MODERATE)
        }
        return RomInfo(Rom.OTHER, "Android ${Build.VERSION.RELEASE}", "", Aggressiveness.NONE)
    }

    /**
     * Read a system property. Order: SystemProperties reflection (fast, blocked on some
     * API 28+ builds), then /system/build.prop parse, then a `getprop` subprocess — the
     * same fallback chain as Blankj/RomUtils.
     */
    private fun prop(key: String): String {
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val value = clazz.getMethod("get", String::class.java).invoke(null, key) as? String
            if (!value.isNullOrBlank()) return value.trim()
        }
        runCatching {
            File("/system/build.prop").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        runCatching {
            val value = Runtime.getRuntime().exec(arrayOf("getprop", key))
                .inputStream.bufferedReader().readText().trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    /**
     * Candidate intents for the vendor's autostart / app-launch management page, ordered
     * from most specific to most general (AutoStarter + XXPermissions lists). Presence is
     * NOT guaranteed — callers must resolve-check each one before launching.
     */
    fun autostartIntents(ctx: Context): List<Intent> = when (detect().rom) {
        Rom.HYPEROS, Rom.MIUI -> listOf(
            component(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // Direct jump to the per-app MIUI permission page (background popup etc.).
            Intent("miui.intent.action.APP_PERMEDITOR").putExtra("extra_pkgname", ctx.packageName),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        Rom.EMUI, Rom.MAGICOS, Rom.HARMONYOS -> listOf(
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        )
        Rom.REALME_UI, Rom.COLOROS -> listOf(
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            component(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        )
        Rom.ORIGINOS, Rom.FUNTOUCH -> listOf(
            component(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            component(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        )
        Rom.FLYME -> listOf(
            Intent("com.meizu.safe.security.SHOW_APPSEC").putExtra("packageName", ctx.packageName),
        )
        Rom.ONEUI -> listOf(
            component(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            component(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )
        Rom.OTHER -> emptyList()
    }

    /**
     * Walk the vendor candidates in order; every intent is resolve-checked and wrapped in
     * try-catch — some ROMs strip even [Settings.ACTION_APPLICATION_DETAILS_SETTINGS], and
     * SecurityManager pages can throw SecurityException. Falls back to app details, then
     * the Settings home page. Returns false only if literally nothing could be opened.
     */
    fun openAutostartSettings(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val candidates = autostartIntents(ctx) + listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (candidate in candidates) {
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
            }.getOrDefault(false)
            if (!resolvable) continue
            if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
