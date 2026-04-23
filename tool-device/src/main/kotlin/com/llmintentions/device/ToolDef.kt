package com.llmintentions.device

data class ParamDef(
    val name: String,
    val description: String,
    val type: ParamType,
    val required: Boolean = true
)

enum class ParamType { STRING, INTEGER, BOOLEAN, ENUM }

data class ToolDef(
    val name: String,
    val description: String,
    val params: List<ParamDef>
) {
    companion object {
        fun allTools(): List<ToolDef> = listOf(
            ToolDef("battery_status", "Get battery level, charging state, and temperature", emptyList()),
            ToolDef("device_info", "Get device model, manufacturer, Android version, and hardware info", emptyList()),
            ToolDef("storage_info", "Get internal storage free/total space", emptyList()),
            ToolDef("memory_info", "Get RAM usage info", emptyList()),
            ToolDef("clipboard_read", "Read the current clipboard contents", emptyList()),
            ToolDef("clipboard_write", "Write text to the clipboard", listOf(
                ParamDef("text", "Text to copy to clipboard", ParamType.STRING)
            )),
            ToolDef("flashlight_on", "Turn on the camera flashlight", emptyList()),
            ToolDef("flashlight_off", "Turn off the camera flashlight", emptyList()),
            ToolDef("vibrate", "Vibrate the device", listOf(
                ParamDef("duration_ms", "Duration in milliseconds (default 500)", ParamType.INTEGER, required = false)
            )),
            ToolDef("volume_get", "Get current volume levels for all audio streams", emptyList()),
            ToolDef("volume_set", "Set volume level for a stream", listOf(
                ParamDef("stream", "Audio stream: music, ring, alarm, notification", ParamType.STRING),
                ParamDef("level", "Volume level (0 to max)", ParamType.INTEGER)
            )),
            ToolDef("ringer_mode", "Get or set ringer mode (normal, vibrate, silent)", listOf(
                ParamDef("mode", "Set mode: normal, vibrate, or silent. Omit to just read.", ParamType.STRING, required = false)
            )),
            ToolDef("tts_speak", "Speak text aloud using text-to-speech", listOf(
                ParamDef("text", "Text to speak", ParamType.STRING)
            )),
            ToolDef("sensor_read", "Read device sensors (accelerometer, gyroscope, compass, light, pressure, proximity)", listOf(
                ParamDef("sensor", "Which sensor: accelerometer, gyroscope, compass, light, pressure, proximity, all", ParamType.STRING, required = false)
            ))
        )
    }
}
