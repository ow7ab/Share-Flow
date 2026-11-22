package com.example.shareflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val PlusJakartaSansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

val IbmPlexSansArabicFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic, FontWeight.Bold)
)

val NotoSansArabicFontFamily = IbmPlexSansArabicFontFamily

val ShareFlowMonoStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp
)

private fun buildShareFlowTypography(
    displayFamily: FontFamily,
    bodyFamily: FontFamily,
    isArabic: Boolean
): Typography {
    // Arabic script benefits from taller line heights and zero negative letter-spacing
    val lineBoost = if (isArabic) 4 else 0
    val tightTracking = if (isArabic) 0.sp else (-0.2).sp
    val labelTracking = if (isArabic) 0.sp else 0.25.sp

    return Typography(
        displayLarge = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            lineHeight = (46 + lineBoost).sp,
            letterSpacing = tightTracking
        ),
        displayMedium = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = (38 + lineBoost).sp,
            letterSpacing = tightTracking
        ),
        displaySmall = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = (34 + lineBoost).sp,
            letterSpacing = 0.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            lineHeight = (32 + lineBoost).sp,
            letterSpacing = 0.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = (28 + lineBoost).sp,
            letterSpacing = 0.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = (24 + lineBoost).sp,
            letterSpacing = 0.sp
        ),
        titleLarge = TextStyle(
            fontFamily = displayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = (24 + lineBoost).sp,
            letterSpacing = 0.sp
        ),
        titleMedium = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = (22 + lineBoost).sp,
            letterSpacing = if (isArabic) 0.sp else 0.1.sp
        ),
        titleSmall = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = (18 + lineBoost).sp,
            letterSpacing = if (isArabic) 0.sp else 0.1.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = (22 + lineBoost).sp,
            letterSpacing = if (isArabic) 0.sp else 0.15.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = (19 + lineBoost).sp,
            letterSpacing = if (isArabic) 0.sp else 0.2.sp
        ),
        bodySmall = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = (16 + lineBoost).sp,
            letterSpacing = if (isArabic) 0.sp else 0.25.sp
        ),
        labelLarge = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = (20 + lineBoost).sp,
            letterSpacing = labelTracking
        ),
        labelMedium = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = (16 + lineBoost).sp,
            letterSpacing = labelTracking
        ),
        labelSmall = TextStyle(
            fontFamily = bodyFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = (14 + lineBoost).sp,
            letterSpacing = labelTracking
        )
    )
}

val ShareFlowTypography: Typography = buildShareFlowTypography(
    displayFamily = SpaceGroteskFontFamily,
    bodyFamily = PlusJakartaSansFontFamily,
    isArabic = false
)

val ShareFlowArabicTypography: Typography = buildShareFlowTypography(
    displayFamily = IbmPlexSansArabicFontFamily,
    bodyFamily = IbmPlexSansArabicFontFamily,
    isArabic = true
)
