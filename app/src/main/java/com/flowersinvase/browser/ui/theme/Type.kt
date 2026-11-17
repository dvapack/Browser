package com.flowersinvase.browser.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = weight, letterSpacing = 0.sp
)
val Typography = Typography(
    displayLarge = style(64, 76), displayMedium = style(48, 58), displaySmall = style(36, 44),
    headlineLarge = style(30, 38), headlineMedium = style(26, 34), headlineSmall = style(24, 32),
    titleLarge = style(20, 28, FontWeight.SemiBold), titleMedium = style(17, 24, FontWeight.SemiBold),
    titleSmall = style(15, 22, FontWeight.Medium), bodyLarge = style(17, 24), bodyMedium = style(15, 22),
    bodySmall = style(13, 18), labelLarge = style(16, 22, FontWeight.Medium),
    labelMedium = style(13, 18), labelSmall = style(11, 16)
)
