package fr.jmethod.app.data

import fr.jmethod.app.model.AppDocument

/** Migrations du document natif, appliquées au chargement. Chacune est idempotente. */
object DataMigrations {
    const val CURRENT_VERSION = 1

    /** Document d’un premier lancement : réglages et matières par défaut, aucune donnée. */
    fun emptyDocument(): AppDocument = AppDocument(initialized = true, dataVersion = CURRENT_VERSION)

    /**
     * Cours du jeu de démonstration livré avec le jalon 1 (ids fixes, aussi utilisés par la démo web).
     * Ils ne peuvent pas venir d’un utilisateur : les cours créés reçoivent un id `course-<horodatage>`.
     */
    private val legacyDemoCourseIds = setOf(
        "course-maths-1", "course-maths-2", "course-maths-3", "course-maths-5", "course-maths-14",
        "course-pc-1", "course-pc-2", "course-pc-4", "course-fr-3",
    )

    /** Créneaux d’exemple (`pronote-demo-1` à `-5`) ; une vraie synchronisation produit `pronote-<N>-<date>-<P>`. */
    private val legacyDemoEvent = Regex("^pronote-demo-\\d+$")

    fun migrate(document: AppDocument): AppDocument {
        var current = document
        if (current.dataVersion < 1) current = removeLegacyDemo(current).copy(dataVersion = 1)
        current = migrateNotebookKeys(current)
        return current
    }

    /** Clés de séance du cahier de textes : anciennes clés `N` vers `pn1:` (idempotent, aussi après un import). */
    fun migrateNotebookKeys(document: AppDocument): AppDocument {
        val migration = fr.jmethod.app.domain.NotebookCourses.migrateKeys(document.courses, document.notebookImports)
        if (migration.courses == document.courses && migration.imports == document.notebookImports) return document
        return document.copy(courses = migration.courses, notebookImports = migration.imports)
    }

    /** v1 : retire les cours, révisions (validations et rattrapages compris) et créneaux de démonstration. */
    fun removeLegacyDemo(document: AppDocument): AppDocument = document.copy(
        courses = document.courses.filter { it.id !in legacyDemoCourseIds },
        revisions = document.revisions.filter { it.courseId !in legacyDemoCourseIds },
        pronoteEvents = document.pronoteEvents.filter { !legacyDemoEvent.matches(it.id) },
    )
}
