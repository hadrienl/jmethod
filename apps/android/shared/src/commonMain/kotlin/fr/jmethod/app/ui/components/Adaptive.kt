package fr.jmethod.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.jmethod.app.ui.platform.LocalPlatform
import fr.jmethod.app.ui.platform.SystemBackHandler
import fr.jmethod.app.ui.platform.VerticalScrollbar
import fr.jmethod.app.ui.platform.escapeToBack

/**
 * Présentation d’une feuille selon la fenêtre : feuille du bas (compacte, comme sur téléphone),
 * panneau latéral modal ou dialogue centré (fenêtre moyenne ou étendue), volet ancré à côté de la
 * liste (deux volets, choisi par `JMethodApp`).
 */
enum class SheetMode { BOTTOM, SIDE, DIALOG, PANE }

/** Volet imposé par le parent (deux volets) ; `null` : choix selon la largeur de la fenêtre. */
val LocalSheetMode = compositionLocalOf<SheetMode?> { null }

/** Largeur du volet de détail en mode deux volets. */
val DetailPaneWidth = 460.dp

/**
 * Feuille adaptative : mêmes réglages qu’avant sur fenêtre compacte (`ModalBottomSheet` plein écran),
 * panneau latéral ou dialogue sur grande fenêtre, volet ancré si le parent le demande.
 * @param gesturesEnabled glisser pour fermer (désactivé pendant la lecture d’un document).
 * @param dismissOnBack Retour (ou Échap) ferme la feuille ; faux quand une page intérieure gère le retour.
 * @param wideMode présentation sur grande fenêtre : [SheetMode.SIDE] ou [SheetMode.DIALOG].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(
    onDismissRequest: () -> Unit,
    gesturesEnabled: Boolean = true,
    dismissOnBack: Boolean = true,
    wideMode: SheetMode = SheetMode.SIDE,
    content: @Composable ColumnScope.() -> Unit,
) {
    val desktop = LocalPlatform.current.isDesktop
    val mode = LocalSheetMode.current ?: if (LocalWindowLayout.current.sidePanels) wideMode else SheetMode.BOTTOM
    when (mode) {
        SheetMode.BOTTOM -> ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
            sheetGesturesEnabled = gesturesEnabled,
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissOnBack),
        ) {
            // Ordinateur en fenêtre étroite : Échap ferme aussi la feuille.
            if (desktop) {
                SystemBackHandler(enabled = dismissOnBack, onBack = onDismissRequest)
                Column(Modifier.escapeToBack()) { content() }
            } else {
                content()
            }
        }
        SheetMode.PANE -> Column(Modifier.fillMaxSize().background(Color.White)) {
            SystemBackHandler(enabled = dismissOnBack, onBack = onDismissRequest)
            content()
        }
        SheetMode.SIDE, SheetMode.DIALOG -> Dialog(
            onDismissRequest = onDismissRequest,
            // Ordinateur : Échap passe par la pile des retours (document ouvert, puis la feuille).
            properties = DialogProperties(dismissOnBackPress = dismissOnBack && !desktop, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
        ) {
            SystemBackHandler(enabled = dismissOnBack, onBack = onDismissRequest)
            BoxWithConstraints(Modifier.fillMaxSize().escapeToBack()) {
                // Voile : un clic à côté ferme la feuille, comme sur téléphone.
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
                        .semantics { contentDescription = "Fermer"; role = Role.Button },
                )
                if (mode == SheetMode.SIDE) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .width(minOf(560.dp, maxWidth * 0.9f)),
                    ) {
                        Column(Modifier.fillMaxSize().padding(top = 16.dp), content = content)
                    }
                } else {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(28.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier.align(Alignment.Center).widthIn(max = 600.dp).fillMaxWidth(0.9f)
                            .heightIn(max = maxHeight * 0.9f),
                    ) {
                        Column(Modifier.padding(top = 24.dp), content = content)
                    }
                }
            }
        }
    }
}

/**
 * Contenu défilant et sa barre de défilement (visible sur ordinateur). [barPadding] écarte la barre
 * des barres de l’écran (bandeau hors ligne, navigation) sans toucher au contenu.
 */
@Composable
fun ScrollbarBox(state: ScrollState, modifier: Modifier = Modifier, barPadding: PaddingValues = PaddingValues(0.dp), content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        VerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = barPadding.calculateTopPadding(), bottom = barPadding.calculateBottomPadding()))
    }
}

@Composable
fun ScrollbarBox(state: LazyListState, modifier: Modifier = Modifier, barPadding: PaddingValues = PaddingValues(0.dp), content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        VerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(top = barPadding.calculateTopPadding(), bottom = barPadding.calculateBottomPadding()))
    }
}

/** Texte sélectionnable à la souris sur ordinateur (cahier de textes, vue « Lecture ») ; inchangé sur Android. */
@Composable
fun DesktopSelection(content: @Composable () -> Unit) {
    if (LocalPlatform.current.isDesktop) androidx.compose.foundation.text.selection.SelectionContainer(content = content) else content()
}
