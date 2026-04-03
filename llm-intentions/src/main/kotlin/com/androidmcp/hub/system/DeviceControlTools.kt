package com.androidmcp.hub.system

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import com.androidmcp.core.protocol.*
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import kotlinx.serialization.json.*

/**
 * Device hardware control tools under the "system" namespace.
 * Flashlight, vibration, toasts, ringer mode, brightness, media playback.
 */
class DeviceControlTools(private val context: Context) {

    fun registerAll(registry: ToolRegistry) {
        registerTorch(registry)
        registerVibrate(registry)
        registerToast(registry)
        registerRingerMode(registry)
        registerBrightness(registry)
        registerMediaControl(registry)
    }

    // --- Flashlight / Torch ---

    private fun registerTorch(registry: ToolRegistry) {
        // Check if the device has a flash
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val hasFlash = try {
            cameraManager.cameraIdList.any { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { false }

        if (!hasFlash) return

        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.torch",
                description = "Turn the flashlight/torch on or off",
                inputSchema = jsonSchema {
                    boolean("on", "true to turn on, false to turn off", required = true)
                }
            ),
            handler = { args ->
                val on = args["on"]?.jsonPrimitive?.booleanOrNull ?: true
                try {
                    val camId = cameraManager.cameraIdList.firstOrNull()
                        ?: return@McpToolDef ToolCallResult(
                            content = listOf(ContentBlock.text("No camera found")),
                            isError = true
                        )
                    cameraManager.setTorchMode(camId, on)
                    ToolCallResult(content = listOf(ContentBlock.text(
                        "Torch ${if (on) "ON" else "OFF"}"
                    )))
                } catch (e: Exception) {
                    ToolCallResult(
                        content = listOf(ContentBlock.text("Torch error: ${e.message}")),
                        isError = true
                    )
                }
            }
        ))
    }

    // --- Vibrate ---

    private fun registerVibrate(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.vibrate",
                description = "Vibrate the device for a given duration",
                inputSchema = jsonSchema {
                    integer("ms", "Duration in milliseconds (default 500, max 5000)", required = false)
                    string("pattern", "Vibration pattern: 'short' (200ms), 'medium' (500ms), " +
                        "'long' (1000ms), 'double' (two pulses), 'sos' (SOS pattern)", required = false)
                }
            ),
            handler = { args ->
                val vibrator = getVibrator()
                if (vibrator == null || !vibrator.hasVibrator()) {
                    return@McpToolDef ToolCallResult(
                        content = listOf(ContentBlock.text("Device has no vibrator")),
                        isError = true
                    )
                }

                val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull
                val ms = args["ms"]?.jsonPrimitive?.intOrNull?.coerceIn(50, 5000) ?: 500

                when (pattern) {
                    "short" -> vibrate(vibrator, 200)
                    "long" -> vibrate(vibrator, 1000)
                    "double" -> vibratePattern(vibrator, longArrayOf(0, 200, 150, 200))
                    "sos" -> vibratePattern(vibrator, longArrayOf(
                        0, 150, 100, 150, 100, 150, 200, 400, 100, 400, 100, 400, 200, 150, 100, 150, 100, 150
                    ))
                    else -> vibrate(vibrator, ms.toLong())
                }

                val desc = pattern ?: "${ms}ms"
                ToolCallResult(content = listOf(ContentBlock.text("Vibrated: $desc")))
            }
        ))
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(vibrator: Vibrator, ms: Long) {
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibratePattern(vibrator: Vibrator, pattern: LongArray) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // --- Toast ---

    private fun registerToast(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.toast",
                description = "Show a brief on-screen toast message",
                inputSchema = jsonSchema {
                    string("text", "Message to display")
                    boolean("long", "Show for longer duration (default false)", required = false)
                }
            ),
            handler = { args ->
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val long = args["long"]?.jsonPrimitive?.booleanOrNull ?: false
                val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

                // Toast must be shown from a thread with a Looper (main thread)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, text, duration).show()
                }

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Toast shown: ${text.take(100)}"
                )))
            }
        ))
    }

    // --- Ringer Mode ---

    private fun registerRingerMode(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.ringer_mode",
                description = "Get or set the device ringer mode (normal, vibrate, silent). " +
                    "Note: setting to silent may require Do Not Disturb permission on some devices.",
                inputSchema = jsonSchema {
                    string("mode", "Set mode: 'normal', 'vibrate', or 'silent'. " +
                        "Omit to just read current mode.", required = false)
                }
            ),
            handler = { args ->
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val setMode = args["mode"]?.jsonPrimitive?.contentOrNull

                if (setMode != null) {
                    val target = when (setMode) {
                        "normal" -> AudioManager.RINGER_MODE_NORMAL
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        else -> null
                    }
                    if (target != null) {
                        try {
                            am.ringerMode = target
                        } catch (e: SecurityException) {
                            return@McpToolDef ToolCallResult(
                                content = listOf(ContentBlock.text(
                                    "Cannot set ringer mode to '$setMode': " +
                                    "Do Not Disturb access required. " +
                                    "Grant in Settings > Apps > Special access > Do Not Disturb."
                                )),
                                isError = true
                            )
                        }
                    }
                }

                val current = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> "unknown"
                }

                ToolCallResult(content = listOf(ContentBlock.text("Ringer mode: $current")))
            }
        ))
    }

    // --- Screen Brightness ---

    private fun registerBrightness(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.brightness",
                description = "Get current screen brightness level (0-255). " +
                    "Setting brightness requires WRITE_SETTINGS permission " +
                    "(user must grant in Settings > Apps > Special access).",
                inputSchema = jsonSchema {
                    integer("level", "Brightness level to set (0-255). " +
                        "Omit to just read current level.", required = false)
                }
            ),
            handler = { args ->
                val setLevel = args["level"]?.jsonPrimitive?.intOrNull

                if (setLevel != null) {
                    if (!Settings.System.canWrite(context)) {
                        return@McpToolDef ToolCallResult(
                            content = listOf(ContentBlock.text(
                                "Cannot set brightness: WRITE_SETTINGS permission not granted. " +
                                "Enable in Settings > Apps > LLM Intentions > Modify system settings."
                            )),
                            isError = true
                        )
                    }
                    val clamped = setLevel.coerceIn(0, 255)
                    // Disable auto-brightness first
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        clamped
                    )
                }

                val current = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Exception) { -1 }

                val auto = try {
                    Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE
                    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                } catch (_: Exception) { false }

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Brightness: $current/255${if (auto) " (auto)" else " (manual)"}"
                )))
            }
        ))
    }

    // --- Media Playback Control ---

    private fun registerMediaControl(registry: ToolRegistry) {
        registry.register(McpToolDef(
            info = ToolInfo(
                name = "system.media_control",
                description = "Control media playback: play, pause, toggle, next track, previous track, stop",
                inputSchema = jsonSchema {
                    enum("action", "Media action",
                        listOf("play", "pause", "toggle", "next", "previous", "stop"))
                }
            ),
            handler = { args ->
                val action = args["action"]?.jsonPrimitive?.content ?: "toggle"
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                val keyCode = when (action) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }

                // Send key down + key up to simulate a media button press
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))

                val isPlaying = am.isMusicActive

                ToolCallResult(content = listOf(ContentBlock.text(
                    "Media: $action sent. Music active: $isPlaying"
                )))
            }
        ))
    }
}
