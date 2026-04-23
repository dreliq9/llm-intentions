package com.llmintentions.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.app.ActivityManager
import android.os.Environment
import com.androidmcp.core.protocol.LatencyClass
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.core.registry.textTool
import com.androidmcp.core.registry.toolMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.Locale

object DeviceToolRegistrar {

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun register(registry: ToolRegistry, ctx: Context) {
        // Init TTS
        if (tts == null) {
            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.language = Locale.US
            }
        }

        registry.textTool("battery_status", "Get battery level, charging state, and temperature",
            jsonSchema { }
        ) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            buildJsonObject {
                put("level_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                put("charging", bm.isCharging)
                put("current_now_ua", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            }.toString()
        }

        registry.textTool("device_info", "Get device model, manufacturer, Android version, and hardware info",
            jsonSchema { }
        ) {
            buildJsonObject {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("device", Build.DEVICE)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("security_patch", Build.VERSION.SECURITY_PATCH)
                put("board", Build.BOARD)
                put("hardware", Build.HARDWARE)
                put("supported_abis", JsonArray(Build.SUPPORTED_ABIS.map { JsonPrimitive(it) }))
            }.toString()
        }

        registry.textTool("storage_info", "Get internal storage free/total space",
            jsonSchema { }
        ) {
            val stat = StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            buildJsonObject {
                put("free_bytes", free)
                put("total_bytes", total)
                put("free_gb", String.format("%.1f", free / 1e9))
                put("total_gb", String.format("%.1f", total / 1e9))
                put("used_percent", String.format("%.0f", (1 - free.toDouble() / total) * 100))
            }.toString()
        }

        registry.textTool("memory_info", "Get RAM usage info",
            jsonSchema { }
        ) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            buildJsonObject {
                put("available_bytes", mi.availMem)
                put("total_bytes", mi.totalMem)
                put("available_mb", mi.availMem / (1024 * 1024))
                put("total_mb", mi.totalMem / (1024 * 1024))
                put("low_memory", mi.lowMemory)
                put("threshold_mb", mi.threshold / (1024 * 1024))
            }.toString()
        }

        registry.textTool("clipboard_read", "Read the current clipboard contents",
            jsonSchema { }
        ) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(ctx).toString()
            } else "(clipboard empty)"
        }

        registry.textTool(
            name = "clipboard_write",
            description = "Write text to the clipboard",
            params = jsonSchema { string("text", "Text to copy to clipboard") },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.FAST
                failureMode(
                    pattern = "secure keyboard|not allowed",
                    hint = "Some secure keyboards block clipboard writes. Ask the user to switch keyboard temporarily.",
                )
                example(intent = "Copy a URL to clipboard.") { args ->
                    args["text"] = "https://example.com"
                }
            },
        ) { args ->
            val text = args["text"]?.jsonPrimitive?.content ?: ""
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LLM Intentions", text))
            "Copied to clipboard: ${text.take(50)}${if (text.length > 50) "..." else ""}"
        }

        registry.textTool(
            name = "flashlight_on",
            description = "Turn on the camera flashlight",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.FAST
                permission("CAMERA")
                failureMode(
                    pattern = "no camera|Camera (error|unavailable)|no flash",
                    hint = "No camera or flash available on this device (emulator?). Use device.all to check hardware.",
                )
                example(intent = "Turn on the flashlight.") { args -> }
            },
        ) {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: throw IllegalArgumentException("No camera found on this device.")
            cm.setTorchMode(cameraId, true)
            "Flashlight ON"
        }

        registry.textTool(
            name = "flashlight_off",
            description = "Turn off the camera flashlight",
            params = jsonSchema { },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.FAST
                permission("CAMERA")
                failureMode(
                    pattern = "no camera|Camera (error|unavailable)|no flash",
                    hint = "No camera or flash available on this device (emulator?). Use device.all to check hardware.",
                )
                example(intent = "Turn off the flashlight.") { args -> }
            },
        ) {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: throw IllegalArgumentException("No camera found on this device.")
            cm.setTorchMode(cameraId, false)
            "Flashlight OFF"
        }

        registry.textTool(
            name = "vibrate",
            description = "Vibrate the device",
            params = jsonSchema { integer("duration_ms", "Duration in milliseconds (default 500)", required = false) },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.FAST
                permission("VIBRATE")
                failureMode(
                    pattern = "permission|SecurityException",
                    hint = "Grant VIBRATE permission to LLM Device Tools in System Settings > Apps > LLM Device Tools > Permissions.",
                )
                example(intent = "Vibrate for half a second.") { args ->
                    args["duration_ms"] = 500
                }
            },
        ) { args ->
            val ms = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 500L
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            "Vibrated for ${ms}ms"
        }

        registry.textTool("volume_get", "Get current volume levels for all audio streams",
            jsonSchema { }
        ) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            buildJsonObject {
                put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                put("music_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                put("ring", am.getStreamVolume(AudioManager.STREAM_RING))
                put("ring_max", am.getStreamMaxVolume(AudioManager.STREAM_RING))
                put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM))
                put("alarm_max", am.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                put("notification_max", am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION))
                put("ringer_mode", when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                })
            }.toString()
        }

        registry.textTool("volume_set", "Set volume level for a stream",
            jsonSchema {
                enum("stream", "Audio stream", listOf("music", "ring", "alarm", "notification"))
                integer("level", "Volume level (0 to max)")
            }
        ) { args ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (args["stream"]?.jsonPrimitive?.content) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
            val level = args["level"]?.jsonPrimitive?.intOrNull ?: 0
            am.setStreamVolume(streamType, level, 0)
            "Volume set to $level"
        }

        registry.textTool("ringer_mode", "Get or set ringer mode (normal, vibrate, silent)",
            jsonSchema { string("mode", "Set mode: normal, vibrate, or silent. Omit to just read.", required = false) }
        ) { args ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = args["mode"]?.jsonPrimitive?.contentOrNull
            if (mode != null) {
                am.ringerMode = when (mode) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                "Ringer mode set to $mode"
            } else {
                when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
            }
        }

        registry.textTool(
            name = "tts_speak",
            description = "Speak text aloud using text-to-speech",
            params = jsonSchema { string("text", "Text to speak") },
            metadata = toolMetadata {
                destructive = true
                idempotent = false
                latencyClass = LatencyClass.SLOW
                failureMode(
                    pattern = "no tts engine|TTS init",
                    hint = "No TTS engine installed. Install one from Play Store.",
                )
                example(intent = "Say hello out loud.") { args ->
                    args["text"] = "Hello!"
                }
            },
        ) { args ->
            val text = args["text"]?.jsonPrimitive?.content ?: ""
            if (!ttsReady) throw IllegalStateException("TTS init failed: no TTS engine ready.")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mcp-tts")
            "Speaking: ${text.take(80)}"
        }

        registry.textTool(
            name = "sensor_read",
            description = "Read device sensors (accelerometer, gyroscope, compass, light, pressure, proximity)",
            params = jsonSchema {
                enum("sensor", "Which sensor to read",
                    listOf("accelerometer", "gyroscope", "compass", "light", "pressure", "proximity", "all"),
                    required = false)
            },
            metadata = toolMetadata {
                destructive = false
                idempotent = true
                latencyClass = LatencyClass.FAST
                failureMode(
                    pattern = "unknown sensor|Unknown sensor|no such sensor",
                    hint = "Call device.all to enumerate available sensors on this device.",
                )
                example(intent = "Read the accelerometer.") { args ->
                    args["sensor"] = "accelerometer"
                }
            },
        ) { args ->
            val sensorName = args["sensor"]?.jsonPrimitive?.contentOrNull ?: "all"
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            val sensorTypes = if (sensorName == "all") {
                mapOf(
                    "accelerometer" to Sensor.TYPE_ACCELEROMETER,
                    "gyroscope" to Sensor.TYPE_GYROSCOPE,
                    "compass" to Sensor.TYPE_MAGNETIC_FIELD,
                    "light" to Sensor.TYPE_LIGHT,
                    "pressure" to Sensor.TYPE_PRESSURE,
                    "proximity" to Sensor.TYPE_PROXIMITY
                )
            } else {
                val type = when (sensorName) {
                    "accelerometer" -> Sensor.TYPE_ACCELEROMETER
                    "gyroscope" -> Sensor.TYPE_GYROSCOPE
                    "compass" -> Sensor.TYPE_MAGNETIC_FIELD
                    "light" -> Sensor.TYPE_LIGHT
                    "pressure" -> Sensor.TYPE_PRESSURE
                    "proximity" -> Sensor.TYPE_PROXIMITY
                    else -> throw IllegalArgumentException("Unknown sensor: $sensorName")
                }
                mapOf(sensorName to type)
            }

            buildJsonObject {
                for ((name, type) in sensorTypes) {
                    val sensor = sm.getDefaultSensor(type)
                    if (sensor == null) {
                        put(name, JsonPrimitive("not available"))
                        continue
                    }
                    val reading = readSensorOnce(sm, sensor)
                    put(name, JsonArray(reading.map { JsonPrimitive(it) }))
                }
            }.toString()
        }
    }

    private suspend fun readSensorOnce(sm: SensorManager, sensor: Sensor): FloatArray {
        val deferred = CompletableDeferred<FloatArray>()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sm.unregisterListener(this)
                deferred.complete(event.values.copyOf())
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
        return try {
            withTimeout(3000) { deferred.await() }
        } catch (e: Exception) {
            sm.unregisterListener(listener)
            floatArrayOf()
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
