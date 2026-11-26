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

// Option B: Leonida Twilight Dusk (GTA VI Dusk Palette — high-contrast Dark Liquid Glass)
object TibanColors {
    val TwilightNight = Color(0xFF090E2E)
    val DeepPlum = Color(0xFF211046)
    val DeepNeonBerry = Color(0xFF661A5E)
    val WarmSunset = Color(0xFFB5425A)
    val HorizonAmber = Color(0xFFD97A4A)

    // Dark Liquid Glass surface & border tokens
    val GlassFillDark = Color(0xB8130A2B)
    val GlassFillMid = Color(0x8C170C36)
    val GlassFillLight = Color(0x28FFFFFF)
    val GlassPillActive = Color(0x3DFFFFFF)
    val GlassBorderBright = Color(0x8CFFFFFF)
    val GlassBorderSoft = Color(0x38FFFFFF)
    val GlassInnerTopGlow = Color(0x66FFFFFF)

    // Accent status colors
    val AccentCyan = Color(0xFF3BF0FF)
    val AccentMint = Color(0xFF28FFA5)
    val AccentPink = Color(0xFFFF4FB8)
    val AccentGold = Color(0xFFFFB86C)
    val ErrorSoft = Color(0xFFFF9AA2)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xE0F4EBFF)
    val TextMuted = Color(0xB8DFD0F5)

    val SunsetWallpaperBrush: Brush = Brush.verticalGradient(
        0.00f to TwilightNight,
        0.26f to DeepPlum,
        0.54f to DeepNeonBerry,
        0.79f to WarmSunset,
        1.00f to HorizonAmber
    )

    val PrimaryActionBrush: Brush = Brush.horizontalGradient(
        listOf(
            Color(0xFF4BE8FF),
            Color(0xFFFF55C8),
            Color(0xFFFFA95B)
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
    secondary = TibanColors.AccentPink,
    tertiary = TibanColors.AccentGold,
    background = TibanColors.TwilightNight,
    surface = TibanColors.GlassFillDark,
    onPrimary = Color(0xFF090E2E),
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
