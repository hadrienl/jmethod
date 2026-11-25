package fr.jmethod.app.ui.settings

import fr.jmethod.app.domain.SuccessSound
import androidx.compose.material3.Switch
import fr.jmethod.app.ui.components.LocalContentWidth
import fr.jmethod.app.ui.components.TwoColumnWidth
import fr.jmethod.app.ui.components.ScrollbarBox
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import fr.jmethod.app.ui.platform.LocalPlatform
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import fr.jmethod.app.domain.SettingsLogic
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.PronoteUiState
import fr.jmethod.app.ui.pronote.PronoteAccountSection
import fr.jmethod.app.ui.pronote.PronoteSyncInfo
import fr.jmethod.app.ui.pronote.PronoteViewModel
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.theme.tabular
import fr.jmethod.app.ui.components.BrandMark
import fr.jmethod.app.ui.components.LocalScreenGutter
import fr.jmethod.app.ui.components.Pill
import fr.jmethod.app.ui.components.ScreenHeader
import fr.jmethod.app.ui.components.SectionTitle
import fr.jmethod.app.ui.components.SurfaceCard
import fr.jmethod.app.ui.components.screenPadding
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import fr.jmethod.app.ui.components.subjectFill
import fr.jmethod.app.domain.ColorWheel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Taille maximale d’une sauvegarde lue (protection contre un fichier démesuré). */
private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    // Même structure pour toutes les sections (Compte Pronote compris) : titre et explication au-dessus, réglages dans la carte.
    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.m)) {
        SectionTitle(title, Modifier.padding(top = JSpacing.s), supporting = subtitle)
        SurfaceCard {
            Column(Modifier.padding(JSpacing.l), verticalArrangement = Arrangement.spacedBy(JSpacing.m)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
    pronoteViewModel: PronoteViewModel,
    pronote: PronoteUiState,
    syncInfo: PronoteSyncInfo?,
    contentPadding: PaddingValues,
    /** « Créer les cours automatiquement » et proposition d’import des 14 derniers jours. */
    autoCreate: Boolean = false,
    importOffer: Int? = null,
    onAutoCreateChange: (Boolean) -> Unit = {},
    onImportOffer: (Boolean) -> Unit = {},
) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()

    val exporter = platform.rememberExportLauncher { sink ->
        if (sink == null) return@rememberExportLauncher
        scope.launch {
            val ok = runCatching {
                val text = viewModel.exportText()
                withContext(Dispatchers.IO) {
                    sink.open().use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }.isSuccess
            viewModel.exported(ok)
        }
    }
    val importer = platform.rememberImportLauncher { source ->
        if (source == null) return@rememberImportLauncher
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    source.open().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (out.size() <= MAX_IMPORT_BYTES) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        if (out.size() > MAX_IMPORT_BYTES) null else out.toString("UTF-8")
                    }
                }.getOrNull()
            }
            viewModel.importText(text)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.exportFlow.collect { event -> exporter(event.fileName) }
    }

    val draft = ui.draft
    val gutter = LocalScreenGutter.current
    Box(Modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        ScrollbarBox(scrollState, Modifier.fillMaxSize(), barPadding = contentPadding) {
        Column(
                Modifier.fillMaxSize().verticalScroll(scrollState)
                    .padding(screenPadding(contentPadding, if (draft.dirty) 120.dp else JSpacing.xl)),
                verticalArrangement = Arrangement.spacedBy(JSpacing.l),
            ) {
                ScreenHeader("Réglages")
                val twoColumns = LocalContentWidth.current >= TwoColumnWidth && LocalDensity.current.fontScale <= 1.3f
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val sectionWidth = if (twoColumns) (maxWidth - JSpacing.l) / 2 else maxWidth
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(JSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(JSpacing.l),
                        maxItemsInEachRow = if (twoColumns) 2 else 1,
                    ) {
                        Column(Modifier.width(sectionWidth), verticalArrangement = Arrangement.spacedBy(JSpacing.l)) {
                            PronoteAccountSection(pronoteViewModel, pronote, syncInfo)
                            NotebookSection(autoCreate, onAutoCreateChange)
                            SuccessSoundSettings(draft.soundEnabled, viewModel::setSoundEnabled)
                        }
                        Column(Modifier.width(sectionWidth), verticalArrangement = Arrangement.spacedBy(JSpacing.l)) {
                            Algorithm(draft, viewModel)
                            RhythmSettingsPanel(ui, viewModel)
                        }
                        Box(Modifier.width(sectionWidth)) { Colors(ui.subjects, onPick = viewModel::openSubjectColor) }
                        Column(Modifier.width(sectionWidth), verticalArrangement = Arrangement.spacedBy(JSpacing.l)) {
                            Backup(ui, onExport = viewModel::requestExport, onImport = { importer() })
                            About()
                        }
                    }
                }
            }
        }
        if (draft.dirty) {
            val direction = LocalLayoutDirection.current
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                shape = JShapes.inner,
                border = BorderStroke(1.dp, JColors.Slate200),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(
                        start = contentPadding.calculateStartPadding(direction) + JSpacing.m + gutter,
                        end = contentPadding.calculateEndPadding(direction) + JSpacing.m + gutter,
                        bottom = contentPadding.calculateBottomPadding() + JSpacing.m,
                    ),
            ) {
                Row(Modifier.padding(start = JSpacing.l, end = JSpacing.s, top = JSpacing.s, bottom = JSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Text("Modifications non enregistrées", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::discard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") }
                    Button(onClick = viewModel::save, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enregistrer") }
                }
            }
        }
    }

    SubjectColorPickerHost(viewModel, ui.subjects)
    importOffer?.let { ImportOfferDialog(it, onImportOffer) }
    ui.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Remplacer tes données ?") },
            text = {
                Text(
                    "Tes ${ui.courseCount} cours et ${ui.revisionCount} séances seront remplacés par ceux de la sauvegarde " +
                        "(${pending.courses.size} cours, ${pending.revisions.size} séances)." +
                        (if (pending.subjects != null) " Les matières seront aussi remplacées." else "") +
                        (if (pending.settings != null) " Les réglages seront remplacés." else "") +
                        " Ton emploi du temps Pronote est conservé. Cette action est définitive.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remplacer mes données", color = JColors.Rose700, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Annuler") } },
            containerColor = Color.White,
        )
    }
}

@Composable
private fun Algorithm(draft: SettingsDraft, viewModel: SettingsViewModel) {
    Section("Algorithme adaptatif", "Point pivot à 50 : règle l’espacement des séances et le seuil de rattrapage.") {
        Column(Modifier.fillMaxWidth().clip(JShapes.control).background(JColors.Slate50).padding(JSpacing.m)) {
            // Titre puis pastille : rien ne se chevauche avec un grand texte.
            Text(
                "Sensibilité de l’espacement : ${SettingsLogic.jsNumber(draft.sensitivity)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(JSpacing.xs))
            Pill(SettingsLogic.sensitivityLabel(draft.sensitivity), JColors.Violet100, JColors.Violet800)
            val steps = ((SettingsLogic.SENSITIVITY_MAX - SettingsLogic.SENSITIVITY_MIN) / SettingsLogic.SENSITIVITY_STEP).roundToInt() - 1
            Slider(
                value = draft.sensitivity.toFloat(),
                onValueChange = { viewModel.setSensitivity(it.toDouble()) },
                valueRange = SettingsLogic.SENSITIVITY_MIN.toFloat()..SettingsLogic.SENSITIVITY_MAX.toFloat(),
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = JColors.Violet700, activeTrackColor = JColors.Violet700, inactiveTrackColor = JColors.Violet100,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Sensibilité de l’espacement"
                    stateDescription = "${SettingsLogic.jsNumber(draft.sensitivity).replace('.', ',')}, ${SettingsLogic.sensitivityLabel(draft.sensitivity)}"
                },
            )
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Doux ±10%", style = MaterialTheme.typography.labelSmall, color = JColors.Slate600)
                Text("Équilibré ±40%", style = MaterialTheme.typography.labelSmall, color = JColors.Violet800)
                Text("Agressif ±80%", style = MaterialTheme.typography.labelSmall, color = JColors.Slate600)
            }
        }
        Column(Modifier.fillMaxWidth().clip(JShapes.control).background(JColors.Slate50).padding(JSpacing.m)) {
            Text(SettingsLogic.thresholdLabel(draft.emergencyThreshold), style = MaterialTheme.typography.titleSmall)
            Slider(
                value = draft.emergencyThreshold.toFloat(),
                onValueChange = { viewModel.setThreshold((it / SettingsLogic.THRESHOLD_STEP).roundToInt() * SettingsLogic.THRESHOLD_STEP) },
                valueRange = SettingsLogic.THRESHOLD_MIN.toFloat()..SettingsLogic.THRESHOLD_MAX.toFloat(),
                steps = (SettingsLogic.THRESHOLD_MAX - SettingsLogic.THRESHOLD_MIN) / SettingsLogic.THRESHOLD_STEP - 1,
                colors = SliderDefaults.colors(
                    thumbColor = JColors.Rose700, activeTrackColor = JColors.Rose700, inactiveTrackColor = JColors.Rose50,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Seuil de rattrapage"
                    stateDescription = "moins de ${draft.emergencyThreshold} sur 100"
                },
            )
            Text(
                "Toute note inférieure à ce seuil programme une séance de rattrapage le lendemain (J+1).",
                style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intervals(draft: SettingsDraft, viewModel: SettingsViewModel) {
    val focus = LocalFocusManager.current
    Section("Intervalles des J", "Paliers de révision appliqués à chaque nouveau cours. Les cours existants gardent leur calendrier.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s), verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
            draft.intervals.forEach { value ->
                Row(
                    Modifier.heightIn(min = 48.dp).clip(JShapes.control).background(JColors.Violet50).border(1.dp, JColors.Violet200, JShapes.control)
                        .padding(start = JSpacing.m, end = if (SettingsLogic.removeButtonShown(value)) 0.dp else JSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("J$value", style = MaterialTheme.typography.titleSmall.tabular(), color = JColors.Violet900)
                    if (SettingsLogic.removeButtonShown(value)) {
                        IconButton(onClick = { viewModel.removeInterval(value) }, enabled = draft.intervals.size > 2, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Retirer le palier J$value", modifier = Modifier.size(18.dp), tint = JColors.Violet800)
                        }
                    }
                }
            }
        }
        if (draft.intervals.size <= 2) {
            Text("Il faut garder au moins deux paliers.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft.newInterval,
                onValueChange = viewModel::setNewInterval,
                label = { Text("Nouveau J") },
                placeholder = { Text("ex. : 120") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.addInterval(); focus.clearFocus() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(JSpacing.s))
            OutlinedButton(onClick = viewModel::addInterval, shape = JShapes.control, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(JSpacing.s))
                Text("Ajouter")
            }
        }
        TextButton(onClick = viewModel::resetIntervals, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rétablir les valeurs initiales") }
    }
}

@Composable
private fun Colors(subjects: List<Subject>, onPick: (String) -> Unit) {
    Section(
        "Les couleurs de tes matières",
        "Choisis les couleurs qui t’aident à te repérer. Une couleur s’applique quand tu l’enregistres dans son sélecteur, sans toucher aux autres réglages.",
    ) {
        if (subjects.isEmpty()) Text("Aucune matière.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate600)
        subjects.forEach { subject ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(JShapes.control)
                    .clickable(onClickLabel = "Changer la couleur", role = Role.Button) { onPick(subject.id) }
                    .padding(horizontal = JSpacing.xs, vertical = JSpacing.xs)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).subjectFill(subject.color, JShapes.badge))
                Spacer(Modifier.width(JSpacing.m))
                Text(subject.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(ColorWheel.normalized(subject.color) ?: subject.color.uppercase(), style = MaterialTheme.typography.bodySmall.tabular(), color = JColors.Slate500)
                Spacer(Modifier.width(JSpacing.xs))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = JColors.Slate400)
            }
        }
    }
}

/** Sélecteur ouvert : seul cet hôte se recompose pendant un geste sur la roue. */
@Composable
private fun SubjectColorPickerHost(viewModel: SettingsViewModel, subjects: List<Subject>) {
    val picker by viewModel.colorPicker.collectAsStateWithLifecycle()
    val draft = picker ?: return
    // Matière supprimée pendant l’édition : le sélecteur reste ouvert avec un message, sans rien recréer.
    val subject = subjects.firstOrNull { it.id == draft.subjectId }
    SubjectColorPicker(
        draft = draft,
        subjectName = subject?.name ?: draft.subjectName,
        removed = draft.removed || subject == null,
        onEdit = viewModel::editSubjectColor,
        onCancel = viewModel::cancelSubjectColor,
        onSave = viewModel::saveSubjectColor,
    )
}

@Composable
private fun Backup(ui: SettingsUiState, onExport: () -> Unit, onImport: () -> Unit) {
    Section(
        "Sauvegarde et export",
        "Tes données sont enregistrées uniquement sur ${LocalPlatform.current.device}. Exporte une copie pour les garder ou les ouvrir dans J-Method sur le web.",
    ) {
        Text("${ui.courseCount} cours et ${ui.revisionCount} séances enregistrés.", style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
        Button(onClick = onExport, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Exporter mes données (JSON)")
        }
        OutlinedButton(onClick = onImport, shape = JShapes.control, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Restaurer une sauvegarde")
        }
        Text(
            "Une restauration remplace tes cours et tes séances ; une confirmation est demandée avant.",
            style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
        )
    }
}

@Composable
private fun About() {
    val platform = LocalPlatform.current
    val version = platform.appVersion
    Section("À propos") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(48.dp)
            Spacer(Modifier.width(JSpacing.m))
            Column {
                Text("J-Method", style = MaterialTheme.typography.titleMedium)
                Text("Version $version", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
            }
        }
        Text(
            "J-Method lit Pronote en lecture seule : emploi du temps et cahier de textes. Rien n’est jamais modifié ni envoyé dans Pronote.",
            style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700,
        )
        Text(
            "Tes cours, tes séances et tes réglages restent sur ${platform.device}, sans compte ni serveur J-Method." +
                platform.backupNote.let { if (it.isEmpty()) "" else " $it" },
            style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700,
        )
    }
}

/** Cahier de textes : création automatique des cours (désactivée par défaut). */
@Composable
private fun SuccessSoundSettings(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val platform = LocalPlatform.current
    Section("Sons") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Effets de réussite", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = onChange,
                modifier = Modifier.semantics { contentDescription = "Effets de réussite" })
        }
        TextButton(enabled = enabled, onClick = { platform.playSuccessSound(SuccessSound.EVENING) }) {
            Text("Écouter Impulsion précise")
        }
    }
}

@Composable
private fun NotebookSection(autoCreate: Boolean, onChange: (Boolean) -> Unit) {
    Section(
        "Cahier de textes",
        "Une séance publiée dans Pronote peut devenir un cours J-Method, avec ses documents. Rien n’est modifié dans Pronote.",
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = autoCreate, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Créer les cours automatiquement", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "À chaque lecture du cahier de textes, les séances du jour et des jours passés deviennent des cours, si leur matière est reconnue. Un cours supprimé n’est jamais recréé.",
                    style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
                )
            }
            Spacer(Modifier.width(JSpacing.m))
            androidx.compose.material3.Switch(checked = autoCreate, onCheckedChange = null)
        }
    }
}

@Composable
internal fun ImportOfferDialog(count: Int, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Importer les séances récentes ?") },
        text = { Text(if (count > 1) "Importer aussi les $count séances des 14 derniers jours ?" else "Importer aussi la séance des 14 derniers jours ?") },
        confirmButton = { TextButton(onClick = { onAnswer(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Importer") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Seulement à partir d’aujourd’hui") } },
        containerColor = Color.White,
    )
}
