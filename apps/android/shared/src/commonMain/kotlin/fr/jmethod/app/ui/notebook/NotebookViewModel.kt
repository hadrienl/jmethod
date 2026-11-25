package fr.jmethod.app.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.jmethod.app.data.AppRepository
import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookAttachment
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.HomeworkItem
import fr.jmethod.app.pronote.agendaKey
import fr.jmethod.app.pronote.agendaSourceTag
import fr.jmethod.app.model.PersonalHomework
import fr.jmethod.app.model.AgendaFollowUp
import fr.jmethod.app.model.AgendaCoverage
import fr.jmethod.app.model.CachedHomework
import fr.jmethod.app.pronote.PronoteConnectionController
import fr.jmethod.app.pronote.PronoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import fr.jmethod.app.data.AppClock
import fr.jmethod.app.documents.CourseCreator
import fr.jmethod.app.documents.CourseFiles
import fr.jmethod.app.documents.DocumentAnalysis
import fr.jmethod.app.documents.DocumentAnalyzer
import fr.jmethod.app.documents.DocType
import fr.jmethod.app.documents.NotebookCourseDraft
import fr.jmethod.app.documents.PendingDocument
import fr.jmethod.app.domain.CourseEditing
import fr.jmethod.app.domain.FrenchDates
import fr.jmethod.app.domain.NotebookCourses
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseDocument
import fr.jmethod.app.pronote.SessionEntry

enum class NotebookTab { CONTENTS, HOMEWORK }

/** État de l’aperçu. Rien n’est persisté : les identifiants de pièces jointes restent en mémoire. */
data class NotebookState(
    val loading: Boolean = false,
    val snapshot: NotebookSnapshot? = null,
    val error: String? = null,
    val refreshedAt: Instant? = null,
    val snapshotScope: String? = null,
    val tab: NotebookTab = NotebookTab.HOMEWORK,
    /** Matière J-Method choisie (depuis la fiche d’un cours) : seuls ses contenus sont affichés. */
    val subjectId: String? = null,
    val downloadingId: String? = null,
    /** Séance dont les documents sont en cours de préparation pour « Créer un cours ». */
    val preparingSessionId: String? = null,
    /** Activation de la création automatique : nombre de séances des 14 derniers jours à proposer. */
    val importOffer: Int? = null,
)

data class NotebookUiState(
    val state: NotebookState,
    val subjects: List<Subject>,
    val subjectName: String?,
    /** État d’import de chaque séance affichée, par identifiant (`sessionKey`). */
    val importStates: Map<String, NotebookCourses.ImportState>,
    val autoCreate: Boolean,
    val agendaScope: String?,
    val followUp: AgendaFollowUp,
    val urgentCount: Int,
)

sealed interface NotebookEvent {
    data object ShowAgenda : NotebookEvent
    data object HomeworkChecked : NotebookEvent
    data class Open(val file: File) : NotebookEvent
    /** Pièce jointe téléchargée depuis une fiche : visionneuse dans la feuille. */
    data class Preview(val file: File, val name: String) : NotebookEvent
    data class Message(val text: String) : NotebookEvent
    /** Formulaire de création prérempli, documents téléchargés. */
    data class CreateCourse(val draft: NotebookCourseDraft) : NotebookEvent
    data class ViewCourse(val courseId: String) : NotebookEvent
}

/**
 * Cahier de textes en lecture seule, ouvert depuis Mes cours ou la fiche d’un cours. Les fichiers
 * téléchargés vont dans le cache privé (`notebook/`), effacé à la déconnexion et au changement d’enfant.
 */
class NotebookViewModel(
    private val controller: PronoteConnectionController,
    private val repository: AppRepository,
    private val cacheDir: File,
    private val creator: CourseCreator,
    private val files: CourseFiles,
    private val clock: AppClock,
) : ViewModel() {
    /** Création automatique : une seule passe à la fois, et une lecture du cahier toutes les 2 h au plus. */
    private val importMutex = Mutex()
    private var lastAutoRead: Instant? = null
    private val state = MutableStateFlow(NotebookState())
    private var stamp: Any? = null
    private var readJob: Job? = null
    private var downloadJob: Job? = null
    /** Incrémenté à chaque réinitialisation : un résultat tardif d’une session précédente est ignoré. */
    @Volatile
    private var resetCount = 0
    private var readGeneration = 0
    private val events = Channel<NotebookEvent>(Channel.BUFFERED)
    val eventFlow: Flow<NotebookEvent> = events.receiveAsFlow()

    val uiState: StateFlow<NotebookUiState?> = combine(repository.document.filterNotNull(), state, controller.uiState) { document, current, _ ->
        val scope = controller.agendaScope()
        val data = scope?.let(document.agendaFollowUp::get) ?: AgendaFollowUp()
        val limit = clock.today().plusDays(1).toString()
        val snapshot = current.snapshot?.takeIf { current.snapshotScope == scope }
        val live = snapshot?.homework.orEmpty()
        val groups = live.groupBy { it.agendaKey() }
        val urgent = live.count { item ->
            val key = item.agendaKey()
            val tag = item.agendaSourceTag()
            val local = data.localChecks[key].takeIf { groups[key]?.size == 1 && tag != null && data.cached[key]?.sourceTag == tag }
            item.dueDate.toString() <= limit && !(local ?: (item.remoteDone == true))
        } + data.cached.values.count { row ->
            row.key !in groups && row.due <= limit && !(data.localChecks[row.key] ?: (row.remoteDone == true))
        } + data.personal.count { !it.done && it.due <= limit }
        NotebookUiState(
            state = current.copy(snapshot = snapshot),
            subjects = document.subjects,
            subjectName = current.subjectId?.let { id -> document.subjects.firstOrNull { it.id == id }?.name },
            importStates = snapshot?.sessions.orEmpty().associate {
                it.key to NotebookCourses.importState(it, document.notebookImports, document.courses)
            },
            autoCreate = document.settings.notebookAutoCreate == true,
            agendaScope = scope,
            followUp = data,
            urgentCount = urgent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        clearFiles()
        // Déconnexion ou changement d’enfant : l’aperçu et les fichiers sont effacés.
        viewModelScope.launch {
            var previous: String? = null
            var previousChild: String? = null
            controller.uiState.map { Triple(it.access, controller.agendaScope(), it.account?.selectedChildId) }
                .distinctUntilChanged().collect { (access, scope, child) ->
                if (scope != previous || child != previousChild || access == fr.jmethod.app.pronote.AccessPhase.SIGNED_OUT) reset()
                if (access == fr.jmethod.app.pronote.AccessPhase.SIGNED_OUT) {
                    runCatching { repository.update { doc ->
                        doc.copy(agendaFollowUp = doc.agendaFollowUp.mapValues { (_, value) -> AgendaFollowUp(personal = value.personal) })
                    } }
                }
                previous = scope
                previousChild = child
            }
        }
        // Après chaque synchronisation réussie au premier plan : création automatique éventuelle.
        viewModelScope.launch {
            repository.document.filterNotNull().map { it.settings.lastPronoteSync }.distinctUntilChanged().drop(1).collect { synced ->
                if (synced != null) autoImportAfterSync()
            }
        }
    }

    private fun notebookDir() = File(cacheDir, "notebook")
    /** Documents préparés pour un cours (survivent à une actualisation de l’aperçu). */
    private fun preparedDir() = File(cacheDir, "notebook-course")

    private fun clearFiles() {
        notebookDir().deleteRecursively()
        preparedDir().deleteRecursively()
    }

    private fun reset() {
        resetCount++
        readGeneration++
        readJob?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        stamp = null
        clearFiles()
        state.update { NotebookState(tab = it.tab) }
    }

    fun open(subjectId: String? = null) {
        state.update { it.copy(subjectId = subjectId, tab = NotebookTab.CONTENTS) }
        enterAgenda()
        events.trySend(NotebookEvent.ShowAgenda)
    }

    fun enterAgenda() {
        if (state.value.snapshot == null && !state.value.loading) refresh()
    }

    fun setTab(tab: NotebookTab) = state.update { it.copy(tab = tab) }
    fun setSubject(subjectId: String?) = state.update { it.copy(subjectId = subjectId) }

    fun setLocalDone(item: HomeworkItem, done: Boolean) {
        val expected = controller.agendaScope() ?: return
        val key = item.agendaKey()
        updateFollowUp(expected) { followUp ->
            val current = state.value
            val snapshot = current.snapshot?.takeIf { current.snapshotScope == expected } ?: return@updateFollowUp followUp
            val tag = item.agendaSourceTag()
            if (tag == null || followUp.cached[key]?.sourceTag != tag || snapshot.homework.none { it == item } ||
                snapshot.homework.count { it.agendaKey() == key } != 1) return@updateFollowUp followUp
            val wasDone = followUp.localChecks[key] ?: (item.remoteDone == true)
            if (wasDone == done) followUp else followUp.copy(localChecks = followUp.localChecks + (key to done))
        }
    }

    fun setCachedLocalDone(key: String, done: Boolean) {
        val expected = controller.agendaScope() ?: return
        updateFollowUp(expected) { followUp ->
            val row = followUp.cached[key] ?: return@updateFollowUp followUp
            if (row.unavailable || row.sourceTag == null) return@updateFollowUp followUp
            val wasDone = followUp.localChecks[key] ?: (row.remoteDone == true)
            if (wasDone == done) followUp else followUp.copy(localChecks = followUp.localChecks + (key to done))
        }
    }

    private suspend fun storeAgendaCache(snapshot: NotebookSnapshot, scope: String, stamp: Instant, generation: Int, request: Int) {
        if (!snapshot.homeworkAvailable) return
        val groups = snapshot.homework.groupBy { it.agendaKey() }
        val incoming = groups.mapNotNull { (key, items) ->
            val item = items.singleOrNull() ?: return@mapNotNull null
            key to CachedHomework(key, item.dueDate.toString(), item.subject, item.description, item.remoteDone,
                stamp.toString(), item.attachments.map { it.name }, sourceTag = item.agendaSourceTag())
        }.toMap()
        repository.update { doc ->
            if (generation != resetCount || request != readGeneration || controller.agendaScope() != scope) return@update doc
            val old = doc.agendaFollowUp[scope] ?: AgendaFollowUp()
            val from = snapshot.from.toString()
            val to = snapshot.to.toString()
            val history = old.cached.mapValues { (_, item) ->
                if (snapshot.homeworkComplete && item.due in from..to && item.key !in groups) item.copy(unavailable = true) else item
            }.filterKeys { (groups[it]?.size ?: 0) <= 1 }
            val checks = old.localChecks.filterKeys { key ->
                val known = history[key]
                val fresh = incoming[key]
                known?.sourceTag != null && !known.unavailable && (fresh == null || fresh.sourceTag == known.sourceTag)
            }
            val coverage = if (snapshot.homeworkComplete) {
                old.coverage + (from to AgendaCoverage(from, to, stamp.toString()))
            } else old.coverage
            doc.copy(agendaFollowUp = doc.agendaFollowUp + (scope to old.copy(cached = history + incoming, localChecks = checks, coverage = coverage)))
        }
    }

    fun savePersonal(id: String?, subjectId: String, description: String, date: String) {
        val expected = controller.agendaScope() ?: return
        val text = description.trim()
        val due = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
        if (text.isBlank() || text.length > 4000) return
        updateFollowUp(expected) { followUp ->
            if (id == null) followUp.copy(personal = followUp.personal + PersonalHomework(UUID.randomUUID().toString(), subjectId, text, due.toString()))
            else followUp.copy(personal = followUp.personal.map {
                if (it.id == id) it.copy(subjectId = subjectId, description = text, due = due.toString()) else it
            })
        }
    }

    fun deletePersonal(id: String) {
        val expected = controller.agendaScope() ?: return
        updateFollowUp(expected) { it.copy(personal = it.personal.filterNot { item -> item.id == id }) }
    }

    fun setPersonalDone(id: String, done: Boolean) {
        val expected = controller.agendaScope() ?: return
        updateFollowUp(expected) { followUp ->
            followUp.copy(personal = followUp.personal.map { if (it.id == id) it.copy(done = done) else it })
        }
    }

    private fun updateFollowUp(expected: String, change: (AgendaFollowUp) -> AgendaFollowUp) {
        val generation = resetCount
        viewModelScope.launch {
            var confirmed = false
            try {
                repository.update { document ->
                    if (generation != resetCount || controller.agendaScope() != expected) return@update document
                    val old = document.agendaFollowUp[expected] ?: AgendaFollowUp()
                    val updated = change(old)
                    confirmed = updated.localChecks.any { (key, value) -> value && old.localChecks[key] != true } ||
                        updated.personal.any { item -> item.done && old.personal.any { it.id == item.id && !it.done } }
                    document.copy(agendaFollowUp = document.agendaFollowUp + (expected to updated))
                }
                if (confirmed && generation == resetCount && controller.agendaScope() == expected) {
                    events.send(NotebookEvent.HomeworkChecked)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                events.send(NotebookEvent.Message("Le suivi du devoir n’a pas pu être enregistré."))
            }
        }
    }

    fun loadAgendaPeriod(previous: Boolean) {
        val scope = controller.agendaScope() ?: return
        val coverage = repository.document.value?.agendaFollowUp?.get(scope)?.coverage?.values.orEmpty()
        readAgenda(AgendaCoverage.adjacentAnchor(clock.today(), coverage, previous))
    }

    fun refresh() = readAgenda(null)

    private fun readAgenda(anchor: LocalDate?) {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        val expectedScope = controller.agendaScope()
        val generation = resetCount
        val request = ++readGeneration
        readJob = viewModelScope.launch {
            try {
                val read = controller.readNotebook(anchor)
                if (generation != resetCount || request != readGeneration) return@launch
                if (anchor == null) withContext(Dispatchers.IO) { clearFiles() }
                if (generation != resetCount || request != readGeneration) return@launch
                val scope = controller.agendaScope()
                if (scope != expectedScope || scope != read.agendaScope) {
                    state.update { it.copy(loading = false) }
                    return@launch
                }
                val at = clock.now()
                if (anchor == null) {
                    stamp = read.stamp
                    state.update { it.copy(snapshot = read.snapshot, refreshedAt = at, snapshotScope = scope) }
                }
                if (scope != null) {
                    try { storeAgendaCache(read.snapshot, scope, at, generation, request) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { events.send(NotebookEvent.Message("Le cache de l’Agenda n’a pas pu être enregistré.")) }
                }
                if (generation != resetCount || request != readGeneration) return@launch
                state.update { it.copy(loading = false) }
                if (anchor == null) autoImport(read.snapshot, scope, generation)
                else if (!read.snapshot.homeworkComplete) {
                    events.send(NotebookEvent.Message("Pronote n’a pas fourni une liste complète pour cette période."))
                }
            } catch (error: CancellationException) {
                if (generation == resetCount && request == readGeneration) state.update { it.copy(loading = false) }
                throw error
            } catch (error: Exception) {
                if (generation != resetCount || request != readGeneration) return@launch
                state.update { it.copy(loading = false, error = message(error, "Lecture du cahier de textes impossible. Réessaie plus tard.")) }
            }
        }
    }

    /** Lecture discrète pour les fiches (section 8.1) : au plus une tentative toutes les 5 min sans aperçu. */
    private var lastEnsure: Instant? = null

    fun ensureRead() {
        val current = state.value
        if (current.snapshot != null || current.loading) return
        val now = clock.now()
        val last = lastEnsure
        if (last != null && Duration.between(last, now) < ENSURE_INTERVAL) return
        lastEnsure = now
        refresh()
    }

    /** @param inApp vrai depuis une fiche : le fichier s’ouvre dans la visionneuse de la feuille. */
    fun download(attachment: NotebookAttachment, inApp: Boolean = false) {
        if (attachment.kind != NotebookAttachment.Kind.FILE || state.value.downloadingId != null) return
        val session = stamp ?: return
        val expectedScope = state.value.snapshotScope
        if (expectedScope != controller.agendaScope()) return
        val generation = resetCount
        state.update { it.copy(downloadingId = attachment.id) }
        downloadJob = viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    File(notebookDir(), UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, NotebookRules.saveAsName(attachment.rawName)) }
                }
                // Nom brut `L` (même vide) pour l’adresse FichiersExternes, comme pronotepy.
                val reread = controller.downloadAttachment(session, attachment.id, attachment.rawName, target, expectedScope)
                if (reread != null && generation == resetCount) {
                    // Session renouvelée avant le téléchargement : l’aperçu reprend les identifiants à jour.
                    stamp = reread.stamp
                    state.update { it.copy(snapshot = reread.snapshot, snapshotScope = reread.agendaScope) }
                }
                if (generation != resetCount || controller.agendaScope() != expectedScope) {
                    withContext(Dispatchers.IO) { target.parentFile?.deleteRecursively() }
                    return@launch
                }
                events.send(if (inApp) NotebookEvent.Preview(target, attachment.name) else NotebookEvent.Open(target))
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (generation == resetCount) {
                    events.send(NotebookEvent.Message(message(error, "Document indisponible. Actualise le cahier de textes puis réessaie.")))
                }
            } finally {
                if (generation == resetCount) state.update { it.copy(downloadingId = null) }
            }
        }
    }

    private fun message(error: Exception, fallback: String) = when (error) {
        is PronoteException -> if (error.code.startsWith("pronote-") || error.code == "http") fallback else error.message
        is IOException -> "Pronote est injoignable. Vérifie ta connexion internet, puis réessaie."
        else -> fallback
    }

    // region Cahier de textes → cours (`notebook-to-course.md`)

    /** Lecture du cahier pour la création de cours (hors écran), avec mise à jour de l’aperçu. */
    private suspend fun readForCourses(): NotebookSnapshot {
        val generation = resetCount
        val expectedScope = controller.agendaScope()
        val read = controller.readNotebook()
        if (generation != resetCount || expectedScope != controller.agendaScope() || expectedScope != read.agendaScope) {
            throw CancellationException("Le compte Pronote a changé.")
        }
        stamp = read.stamp
        state.update { it.copy(snapshot = read.snapshot, error = null, snapshotScope = read.agendaScope) }
        return read.snapshot
    }

    /**
     * Télécharge les fichiers de la séance dans l’ordre (contenus puis pièces jointes) : au plus 10
     * enregistrés, les échecs ne comptent pas (README des documents, règle 4). Chaque fichier est analysé.
     */
    private suspend fun prepareDocuments(
        session: SessionEntry,
        subjects: List<Subject>,
        expectedScope: String?,
        generation: Int,
    ): Pair<List<PendingDocument>, Int> {
        val attachments = session.contents.flatMap { it.attachments }.filter { it.kind == NotebookAttachment.Kind.FILE }.distinctBy { it.id }
        val names = NotebookCourses.subjectNames(session, subjects)
        val documents = mutableListOf<PendingDocument>()
        var failed = 0
        for (attachment in attachments) {
            if (documents.size >= NotebookCourses.MAX_DOCUMENTS) break
            try {
                val current = stamp ?: run { readForCourses(); stamp } ?: throw IOException("session")
                val target = withContext(Dispatchers.IO) {
                    File(preparedDir(), UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, NotebookRules.saveAsName(attachment.rawName)) }
                }
                val reread = controller.downloadAttachment(current, attachment.id, attachment.rawName, target, expectedScope)
                if (reread != null) {
                    stamp = reread.stamp
                    state.update { it.copy(snapshot = reread.snapshot, snapshotScope = reread.agendaScope) }
                }
                if (generation != resetCount || controller.agendaScope() != expectedScope) {
                    withContext(Dispatchers.IO) { target.parentFile?.deleteRecursively() }
                    throw CancellationException("Le compte Pronote a changé.")
                }
                val analysis = withContext(Dispatchers.Default) { DocumentAnalyzer.analyze(target, attachment.name) }
                analyses[target] = analysis
                documents += PendingDocument(
                    attachment.name, target, analysis.type ?: DocType.displayTypeOf(attachment.name),
                    NotebookCourses.extractedTitle(analysis.extraction, attachment.name, names),
                    extractable = analysis.type != null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed++
            }
        }
        return documents to failed
    }

    /** Analyses des documents préparés, par fichier temporaire. */
    private val analyses = mutableMapOf<File, DocumentAnalysis>()

    private fun draftFor(session: SessionEntry, documents: List<PendingDocument>, failed: Int, subjects: List<Subject>): NotebookCourseDraft {
        val subject = SubjectMatcher.match(session.subject, subjects)
        // Document principal : premier fichier téléchargé de type extractible (règle 2).
        val principal = documents.firstOrNull { it.extractable }
        val result = NotebookCourses.titlePipeline(
            principal?.name, principal?.let { analyses[it.file]?.extraction },
            NotebookCourses.contentTitle(session), NotebookCourses.subjectNames(session, subjects), session.date,
        )
        val notices = listOfNotNull(
            if (subject == null) "Matière « ${session.subject} » non reconnue : choisis-la ci-dessous." else null,
            when {
                failed == 1 -> "Un document n’a pas pu être téléchargé ; il reste consultable dans le cahier de textes."
                failed > 1 -> "$failed documents n’ont pas pu être téléchargés ; ils restent consultables dans le cahier de textes."
                else -> null
            },
        )
        return NotebookCourseDraft(
            sessionKey = session.key,
            ordinal = session.ordinal,
            subjectId = subject?.id,
            subjectLabel = session.subject,
            date = session.date.toString(),
            chapter = result.chapter,
            title = result.title,
            description = NotebookCourses.description(session),
            documents = documents,
            notice = notices.joinToString("\n").ifEmpty { null },
        )
    }

    /** Bouton « Créer un cours » : documents téléchargés et titre extrait avant d’ouvrir le formulaire. */
    fun prepareCourse(session: SessionEntry) {
        if (state.value.preparingSessionId != null) return
        val generation = resetCount
        val expectedScope = state.value.snapshotScope
        if (expectedScope != controller.agendaScope()) return
        state.update { it.copy(preparingSessionId = session.id) }
        viewModelScope.launch {
            try {
                // État vu au clic : si la séance est créée ou supprimée pendant la préparation, rien ne sera écrit.
                val seen = repository.current().notebookImports[session.key]
                val subjects = repository.current().subjects
                val (documents, failed) = prepareDocuments(session, subjects, expectedScope, generation)
                if (generation != resetCount || expectedScope != controller.agendaScope()) {
                    withContext(Dispatchers.IO) { documents.forEach { it.file.parentFile?.deleteRecursively() } }
                    return@launch
                }
                events.send(NotebookEvent.CreateCourse(draftFor(session, documents, failed, subjects).copy(seenImport = seen)))
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                events.send(NotebookEvent.Message(message(error, "Préparation du cours impossible. Réessaie plus tard.")))
            } finally {
                if (generation == resetCount) state.update { it.copy(preparingSessionId = null) }
            }
        }
    }

    fun viewCourse(courseId: String) {
        viewModelScope.launch { events.send(NotebookEvent.ViewCourse(courseId)) }
    }

    /**
     * Création automatique (section 4) : séances éligibles du [snapshot], une passe à la fois.
     * @param since date de départ imposée (import des 14 derniers jours à l’activation).
     */
    private suspend fun autoImport(snapshot: NotebookSnapshot, expectedScope: String?, generation: Int) {
        fun stillCurrent() = generation == resetCount && expectedScope == controller.agendaScope()
        val document = repository.current()
        if (document.settings.notebookAutoCreate != true) return
        importMutex.withLock {
            if (!stillCurrent()) return
            val current = repository.current()
            val sessions = NotebookCourses.eligible(snapshot, current.settings, current.notebookImports, current.subjects, clock.today(), current.courses)
            var created = 0
            for (session in sessions) {
                if (!stillCurrent()) return
                val (documents, failed) = try {
                    prepareDocuments(session, current.subjects, expectedScope, generation)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList<PendingDocument>() to 0
                }
                val draft = draftFor(session, documents, failed, repository.current().subjects)
                val subjectId = draft.subjectId ?: continue
                val form = CourseEditing.Form(subjectId, draft.chapter, draft.title, draft.date, draft.description)
                val result = withContext(Dispatchers.IO) { creator.create(form, draft.source, documents, CourseCreator.Expectation.Automatic, stillCurrent = ::stillCurrent) }
                withContext(Dispatchers.IO) { documents.forEach { it.file.parentFile?.deleteRecursively() } }
                if (result != null) created++
            }
            NotebookCourses.toast(created)?.let { events.send(NotebookEvent.Message(it)) }
        }
    }

    /** Après une synchronisation réussie : au plus une lecture du cahier toutes les 2 h pour ce besoin. */
    private suspend fun autoImportAfterSync() {
        if (repository.current().settings.notebookAutoCreate != true) return
        val now = clock.now()
        val last = lastAutoRead
        if (last != null && Duration.between(last, now) < AUTO_READ_INTERVAL) return
        lastAutoRead = now
        val generation = resetCount
        val expectedScope = controller.agendaScope()
        runCatching { autoImport(readForCourses(), expectedScope, generation) }
    }

    /** Réglage « Créer les cours automatiquement ». */
    fun setAutoCreate(enabled: Boolean) {
        viewModelScope.launch {
            val today = clock.today()
            repository.update {
                it.copy(settings = it.settings.copy(notebookAutoCreate = enabled, notebookAutoCreateSince = if (enabled) today.toString() else it.settings.notebookAutoCreateSince))
            }
            if (!enabled) {
                state.update { it.copy(importOffer = null) }
                return@launch
            }
            try {
                lastAutoRead = clock.now()
                val generation = resetCount
                val expectedScope = controller.agendaScope()
                val snapshot = readForCourses()
                val document = repository.current()
                if (generation != resetCount || expectedScope != controller.agendaScope()) return@launch
                val offer = NotebookCourses.backfill(snapshot, document.notebookImports, document.subjects, today, document.courses)
                if (offer.count > 0) state.update { it.copy(importOffer = offer.count) } else autoImport(snapshot, expectedScope, generation)
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                events.send(NotebookEvent.Message(message(error, "Lecture du cahier de textes impossible. Les cours seront créés à la prochaine lecture.")))
            }
        }
    }

    /** Réponse à « Importer aussi les N séances des 14 derniers jours ? ». */
    fun answerImportOffer(importPast: Boolean) {
        state.update { it.copy(importOffer = null) }
        viewModelScope.launch {
            val today = clock.today()
            if (importPast) {
                repository.update { it.copy(settings = it.settings.copy(notebookAutoCreateSince = today.minusDays(NotebookCourses.BACKFILL_DAYS).toString())) }
            }
            val current = state.value
            current.snapshot?.let { runCatching { autoImport(it, current.snapshotScope, resetCount) } }
        }
    }

    /**
     * « Joindre à ce cours » depuis une pièce jointe du cahier (section 8.5) : téléchargée, analysée (titre
     * lu) puis copiée dans les documents du cours.
     */
    fun attachToCourse(courseId: String, sessionKey: String, attachment: NotebookAttachment) {
        if (attachment.kind != NotebookAttachment.Kind.FILE || state.value.downloadingId != null) return
        val current = stamp ?: return
        val expectedScope = state.value.snapshotScope
        if (expectedScope != controller.agendaScope()) return
        val generation = resetCount
        state.update { it.copy(downloadingId = attachment.id) }
        viewModelScope.launch {
            var target: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    File(preparedDir(), UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, NotebookRules.saveAsName(attachment.rawName)) }
                }
                target = file
                controller.downloadAttachment(current, attachment.id, attachment.rawName, file, expectedScope)?.let { reread ->
                    if (generation == resetCount) {
                        stamp = reread.stamp
                        state.update { it.copy(snapshot = reread.snapshot, snapshotScope = reread.agendaScope) }
                    }
                }
                if (generation != resetCount || controller.agendaScope() != expectedScope) return@launch
                val subjects = repository.current().subjects
                val session = state.value.snapshot?.sessions?.firstOrNull { it.key == sessionKey }
                val names = session?.let { NotebookCourses.subjectNames(it, subjects) } ?: emptyList()
                val analysis = withContext(Dispatchers.Default) { DocumentAnalyzer.analyze(file, attachment.name) }
                val title = if (analysis.type != null) NotebookCourses.extractedTitle(analysis.extraction, attachment.name, names) else null
                val attached = withContext(Dispatchers.IO) {
                    creator.attach(courseId, file, attachment.name, analysis.type ?: DocType.displayTypeOf(attachment.name), title, sourceSessionKey = sessionKey)
                }
                events.send(NotebookEvent.Message(if (attached != null) "Document joint au cours." else "Ce document n’a pas pu être joint au cours."))
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (generation == resetCount) events.send(NotebookEvent.Message(message(error, "Document indisponible. Réessaie plus tard.")))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { target?.parentFile?.deleteRecursively() }
                if (generation == resetCount) state.update { it.copy(downloadingId = null) }
            }
        }
    }

    /** Document absent (restauration) : relit le cahier et le retélécharge si la séance est encore dans la fenêtre. */
    fun redownload(course: Course, document: CourseDocument) {
        viewModelScope.launch {
            try {
                val snapshot = readForCourses()
                val expectedScope = state.value.snapshotScope
                val attachment = fr.jmethod.app.domain.RevisionSession.notebookDocumentCandidates(course, document, snapshot).singleOrNull()
                if (attachment == null) {
                    events.send(NotebookEvent.Message("Ce document n’a pas été retrouvé de façon unique dans ses séances Pronote. Ouvre le cahier de textes pour le récupérer."))
                    return@launch
                }
                val target = withContext(Dispatchers.IO) {
                    File(preparedDir(), UUID.randomUUID().toString()).apply { mkdirs() }.let { File(it, NotebookRules.saveAsName(attachment.rawName)) }
                }
                val session2 = stamp ?: return@launch
                controller.downloadAttachment(session2, attachment.id, attachment.rawName, target, expectedScope)?.let { stamp = it.stamp }
                withContext(Dispatchers.IO) {
                    val updated = files.replace(document, target)
                    target.parentFile?.deleteRecursively()
                    if (updated != null) {
                        repository.update { doc ->
                            doc.copy(courses = doc.courses.map { c ->
                                if (c.id != course.id) c else c.copy(documents = c.documents?.map { if (it.id == updated.id) it.copy(size = updated.size, extractedTitle = updated.extractedTitle) else it })
                            })
                        }
                    }
                }
                events.send(NotebookEvent.Message("Document retéléchargé."))
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                events.send(NotebookEvent.Message(message(error, "Document indisponible. Réessaie plus tard.")))
            }
        }
    }

    // endregion

    companion object {
        val AUTO_READ_INTERVAL: Duration = Duration.ofHours(2)
        val ENSURE_INTERVAL: Duration = Duration.ofMinutes(5)

        /** La matière Pronote d’une séance correspond-elle à la matière J-Method ? */
        fun matches(label: String, subjectId: String, subjects: List<Subject>): Boolean = SubjectMatcher.match(label, subjects)?.id == subjectId
    }
}
