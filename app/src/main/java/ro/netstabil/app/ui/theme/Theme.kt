package ro.netstabil.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val EmeraldPrimary = Color(0xFF10B981)
val EmeraldLight = Color(0xFF34D399)
val EmeraldDark = Color(0xFF059669)
val AmberWarn = Color(0xFFF59E0B)
val CrimsonAlert = Color(0xFFEF4444)
val CyanAccent = Color(0xFF38BDF8)

private val AmoledDarkColors = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF032217),
    primaryContainer = Color(0xFF0D3326),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = CyanAccent,
    onSecondary = Color(0xFF062536),
    secondaryContainer = Color(0xFF102A3C),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = AmberWarn,
    background = Color(0xFF090D12),
    onBackground = Color(0xFFE5ECF4),
    surface = Color(0xFF111720),
    onSurface = Color(0xFFE5ECF4),
    surfaceVariant = Color(0xFF18212E),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF263345),
    error = CrimsonAlert,
    onError = Color.White
)

private val CleanLightColors = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val NetStabilTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun NetStabilTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AmoledDarkColors else CleanLightColors,
        typography = NetStabilTypography,
        content = content
    )
}
