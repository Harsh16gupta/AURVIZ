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
    background = Color(0xFF121212),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1F1F1F),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFF5F5F5),
    onBackgroundMuted = Color(0xA8F5F5F5),
    primary = Color(0xFF1084FE),
    onPrimary = Color(0xFFFFFFFF),
    border = Color(0xFF383838),
    textSelection = Color(0x551084FE),
    destructive = Color(0xFFFF5667),
    onDestructive = Color(0xFFFFFFFF),
    panel = Color(0xFF0E0E0E),
    card = Color(0xFF1F1F1F),
    composer = Color(0xFF1F1F1F),
    composerRing = Color(0xFF2A2A2A),
    inset = Color(0xFF1B1B1B),
    edge = Color(0x14F5F5F5),
    inkTertiary = Color(0x91F5F5F5),
    bubbleUser = Color(0xFF5A5A5A),
    bubbleUserInk = Color(0xFFF5F5F5),
    success = Color(0xFF38D591),
    onSuccess = Color(0xFF072114),
    warning = Color(0xFFFF9800),
    accentText = Color(0xFF7FC0FF),
    scrim = Color(0x6B0C0C0E),
    isDark = true,
)

val LightThemeColors = ThemeColors(
    background = Color(0xFFFDFDFD),
    onBackground = Color(0xFF1C1C1C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF1C1C1C),
    onBackgroundMuted = Color(0xAD1C1C1C),
    primary = Color(0xFF126FD4),
    onPrimary = Color(0xFFFFFFFF),
    border = Color(0x3D1C1C1C),
    textSelection = Color(0x40126FD4),
    destructive = Color(0xFFCD2834),
    onDestructive = Color(0xFFFFFFFF),
    panel = Color(0xFFF7F7F7),
    card = Color(0xFFEFEFEF),
    composer = Color(0xFFFFFFFF),
    composerRing = Color(0x1F1C1C1C),
    inset = Color(0xFFE9E9E9),
    edge = Color(0x141C1C1C),
    inkTertiary = Color(0xA61C1C1C),
    bubbleUser = Color(0xFF1C1C1C),
    bubbleUserInk = Color(0xFFFCFCFC),
    success = Color(0xFF007A44),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFF9C5B00),
    accentText = Color(0xFF0B69CC),
    scrim = Color(0x4D0C0C0E),
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
