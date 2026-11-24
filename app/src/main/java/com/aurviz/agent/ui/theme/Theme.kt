package com.aurviz.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Immutable
data class ThemeColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val onBackgroundMuted: Color,
    val primary: Color,
    val onPrimary: Color,
    val border: Color,
    val textSelection: Color,
    val destructive: Color,
    val onDestructive: Color,
    val panel: Color,
    val card: Color,
    val composer: Color,
    val composerRing: Color,
    val inset: Color,
    val edge: Color,
    val inkTertiary: Color,
    val bubbleUser: Color,
    val bubbleUserInk: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val accentText: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val DarkThemeColors = ThemeColors(
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF22242A),
    onSurfaceVariant = Color(0xFFE4E5E9),
    onBackgroundMuted = Color(0xA6F5F5F7),
    primary = Color(0xFF1084FE),
    onPrimary = Color(0xFFFFFFFF),
    border = Color(0xFF2E3038),
    textSelection = Color(0x551084FE),
    destructive = Color(0xFFFF5667),
    onDestructive = Color(0xFFFFFFFF),
    panel = Color(0xFF131418),
    card = Color(0xFF17181C),
    composer = Color(0xFF1B1C22),
    composerRing = Color(0xFF2C2E36),
    inset = Color(0xFF131418),
    edge = Color(0x14F5F5F7),
    inkTertiary = Color(0x7AF5F5F7),
    bubbleUser = Color(0xFF262830),
    bubbleUserInk = Color(0xFFF5F5F7),
    success = Color(0xFF34D399),
    onSuccess = Color(0xFF072114),
    warning = Color(0xFFFBBF24),
    accentText = Color(0xFF70B8FF),
    scrim = Color(0x7308090C),
    isDark = true,
)

val LightThemeColors = ThemeColors(
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF141518),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141518),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF24262B),
    onBackgroundMuted = Color(0xA8141518),
    primary = Color(0xFF0E6CD6),
    onPrimary = Color(0xFFFFFFFF),
    border = Color(0x26141518),
    textSelection = Color(0x400E6CD6),
    destructive = Color(0xFFC92532),
    onDestructive = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    composer = Color(0xFFFFFFFF),
    composerRing = Color(0x1F141518),
    inset = Color(0xFFF1F3F6),
    edge = Color(0x14141518),
    inkTertiary = Color(0x80141518),
    bubbleUser = Color(0xFF18191D),
    bubbleUserInk = Color(0xFFFFFFFF),
    success = Color(0xFF057A4B),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFF9A5B00),
    accentText = Color(0xFF0B63C4),
    scrim = Color(0x4D08090C),
    isDark = false,
)

@Immutable
data class ThemeTypography(
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    val code: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
)

@Immutable
data class ThemeShapes(
    val small: Shape = RoundedCornerShape(12.dp),
    val medium: Shape = RoundedCornerShape(20.dp),
    val large: Shape = RoundedCornerShape(28.dp),
    val fullyRounded: Shape = RoundedCornerShape(50),
    val card: Shape = RoundedCornerShape(24.dp),
    val grouped: Shape = RoundedCornerShape(16.dp),
    val field: Shape = RoundedCornerShape(10.dp),
)

fun themeColors(dark: Boolean = true): ThemeColors =
    if (dark) DarkThemeColors else LightThemeColors

@Composable
fun resolveThemeTypography(typography: ThemeTypography = ThemeTypography()): ThemeTypography =
    typography

private val LocalColors = staticCompositionLocalOf<ThemeColors> { error("Theme is missing") }
private val LocalTypography = staticCompositionLocalOf<ThemeTypography> { error("Theme is missing") }
private val LocalSpace = staticCompositionLocalOf { 4.dp }
private val LocalShapes = staticCompositionLocalOf<ThemeShapes> { error("Theme is missing") }

val LocalContentColor = compositionLocalOf<Color> { error("Theme is missing") }
val LocalTextStyle = compositionLocalOf<TextStyle> { error("Theme is missing") }

object Theme {
    val colors: ThemeColors @Composable get() = LocalColors.current
    val typography: ThemeTypography @Composable get() = LocalTypography.current
    val shapes: ThemeShapes @Composable get() = LocalShapes.current
}

val Space: Dp @Composable get() = LocalSpace.current

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
        LocalTextSelectionColors provides TextSelectionColors(
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
