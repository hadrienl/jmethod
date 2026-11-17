package fr.jmethod.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Palette claire violet et citron (`docs/design-direction.md`, classes Tailwind du web). */
object JColors {
    val Violet700 = Color(0xFF6D28D9)
    val Violet800 = Color(0xFF5B21B6)
    val Violet900 = Color(0xFF4C1D95)
    val Violet950 = Color(0xFF2E1065)
    val Violet50 = Color(0xFFF5F3FF)
    val Violet100 = Color(0xFFEDE9FE)
    val Violet200 = Color(0xFFDDD6FE)
    val Lime200 = Color(0xFFD9F99D)
    val Slate50 = Color(0xFFF8FAFC)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate900 = Color(0xFF0F172A)
    val Amber50 = Color(0xFFFFFBEB)
    val Amber700 = Color(0xFFB45309)
    val Amber900 = Color(0xFF78350F)
    val Emerald50 = Color(0xFFECFDF5)
    val Emerald200 = Color(0xFFA7F3D0)
    val Emerald700 = Color(0xFF047857)
    val Emerald800 = Color(0xFF065F46)
    val Emerald950 = Color(0xFF022C22)
    val Rose700 = Color(0xFFBE123C)
    val Rose800 = Color(0xFF9F1239)
    val Rose50 = Color(0xFFFFF1F2)
    val Sky700 = Color(0xFF0369A1)
    val Sky50 = Color(0xFFF0F9FF)
    val Indigo700 = Color(0xFF4338CA)
    val Blue50 = Color(0xFFEFF6FF)
    val Blue800 = Color(0xFF1E40AF)
    val Orange50 = Color(0xFFFFF7ED)
    val Orange800 = Color(0xFF9A3412)
}

private val LightColors = lightColorScheme(
    primary = JColors.Violet700,
    onPrimary = Color.White,
    primaryContainer = JColors.Violet100,
    onPrimaryContainer = JColors.Violet900,
    secondary = JColors.Violet800,
    onSecondary = Color.White,
    secondaryContainer = JColors.Violet100,
    onSecondaryContainer = JColors.Violet900,
    tertiary = JColors.Lime200,
    onTertiary = JColors.Violet950,
    tertiaryContainer = JColors.Lime200,
    onTertiaryContainer = JColors.Violet950,
    background = JColors.Slate50,
    onBackground = JColors.Slate900,
    surface = JColors.Slate50,
    onSurface = JColors.Slate900,
    surfaceVariant = JColors.Slate100,
    onSurfaceVariant = JColors.Slate600,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = JColors.Slate100,
    outline = JColors.Slate300,
    outlineVariant = JColors.Slate200,
    error = JColors.Rose700,
    onError = Color.White,
    errorContainer = JColors.Rose50,
    onErrorContainer = JColors.Rose800,
)

/** Thème clair uniquement, typographie Material par défaut (tailles système respectées en sp). */
@Composable
fun JMethodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
