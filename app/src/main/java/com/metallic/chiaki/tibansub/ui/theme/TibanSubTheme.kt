package com.metallic.chiaki.tibansub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.metallic.chiaki.R

// Pure Twilight-to-Sunset Color Palette (from user reference — smooth colors only, zero trees/icons)
object TibanColors {
    val IndigoDeep = Color(0xFF1D2B9E)
    val IndigoRoyal = Color(0xFF3935BE)
    val VioletElectric = Color(0xFF8A3FD9)
    val MagentaVibrant = Color(0xFFF544C2)
    val CoralPeach = Color(0xFFFF7D6E)
    val SunsetAmber = Color(0xFFFFA659)
    val GoldenSun = Color(0xFFFFC758)

    // Glassy V20 surface & border tokens
    val GlassFillDark = Color(0x47120A2A)
    val GlassFillLight = Color(0x26FFFFFF)
    val GlassPillActive = Color(0x3DFFFFFF)
    val GlassBorderBright = Color(0x99FFFFFF)
    val GlassBorderSoft = Color(0x33FFFFFF)
    val GlassInnerTopGlow = Color(0x73FFFFFF)

    // Accent status colors
    val AccentCyan = Color(0xFF3BF0FF)
    val AccentMint = Color(0xFF28FFA5)
    val AccentPink = Color(0xFFFF4FB8)
    val AccentGold = Color(0xFFFFD166)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xE0F6EEFF)
    val TextMuted = Color(0xB8EAD8FF)

    val SunsetWallpaperBrush: Brush = Brush.linearGradient(
        0.00f to IndigoDeep,
        0.22f to IndigoRoyal,
        0.44f to VioletElectric,
        0.64f to MagentaVibrant,
        0.83f to CoralPeach,
        1.00f to GoldenSun
    )

    val PrimaryActionBrush: Brush = Brush.horizontalGradient(
        listOf(
            Color(0xFF4BE8FF),
            Color(0xFFFF55C8),
            Color(0xFFFFA95B)
        )
    )

    val SubtleActionBrush: Brush = Brush.horizontalGradient(
        listOf(
            Color(0x59FFFFFF),
            Color(0x38FFDDF5)
        )
    )
}

// Exact PS-Translator.apk Typography (GTA Art Deco + Prompt Light & SemiBold)
val TibanPromptFontFamily = FontFamily(
    Font(R.font.gta_prompt_light, FontWeight.Light),
    Font(R.font.gta_prompt_light, FontWeight.Normal),
    Font(R.font.gta_prompt_light, FontWeight.Medium),
    Font(R.font.gta_prompt_semibold, FontWeight.SemiBold),
    Font(R.font.gta_prompt_semibold, FontWeight.Bold)
)

val TibanTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = TibanPromptFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val TibanColorScheme = darkColorScheme(
    primary = TibanColors.AccentCyan,
    secondary = TibanColors.MagentaVibrant,
    tertiary = TibanColors.GoldenSun,
    background = TibanColors.IndigoDeep,
    surface = TibanColors.GlassFillDark,
    onPrimary = Color(0xFF0A0618),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun TibanSubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TibanColorScheme,
        typography = TibanTypography,
        content = content
    )
}
