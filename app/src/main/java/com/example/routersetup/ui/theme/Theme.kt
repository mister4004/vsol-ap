package com.example.routersetup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Teal,
    background = White
)

private val DarkColors = darkColorScheme(
    primary = LightBlue,
    secondary = TealDark,
    background = Black
)

@Composable
fun RouterSetupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
