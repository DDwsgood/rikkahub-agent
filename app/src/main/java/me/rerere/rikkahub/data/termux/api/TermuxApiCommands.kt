package me.rerere.rikkahub.data.termux.api

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.NotificationUtil
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
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
// termux-clipboard-set / termux-clipboard-get
// ---------------------------------------------------------------------------

internal fun parseClipboardSetArgs(args: List<String>): ParsedArgs<String> {
    for ((i, opt) in args.withIndex()) {
        when {
            opt == "-h" -> return ParsedArgs.Help
            opt == "--" -> return ParsedArgs.Ok(args.subList(i + 1, args.size).joinToString(" "))
            opt.startsWith("-") && opt.length > 1 -> return ParsedArgs.Invalid("illegal option $opt")
            // getopts 在首个非选项参数处停止：其后一律视为剪贴板文本。
            else -> return ParsedArgs.Ok(args.subList(i, args.size).joinToString(" "))
        }
    }
    return ParsedArgs.Ok("")
}

internal fun parseClipboardGetArgs(args: List<String>): ParsedArgs<Unit> {
    if (args.isEmpty()) return ParsedArgs.Ok(Unit)
    val first = args[0]
    return when {
        first == "-h" -> ParsedArgs.Help
        first == "--" ->
            if (args.size == 1) ParsedArgs.Ok(Unit) else ParsedArgs.Invalid("too many arguments")

        first.startsWith("-") && first.length > 1 -> ParsedArgs.Invalid("illegal option $first")
        else -> ParsedArgs.Invalid("too many arguments")
    }
}

private fun clipboardSetHandler(context: Context, args: List<String>): TermuxApiResult =
    parseClipboardSetArgs(args).intoResult("termux-clipboard-set", CLIPBOARD_SET_USAGE) { text ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-clipboard-set: clipboard service unavailable\n",
            )
        runCatching {
            // Android 13+ 系统会自动弹出"已复制"预览，与官方行为一致。
            clipboard.setPrimaryClip(ClipData.newPlainText("", text))
        }.onFailure {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-clipboard-set: failed to set clipboard: ${it.message}\n",
            )
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

private fun clipboardGetHandler(context: Context, args: List<String>): TermuxApiResult =
    parseClipboardGetArgs(args).intoResult("termux-clipboard-get", CLIPBOARD_GET_USAGE) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-clipboard-get: clipboard service unavailable\n",
            )
        // Android 10+ 限制后台读取剪贴板：app 不在前台时拿到空 clip，
        // 与官方一致输出空内容、不报错。读取异常同样按空处理。
        val text = runCatching {
            val clip = clipboard.primaryClip ?: return@runCatching ""
            buildString {
                for (i in 0 until clip.itemCount) {
                    val itemText = clip.getItemAt(i).coerceToText(context)
                    if (!itemText.isNullOrEmpty()) append(itemText)
                }
            }
        }.getOrDefault("")
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK, stdout = text)
    }

// ---------------------------------------------------------------------------
// termux-tts-speak
// ---------------------------------------------------------------------------

internal data class TtsOptions(
    val engine: String?,
    val language: String?,
    val region: String?,
    val variant: String?,
    val pitch: Float,
    val rate: Float,
    val stream: Int,
    val text: String,
)

private fun ttsStreamConstant(name: String): Int = when (name) {
    "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
    "ALARM" -> AudioManager.STREAM_ALARM
    "RING" -> AudioManager.STREAM_RING
    "SYSTEM" -> AudioManager.STREAM_SYSTEM
    "VOICE_CALL" -> AudioManager.STREAM_VOICE_CALL
    // 官方对未知 stream 不报错，静默回退 MUSIC（实现默认流）。
    else -> AudioManager.STREAM_MUSIC
}

internal fun parseTtsArgs(args: List<String>): ParsedArgs<TtsOptions> {
    var engine: String? = null
    var language: String? = null
    var region: String? = null
    var variant: String? = null
    var pitch = 1.0f
    var rate = 1.0f
    var stream = AudioManager.STREAM_MUSIC

    fun options(text: String) =
        TtsOptions(engine, language, region, variant, pitch, rate, stream, text)

    var i = 0
    while (i < args.size) {
        when (val opt = args[i]) {
            "-h" -> return ParsedArgs.Help
            "-e", "-l", "-n", "-v", "-s" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    when (opt) {
                        "-e" -> engine = r.value.first
                        "-l" -> language = r.value.first
                        "-n" -> region = r.value.first
                        "-v" -> variant = r.value.first
                        "-s" -> stream = ttsStreamConstant(r.value.first)
                    }
                    i = r.value.second
                }

                else -> return r.mapError()
            }

            "-p", "-r" -> when (val r = optionValue(args, i, opt)) {
                is ParsedArgs.Ok -> {
                    val name = if (opt == "-p") "pitch" else "rate"
                    val v = r.value.first.toFloatOrNull()
                        ?: return ParsedArgs.Invalid("invalid $name '${r.value.first}'")
                    if (opt == "-p") {
                        if (v !in 0.1f..2.0f) {
                            return ParsedArgs.Invalid("pitch '$v' out of range (0.1-2.0)")
                        }
                        pitch = v
                    } else {
                        if (v !in 0.1f..4.0f) {
                            return ParsedArgs.Invalid("rate '$v' out of range (0.1-4.0)")
                        }
                        rate = v
                    }
                    i = r.value.second
                }

                else -> return r.mapError()
            }

            "--" -> return ParsedArgs.Ok(options(args.subList(i + 1, args.size).joinToString(" ")))
            else -> {
                // getopts 在首个非选项参数处停止：其后一律视为待朗读文本。
                if (opt.startsWith("-") && opt.length > 1) {
                    return ParsedArgs.Invalid("illegal option $opt")
                }
                return ParsedArgs.Ok(options(args.subList(i, args.size).joinToString(" ")))
            }
        }
    }
    return ParsedArgs.Ok(options(""))
}

private const val TTS_INIT_TIMEOUT_MS = 10_000L
private const val TTS_SPEAK_TIMEOUT_MS = 60_000L

private fun ttsSpeakHandler(context: Context, args: List<String>): TermuxApiResult =
    parseTtsArgs(args).intoResult("termux-tts-speak", TTS_USAGE) { opts ->
        // handler 是同步签名；TTS 初始化与 utterance 完成都是异步回调，
        // 在 Dispatchers.IO 上 runBlocking 挂起等待（不占用主线程）。
        runBlocking { speakText(context, opts) }
    }

private suspend fun speakText(context: Context, opts: TtsOptions): TermuxApiResult {
    // TextToSpeech 需要在有 Looper 的线程上构造（回调经 main executor 分发），
    // 放在主线程创建，挂起等待不阻塞线程。
    val tts = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                val holder = arrayOfNulls<TextToSpeech>(1)
                // invokeOnCancellation 必须先于任何 resume 注册，故用 holder 延迟取值。
                cont.invokeOnCancellation { holder[0]?.let { runCatching { it.shutdown() } } }
                try {
                    holder[0] = TextToSpeech(context, { status ->
                        cont.resume(if (status == TextToSpeech.SUCCESS) holder[0] else null)
                    }, opts.engine)
                } catch (_: Throwable) {
                    cont.resume(null)
                }
            }
        }
    } ?: return TermuxApiResult(
        exitCode = TermuxApiServer.EXIT_ERROR,
        stderr = "termux-tts-speak: TTS engine unavailable\n",
    )
    try {
        if (opts.language != null) {
            // 官方仅在引擎不支持该语言时记日志，不报错。
            tts.setLanguage(
                when {
                    opts.variant != null -> Locale(opts.language, opts.region ?: "", opts.variant)
                    opts.region != null -> Locale(opts.language, opts.region)
                    else -> Locale(opts.language)
                }
            )
        }
        tts.setPitch(opts.pitch)
        tts.setSpeechRate(opts.rate)

        // 官方逐行 QUEUE_ADD，等待全部非空行的 utterance 完成（onDone/onError 都算完成）。
        val lines = opts.text.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) return TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)

        // expected = -1 表示 speak() 尚未全部提交，避免首条 utterance 提前回调
        // 导致 finished >= expected 误判而 shutdown 未播队列。
        val expected = AtomicInteger(-1)
        val finished = AtomicInteger(0)
        val resumed = AtomicBoolean(false)
        // utterance 回调依赖 TTS 引擎；引擎挂起时不能永久占住 API server 的连接槽
        // (上限 8)，超时返回并让 invokeOnCancellation 的 tts.stop() 兜底清队列。
        val completed = withTimeoutOrNull(TTS_SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                fun tryFinish() {
                    val exp = expected.get()
                    if (exp >= 0 && finished.get() >= exp && resumed.compareAndSet(false, true)) {
                        cont.resume(Unit)
                    }
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        finished.incrementAndGet()
                        tryFinish()
                    }

                    @Deprecated("Deprecated in Android API")
                    override fun onError(utteranceId: String?) {
                        finished.incrementAndGet()
                        tryFinish()
                    }
                })
                cont.invokeOnCancellation { runCatching { tts.stop() } }
                var submitted = 0
                lines.forEachIndexed { index, line ->
                    val utteranceId = "rikkahub-tts-$index"
                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, opts.stream)
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    if (tts.speak(line, TextToSpeech.QUEUE_ADD, params, utteranceId) == TextToSpeech.SUCCESS) {
                        submitted++
                    }
                }
                expected.set(submitted)
                // submitted == 0（全部 speak() 同步失败）或回调已全部到达时立即返回。
                tryFinish()
            }
        }
        if (completed == null) {
            return TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-tts-speak: timed out waiting for TTS engine\n",
            )
        }
        return TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    } finally {
        runCatching { tts.shutdown() }
    }
}

// ---------------------------------------------------------------------------
// termux-notification-remove
// ---------------------------------------------------------------------------

internal fun parseNotificationRemoveArgs(args: List<String>): ParsedArgs<String> {
    if (args.isEmpty()) return ParsedArgs.Invalid("no notification id specified")
    val first = args[0]
    return when {
        first == "-h" -> ParsedArgs.Help
        first == "--" ->
            if (args.size == 2) ParsedArgs.Ok(args[1])
            else ParsedArgs.Invalid("no notification id specified")

        first.startsWith("-") && first.length > 1 -> ParsedArgs.Invalid("illegal option $first")
        args.size == 1 -> ParsedArgs.Ok(first)
        else -> ParsedArgs.Invalid("no notification id specified")
    }
}

private fun notificationRemoveHandler(context: Context, args: List<String>): TermuxApiResult =
    parseNotificationRemoveArgs(args).intoResult("termux-notification-remove", NOTIFICATION_REMOVE_USAGE) { id ->
        // termux-notification 以 tag=id、id=0 发布，按同键取消。
        runCatching {
            NotificationManagerCompat.from(context).cancel(id, 0)
        }.onFailure {
            return@intoResult TermuxApiResult(
                exitCode = TermuxApiServer.EXIT_ERROR,
                stderr = "termux-notification-remove: failed to remove notification: ${it.message}\n",
            )
        }
        TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
    }

// ---------------------------------------------------------------------------
// termux-volume
// ---------------------------------------------------------------------------

internal sealed interface VolumeRequest {
    data object Query : VolumeRequest
    data class Set(val stream: String, val volume: Int) : VolumeRequest
}

internal fun parseVolumeArgs(args: List<String>): ParsedArgs<VolumeRequest> = when {
    args.isEmpty() -> ParsedArgs.Ok(VolumeRequest.Query)
    args.size != 2 -> ParsedArgs.Invalid("Invalid argument count")
    args[1].isEmpty() || !args[1].all { it.isDigit() } ->
        ParsedArgs.Invalid("ERROR: Volume must be a number")

    else -> ParsedArgs.Ok(VolumeRequest.Set(args[0], args[1].toInt()))
}

// 官方 SparseArray 按 key 升序迭代，输出顺序为 call/system/ring/music/alarm/notification。
private val VOLUME_STREAMS = listOf(
    AudioManager.STREAM_VOICE_CALL to "call",
    AudioManager.STREAM_SYSTEM to "system",
    AudioManager.STREAM_RING to "ring",
    AudioManager.STREAM_MUSIC to "music",
    AudioManager.STREAM_ALARM to "alarm",
    AudioManager.STREAM_NOTIFICATION to "notification",
)

// 官方 ResultJsonWriter 使用两空格缩进。
private val VOLUME_JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun volumeHandler(context: Context, args: List<String>): TermuxApiResult =
    when (val parsed = parseVolumeArgs(args)) {
        // 官方脚本把错误与 usage 输出到 stdout 并以 0 退出，保持一致。
        is ParsedArgs.Invalid -> TermuxApiResult(
            exitCode = TermuxApiServer.EXIT_OK,
            stdout = "${parsed.message}\n$VOLUME_USAGE",
        )

        ParsedArgs.Help -> TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK, stdout = VOLUME_USAGE)
        is ParsedArgs.Ok -> when (val request = parsed.value) {
            VolumeRequest.Query -> {
                val audioManager = context.getSystemService(AudioManager::class.java)
                    ?: return TermuxApiResult(
                        exitCode = TermuxApiServer.EXIT_ERROR,
                        stderr = "termux-volume: audio service unavailable\n",
                    )
                val json = buildJsonArray {
                    for ((stream, name) in VOLUME_STREAMS) {
                        add(
                            buildJsonObject {
                                put("stream", name)
                                put("volume", audioManager.getStreamVolume(stream))
                                put("max_volume", audioManager.getStreamMaxVolume(stream))
                            }
                        )
                    }
                }
                TermuxApiResult(
                    exitCode = TermuxApiServer.EXIT_OK,
                    stdout = VOLUME_JSON.encodeToString(JsonElement.serializer(), json) + "\n",
                )
            }

            is VolumeRequest.Set -> {
                val stream = VOLUME_STREAMS.firstOrNull { it.second == request.stream }?.first
                    // 官方服务端把未知 stream 错误打到 stdout 且 exit 0。
                    ?: return TermuxApiResult(
                        exitCode = TermuxApiServer.EXIT_OK,
                        stdout = "ERROR: Unknown stream: ${request.stream}\n",
                    )
                val audioManager = context.getSystemService(AudioManager::class.java)
                    ?: return TermuxApiResult(
                        exitCode = TermuxApiServer.EXIT_ERROR,
                        stderr = "termux-volume: audio service unavailable\n",
                    )
                val volume = request.volume.coerceIn(0, audioManager.getStreamMaxVolume(stream))
                runCatching { audioManager.setStreamVolume(stream, volume, 0) }.onFailure {
                    return TermuxApiResult(
                        exitCode = TermuxApiServer.EXIT_ERROR,
                        stderr = "termux-volume: failed to set volume: ${it.message}\n",
                    )
                }
                TermuxApiResult(exitCode = TermuxApiServer.EXIT_OK)
            }
        }
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
    "termux-clipboard-set" to { args: List<String> -> clipboardSetHandler(context, args) },
    "termux-clipboard-get" to { args: List<String> -> clipboardGetHandler(context, args) },
    "termux-tts-speak" to { args: List<String> -> ttsSpeakHandler(context, args) },
    "termux-notification-remove" to { args: List<String> -> notificationRemoveHandler(context, args) },
    "termux-volume" to { args: List<String> -> volumeHandler(context, args) },
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

private const val CLIPBOARD_SET_USAGE = """Usage: termux-clipboard-set [text]
Set the system clipboard text. The text to set is either supplied as arguments or read from stdin if no arguments are given.
"""

private const val CLIPBOARD_GET_USAGE = """Usage: termux-clipboard-get
Get the system clipboard text.
"""

private const val TTS_USAGE = """Usage: termux-tts-speak [-e engine] [-l language] [-n region] [-v variant] [-p pitch] [-r rate] [-s stream] [text-to-speak]
Speak text with a system text-to-speech (TTS) engine. The text to speak is either supplied as arguments or read from stdin if no arguments are given.
  -e engine    TTS engine to use (see termux-tts-engines)
  -l language  language to speak in (may be unsupported by the engine)
  -n region    region of language to speak in
  -v variant   variant of the language to speak in
  -p pitch     pitch to use in speech. 1.0 is the normal pitch,
                 lower values lower the tone of the synthesized voice,
                 greater values increase it.
  -r rate      speech rate to use. 1.0 is the normal speech rate,
                 lower values slow down the speech
                 (0.5 is half the normal speech rate)
                 while greater values accelerates it
                 (2.0 is twice the normal speech rate).
  -s stream    audio stream to use (default:NOTIFICATION), one of:
                 ALARM, MUSIC, NOTIFICATION, RING, SYSTEM, VOICE_CALL
"""

private const val NOTIFICATION_REMOVE_USAGE = """Usage: termux-notification-remove notification-id
Remove a notification previously shown with termux-notification --id.
"""

private const val VOLUME_USAGE = """Usage: termux-volume stream volume
Change volume of audio stream
Valid audio streams are: alarm, music, notification, ring, system, call
Call w/o arguments to show information about each audio stream
"""
