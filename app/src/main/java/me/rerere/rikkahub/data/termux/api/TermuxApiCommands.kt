package me.rerere.rikkahub.data.termux.api

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.NotificationUtil
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

/**
 * termux-* shim 命令的 app 侧实现，对齐官方 Termux:API 的参数与输出格式。
 *
 * 每个 handler 接收解码后的参数列表，返回 [TermuxApiResult]。
 * 参数解析拆成纯函数（不依赖 Android）以便单测。
 */

// ---------------------------------------------------------------------------
// 参数解析结果
// ---------------------------------------------------------------------------

internal sealed interface ParsedArgs<out T> {
    data class Ok<T>(val value: T) : ParsedArgs<T>
    data class Invalid(val message: String) : ParsedArgs<Nothing>
    data object Help : ParsedArgs<Nothing>
}

private fun <T> ParsedArgs<T>.intoResult(command: String, usage: String, run: (T) -> TermuxApiResult): TermuxApiResult =
    when (this) {
        is ParsedArgs.Ok -> run(value)
        is ParsedArgs.Invalid -> TermuxApiResult(
            exitCode = TermuxApiServer.EXIT_ERROR,
            stderr = "$command: $message\n",
        )

        ParsedArgs.Help -> TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK, stdout = usage)
    }

/** 消费一个需要值的选项；缺值时报错。 */
private fun optionValue(args: List<String>, index: Int, option: String): ParsedArgs<Pair<String, Int>> {
    val value = args.getOrNull(index + 1)
        ?: return ParsedArgs.Invalid("option '$option' requires an argument")
    return ParsedArgs.Ok(value to index + 2)
}

// ---------------------------------------------------------------------------
// termux-notification
// ---------------------------------------------------------------------------

internal data class NotificationOptions(
    val title: String?,
    val content: String?,
    val id: String?,
    val priority: String,
    val ongoing: Boolean,
    val alertOnce: Boolean,
    val sound: Boolean,
    val vibratePattern: LongArray?,
    val group: String?,
    val channel: String?,
)

internal fun parseNotificationArgs(args: List<String>): ParsedArgs<NotificationOptions> {
    var title: String? = null
    var content: String? = null
    var id: String? = null
    var priority = "default"
    var ongoing = false
    var alertOnce = false
    var sound = false
    var vibratePattern: LongArray? = null
    var group: String? = null
    var channel: String? = null

    // 官方支持但 Phase 1 忽略的选项：带值的占位消费，避免误报 "unknown option"。
    val ignoredValueOptions = setOf(
        "--action", "--on-delete", "--icon", "--image-path", "--type",
        "--button1", "--button1-action", "--button2", "--button2-action",
        "--button3", "--button3-action", "--led-color", "--led-on", "--led-off",
        "--media-next", "--media-pause", "--media-play", "--media-previous",
    )

    var i = 0
    while (i < args.size) {
        when (val opt = args[i]) {
            "-h", "--help" -> return ParsedArgs.Help
            "--help-actions" -> return ParsedArgs.Help
            "-t", "--title" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> { title = r.value.first; i = r.value.second }
                else -> return r.mapError()
            }

            "-c", "--content" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> { content = r.value.first; i = r.value.second }
                else -> return r.mapError()
            }

            "-i", "--id" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> { id = r.value.first; i = r.value.second }
                else -> return r.mapError()
            }

            "--priority" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    val v = r.value.first
                    if (v !in setOf("min", "low", "default", "high", "max")) {
                        return ParsedArgs.Invalid("invalid priority '$v' (min/low/default/high/max)")
                    }
                    priority = v; i = r.value.second
                }

                else -> return r.mapError()
            }

            "--channel" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> { channel = r.value.first; i = r.value.second }
                else -> return r.mapError()
            }

            "--group" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> { group = r.value.first; i = r.value.second }
                else -> return r.mapError()
            }

            "--vibrate" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    vibratePattern = r.value.first.split(',')
                        .map { it.trim().toLongOrNull() ?: return ParsedArgs.Invalid("invalid --vibrate pattern") }
                        .toLongArray()
                    i = r.value.second
                }

                else -> return r.mapError()
            }

            "--ongoing" -> { ongoing = true; i++ }
            "--alert-once" -> { alertOnce = true; i++ }
            "--sound" -> { sound = true; i++ }
            in ignoredValueOptions -> {
                if (args.getOrNull(i + 1) == null) {
                    return ParsedArgs.Invalid("option '$opt' requires an argument")
                }
                i += 2
            }

            else -> return ParsedArgs.Invalid("illegal option $opt")
        }
    }

    if (ongoing && id == null) {
        return ParsedArgs.Invalid("ongoing notifications without an ID are not removable; set --id")
    }
    return ParsedArgs.Ok(
        NotificationOptions(
            title = title,
            content = content,
            id = id,
            priority = priority,
            ongoing = ongoing,
            alertOnce = alertOnce,
            sound = sound,
            vibratePattern = vibratePattern,
            group = group,
            channel = channel,
        )
    )
}

private fun <T> ParsedArgs<T>.mapError(): ParsedArgs<Nothing> =
    this as? ParsedArgs.Invalid ?: ParsedArgs.Invalid("invalid arguments")

private const val NOTIFICATION_CHANNEL_ID = "rikkahub_termux"
private const val NOTIFICATION_CHANNEL_NAME = "Termux API"

private fun notificationImportance(priority: String): Int = when (priority) {
    "high", "max" -> NotificationManager.IMPORTANCE_HIGH
    "low" -> NotificationManager.IMPORTANCE_LOW
    "min" -> NotificationManager.IMPORTANCE_MIN
    else -> NotificationManager.IMPORTANCE_DEFAULT
}

private fun notificationCompatPriority(priority: String): Int = when (priority) {
    "high" -> NotificationCompat.PRIORITY_HIGH
    "max" -> NotificationCompat.PRIORITY_MAX
    "low" -> NotificationCompat.PRIORITY_LOW
    "min" -> NotificationCompat.PRIORITY_MIN
    else -> NotificationCompat.PRIORITY_DEFAULT
}

private fun notificationHandler(context: Context, args: List<String>): TermuxApiResult =
    parseNotificationArgs(args).intoResult("termux-notification", NOTIFICATION_USAGE) { opts ->
        if (!NotificationUtil.hasNotificationPermission(context)) {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-notification: notification permission not granted\n",
            )
        }
        // 与官方一致：channel 在首次使用时创建，importance 取当时的 --priority，
        // 之后同名 channel 的 importance 不再变化（Android 限制）。
        val channelId = opts.channel ?: NOTIFICATION_CHANNEL_ID
        val channel = NotificationChannelCompat.Builder(channelId, notificationImportance(opts.priority))
            .setName(if (channelId == NOTIFICATION_CHANNEL_ID) NOTIFICATION_CHANNEL_NAME else channelId)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(opts.title)
            .setPriority(notificationCompatPriority(opts.priority))
            .setOngoing(opts.ongoing)
            .setOnlyAlertOnce(opts.alertOnce)
            .setAutoCancel(!opts.ongoing)
        if (opts.content != null) {
            builder.setContentText(opts.content)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(opts.content))
        }
        if (opts.sound) builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        opts.vibratePattern?.let { builder.setVibrate(it) }
        opts.group?.let { builder.setGroup(it) }
        val launchIntent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        builder.setContentIntent(
            PendingIntent.getActivity(
                context,
                (opts.id ?: channelId).hashCode(),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        val tag = opts.id ?: UUID.randomUUID().toString()
        runCatching {
            NotificationManagerCompat.from(context).notify(tag, 0, builder.build())
        }.onFailure {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-notification: failed to post notification: ${it.message}\n",
            )
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

// ---------------------------------------------------------------------------
// termux-toast
// ---------------------------------------------------------------------------

internal data class ToastOptions(
    val short: Boolean,
    val gravity: String?,
    val text: String,
)

internal fun parseToastArgs(args: List<String>): ParsedArgs<ToastOptions> {
    var short = false
    var gravity: String? = null
    val textParts = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val opt = args[i]) {
            "-h" -> return ParsedArgs.Help
            "-s" -> { short = true; i++ }
            // 官方支持 -b/-c 颜色，但 Android 12+ 不再允许自定义 toast 视图；
            // 解析并消费参数以保持 CLI 兼容，实际不生效。
            "-c", "-b" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> i = r.value.second
                else -> return r.mapError()
            }

            "-g" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    val v = r.value.first
                    if (v !in setOf("top", "middle", "bottom")) {
                        return ParsedArgs.Invalid("invalid gravity '$v' (top/middle/bottom)")
                    }
                    gravity = v; i = r.value.second
                }

                else -> return r.mapError()
            }

            "--" -> {
                textParts += args.subList(i + 1, args.size)
                i = args.size
            }

            else -> {
                if (opt.startsWith("-") && opt.length > 1) {
                    return ParsedArgs.Invalid("illegal option $opt")
                }
                textParts += opt
                i++
            }
        }
    }
    return ParsedArgs.Ok(ToastOptions(short = short, gravity = gravity, text = textParts.joinToString(" ")))
}

private fun toastHandler(context: Context, args: List<String>): TermuxApiResult =
    parseToastArgs(args).intoResult("termux-toast", TOAST_USAGE) { opts ->
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val toast = Toast.makeText(
                    context,
                    opts.text,
                    if (opts.short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                )
                when (opts.gravity) {
                    "top" -> @Suppress("DEPRECATION") toast.setGravity(android.view.Gravity.TOP, 0, 0)
                    "middle" -> @Suppress("DEPRECATION") toast.setGravity(android.view.Gravity.CENTER, 0, 0)
                    "bottom" -> @Suppress("DEPRECATION") toast.setGravity(android.view.Gravity.BOTTOM, 0, 0)
                }
                toast.show()
            }
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

// ---------------------------------------------------------------------------
// termux-vibrate
// ---------------------------------------------------------------------------

internal data class VibrateOptions(
    val durationMs: Int,
    val force: Boolean,
)

internal fun parseVibrateArgs(args: List<String>): ParsedArgs<VibrateOptions> {
    var durationMs = 1000
    var force = false
    var i = 0
    while (i < args.size) {
        when (val opt = args[i]) {
            "-h" -> return ParsedArgs.Help
            "-f" -> { force = true; i++ }
            "-d" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    durationMs = r.value.first.toIntOrNull()
                        ?: return ParsedArgs.Invalid("invalid duration '${r.value.first}'")
                    i = r.value.second
                }

                else -> return r.mapError()
            }

            else -> return ParsedArgs.Invalid("illegal option $opt")
        }
    }
    return ParsedArgs.Ok(VibrateOptions(durationMs = durationMs, force = force))
}

private fun vibrateHandler(context: Context, args: List<String>): TermuxApiResult =
    parseVibrateArgs(args).intoResult("termux-vibrate", VIBRATE_USAGE) { opts ->
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-vibrate: no vibrator available\n",
            )
        }
        // 与官方一致：静音模式且未 -f 时静默跳过。
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager != null &&
            audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT &&
            !opts.force
        ) {
            return@intoResult TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
        }
        val duration = opts.durationMs.coerceAtLeast(1).toLong()
        val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        if (opts.force) {
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } else {
            vibrator.vibrate(effect)
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

// ---------------------------------------------------------------------------
// termux-torch
// ---------------------------------------------------------------------------

internal fun parseTorchArgs(args: List<String>): ParsedArgs<Boolean> = when {
    args.size == 1 && args[0] == "on" -> ParsedArgs.Ok(true)
    args.size == 1 && args[0] == "off" -> ParsedArgs.Ok(false)
    args.size == 1 && (args[0] == "-h" || args[0] == "--help") -> ParsedArgs.Help
    args.size != 1 -> ParsedArgs.Invalid("illegal param count")
    else -> ParsedArgs.Invalid("illegal parameter: ${args[0]}")
}

private fun torchHandler(context: Context, args: List<String>): TermuxApiResult =
    parseTorchArgs(args).intoResult("termux-torch", TORCH_USAGE) { enabled ->
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-torch: camera service unavailable\n",
            )
        val flashId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
            ?: return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-torch: no flash unit available\n",
            )
        runCatching { cameraManager.setTorchMode(flashId, enabled) }.onFailure {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-torch: error toggling torch: ${it.message}\n",
            )
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

// ---------------------------------------------------------------------------
// termux-battery-status
// ---------------------------------------------------------------------------

internal fun batteryHealthString(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
    BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
    BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
    BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
    else -> health.toString()
}

internal fun batteryPluggedString(plugged: Int): String = when (plugged) {
    0 -> "UNPLUGGED"
    BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
    BatteryManager.BATTERY_PLUGGED_DOCK -> "PLUGGED_DOCK"
    BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
    else -> "PLUGGED_$plugged"
}

internal fun batteryStatusString(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
    BatteryManager.BATTERY_STATUS_FULL -> "FULL"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
    else -> "UNKNOWN"
}

private fun batteryStatusHandler(context: Context, args: List<String>): TermuxApiResult {
    if (args.any { it == "-h" || it == "--help" }) {
        return TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK, stdout = BATTERY_USAGE)
    }
    if (args.isNotEmpty()) {
        return TermuxApiResult(
            exitCode = TermuxApiServer.EXIT_ERROR,
            stderr = "termux-battery-status: too many arguments\n",
        )
    }
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return TermuxApiResult(
            exitCode = TermuxApiServer.EXIT_ERROR,
            stderr = "termux-battery-status: battery status unavailable\n",
        )
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    fun intProperty(prop: Int): Int? =
        batteryManager?.getIntProperty(prop)?.takeIf { it != Int.MIN_VALUE }

    var voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    if (voltage in 0..99) voltage *= 1000 // 部分设备返回伏特，官方同样修正为 mV

    // 部分设备返回毫安而非微安，官方同样做放大修正。
    var currentNow = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    if (currentNow != null && abs(currentNow) < 1000) currentNow *= 1000

    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val json = buildJsonObject {
        put("present", intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
        intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.let { put("technology", it) }
        put("health", batteryHealthString(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
        put("plugged", batteryPluggedString(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
        put("status", batteryStatusString(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
        if (tempTenths != Int.MIN_VALUE) {
            put("temperature", round(tempTenths.toDouble() / 10.0 * 10.0) / 10.0)
        }
        put("voltage", voltage)
        currentNow?.let { put("current", it) }
        intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)?.let { put("current_average", it) }
        intProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { put("percentage", it) }
        put("level", intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
        put("scale", intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let { put("charge_counter", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycle = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cycle != -1) put("cycle", cycle)
        }
    }
    return TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK, stdout = json.toString() + "\n")
}

// ---------------------------------------------------------------------------
// 命令表与 usage 文本
// ---------------------------------------------------------------------------

fun buildTermuxApiHandlers(context: Context): Map<String, TermuxApiHandler> = mapOf(
    "termux-notification" to { args: List<String> -> notificationHandler(context, args) },
    "termux-toast" to { args: List<String> -> toastHandler(context, args) },
    "termux-vibrate" to { args: List<String> -> vibrateHandler(context, args) },
    "termux-torch" to { args: List<String> -> torchHandler(context, args) },
    "termux-battery-status" to { args: List<String> -> batteryStatusHandler(context, args) },
)

private const val NOTIFICATION_USAGE = """Usage: termux-notification [options]
Display a system notification. Content text is specified using -c/--content or read from stdin.
  --alert-once             do not alert when the notification is edited
  -c/--content content     content to show in the notification
  --channel channel-id     notification channel id
  --group group            notification group
  -h/--help                show this help
  -i/--id id               notification id (will overwrite any previous notification
                           with the same id)
  --ongoing                pin the notification (requires --id)
  --priority prio          notification priority (high/low/max/min/default)
  --sound                  play a sound with the notification
  -t/--title title         notification title to show
  --vibrate pattern        vibrate pattern, comma separated as in 500,1000,200
Action/button/media options are accepted for compatibility but not yet supported.
"""

private const val TOAST_USAGE = """Usage: termux-toast [-b bgcolor] [-c color] [-g gravity] [-s] [text]
Show text in a Toast (a transient popup).
 -h  show this help
 -b  set background color (unsupported on Android 12+, ignored)
 -c  set text color (unsupported on Android 12+, ignored)
 -g  set position of toast: [top, middle, or bottom] (default: middle)
 -s  only show the toast for a short while
"""

private const val VIBRATE_USAGE = """Usage: termux-vibrate [-d duration] [-f]
Vibrate the device.
  -d duration  the duration to vibrate in ms (default:1000)
  -f           force vibration even in silent mode
"""

private const val TORCH_USAGE = """Usage: termux-torch [on | off]
Toggle LED Torch on device
"""

private const val BATTERY_USAGE = """Usage: termux-battery-status
Get the status of the device battery.
"""
