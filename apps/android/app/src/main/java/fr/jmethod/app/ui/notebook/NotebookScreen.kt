package fr.jmethod.app.ui.notebook

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
import fr.jmethod.app.ui.components.fullScreenGutter
import androidx.compose.foundation.layout.offset
import java.io.File
import java.time.LocalDate
import java.util.Locale

private fun dayLabel(date: LocalDate) = FrenchDates.weekdayDayMonth(date).sentenceCase()

/** Ouvre un lien `http(s)` déjà validé dans le navigateur externe, jamais dans la WebView Pronote. */
internal fun openLink(context: Context, url: String) {
    try {
        // Navigateur intégré (Custom Tabs), sans cookies ni WebView de l’app.
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/**
 * Ouvre une pièce jointe téléchargée par une URI `content://` en lecture seule. Un document actif
 * (HTML, SVG) est présenté comme texte : jamais exécuté.
 */
internal fun openFile(context: Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "fr.jmethod.app.files", file)
    val extension = file.extension.lowercase(Locale.ROOT)
    val active = extension in setOf("html", "htm", "xhtml", "svg", "xml", "js", "mht", "mhtml")
    val type = if (active) "text/plain" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        // Aucune app de lecture : proposer de transmettre le fichier (jamais son adresse Pronote).
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(send, "Aucune app ne lit ce document. L’envoyer vers…"))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(ui: NotebookUiState, viewModel: NotebookViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val state = ui.state
    val context = LocalContext.current
    BackHandler(onBack = viewModel::close)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = JColors.Slate50,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cahier de textes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = viewModel::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer le cahier de textes") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualiser le cahier de textes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        val snapshot = state.snapshot
        val subjectId = state.subjectId
        val sessions = snapshot?.sessions.orEmpty().filter { subjectId == null || NotebookViewModel.matches(it.subject, subjectId, ui.subjects) }
        val homework = snapshot?.homework.orEmpty().filter { subjectId == null || NotebookViewModel.matches(it.subject, subjectId, ui.subjects) }
        val attachmentCount = if (subjectId == null) snapshot?.attachmentCount ?: 0
        else sessions.sumOf { s -> s.contents.sumOf { it.attachments.size } } + homework.sumOf { it.attachments.size }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = JSpacing.l + fullScreenGutter(), vertical = JSpacing.l),
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
                        if (!snapshot.homeworkAvailable) {
                            Text("Le travail à faire n’est pas disponible pour ce compte.", style = MaterialTheme.typography.bodySmall, color = JColors.Slate600)
                        }
                    }
                }
                item(key = "tabs") {
                    SecondaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = Color.Transparent) {
                        Tab(selected = state.tab == NotebookTab.CONTENTS, onClick = { viewModel.setTab(NotebookTab.CONTENTS) },
                            text = { Text("Cours publiés (${sessions.size})") })
                        Tab(selected = state.tab == NotebookTab.HOMEWORK, onClick = { viewModel.setTab(NotebookTab.HOMEWORK) },
                            text = { Text("Devoirs (${homework.size})") })
                    }
                }
                if (state.tab == NotebookTab.CONTENTS) {
                    if (sessions.isEmpty()) item(key = "no-session") { Empty("Aucun contenu de cours publié sur cette période.") }
                    items(sessions, key = { "s-" + it.id }) { session ->
                        SessionCard(session, state.downloadingId, viewModel::download, { url -> openLink(context, url) }) {
                            CourseAction(ui.importStates[session.key], state.preparingSessionId == session.id, state.preparingSessionId != null,
                                onCreate = { viewModel.prepareCourse(session) }, onView = viewModel::viewCourse)
                        }
                    }
                } else {
                    if (homework.isEmpty()) item(key = "no-homework") { Empty("Aucun devoir publié sur cette période.") }
                    items(homework, key = { "h-" + it.id }) { HomeworkCard(it, state.downloadingId, viewModel::download) { url -> openLink(context, url) } }
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
private fun HomeworkCard(item: HomeworkItem, downloading: String?, onDownload: (NotebookAttachment) -> Unit, onLink: (String) -> Unit) {
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
                "Statut Pronote : ${if (item.done) "fait" else "à faire"}",
                style = MaterialTheme.typography.bodySmall, color = if (item.done) JColors.Emerald800 else JColors.Slate600,
            )
        }
        Links(item.links, onLink)
        Attachments(item.attachments, downloading, onDownload, onLink)
    }
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
