package fr.jmethod.app.data

import fr.jmethod.app.domain.AdaptiveAlgorithm
import fr.jmethod.app.domain.SubjectMatcher
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.DEFAULT_SETTINGS
import fr.jmethod.app.model.DEFAULT_SUBJECTS
import fr.jmethod.app.model.PronoteEvent
import fr.jmethod.app.model.RevisionStatus
import fr.jmethod.app.model.Subject
import java.time.LocalDate
import kotlin.math.floor
import kotlin.random.Random

/** Port de `initializeDefaultDataIfEmpty` (`src/lib/storage.ts`) : jeu de démonstration du premier lancement. */
object DemoData {

    fun create(today: LocalDate, random: () -> Double = { Random.nextDouble() }): AppDocument {
        val todayStr = today.toString()
        fun ago(days: Long) = today.minusDays(days).toString()
        val yesterday = ago(1)
        val twoDaysAgo = ago(2)
        val sevenDaysAgo = ago(7)
        val fourteenDaysAgo = ago(14)
        val twentyFiveDaysAgo = ago(25)

        val courses = listOf(
            Course("course-maths-1", "maths", "Arithmétique", 1, "Division euclidienne & divisibilité", twentyFiveDaysAgo,
                "Définition, quotient, reste et critères de divisibilité par 2, 3, 5, 9.", twentyFiveDaysAgo),
            Course("course-maths-2", "maths", "Arithmétique", 2, "Nombres premiers & décomposition", fourteenDaysAgo,
                "Liste des nombres premiers < 30, algorithme de décomposition en facteurs.", fourteenDaysAgo),
            Course("course-maths-3", "maths", "Arithmétique", 3, "Fractions irréductibles", sevenDaysAgo,
                "Simplification de fractions par décomposition en facteurs premiers.", sevenDaysAgo),
            Course("course-maths-5", "maths", "Arithmétique", 5, "Problèmes de PGCD dans la vie réelle", twoDaysAgo,
                "Découpage de dalles et composition de paquets identiques.", twoDaysAgo),
            Course("course-maths-14", "maths", "Arithmétique", 14, "Algorithme d’Euclide avancé", todayStr,
                "Méthode des soustractions successives et divisions en chaîne.", todayStr),
            Course("course-pc-1", "physique-chimie", "Poids et Masse", 1, "Distinction entre masse et poids", sevenDaysAgo,
                "Relation P = m × g, unités kg et Newton.", sevenDaysAgo),
            Course("course-pc-2", "physique-chimie", "Poids et Masse", 2, "Mesure avec dynamomètre", yesterday,
                "Utilisation du dynamomètre et étalonnage du ressort.", yesterday),
            Course("course-pc-4", "physique-chimie", "Poids et Masse", 4, "Gravitation universelle et astres", todayStr,
                "Comparaison de g sur la Terre et sur la Lune.", todayStr),
            Course("course-fr-3", "francais", "Grammaire", 3, "Les propositions subordonnées relatives", twoDaysAgo,
                "Pronoms relatifs simples et composés, antécédent.", twoDaysAgo),
        )

        val revisions = courses
            .flatMap { AdaptiveAlgorithm.generateInitialRevisions(it, DEFAULT_SETTINGS.intervals) }
            .map { rev ->
                // Comme le web : passé = terminé à la date prévue ; aujourd’hui et futur = en attente.
                if (rev.scheduledDate < todayStr) {
                    rev.copy(
                        status = RevisionStatus.COMPLETED,
                        completedDate = rev.scheduledDate,
                        score = floor(65 + random() * 25).toInt(),
                    )
                } else {
                    rev
                }
            }

        return AppDocument(
            settings = DEFAULT_SETTINGS,
            subjects = DEFAULT_SUBJECTS,
            courses = courses,
            revisions = revisions,
            pronoteEvents = samplePronoteEvents(today.plusDays(1), DEFAULT_SUBJECTS),
            initialized = true,
        )
    }

    private data class SampleSlot(
        val uid: String, val start: String, val end: String, val summary: String,
        val location: String, val description: String, val cancelled: Boolean,
    )

    /**
     * Équivalent de `parseICalFeed(generateSamplePronoteFeed(), DEFAULT_SUBJECTS)` : cours du lendemain,
     * conforme au flux du soir. Même appariement et même règle d’annulation que le parseur web.
     */
    fun samplePronoteEvents(date: LocalDate, subjects: List<Subject>): List<PronoteEvent> {
        val slots = listOf(
            SampleSlot("pronote-demo-1", "08:30", "09:30", "MATHEMATIQUES", "Salle 204", "M. Dupont - Arithmétique & calcul littéral", false),
            SampleSlot("pronote-demo-2", "09:30", "10:30", "PHYSIQUE-CHIMIE", "Labo Sciences 2", "Mme Martin - Poids et Masse", false),
            SampleSlot("pronote-demo-3", "10:45", "11:45", "FRANCAIS", "Salle 105", "Mme Bernard - Grammaire & Analyse", false),
            SampleSlot("pronote-demo-4", "13:30", "14:30", "HISTOIRE-GEOGRAPHIE", "Salle 301", "M. Lambert - La Première Guerre mondiale", false),
            SampleSlot("pronote-demo-5", "14:30", "15:30", "[ANNULÉ] ANGLAIS", "Salle 108", "Professeur absent", true),
        )
        return slots.map { slot ->
            val matched = SubjectMatcher.match(slot.summary, subjects)
            val cancelled = slot.cancelled || slot.summary.lowercase().contains("annulé")
            PronoteEvent(
                id = slot.uid,
                subjectName = matched?.name ?: slot.summary.replace(Regex("\\[annulé]", RegexOption.IGNORE_CASE), "").trim(),
                matchedSubjectId = matched?.id,
                date = date.toString(),
                startTime = slot.start,
                endTime = slot.end,
                room = slot.location,
                teacher = slot.description,
                isCancelled = cancelled,
                isCourseCreated = false,
            )
        }
    }
}
