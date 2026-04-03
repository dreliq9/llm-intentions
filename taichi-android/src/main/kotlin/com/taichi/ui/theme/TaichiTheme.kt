package com.taichi.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TaichiBackground = Color(0xFF121212)
val TaichiSurface = Color(0xFF1E1E1E)
val TaichiSurfaceVariant = Color(0xFF2A2A2A)
val TaichiProfit = Color(0xFF4CAF50)
val TaichiLoss = Color(0xFFF44336)
val TaichiWarning = Color(0xFFFFC107)
val TaichiAccent = Color(0xFF2196F3)
val TaichiOnSurface = Color(0xFFFFFFFF)
val TaichiOnSurfaceVariant = Color(0xFFB0B0B0)
val TaichiOutline = Color(0xFF3A3A3A)

private val TaichiColorScheme = darkColorScheme(
    primary = TaichiProfit,
    secondary = TaichiAccent,
    tertiary = TaichiWarning,
    background = TaichiBackground,
    surface = TaichiSurface,
    surfaceVariant = TaichiSurfaceVariant,
    error = TaichiLoss,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = TaichiOnSurface,
    onSurface = TaichiOnSurface,
    onSurfaceVariant = TaichiOnSurfaceVariant,
    onError = Color.White,
    outline = TaichiOutline,
)

@Composable
fun TaichiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TaichiColorScheme,
        content = content
    )
}
