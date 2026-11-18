package fr.jmethod.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Palette claire violet et citron (`docs/design-direction.md`, classes Tailwind du web). */
object JColors {
    val Violet700 = Color(0xFF6D28D9)
    val Violet800 = Color(0xFF5B21B6)
    val Violet900 = Color(0xFF4C1D95)
    val Violet950 = Color(0xFF2E1065)
    val Violet50 = Color(0xFFF5F3FF)
    val Violet100 = Color(0xFFEDE9FE)
    val Violet200 = Color(0xFFDDD6FE)
    val Violet300 = Color(0xFFC4B5FD)
    val Lime200 = Color(0xFFD9F99D)
    val Slate50 = Color(0xFFF8FAFC)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate900 = Color(0xFF0F172A)
    val Amber50 = Color(0xFFFFFBEB)
    val Amber300 = Color(0xFFFCD34D)
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
    inverseSurface = JColors.Slate900,
    inverseOnSurface = Color.White,
    inversePrimary = JColors.Violet200,
    scrim = JColors.Slate900,
)

/**
 * Jetons d’espacement (grille de 4 dp). Toujours passer par ces valeurs plutôt que par des dp
 * isolés : 4 / 8 / 12 / 16 / 24 / 32.
 */
object JSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Rayons : pastilles 8, boutons, champs et blocs internes 12, cartes internes 16, cartes d’écran 24. */
object JShapes {
    val badge = RoundedCornerShape(8.dp)
    val control = RoundedCornerShape(12.dp)
    val inner = RoundedCornerShape(16.dp)
    val card = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val MaterialShapes = Shapes(
    // Champs de saisie, menus et snackbars (M3 : extraSmall) alignés sur les boutons.
    extraSmall = JShapes.control,
    small = JShapes.badge,
    medium = JShapes.control,
    large = JShapes.inner,
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Typographie M3 (police système, tailles en sp : la taille de texte du téléphone est respectée).
 * Graisses portées par le thème : titres d’écran très gras, titres de carte gras, libellés demi-gras.
 */
private val Base = Typography()
private val MaterialTypography = Base.copy(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** Chiffres à chasse fixe dans la police du texte (scores, J, compteurs) : remplace la police monospace. */
val TabularNumbers = SpanStyle(fontFeatureSettings = "tnum")

/** Applique les chiffres tabulaires à un style de texte. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * Thème clair uniquement, y compris quand le téléphone est en mode sombre : la palette violet et
 * citron n’a pas encore de déclinaison sombre validée, et un thème clair lisible vaut mieux qu’un
 * sombre approximatif. Les barres système gardent des icônes sombres (voir `MainActivity`).
 */
@Composable
fun JMethodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = MaterialTypography, shapes = MaterialShapes, content = content)
}
