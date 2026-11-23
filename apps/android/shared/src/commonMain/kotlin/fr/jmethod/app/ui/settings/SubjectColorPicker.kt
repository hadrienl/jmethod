package fr.jmethod.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.jmethod.app.domain.ColorWheel
import fr.jmethod.app.domain.SubjectColorDraft
import fr.jmethod.app.model.Phase
import fr.jmethod.app.ui.components.Pill
import fr.jmethod.app.ui.components.SubjectDot
import fr.jmethod.app.ui.components.SubjectOutlineColor
import fr.jmethod.app.ui.components.SubjectStripe
import fr.jmethod.app.ui.components.phaseColors
import fr.jmethod.app.ui.components.subjectColor
import fr.jmethod.app.ui.components.subjectNeedsOutline
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Sélecteur de couleur d’une matière (#8), commun à Android et à l’ordinateur : anneau de teinte,
 * carré saturation/luminosité, code hexadécimal et aperçu. Tout reste dans [draft] (brouillon du
 * ViewModel) jusqu’à [onSave] ; [onCancel] (Annuler, fermeture, Échap, Retour) l’abandonne.
 *
 * Dialogue centré sur ordinateur ; sur téléphone il prend la largeur disponible et défile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectColorPicker(
    draft: SubjectColorDraft,
    subjectName: String,
    /** Matière supprimée pendant l’édition : rien ne peut être enregistré. */
    removed: Boolean,
    onEdit: ((SubjectColorDraft) -> SubjectColorDraft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val canSave = draft.canSave && !removed
    val message = draft.failure ?: if (removed) SubjectColorDraft.REMOVED_MESSAGE else null
    val save by rememberUpdatedState { if (canSave) onSave() }
    val cancel by rememberUpdatedState(onCancel)
    Dialog(
        onDismissRequest = onCancel,
        // Un toucher à côté n’abandonne pas le brouillon ; Échap et Retour, si.
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = Color.White,
            shape = JShapes.card,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(JSpacing.l).widthIn(max = 440.dp).fillMaxWidth()
                .semantics { paneTitle = "Couleur de la matière" },
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(JSpacing.xl)
                    // Entrée non consommée (contrôles de la roue, champ) : enregistrer si le code est valide.
                    // Les boutons gardent leur propre Entrée. Échap abandonne le brouillon, comme Retour.
                    .onKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when {
                            enter && event.type == KeyEventType.KeyUp -> { save(); true }
                            event.key == Key.Escape && event.type == KeyEventType.KeyDown -> { cancel(); true }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(JSpacing.l),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                        Text("Couleur de la matière", style = MaterialTheme.typography.titleLarge, color = JColors.Slate900)
                        Text(subjectName, style = MaterialTheme.typography.bodyLarge, color = JColors.Slate600)
                    }
                    IconButton(onClick = onCancel, enabled = !draft.saving, modifier = Modifier.size(48.dp).focusOutline(JShapes.pill)) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer sans enregistrer", tint = JColors.Slate700)
                    }
                }
                WheelControls(draft, onEdit, Modifier.align(Alignment.CenterHorizontally))
                ChosenColor(draft, onEdit)
                Preview(draft.hex, subjectName)
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium, color = JColors.Rose700, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(JSpacing.s, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(JSpacing.s),
                ) {
                    OutlinedButton(
                        onClick = onCancel, enabled = !draft.saving, shape = JShapes.control,
                        modifier = Modifier.heightIn(min = 48.dp).focusOutline(JShapes.control),
                    ) { Text(if (removed) "Fermer" else "Annuler") }
                    Button(
                        onClick = onSave, enabled = canSave, shape = JShapes.control,
                        modifier = Modifier.heightIn(min = 48.dp).focusOutline(JShapes.control),
                    ) { Text(if (draft.saving) "Enregistrement…" else if (draft.failure != null && !removed) "Réessayer" else "Enregistrer") }
                }
            }
        }
    }
}

/** Contour de focus visible (clavier) autour d’un bouton. */
@Composable
private fun Modifier.focusOutline(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this.then(if (focused) Modifier.border(2.dp, JColors.Violet700, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
}

private enum class WheelPart { HUE, SATURATION, VALUE }

/** Géométrie de la roue en pixels, recalculée à chaque dessin et chaque geste (redimensionnement, densité). */
private class WheelGeometry(side: Float, density: Density) {
    val center = Offset(side / 2, side / 2)
    val outer: Float
    val thickness: Float
    val inner: Float
    val middle: Float
    val square: Rect
    private val ringSlop: Float
    private val squareSlop: Float

    init {
        with(density) {
            // Marge pour le contour de focus et le débord des curseurs.
            outer = side / 2 - 8.dp.toPx()
            thickness = 28.dp.toPx()
            ringSlop = 10.dp.toPx()
            squareSlop = 6.dp.toPx()
            inner = outer - thickness
            middle = outer - thickness / 2
            val half = ((inner - 10.dp.toPx()) / sqrt(2f)).coerceAtLeast(1f)
            square = Rect(center.x - half, center.y - half, center.x + half, center.y + half)
        }
    }

    /** Zone touchée : le carré (avec une petite marge), sinon l’anneau ; `null` ailleurs (geste ignoré). */
    fun zone(point: Offset): WheelPart? {
        val inSquare = point.x in (square.left - squareSlop)..(square.right + squareSlop) &&
            point.y in (square.top - squareSlop)..(square.bottom + squareSlop)
        if (inSquare) return WheelPart.SATURATION
        val distance = hypot(point.x - center.x, point.y - center.y)
        return if (distance in (inner - ringSlop)..(outer + ringSlop)) WheelPart.HUE else null
    }
}

/** Couleurs du dégradé circulaire : le balayage part de 3 h dans le sens horaire, la teinte 0 (rouge) est en haut. */
private val RingColors = (0..36).map { Color.hsv(((it * 10f) + 90f) % 360f, 1f, 1f) }

@Composable
private fun WheelControls(draft: SubjectColorDraft, onEdit: ((SubjectColorDraft) -> SubjectColorDraft) -> Unit, modifier: Modifier = Modifier) {
    val edit by rememberUpdatedState(onEdit)
    var focused by remember { mutableStateOf<WheelPart?>(null) }
    val hueFocus = remember { FocusRequester() }
    val saturationFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val hue = draft.hue
    val saturation = draft.saturation
    val value = draft.value
    val current = subjectColor(draft.hex)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, 300.dp)
            val sidePx = with(density) { side.toPx() }
            val geometry = WheelGeometry(sidePx, density)
            val squareSide = with(density) { geometry.square.width.toDp() }
            Box(
                Modifier.size(side).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val shape = WheelGeometry(minOf(size.width, size.height).toFloat(), this)
                        // Un geste reste attaché au contrôle où il a commencé ; hors des zones, il est ignoré.
                        val part = shape.zone(down.position) ?: return@awaitEachGesture
                        down.consume()
                        if (down.type == PointerType.Mouse) runCatching { (if (part == WheelPart.HUE) hueFocus else saturationFocus).requestFocus() }
                        fun apply(point: Offset) {
                            if (part == WheelPart.HUE) {
                                ColorWheel.hue((point.x - shape.center.x).toDouble(), (point.y - shape.center.y).toDouble())
                                    ?.let { h -> edit { it.withHue(h) } }
                            } else {
                                val (s, v) = ColorWheel.square(
                                    (point.x - shape.square.left).toDouble(), (point.y - shape.square.top).toDouble(),
                                    shape.square.width.toDouble(), shape.square.height.toDouble(),
                                )
                                edit { it.withSquare(s, v) }
                            }
                        }
                        apply(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            if (change.positionChanged()) { apply(change.position); change.consume() }
                        }
                    }
                },
            ) {
                Canvas(Modifier.size(side)) {
                    val g = WheelGeometry(size.minDimension, this)
                    drawCircle(Brush.sweepGradient(RingColors, g.center), radius = g.middle, center = g.center, style = Stroke(g.thickness))
                    val hueColor = Color.hsv(hue.toFloat(), 1f, 1f)
                    val topLeft = g.square.topLeft
                    val squareSize = Size(g.square.width, g.square.height)
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor), startX = g.square.left, endX = g.square.right), topLeft, squareSize)
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = g.square.top, endY = g.square.bottom), topLeft, squareSize)
                    drawRect(JColors.Slate300, topLeft, squareSize, style = Stroke(1.dp.toPx()))
                    val focusStroke = Stroke(2.5.dp.toPx())
                    when (focused) {
                        WheelPart.HUE -> drawCircle(JColors.Violet700, radius = g.outer + 4.dp.toPx(), center = g.center, style = focusStroke)
                        WheelPart.SATURATION, WheelPart.VALUE -> {
                            val gap = 4.dp.toPx()
                            drawRect(JColors.Violet700, Offset(topLeft.x - gap, topLeft.y - gap), Size(squareSize.width + 2 * gap, squareSize.height + 2 * gap), style = focusStroke)
                        }
                        null -> Unit
                    }
                    val (dx, dy) = ColorWheel.ringDirection(hue)
                    cursor(Offset(g.center.x + dx.toFloat() * g.middle, g.center.y + dy.toFloat() * g.middle), hueColor, g.thickness / 2 - 3.dp.toPx())
                    val (x, y) = ColorWheel.squarePoint(saturation, value, g.square.width.toDouble(), g.square.height.toDouble())
                    cursor(Offset(g.square.left + x.toFloat(), g.square.top + y.toFloat()), current, 10.dp.toPx())
                }
                // Contrôles accessibles (lecteur d’écran, clavier) superposés à la roue : teinte sur l’anneau,
                // saturation et luminosité sur le carré. Ils ne captent pas le pointeur.
                Box(
                    Modifier.size(side)
                        .focusRequester(hueFocus)
                        .onFocusChanged { if (it.isFocused) focused = WheelPart.HUE else if (focused == WheelPart.HUE) focused = null }
                        .onKeyEvent { event -> arrowStep(event, 1.0, 10.0) { step -> edit { it.stepHue(step) } } }
                        .semantics {
                            contentDescription = "Teinte"
                            stateDescription = "${hue.roundToInt() % 360} degrés, ${hueName(hue)}"
                            progressBarRangeInfo = ProgressBarRangeInfo(hue.toFloat().coerceIn(0f, 360f), 0f..360f)
                            setProgress { target -> edit { it.withHue(target.toDouble()) }; true }
                        }
                        .focusable(),
                )
                Box(
                    Modifier.offset { IntOffset(geometry.square.left.roundToInt(), geometry.square.top.roundToInt()) }.size(squareSide),
                ) {
                    Box(
                        Modifier.size(squareSide)
                            .focusRequester(saturationFocus)
                            .onFocusChanged { if (it.isFocused) focused = WheelPart.SATURATION else if (focused == WheelPart.SATURATION) focused = null }
                            .onKeyEvent { event -> arrowStep(event, 0.01, 0.1) { step -> edit { it.stepSaturation(step) } } }
                            .semantics {
                                contentDescription = "Saturation"
                                stateDescription = percent(saturation)
                                progressBarRangeInfo = ProgressBarRangeInfo((saturation * 100).toFloat(), 0f..100f)
                                setProgress { target -> edit { it.withSquare(target / 100.0, it.value) }; true }
                            }
                            .focusable(),
                    )
                    Box(
                        Modifier.size(squareSide)
                            .onFocusChanged { if (it.isFocused) focused = WheelPart.VALUE else if (focused == WheelPart.VALUE) focused = null }
                            .onKeyEvent { event -> arrowStep(event, 0.01, 0.1) { step -> edit { it.stepValue(step) } } }
                            .semantics {
                                contentDescription = "Luminosité"
                                stateDescription = percent(value)
                                progressBarRangeInfo = ProgressBarRangeInfo((value * 100).toFloat(), 0f..100f)
                                setProgress { target -> edit { it.withSquare(it.saturation, target / 100.0) }; true }
                            }
                            .focusable(),
                    )
                }
            }
        }
        Spacer(Modifier.height(JSpacing.s))
        // Valeurs lisibles ; le contrôle actif au clavier est mis en avant (les contrôles s’annoncent eux-mêmes).
        val highlight = SpanStyle(color = JColors.Violet800, fontWeight = FontWeight.Bold)
        Text(
            buildAnnotatedString {
                fun part(kind: WheelPart, text: String) = if (focused == kind) withStyle(highlight) { append(text) } else append(text)
                part(WheelPart.HUE, "Teinte ${hue.roundToInt() % 360}°")
                append(" · ")
                part(WheelPart.SATURATION, "Saturation ${percent(saturation)}")
                append(" · ")
                part(WheelPart.VALUE, "Luminosité ${percent(value)}")
            },
            style = MaterialTheme.typography.bodySmall.tabular(), color = JColors.Slate600,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (focused != null) {
            Text(
                "Flèches pour ajuster, Maj pour aller plus vite.",
                style = MaterialTheme.typography.bodySmall, color = JColors.Slate500,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** Flèches droite/haut pour augmenter, gauche/bas pour diminuer ; Maj : pas plus grand. */
private fun arrowStep(event: KeyEvent, step: Double, bigStep: Double, apply: (Double) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val direction = when (event.key) {
        Key.DirectionRight, Key.DirectionUp -> 1
        Key.DirectionLeft, Key.DirectionDown -> -1
        else -> return false
    }
    apply(direction * if (event.isShiftPressed) bigStep else step)
    return true
}

private fun percent(fraction: Double) = "${(fraction * 100).roundToInt()} %"

private fun hueName(hue: Double): String = when {
    hue < 15 || hue >= 345 -> "rouge"
    hue < 45 -> "orange"
    hue < 70 -> "jaune"
    hue < 160 -> "vert"
    hue < 195 -> "cyan"
    hue < 255 -> "bleu"
    hue < 290 -> "violet"
    else -> "rose"
}

/** Curseur à double contour (sombre, blanc, sombre) : visible sur blanc, noir et couleurs saturées. */
private fun DrawScope.cursor(at: Offset, fill: Color, radius: Float) {
    drawCircle(fill, radius, at)
    drawCircle(Color.White, radius, at, style = Stroke(3.dp.toPx()))
    drawCircle(JColors.Slate900, radius + 2.25.dp.toPx(), at, style = Stroke(1.5.dp.toPx()))
    drawCircle(JColors.Slate900, radius - 2.dp.toPx(), at, style = Stroke(1.dp.toPx()))
}

@Composable
private fun ChosenColor(draft: SubjectColorDraft, onEdit: ((SubjectColorDraft) -> SubjectColorDraft) -> Unit) {
    // Copie locale du champ : la saisie garde son curseur ; le champ ne reprend le brouillon qu’après un geste ou une validation.
    var field by remember(draft.subjectId) { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length))) }
    LaunchedEffect(draft.subjectId, draft.textRevision) {
        if (field.text != draft.text) field = TextFieldValue(draft.text, TextRange(draft.text.length))
    }
    var hadFocus by remember { mutableStateOf(false) }
    val error = draft.textError
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        Text("Couleur choisie", style = MaterialTheme.typography.titleSmall, color = JColors.Slate900)
        Row(verticalAlignment = Alignment.Top) {
            // Échantillon toujours bordé ; contour plus marqué pour les couleurs trop claires (même règle que l’app).
            val outline = subjectNeedsOutline(draft.hex)
            Box(
                Modifier.padding(top = 8.dp).size(48.dp).background(subjectColor(draft.hex), JShapes.control)
                    .border(if (outline) 1.5.dp else 1.dp, if (outline) SubjectOutlineColor else JColors.Slate300, JShapes.control)
                    .clearAndSetSemantics {},
            )
            Spacer(Modifier.width(JSpacing.m))
            OutlinedTextField(
                value = field,
                onValueChange = { next ->
                    field = next
                    if (next.text != draft.text) onEdit { it.withText(next.text) }
                },
                label = { Text("Code hexadécimal") },
                placeholder = { Text("#2563EB") },
                singleLine = true,
                readOnly = draft.saving,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodyLarge.tabular(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onEdit { it.committed() } }),
                modifier = Modifier.weight(1f)
                    .semantics { if (error != null) error(error) }
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus) { hadFocus = false; onEdit { d -> d.committed() } }
                    },
            )
        }
    }
}

/** Aperçu avec les composants réels (liseré et pastille de matière, badge J), sans créer de séance. */
@Composable
private fun Preview(hex: String, subjectName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
        Text("Aperçu", style = MaterialTheme.typography.titleSmall, color = JColors.Slate900)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(JShapes.inner).background(Color.White)
                .border(1.dp, JColors.Slate200, JShapes.inner)
                .clearAndSetSemantics { contentDescription = "Aperçu : J3, $subjectName, C1 + C2, Encodage" },
        ) {
            SubjectStripe(hex)
            Column(Modifier.weight(1f).padding(JSpacing.m), verticalArrangement = Arrangement.spacedBy(JSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("J3", JColors.Slate100, JColors.Slate700)
                    Spacer(Modifier.width(JSpacing.s))
                    SubjectDot(hex, 12.dp)
                    Spacer(Modifier.width(JSpacing.s))
                    Text(subjectName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = JColors.Slate900, modifier = Modifier.weight(1f, fill = false))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("C1 + C2", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                    Spacer(Modifier.width(JSpacing.s))
                    val (container, content) = phaseColors(Phase.ENCODAGE)
                    Pill("Encodage", container, content)
                }
            }
        }
    }
}
