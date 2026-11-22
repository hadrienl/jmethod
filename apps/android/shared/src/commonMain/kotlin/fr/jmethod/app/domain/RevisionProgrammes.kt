package fr.jmethod.app.domain

import fr.jmethod.app.model.*
import java.time.LocalDate
import java.util.UUID

/** Feuilles personnelles et programmes : aucun appel à Pronote et aucun devoir comme entrée. */
object RevisionProgrammes {
    fun validate(rhythm: RevisionRhythm) {
        require(rhythm.id.isNotBlank() && rhythm.name.isNotBlank() && rhythm.revision > 0) { "Nomme le rythme et sa version." }
        require(rhythm.steps.all { it.id.isNotBlank() }) { "Une étape doit avoir un identifiant." }
        require(rhythm.steps.isNotEmpty() && rhythm.steps.first().offsetDays == 0) { "Le rythme doit commencer à J0." }
        require(rhythm.steps.all { it.offsetDays in 0..3650 }) { "Un jour J doit être compris entre 0 et 3650." }
        require(rhythm.steps.zipWithNext().all { (a, b) -> a.offsetDays < b.offsetDays }) { "Les jours J doivent être uniques et croissants." }
        require(rhythm.steps.map { it.id }.distinct().size == rhythm.steps.size) { "Les étapes doivent avoir des identifiants distincts." }
    }

    fun validateDocument(document: AppDocument, checkSubjects: Boolean = true) {
        require(document.schemaVersion == 2)
        require(document.courses.map { it.id }.distinct().size == document.courses.size)
        require(document.revisions.map { it.id }.distinct().size == document.revisions.size)
        require(document.programmes.map { it.id }.distinct().size == document.programmes.size)
        document.settings.rhythm?.let(::validate)
        for (programme in document.programmes) {
            require(programme.courseIds.isNotEmpty() && programme.courseIds.distinct().size == programme.courseIds.size)
            require(!checkSubjects || document.subjects.any { it.id == programme.subjectId })
            require(programme.courseIds.all { id -> document.courses.any { it.id == id && it.subjectId == programme.subjectId } })
            LocalDate.parse(programme.startDate)
            if (!programme.needsReview) validate(programme.rhythmSnapshot)
        }
        for (revision in document.revisions) {
            val programme = requireNotNull(document.programmes.firstOrNull { it.id == revision.programmeId })
            require(revision.courseIds == programme.courseIds && revision.courseId == programme.courseIds.first())
            require(revision.score == null || revision.score in 0..100)
            LocalDate.parse(revision.scheduledDate)
            revision.completedDate?.let { LocalDate.parse(it) }
            if (!programme.needsReview && revision.status != RevisionStatus.COMPLETED) require(revision.nominalOffsetDays != null && revision.rhythmStepId != null)
        }
    }

    fun selectedRhythm(settings: AppSettings): RevisionRhythm = settings.rhythm ?: RevisionRhythm.DEFAULT

    fun references(revision: Revision, courses: List<Course>): String = (revision.courseIds ?: listOf(revision.courseId))
        .mapNotNull { id -> courses.firstOrNull { it.id == id }?.displayReference }.joinToString(" + ")

    fun start(document: AppDocument, courseIds: List<String>, startDate: String, id: String = "programme-${UUID.randomUUID()}"): AppDocument {
        require(courseIds.isNotEmpty() && courseIds.distinct().size == courseIds.size) { "Sélectionne au moins une feuille." }
        val courses = courseIds.map { id -> requireNotNull(document.courses.firstOrNull { it.id == id }) { "Une feuille n’existe plus." } }
        require(courses.map { it.subjectId }.distinct().size == 1) { "Choisis des feuilles de la même matière." }
        require(courses.all { c -> document.revisions.none { it.appliesTo(c.id) && it.status != RevisionStatus.COMPLETED } }) {
            "Une feuille a déjà un programme en cours. Termine-le avant d’en commencer un autre."
        }
        require(document.programmes.none { it.id == id }) { "Ce programme existe déjà." }
        val date = LocalDate.parse(startDate)
        val rhythm = selectedRhythm(document.settings).let { it.copy(steps = it.steps.toList()) }
        validate(rhythm)
        val programme = RevisionProgramme(id, courses.first().subjectId, courseIds.toList(), startDate, rhythm, document.settings.adaptiveEnabled)
        val sessions = rhythm.steps.mapIndexed { index, step ->
            Revision("$id-${step.id}", courseIds.first(), index, "J${step.offsetDays}", date.plusDays(step.offsetDays.toLong()).toString(),
                status = RevisionStatus.PENDING, phase = step.phase, adjustedIntervalDays = if (index == 0) 0 else step.offsetDays-rhythm.steps[index-1].offsetDays,
                programmeId = id, courseIds = courseIds.toList(), rhythmStepId = step.id, nominalOffsetDays = step.offsetDays)
        }
        return document.copy(programmes = document.programmes + programme, revisions = document.revisions + sessions, schemaVersion = 2)
    }

    /** Préserve les séances déjà faites et les rattrapages ; les étapes passées sont dues, jamais validées implicitement. */
    fun reapply(document: AppDocument, programmeIds: Set<String>, rhythm: RevisionRhythm): AppDocument {
        validate(rhythm)
        var result = document
        for (programme in document.programmes.filter { it.id in programmeIds }) {
            val sessions = result.revisions.filter { it.programmeId == programme.id }
            if (sessions.none { it.status != RevisionStatus.COMPLETED }) continue
            val retained = sessions.filter { it.status == RevisionStatus.COMPLETED || it.isEmergencySession == true }
            val doneDays = retained.filter { it.status == RevisionStatus.COMPLETED && it.isEmergencySession != true }.mapNotNull { it.nominalOffsetDays }.toSet()
            val start = LocalDate.parse(programme.startDate)
            val pending = rhythm.steps.mapIndexedNotNull { index, step ->
                if (step.offsetDays in doneDays) null else Revision(
                    "${programme.id}-rhythm-${rhythm.revision}-${step.id}", programme.courseIds.first(), index, "J${step.offsetDays}",
                    start.plusDays(step.offsetDays.toLong()).toString(), status = RevisionStatus.PENDING, phase = step.phase,
                    programmeId = programme.id, courseIds = programme.courseIds, rhythmStepId = step.id, nominalOffsetDays = step.offsetDays)
            }
            result = result.copy(revisions = result.revisions.filter { it.programmeId != programme.id } + retained + pending,
                programmes = result.programmes.map { if (it.id == programme.id) it.copy(rhythmSnapshot = rhythm, needsReview = retained.any { r -> r.status != RevisionStatus.COMPLETED && r.nominalOffsetDays == null }) else it })
        }
        return result
    }

    /** Conversion à une feuille par programme : aucun regroupement arbitraire et aucune date déplacée. */
    fun migrate(document: AppDocument): AppDocument {
        require(document.schemaVersion <= 2) { "Cette sauvegarde nécessite une version plus récente de J-Method." }
        if (document.schemaVersion == 2) return document
        var sessions = document.revisions
        val programmes = document.programmes.toMutableList()
        for (course in document.courses) {
            val legacy = sessions.filter { it.courseId == course.id && it.programmeId == null }
            if (legacy.isEmpty()) continue
            val steps = legacy.filter { it.isEmergencySession != true }.sortedBy { it.stepIndex }.map { r ->
                val day = Regex("^J(\\d+)").find(r.stepLabel)?.groupValues?.get(1)?.toIntOrNull()
                RhythmStep("legacy-${r.stepIndex}", day ?: 0, r.phase)
            }.distinctBy { it.id }
            val rhythm = RevisionRhythm("legacy-${course.id}", "Rythme historique", 1, steps)
            val uncertain = runCatching { validate(rhythm) }.isFailure
            val id = "programme-legacy-${course.id}"
            programmes += RevisionProgramme(id, course.subjectId, listOf(course.id), course.date, rhythm, true, uncertain)
            sessions = sessions.map { r -> if (r in legacy) r.copy(programmeId = id, courseIds = listOf(course.id),
                rhythmStepId = "legacy-${r.stepIndex}", nominalOffsetDays = Regex("^J(\\d+)").find(r.stepLabel)?.groupValues?.get(1)?.toIntOrNull()) else r }
        }
        return document.copy(programmes = programmes, revisions = sessions, schemaVersion = 2,
            settings = document.settings.copy(rhythm = document.settings.rhythm ?: if (document.settings.intervals == DEFAULT_INTERVALS) RevisionRhythm.DEFAULT
                else RevisionRhythm("custom-legacy", "Rythme personnalisé", 1, document.settings.intervals.mapIndexed { index, day ->
                    RhythmStep("day-$day", day, AdaptiveAlgorithm.phaseForStep(index)) })))
    }
}
