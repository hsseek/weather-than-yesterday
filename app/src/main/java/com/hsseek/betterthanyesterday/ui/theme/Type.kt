package com.hsseek.betterthanyesterday.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    h1 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 130.sp,
        textAlign = TextAlign.Center,
    ),
    h3 = TextStyle(
        fontSize = 32.sp,
        textAlign = TextAlign.Center,
    ),
    h6 = TextStyle(
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        color = Grey000,
    ),
    body1 = TextStyle(
        fontSize = 16.sp
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