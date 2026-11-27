package fr.jmethod.app.ui.notebook

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.key
import fr.jmethod.app.model.PersonalHomework
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.agendaKey
import fr.jmethod.app.pronote.agendaSourceTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import fr.jmethod.app.ui.components.DesktopSelection
import androidx.compose.foundation.lazy.rememberLazyListState
import fr.jmethod.app.ui.components.ScrollbarBox
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.domain.sentenceCase
import fr.jmethod.app.pronote.HomeworkItem
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookLink
import fr.jmethod.app.pronote.SessionEntry
import androidx.compose.material.icons.filled.Lock
import fr.jmethod.app.ui.theme.JColors
import fr.jmethod.app.ui.theme.JIcons
import fr.jmethod.app.ui.theme.JShapes
import fr.jmethod.app.ui.theme.JSpacing
import fr.jmethod.app.ui.components.EmptyState
import androidx.compose.foundation.layout.offset
import fr.jmethod.app.ui.platform.LocalPlatform
import java.io.File
import java.time.LocalDate
import java.util.Locale

private fun dayLabel(date: LocalDate) = FrenchDates.weekdayDayMonth(date).sentenceCase()

private enum class HomeworkFilter(val label: String) { TODO("À faire"), DONE("Faits"), ALL("Tous") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(ui: NotebookUiState, viewModel: NotebookViewModel, modifier: Modifier = Modifier) {
    val state = ui.state
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HomeworkFilter.TODO) }
    var period by rememberSaveable { mutableStateOf<Int?>(null) }
    val today = LocalDate.now(fr.jmethod.app.data.AppClock.ZONE)
    var editing by rememberSaveable(ui.agendaScope) { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable(ui.agendaScope) { mutableStateOf<String?>(null) }
    editing?.let { id ->
        key(id) {
            PersonalHomeworkEditor(ui.followUp.personal.firstOrNull { it.id == id }, ui.subjects, today,
                onDismiss = { editing = null },
                onSave = { subject, text, due -> viewModel.savePersonal(id.takeIf { it.isNotEmpty() }, subject, text, due); editing = null })
        }
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Supprimer ce devoir personnel ?") },
            confirmButton = { TextButton(onClick = { viewModel.deletePersonal(id); deleting = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } })
    }
    val platform = LocalPlatform.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = JColors.Slate50,
        topBar = {
            TopAppBar(
                title = { Text("Agenda", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser le cahier de textes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        val snapshot = state.snapshot?.takeIf { state.snapshotScope == ui.agendaScope }
        val subjectId = state.subjectId
        fun subjectMatches(subject: String) = subjectId == null || NotebookViewModel.matches(subject, subjectId, ui.subjects)
        fun matchesDate(date: LocalDate) = period?.let { date <= today.plusDays(it.toLong()) } ?: true
        val sessions = snapshot?.sessions.orEmpty().filter { item ->
            subjectMatches(item.subject) && matchesDate(item.date) && (search.isBlank() ||
                item.subject.contains(search.trim(), ignoreCase = true) ||
                item.contents.any { it.description.contains(search.trim(), ignoreCase = true) })
        }
        val byKey = snapshot?.homework.orEmpty().groupingBy(HomeworkItem::agendaKey).eachCount()
        fun canTrack(item: HomeworkItem): Boolean {
            val key = item.agendaKey()
            val tag = item.agendaSourceTag()
            return ui.agendaScope != null && state.snapshotScope == ui.agendaScope && byKey[key] == 1 &&
                tag != null && ui.followUp.cached[key]?.sourceTag == tag
        }
        fun local(item: HomeworkItem) = ui.followUp.localChecks[item.agendaKey()].takeIf { canTrack(item) }
        fun done(item: HomeworkItem) = local(item) ?: (item.remoteDone == true)
        fun statusMatches(done: Boolean) = filter == HomeworkFilter.ALL || (filter == HomeworkFilter.DONE) == done
        val personal = ui.followUp.personal.mapNotNull { item ->
            val date = runCatching { LocalDate.parse(item.due) }.getOrNull() ?: return@mapNotNull null
            val subject = ui.subjects.firstOrNull { it.id == item.subjectId }?.name ?: "Matière supprimée"
            if ((subjectId != null && subjectId != item.subjectId) || !matchesDate(date) || !statusMatches(item.done) ||
                (!item.description.contains(search.trim(), ignoreCase = true) && !subject.contains(search.trim(), ignoreCase = true))) null
            else Triple(item, date, subject)
        }.sortedBy { it.second }
        val homework = snapshot?.homework.orEmpty().filter { item ->
            subjectMatches(item.subject) && matchesDate(item.dueDate) &&
                (search.isBlank() || item.description.contains(search.trim(), ignoreCase = true) || item.subject.contains(search.trim(), ignoreCase = true)) &&
                statusMatches(done(item))
        }
        val cached = ui.followUp.cached.values.filter { item ->
            val due = runCatching { LocalDate.parse(item.due) }.getOrNull() ?: return@filter false
            item.key !in byKey && subjectMatches(item.subject) && matchesDate(due) &&
                statusMatches(ui.followUp.localChecks[item.key] ?: (item.remoteDone == true)) &&
                (item.description.contains(search.trim(), ignoreCase = true) || item.subject.contains(search.trim(), ignoreCase = true))
        }.sortedBy { it.due }
        val attachmentCount = if (subjectId == null) snapshot?.attachmentCount ?: 0
        else sessions.sumOf { s -> s.contents.sumOf { it.attachments.size } } + homework.sumOf { it.attachments.size }
        val listState = rememberLazyListState()
        ScrollbarBox(listState, Modifier.fillMaxSize().padding(padding)) {
        DesktopSelection {
LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(horizontal = JSpacing.l + fr.jmethod.app.ui.components.LocalScreenGutter.current, vertical = JSpacing.l),
                verticalArrangement = Arrangement.spacedBy(JSpacing.m),
            ) {
                item(key = "intro") {
                    // Mention courte, alignée sur iOS.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = JColors.Slate500, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(JSpacing.s))
                        Text(
                            if (ui.autoCreate) "Lecture seule : rien n’est modifié dans Pronote. Les séances deviennent des cours automatiquement (Réglages)." else "Lecture seule : rien n’est modifié dans Pronote. « Créer un cours » transforme une séance en cours J-Method.",
                            style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
                        )
                    }
                }
                if (ui.subjectName != null) {
                    item(key = "subject") {
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.setSubject(null) },
                            label = { Text(ui.subjectName) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Afficher toutes les matières", modifier = Modifier.size(18.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = JColors.Violet100, selectedLabelColor = JColors.Violet900),
                        )
                    }
                }
                if (state.error != null) {
                    item(key = "error") {
                        Column(
                            Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Rose50).padding(JSpacing.l)
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Text(state.error, color = JColors.Rose800, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(JSpacing.s))
                            OutlinedButton(onClick = viewModel::refresh, shape = JShapes.control, modifier = Modifier.heightIn(min = 48.dp)) { Text("Réessayer") }
                        }
                    }
                }
                if (state.loading) {
                    item(key = "loading") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = JColors.Violet700, trackColor = JColors.Violet100)
                            Spacer(Modifier.width(JSpacing.m))
                            Text("Lecture du cahier de textes…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item(key = "tabs") {
                    SecondaryTabRow(selectedTabIndex = if (state.tab == NotebookTab.HOMEWORK) 0 else 1, containerColor = Color.Transparent) {
                        Tab(selected = state.tab == NotebookTab.HOMEWORK, onClick = { search = ""; viewModel.setTab(NotebookTab.HOMEWORK) },
                            text = { Text("Devoirs") })
                        Tab(selected = state.tab == NotebookTab.CONTENTS, onClick = { search = ""; viewModel.setTab(NotebookTab.CONTENTS) },
                            text = { Text("Contenus et documents") })
                    }
                }
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                        OutlinedTextField(search, { search = it }, label = { Text("Rechercher") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                            item { FilterChip(subjectId == null, { viewModel.setSubject(null) }, label = { Text("Toutes les matières") }) }
                            items(ui.subjects, key = { it.id }) { subject ->
                                FilterChip(subjectId == subject.id, { viewModel.setSubject(subject.id) }, label = { Text(subject.name) })
                            }
                        }
                        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                            item { FilterChip(period == null, { period = null }, label = { Text("Toute la période lue") }) }
                            item { FilterChip(period == 7, { period = 7 }, label = { Text("Jusqu’à 7 jours") }) }
                            if (state.tab == NotebookTab.HOMEWORK) items(HomeworkFilter.entries) {
                                FilterChip(filter == it, { filter = it }, label = { Text(it.label) })
                            }
                        }
                    }
                }
                if (state.tab == NotebookTab.HOMEWORK) {
                    item(key = "agenda-periods") {
                        var expanded by remember { mutableStateOf(false) }
                        Column {
                            TextButton(onClick = { expanded = !expanded }) { Text("Périodes Pronote chargées") }
                            if (expanded) {
                                val ranges = ui.followUp.coverage.values.sortedBy { it.from }
                                if (ranges.isEmpty()) Text("Aucune période complète enregistrée.")
                                ranges.forEach {
                                    val from = runCatching { FrenchDates.numeric(LocalDate.parse(it.from)) }.getOrDefault(it.from)
                                    val to = runCatching { FrenchDates.numeric(LocalDate.parse(it.to)) }.getOrDefault(it.to)
                                    val read = runCatching { FrenchDates.numeric(LocalDate.parse(it.observedAt.take(10))) }.getOrDefault(it.observedAt.take(10))
                                    Text("Du $from au $to · lu le $read")
                                }
                                val canRead = !state.loading && ui.agendaScope != null
                                if (ranges.any { today.toString() in it.from..it.to }) {
                                    TextButton(onClick = { viewModel.loadAgendaPeriod(previous = true) }, enabled = canRead) {
                                        Text("Charger la période précédente")
                                    }
                                    TextButton(onClick = { viewModel.loadAgendaPeriod(previous = false) }, enabled = canRead) {
                                        Text("Charger la période suivante")
                                    }
                                } else {
                                    TextButton(onClick = viewModel::refresh, enabled = canRead) { Text("Charger la période courante") }
                                }
                            }
                        }
                    }
                    item(key = "new-personal") {
                        Button(onClick = { editing = "" }, enabled = ui.agendaScope != null && ui.subjects.isNotEmpty()) {
                            Text("Ajouter un devoir personnel")
                        }
                    }
                    if (personal.isNotEmpty()) item(key = "personal-title") {
                        Text("Devoirs personnels", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                    items(personal, key = { "personnel-" + it.first.id }) { (item, date, subject) ->
                        Card {
                            Text("$subject · ${if (date < today && !item.done) "En retard · " else ""}${dayLabel(date)}")
                            Text(item.description)
                            LocalHomeworkCheck(item.done) { viewModel.setPersonalDone(item.id, it) }
                            Row {
                                TextButton(onClick = { editing = item.id }) { Text("Modifier") }
                                TextButton(onClick = { deleting = item.id }) { Text("Supprimer") }
                            }
                        }
                    }
                }
                if (state.tab == NotebookTab.HOMEWORK && cached.isNotEmpty()) {
                    item(key = "cache-title") { Text("Devoirs connus · cache local", style = MaterialTheme.typography.titleMedium) }
                    items(cached, key = { "cache-" + it.key }) { item ->
                        val done = ui.followUp.localChecks[item.key] ?: (item.remoteDone == true)
                        Card {
                            Text(item.subject + " · " + (if (!done && item.due < today.toString()) "En retard · " else "") + dayLabel(LocalDate.parse(item.due)))
                            Text(item.description)
                            Text(if (item.unavailable) "Source indisponible sur la dernière lecture" else "Dernière lecture : " + item.observedAt.take(10),
                                style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                            Text("Statut Pronote connu : " + when (item.remoteDone) { true -> "fait"; false -> "à faire"; null -> "inconnu" })
                            if (item.documentNames.isNotEmpty()) Text("Documents à actualiser : " + item.documentNames.joinToString())
                            if (!item.unavailable && item.sourceTag != null) {
                                LocalHomeworkCheck(done) { viewModel.setCachedLocalDone(item.key, it) }
                            }
                        }
                    }
                }
                if (snapshot != null) {
                    item(key = "summary") {
                        Column(
                            Modifier.fillMaxWidth().clip(JShapes.inner).background(JColors.Violet50)
                                .border(1.dp, JColors.Violet200, JShapes.inner).padding(JSpacing.l)
                                .semantics(mergeDescendants = true) {},
                        ) {
                            Text(
                                "${sessions.size} ${if (sessions.size > 1) "séances publiées" else "séance publiée"} · " +
                                    "${homework.size} ${if (homework.size > 1) "devoirs" else "devoir"} · " +
                                    "$attachmentCount ${if (attachmentCount > 1) "pièces jointes et liens" else "pièce jointe ou lien"}",
                                style = MaterialTheme.typography.titleSmall, color = JColors.Violet900,
                            )
                            Text(
                                "Du ${FrenchDates.numeric(snapshot.from)} au ${FrenchDates.numeric(snapshot.to)}",
                                style = MaterialTheme.typography.bodySmall, color = JColors.Slate600,
                            )
                            if (snapshot.skippedEntries > 0) {
                                Spacer(Modifier.height(JSpacing.xs))
                                Text(
                                    "${snapshot.skippedEntries} ${if (snapshot.skippedEntries > 1) "entrées sans date exploitable n’ont pas été affichées" else "entrée sans date exploitable n’a pas été affichée"}.",
                                    style = MaterialTheme.typography.bodySmall, color = JColors.Amber900,
                                )
                            }
                            state.refreshedAt?.let {
                                Text("Lu le " + java.time.format.DateTimeFormatter.ofPattern("dd/MM à HH:mm")
                                    .withZone(fr.jmethod.app.data.AppClock.ZONE).format(it),
                                    style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                            }
                            if (!snapshot.homeworkAvailable) {
                                Text("Le travail à faire n’est pas disponible pour ce compte.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                            } else if (!snapshot.homeworkComplete) {
                                Text("La liste des devoirs est incomplète. Les devoirs déjà connus sont conservés.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                            }
                        }
                    }
                    if (state.tab == NotebookTab.CONTENTS) {
                        if (sessions.isEmpty()) item(key = "no-session") { Empty("Aucun contenu de cours publié sur cette période.") }
                        items(sessions, key = { "s-" + it.id }) { session ->
                            SessionCard(session, state.downloadingId, viewModel::download, { url -> platform.openLink(url) }) {
                                CourseAction(ui.importStates[session.key], state.preparingSessionId == session.id, state.preparingSessionId != null,
                                    onCreate = { viewModel.prepareCourse(session) }, onView = viewModel::viewCourse)
                            }
                        }
                    } else {
                        if (homework.isEmpty() && personal.isEmpty() && cached.isEmpty() && snapshot.homeworkComplete && !state.loading && state.error == null) {
                            item(key = "no-homework") { Empty("Aucun devoir ne correspond aux filtres de la période chargée.") }
                        }
                        homework.groupBy { if (it.dueDate < today && !done(it)) null else it.dueDate }
                            .toList().sortedBy { it.first ?: LocalDate.MIN }.forEach { (date, group) ->
                                item(key = "date-$date") { Text(date?.let(::dayLabel) ?: "En retard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                                items(group) { item ->
                                    HomeworkCard(item, state.downloadingId, viewModel::download, platform::openLink) {
                                        if (canTrack(item)) LocalHomeworkCheck(done(item)) { viewModel.setLocalDone(item, it) }
                                    }
                                }
                            }
                    }
                }
            }
}
        }
    }
}

@Composable
private fun Empty(text: String) {
    EmptyState(JIcons.Notebook, "Rien de publié", text)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(JShapes.card).background(Color.White)
            .border(1.dp, JColors.Slate200, JShapes.card).padding(JSpacing.l),
        verticalArrangement = Arrangement.spacedBy(JSpacing.s),
    ) { content() }
}

@Composable
private fun SessionCard(
    session: SessionEntry,
    downloading: String?,
    onDownload: (NotebookAttachment) -> Unit,
    onLink: (String) -> Unit,
    action: @Composable () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Text(session.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(dayLabel(session.date), style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
        session.contents.forEach { content -> NotebookContentBlock(content, downloading, onDownload, onLink) }
        action()
    }
}

/** « Créer un cours » (jamais importée ou cours supprimé) ou « Voir le cours » (cours créé). */
@Composable
private fun CourseAction(
    importState: fr.jmethod.app.domain.NotebookCourses.ImportState?,
    preparing: Boolean,
    busy: Boolean,
    onCreate: () -> Unit,
    onView: (String) -> Unit,
) {
    when (importState) {
        is fr.jmethod.app.domain.NotebookCourses.ImportState.View -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald700, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cours créé", style = MaterialTheme.typography.bodySmall, color = JColors.Emerald800, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCreate, enabled = !busy) { Text("Autre feuille") }
            OutlinedButton(onClick = { onView(importState.courseId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Voir le cours") }
        }
        else -> Button(
            onClick = onCreate,
            enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (preparing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Préparation du cours…")
            } else {
                Text("Créer un cours")
            }
        }
    }
}

@Composable
internal fun NotebookContentBlock(content: NotebookContent, downloading: String?, onDownload: (NotebookAttachment) -> Unit, onLink: (String) -> Unit) {
    Column(Modifier.padding(top = JSpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (content.category.isNotEmpty()) Text(content.category, style = MaterialTheme.typography.labelMedium, color = JColors.Violet800)
        Text(content.title.ifEmpty { "Séance publiée sans titre" }, style = MaterialTheme.typography.titleMedium)
        if (content.description.isNotEmpty()) {
            Text(content.description, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
        }
        Links(content.links, onLink)
        Attachments(content.attachments, downloading, onDownload, onLink)
    }
}

@Composable
private fun HomeworkCard(item: HomeworkItem, downloading: String?, onDownload: (NotebookAttachment) -> Unit, onLink: (String) -> Unit, tracking: @Composable () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Text(item.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Pour le ${dayLabel(item.dueDate).replaceFirstChar { it.lowercase() }}", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
        }
        Text(item.description.ifEmpty { "Travail à faire" }, style = MaterialTheme.typography.bodyMedium, color = JColors.Slate700)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            if (item.done) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = JColors.Emerald700, modifier = Modifier.size(16.dp))
            if (item.done) Spacer(Modifier.width(JSpacing.xs))
            Text(
                when (item.remoteDone) {
                    true -> "Statut Pronote : fait"
                    false -> "Statut Pronote : à faire"
                    null -> "Statut Pronote : inconnu"
                },
                style = MaterialTheme.typography.bodySmall, color = if (item.done) JColors.Emerald800 else JColors.Slate600,
            )
        }
        Links(item.links, onLink)
        Attachments(item.attachments, downloading, onDownload, onLink)
        tracking()
    }
}

@Composable
private fun LocalHomeworkCheck(done: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "Fait sur cet appareil" else "Suivi sur cet appareil", Modifier.weight(1f))
        Switch(done, onChange, modifier = Modifier.semantics { contentDescription = "Suivi sur cet appareil" })
    }
}

@Composable
private fun PersonalHomeworkEditor(item: PersonalHomework?, subjects: List<Subject>, today: LocalDate,
    onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var subject by rememberSaveable { mutableStateOf(item?.subjectId ?: subjects.firstOrNull()?.id) }
    var text by rememberSaveable { mutableStateOf(item?.description.orEmpty()) }
    var due by rememberSaveable { mutableStateOf(item?.due ?: today.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Ajouter un devoir personnel" else "Modifier le devoir personnel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(JSpacing.m)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(JSpacing.s)) {
                    items(subjects, key = { it.id }) {
                        FilterChip(subject == it.id, { subject = it.id }, label = { Text(it.name) })
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text("Consigne") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(due, { due = it }, label = { Text("Échéance (AAAA-MM-JJ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = subject != null && text.isNotBlank() && text.trim().length <= 4000 &&
                runCatching { LocalDate.parse(due) }.isSuccess,
                onClick = { subject?.let { onSave(it, text, due) } }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun Links(links: List<NotebookLink>, onLink: (String) -> Unit) {
    links.forEach { link ->
        LinkButton(link.label, onClick = { onLink(link.url) })
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        shape = JShapes.control,
        contentPadding = PaddingValues(horizontal = JSpacing.s),
        modifier = Modifier.offset(x = (-8).dp).heightIn(min = 48.dp).semantics { contentDescription = "Ouvrir le lien dans le navigateur : $label" },
    ) {
        Icon(JIcons.Link, contentDescription = null, tint = JColors.Violet700, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(JSpacing.s))
        Text(label, color = JColors.Violet800, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}

@Composable
private fun Attachments(items: List<NotebookAttachment>, downloading: String?, onDownload: (NotebookAttachment) -> Unit, onLink: (String) -> Unit) {
    items.forEach { attachment ->
        when (attachment.kind) {
            NotebookAttachment.Kind.LINK -> attachment.url?.let { url -> LinkButton(attachment.name) { onLink(url) } }
            NotebookAttachment.Kind.FILE -> Button(
                onClick = { onDownload(attachment) },
                enabled = downloading == null,
                shape = JShapes.control,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = JColors.Violet50, contentColor = JColors.Violet800),
                contentPadding = PaddingValues(horizontal = JSpacing.m, vertical = JSpacing.s),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Ouvrir la pièce jointe : ${attachment.name}" },
            ) {
                if (downloading == attachment.id) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = JColors.Violet800)
                } else {
                    Icon(JIcons.Attachment, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(JSpacing.s))
                Text(attachment.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
