package fr.jmethod.app.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.ColorWheel
import fr.jmethod.app.model.Phase
import fr.jmethod.app.ui.platform.animationsEnabled
import fr.jmethod.app.ui.theme.JIcons
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing

/**
 * Marge latérale supplémentaire des écrans à onglets sur grand écran ou en paysage, pour garder une
 * colonne de lecture d’environ 720 dp (calculée par `JMethodApp`).
 */
val LocalScreenGutter = compositionLocalOf { 0.dp }

/** Largeur maximale d’une colonne de lecture. */
val ReadingWidth = 720.dp

/** Classe de largeur de fenêtre (Material 3) : compacte (téléphone), moyenne, étendue. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

enum class NavigationMode(val width: Dp) { BOTTOM(0.dp), RAIL(81.dp), SIDEBAR(240.dp) }

internal val LocalContentWidth = compositionLocalOf { 400.dp }
internal val TwoColumnWidth = 960.dp

/** Taille de la fenêtre de l’app, mesurée à la racine par `JMethodApp`. */
data class WindowLayout(val width: Dp, val height: Dp) {
    val widthClass: WidthClass
        get() = when {
            width < 600.dp -> WidthClass.COMPACT
            width < 840.dp -> WidthClass.MEDIUM
            else -> WidthClass.EXPANDED
        }

    /** Au moins 600 dp de large (téléphone en paysage compris) : en-têtes compacts, rail de navigation. */
    val wide: Boolean get() = widthClass != WidthClass.COMPACT

    /**
     * Hauteur d’au moins 480 dp : exclut le téléphone en paysage, qui garde ses feuilles du bas et un
     * seul volet comme avant ; tablettes, pliables et ordinateur passent aux mises en page adaptatives.
     */
    val tall: Boolean get() = height >= 480.dp

    /** Les feuilles du bas deviennent un panneau latéral ou un dialogue. */
    val sidePanels: Boolean get() = wide && tall

    val navigationMode: NavigationMode
        get() = when {
            width >= 1200.dp -> NavigationMode.SIDEBAR
            wide -> NavigationMode.RAIL
            else -> NavigationMode.BOTTOM
        }
    val contentWidth: Dp get() = width - navigationMode.width
    val detailWidth: Dp get() = DetailPaneWidth + 1.dp
    val twoPane: Boolean get() = tall && contentWidth >= 480.dp + detailWidth

    fun listWidth(docked: Boolean): Dp = contentWidth - if (docked) detailWidth else 0.dp
}

val LocalWindowLayout = compositionLocalOf { WindowLayout(400.dp, 800.dp) }

/** Gouttière des écrans plein écran (formulaire, cahier de textes), hors navigation. */
@Composable
internal fun fullScreenGutter(): Dp {
    val width = LocalWindowLayout.current.width
    return ((width - ReadingWidth) / 2).coerceAtLeast(0.dp)
}

/** Couleurs des étapes (Découvrir bleu, S’entraîner orange, Se tester vert). */
internal fun phaseColors(phase: Phase): Pair<Color, Color> = when (phase) {
    Phase.ENCODAGE -> JColors.Blue50 to JColors.Blue800
    Phase.CONSOLIDATION -> JColors.Orange50 to JColors.Orange800
    Phase.RECUPERATION -> JColors.Emerald50 to JColors.Emerald800
}

/** Couleur d’une matière (#RRGGBB choisi par l’élève), ardoise si le code est illisible. */
internal fun subjectColor(hex: String): Color = parseHexColor(hex) ?: JColors.Slate500

/** `#RRGGBB` ou `#AARRGGBB` (comme `android.graphics.Color.parseColor` pour ces formes). */
internal fun parseHexColor(hex: String): Color? {
    if (!hex.startsWith("#")) return null
    val digits = hex.substring(1)
    val value = digits.toLongOrNull(16) ?: return null
    return when (digits.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> null
    }
}

/** Durée d’animation sobre, coupée si l’élève a désactivé les animations du système. */
internal fun <T> motion(durationMillis: Int = 220): FiniteAnimationSpec<T> =
    if (animationsEnabled()) tween(durationMillis) else snap()

/** Marges d’un écran à onglet : 16 dp plus les encarts du Scaffold et la gouttière des grands écrans. */
@Composable
internal fun screenPadding(contentPadding: PaddingValues, bottomExtra: Dp = JSpacing.xl): PaddingValues {
    val direction = LocalLayoutDirection.current
    val gutter = LocalScreenGutter.current
    return PaddingValues(
        start = contentPadding.calculateStartPadding(direction) + JSpacing.l + gutter,
        end = contentPadding.calculateEndPadding(direction) + JSpacing.l + gutter,
        top = contentPadding.calculateTopPadding() + JSpacing.l,
        bottom = contentPadding.calculateBottomPadding() + bottomExtra,
    )
}

/** Carte blanche bordée, rayon 24 : le conteneur standard des écrans. */
@Composable
internal fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = JShapes.card,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, JColors.Slate200),
    ) { content() }
}

/** En-tête d’écran : titre (headlineMedium), sous-titre facultatif et action à droite. */
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = JColors.Slate900,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing?.invoke(this)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(JSpacing.xs))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
        }
    }
}

/** Titre de section dans un écran (titleLarge) avec un complément discret. */
@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = JColors.Slate900)
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
        }
    }
}

/** Pastille de texte (étape, Jn, statut). */
@Composable
internal fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = modifier.clip(JShapes.badge).background(container).padding(horizontal = JSpacing.s, vertical = JSpacing.xs),
    )
}

/** Contour neutre des couleurs de matière trop claires sur blanc (contraste < 3:1). */
internal val SubjectOutlineColor = JColors.Slate500

/**
 * Règle unique de contraste des couleurs de matière (#8), partagée par l’app et l’aperçu du sélecteur :
 * la couleur brute est conservée et reçoit un contour neutre si elle est trop claire sur blanc, ou si
 * son code est illisible (`needsOutline`, comme iOS).
 */
internal fun subjectNeedsOutline(hex: String?): Boolean = hex != null && ColorWheel.needsOutline(hex)

/** Fond d’une pastille de matière (couleur brute), contour neutre si elle est trop claire. */
internal fun Modifier.subjectFill(hex: String?, shape: Shape): Modifier {
    val filled = clip(shape).background(hex?.let(::subjectColor) ?: JColors.Slate400)
    return if (subjectNeedsOutline(hex)) filled.border(1.dp, SubjectOutlineColor, shape) else filled
}

/** Pastille de couleur d’une matière, avec un contour neutre pour les teintes très claires. */
@Composable
internal fun SubjectDot(hex: String?, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).subjectFill(hex, CircleShape).clearAndSetSemantics {})
}

/** Liseré de matière au bord d’une carte (planning, aperçu du sélecteur), séparé du fond blanc s’il est trop clair. */
@Composable
internal fun SubjectStripe(hex: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxHeight().clearAndSetSemantics {}) {
        Box(Modifier.width(JSpacing.xs).fillMaxHeight().background(subjectColor(hex)))
        if (subjectNeedsOutline(hex)) Box(Modifier.width(1.dp).fillMaxHeight().background(SubjectOutlineColor))
    }
}

/** État vide : icône douce, titre, explication et actions facultatives. */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    tint: Color = JColors.Violet700,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = JSpacing.xl, vertical = JSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(JColors.Violet50),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(JSpacing.l))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(JSpacing.s))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600, textAlign = TextAlign.Center)
        if (actions != null) {
            Spacer(Modifier.height(JSpacing.l))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JSpacing.s)) { actions() }
        }
    }
}

/** Marque J-Method : le monogramme de l’icône d’application sur son carré violet. */
@Composable
internal fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f)).background(JColors.Violet700)
            .clearAndSetSemantics {},
    ) {
        Image(rememberVectorPainter(JIcons.Monogram), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}
