package com.hsseek.betterthanyesterday.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

private val DarkColorPalette = darkColors(
    primary = Amber400,
    primaryVariant = Amber400,
    secondary = Pink700,
    secondaryVariant = Pink700,
    background = Gray500,
    onBackground = White,
    surface = Gray500,
    onSurface = White,
)

private val LightColorPalette = lightColors(
    primary = Amber600,
    primaryVariant = Amber600,
    secondary = Pink400,
    secondaryVariant = Pink400,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,

    /* Other default colors to override
    onPrimary = Color.White,
    onSecondary = Color.Black,
    */
)

@Composable
fun BetterThanYesterdayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}