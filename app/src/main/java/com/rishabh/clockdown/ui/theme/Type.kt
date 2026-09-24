package com.rishabh.clockdown.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.rishabh.clockdown.R

// Outfit (OFL) as a variable font, so any weight from 300 to 900 is available from one 110 KB file.
private fun outfit(weight: Int) =
    Font(R.font.outfit, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700), outfit(800), outfit(900))

private val base = Typography()
private fun TextStyle.x(weight: FontWeight, spacing: TextUnit = this.letterSpacing) =
    copy(fontFamily = Outfit, fontWeight = weight, letterSpacing = spacing)

// Expressive typography: larger, heavier headlines with tight tracking; calm regular body text.
val Typography = Typography(
    displayLarge = base.displayLarge.x(FontWeight.Black, (-2).sp),
    displayMedium = base.displayMedium.x(FontWeight.Black, (-1.5).sp),
    displaySmall = base.displaySmall.x(FontWeight.ExtraBold, (-1).sp),
    headlineLarge = base.headlineLarge.x(FontWeight.ExtraBold, (-0.5).sp),
    headlineMedium = base.headlineMedium.x(FontWeight.Bold, (-0.3).sp),
    headlineSmall = base.headlineSmall.x(FontWeight.Bold),
    titleLarge = base.titleLarge.x(FontWeight.Bold),
    titleMedium = base.titleMedium.x(FontWeight.SemiBold),
    titleSmall = base.titleSmall.x(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.x(FontWeight.Normal),
    bodyMedium = base.bodyMedium.x(FontWeight.Normal),
    bodySmall = base.bodySmall.x(FontWeight.Normal),
    labelLarge = base.labelLarge.x(FontWeight.SemiBold),
    labelMedium = base.labelMedium.x(FontWeight.SemiBold),
    labelSmall = base.labelSmall.x(FontWeight.SemiBold, 0.5.sp),
)
