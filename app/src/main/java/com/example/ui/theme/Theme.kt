package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF00FF88),      // Neon matrix green
    secondary = Color(0xFF00E5FF),    // Cyber cyber cyan
    tertiary = Color(0xFFFF3D00),     // Alert orange-red
    background = Color(0xFF000000),   // Full black
    surface = Color(0xFF121212),      // Dark gray surface
    onPrimary = Color(0xFF000000),
    onSecondary = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFCCCCCC)
  )

private val LightColorScheme = DarkColorScheme // Force dark theme for AMOLED developer cockpit

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Dynamic color sets standard system accent - disable it to preserve our premium hand-crafted matrix neon theme!
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
