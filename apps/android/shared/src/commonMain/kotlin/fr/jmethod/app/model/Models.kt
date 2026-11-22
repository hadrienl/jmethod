package fr.jmethod.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modèle de données au schéma du web (`src/types/index.ts`). Les noms de propriétés sont
 * identiques (camelCase) pour que la sauvegarde JSON soit échangeable entre web, iOS et Android.
 * Les champs optionnels du web sont nullables et omis à l’écriture (`explicitNulls = false`).
 */
@Serializable
enum class Phase {
    @SerialName("encodage") ENCODAGE,
    @SerialName("consolidation") CONSOLIDATION,
    @SerialName("recuperation") RECUPERATION,
}

@Serializable
enum class RevisionStatus {
    @SerialName("pending") PENDING,
    @SerialName("completed") COMPLETED,
    @SerialName("overdue") OVERDUE,
}

@Serializable
data class Subject(
    val id: String,
    val name: String,
    val color: String,
    val description: String? = null,
    val isCustom: Boolean? = null,
)

@Serializable
data class Course(
    val id: String,
    val subjectId: String,
    val chapter: String,
    val courseNumber: Int,
    val title: String,
    /** YYYY-MM-DD, date du cours (J0). */
    val date: String,
    val description: String? = null,
    val createdAt: String,
    /** Séance du cahier de textes d’origine (`notebook-to-course.md`), absente pour un cours saisi. */
    val source: CourseSource? = null,
    /** Documents copiés dans le stockage privé ; l’export n’en contient que les métadonnées. */
    val documents: List<CourseDocument>? = null,
    val reference: String? = null,
    val sources: List<CourseSource>? = null,
) {
    val displayReference: String get() = reference?.takeIf { it.isNotBlank() } ?: "C$courseNumber"
}

@Serializable
data class CourseSource(
    /** `pronote-notebook`. */
    val kind: String,
    val sessionKey: String,
    val subjectLabel: String,
    /** YYYY-MM-DD, date de la séance. */
    val date: String,
    /** Rang de la séance parmi celles de même date et même matière (clé `pn1:`). */
    val ordinal: Int? = null,
)

@Serializable
data class CourseDocument(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    /** Chemin relatif au stockage privé : `documents/<courseId>/<docId>.<ext>`. */
    val file: String,
    val extractedTitle: String? = null,
    /** Séance Pronote du fichier, indépendante des autres ressources de la feuille. */
    val sourceSessionKey: String? = null,
)

/** Séance déjà traitée : `created` (cours existant) ou `dismissed` (cours supprimé, jamais recréé automatiquement). */
@Serializable
data class NotebookImport(
    val status: String,
    val courseId: String? = null,
    /** Horodatage ISO UTC. */
    val at: String,
) {
    companion object {
        const val CREATED = "created"
        const val DISMISSED = "dismissed"
    }
}

@Serializable
data class Revision(
    val id: String,
    val courseId: String,
    val stepIndex: Int,
    val stepLabel: String,
    /** YYYY-MM-DD, date cible de la séance. */
    val scheduledDate: String,
    /** YYYY-MM-DD, date réelle de validation. */
    val completedDate: String? = null,
    val score: Int? = null,
    val status: RevisionStatus,
    val phase: Phase,
    val adjustedIntervalDays: Int? = null,
    val isEmergencySession: Boolean? = null,
    val programmeId: String? = null,
    val courseIds: List<String>? = null,
    val rhythmStepId: String? = null,
    val nominalOffsetDays: Int? = null,
    val originRevisionId: String? = null,
) {
    val phaseLabel: String get() = if (programmeId == null) phase.info.label else when (phase) {
        Phase.ENCODAGE -> "Encodage"
        Phase.CONSOLIDATION -> "Consolidation"
        Phase.RECUPERATION -> "Récupération"
    }
    fun appliesTo(id: String): Boolean = id in (courseIds ?: listOf(courseId))
}

@Serializable
data class PronoteEvent(
    val id: String,
    val subjectName: String,
    val matchedSubjectId: String? = null,
    /** YYYY-MM-DD, heure murale de l’établissement. */
    val date: String,
    /** HH:mm */
    val startTime: String,
    /** HH:mm, vide si Pronote ne permet pas de la déduire. */
    val endTime: String,
    val room: String? = null,
    val teacher: String? = null,
    val isCancelled: Boolean,
    val isCourseCreated: Boolean? = null,
)

@Serializable
data class AppSettings(
    val intervals: List<Int> = DEFAULT_INTERVALS,
    val sensitivity: Double = 0.4,
    val emergencyThreshold: Int = 30,
    val pronoteIcalUrl: String = "",
    val lastPronoteSync: String? = null,
    val soundEnabled: Boolean = true,
    val userName: String = "Élève",
    val userGrade: String = "3ème",
    /** « Créer les cours automatiquement » ; `null` = désactivé (clé absente des sauvegardes web). */
    val notebookAutoCreate: Boolean? = null,
    /** YYYY-MM-DD : seules les séances à partir de cette date sont créées automatiquement. */
    val notebookAutoCreateSince: String? = null,
    val rhythm: RevisionRhythm? = null,
    val adaptiveEnabled: Boolean = true,
)

/**
 * Document unique persistant de l’app. Il reprend les clés du stockage web et du format
 * d’export (`version`, `courses`, `revisions`, `subjects`, `settings`) et y ajoute
 * `pronoteEvents`, `initialized` et `dataVersion`. Le jeton Pronote n’y figure jamais.
 */
@Serializable
data class AppDocument(
    val version: String = "1.0",
    val settings: AppSettings = AppSettings(),
    val subjects: List<Subject> = DEFAULT_SUBJECTS,
    val courses: List<Course> = emptyList(),
    val revisions: List<Revision> = emptyList(),
    val pronoteEvents: List<PronoteEvent> = emptyList(),
    val initialized: Boolean = false,
    /** Version des données natives (migrations). Absente des documents antérieurs : 0. */
    val dataVersion: Int = 0,
    /** Séances du cahier de textes déjà transformées en cours, par `sessionKey`. */
    val notebookImports: Map<String, NotebookImport> = emptyMap(),
    val programmes: List<RevisionProgramme> = emptyList(),
    val schemaVersion: Int = 1,
)
