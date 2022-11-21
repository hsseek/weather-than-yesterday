package com.hsseek.betterthanyesterday.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorPalette = darkColors(
    primary = Amber400,
    primaryVariant = Amber800,
    secondary = Pink700,
    secondaryVariant = Pink700,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkBackground,
    onSurface = Color.White,
)

private val LightColorPalette = lightColors(
    primary = Amber600,
    primaryVariant = Amber400,
    secondary = Pink400,
    secondaryVariant = Pink400,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,

    /* Other default colors to override
    onPrimary = Color.White,
    onSecondary = Color.Black,
    */
)

@Composable
fun BetterThanYesterdayTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette
    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}