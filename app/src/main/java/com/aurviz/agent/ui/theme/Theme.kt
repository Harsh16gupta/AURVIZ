package com.aurviz.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Adapted: Vela used com.composables.googlefonts.rememberGoogleFontFamily
// (Space Grotesk / Inter / JetBrains Mono). To avoid an extra Maven
// dependency, we resolve to system fonts with the same weights — the
// type scale (sizes/line-heights/weights) is unchanged.

@Immutable
data class ThemeColors(
    val `background`: Color = Color(0xFF0E0F12),
    val `onBackground`: Color = Color(0xFFECECEF),
    val `surface`: Color = Color(0xFF18191E),
    val `onSurface`: Color = Color(0xFFECECEF),
    val `surfaceVariant`: Color = Color(0xFF24252C),
    val `onSurfaceVariant`: Color = Color(0xFFECECEF),
    val `onBackgroundMuted`: Color = Color(0xFF8D8F99),
    val `primary`: Color = Color(0xFFD6F36B),
    val `onPrimary`: Color = Color(0xFF0E0F12),
    val `border`: Color = Color(0xFF2B2C34),
    val `textSelection`: Color = Color(0x66D6F36B),
    val `destructive`: Color = Color(0xFFFF7B72),
    val `onDestructive`: Color = Color(0xFF0E0F12),
)

@Immutable
data class ThemeTypography(
    val `display`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight(500),
        ),
    val `title`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight(500),
        ),
    val `body`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight(400),
        ),
    val `label`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight(500),
        ),
    val `caption`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight(400),
        ),
    val `code`: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight(400),
        ),
)

@Immutable
data class ThemeShapes(
    val `small`: Shape = RoundedCornerShape(12.dp),
    val `medium`: Shape = RoundedCornerShape(20.dp),
    val `large`: Shape = RoundedCornerShape(28.dp),
    val `fullyRounded`: Shape = RoundedCornerShape(50),
)

fun themeColors(dark: Boolean = false): ThemeColors = ThemeColors()

@Composable
fun resolveThemeTypography(typography: ThemeTypography = ThemeTypography()): ThemeTypography {
    // System-font fallback keeping Vela's scale: display/title -> SansSerif
    // Medium (Space Grotesk stand-in), body/label -> Default (Inter stand-in),
    // code -> Monospace (JetBrains Mono stand-in).
    return typography.copy(
        `display` = typography.`display`.copy(fontFamily = FontFamily.SansSerif),
        `title` = typography.`title`.copy(fontFamily = FontFamily.SansSerif),
        `body` = typography.`body`.copy(fontFamily = FontFamily.Default),
        `label` = typography.`label`.copy(fontFamily = FontFamily.Default),
        `caption` = typography.`caption`.copy(fontFamily = FontFamily.Default),
        `code` = typography.`code`.copy(fontFamily = FontFamily.Monospace),
    )
}

private val LocalColors = staticCompositionLocalOf<ThemeColors> { error("Theme is missing") }
private val LocalTypography =
    staticCompositionLocalOf<ThemeTypography> { error("Theme is missing") }
private val LocalSpace = staticCompositionLocalOf { 4.dp }
private val LocalShapes = staticCompositionLocalOf<ThemeShapes> { error("Theme is missing") }

val LocalContentColor = compositionLocalOf<Color> { error("Theme is missing") }
val LocalTextStyle = compositionLocalOf<TextStyle> { error("Theme is missing") }

object Theme {
    val colors: ThemeColors
        @Composable get() = LocalColors.current

    val typography: ThemeTypography
        @Composable get() = LocalTypography.current

    val shapes: ThemeShapes
        @Composable get() = LocalShapes.current
}

val Space: Dp
    @Composable get() = LocalSpace.current

@Composable
fun VelaTheme(
    colors: ThemeColors = themeColors(isSystemInDarkTheme()),
    typography: ThemeTypography = resolveThemeTypography(),
    space: Dp = 4.dp,
    shapes: ThemeShapes = ThemeShapes(),
    contentColor: Color = colors.onBackground,
    textStyle: TextStyle = typography.body,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTypography provides typography,
        LocalSpace provides space,
        LocalShapes provides shapes,
        LocalContentColor provides contentColor,
        LocalTextStyle provides textStyle,
        LocalTextSelectionColors provides
            TextSelectionColors(
                handleColor = colors.primary,
                backgroundColor = colors.textSelection,
            ),
        content = content,
    )
}

@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color, content = content)
}

@Composable
fun ProvideTextStyle(style: TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.merge(style),
        content = content,
    )
}
