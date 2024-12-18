package com.hsseek.betterthanyesterday.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.hsseek.betterthanyesterday.R

// Set of Material typography styles to start with
val Typography = Typography(
    h1 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 150.sp,
        textAlign = TextAlign.Center,
    ),
    h2 = TextStyle(
        fontSize = 32.sp,
        textAlign = TextAlign.Center,
    ),
    h3 = TextStyle(
        fontSize = 24.sp,
    ),
    h4 = TextStyle(
        fontSize = 18.sp,
    ),
    h6 = TextStyle(
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        color = Gray000,
        lineHeight = 16.sp,
    ),
    body1 = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontSize = 13.sp,
    ),
    /* Other default text styles to override
    button = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp
    ),
    */
)

val gmarket = FontFamily(
    Font(R.font.gmarket_sans, FontWeight.Normal)
)