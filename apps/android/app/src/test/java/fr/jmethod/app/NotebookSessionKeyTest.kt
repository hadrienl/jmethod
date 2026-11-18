package fr.jmethod.app

import fr.jmethod.app.data.DataMigrations
import fr.jmethod.app.domain.NotebookCourses
import fr.jmethod.app.model.AppDocument
import fr.jmethod.app.model.AppSettings
import fr.jmethod.app.model.Course
import fr.jmethod.app.model.CourseSource
import fr.jmethod.app.model.NotebookImport
import fr.jmethod.app.model.Subject
import fr.jmethod.app.pronote.NotebookContent
import fr.jmethod.app.pronote.NotebookRules
import fr.jmethod.app.pronote.NotebookSnapshot
import fr.jmethod.app.pronote.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Clé de séance stable entre sessions Pronote (`notebook-to-course.md`, section 1) : les `N` du cahier
 * changent à chaque session. Données synthétiques.
 */
class NotebookSessionKeyTest {
    private val subjects = listOf(Subject("maths", "Mathématiques", "#2563eb"), Subject("anglais", "Anglais", "#db2777"))
    private val today = LocalDate.parse("2026-11-18")
    private val content = listOf(NotebookContent("Titre", "", emptyList(), "", emptyList()))

    private fun session(n: String, date: String, subject: String) = SessionEntry(n, "lesson-$n", LocalDate.parse(date), subject, content)

    private fun snapshot(vararg sessions: SessionEntry) =
        NotebookSnapshot(today.minusDays(28), today.plusDays(7), NotebookRules.withOrdinals(sessions.toList()), emptyList(), 0, 0)

    private fun course(id: String, createdAt: String, source: CourseSource?) =
        Course(id, "maths", "Sans chapitre", 1, "Cours $id", source?.date ?: "2026-11-10", null, createdAt, source)

    @Test
    fun keyNormalizesSubjectAndRanksSameDaySessions() {
        assertEquals("anglais lv1", NotebookRules.normalizeSubject("  ANGLAIS  LV1 "))
        assertEquals("sciences de la vie de la terre", NotebookRules.normalizeSubject("SCIENCES DE LA VIE & DE LA TERRE"))
        assertEquals("education physique sportive", NotebookRules.normalizeSubject("ÉDUCATION PHYSIQUE-&-SPORTIVE"))
        val sessions = snapshot(
            session("18#a", "2026-11-17", "MATHEMATIQUES"),
            session("18#b", "2026-11-17", "ANGLAIS LV1"),
            session("18#c", "2026-11-17", "Mathématiques"),
        ).sessions
        assertEquals(
            listOf("pn1:2026-11-17|mathematiques|1", "pn1:2026-11-17|anglais lv1|1", "pn1:2026-11-17|mathematiques|2"),
            sessions.map { it.key },
        )
    }

    @Test
    fun newPronoteSessionCreatesNothing() {
        val settings = AppSettings(notebookAutoCreate = true, notebookAutoCreateSince = "2026-11-01")
        val first = snapshot(session("18#a1", "2026-11-17", "MATHEMATIQUES"), session("18#b1", "2026-11-16", "ANGLAIS LV1"))
        val eligible = NotebookCourses.eligible(first, settings, emptyMap(), subjects, today)
        assertEquals(2, eligible.size)
        val imports = eligible.associate { it.key to NotebookImport(NotebookImport.CREATED, "course-${it.key}", "") }
        val courses = eligible.map { course("course-${it.key}", "2026-11-18", CourseSource(NotebookCourses.SOURCE_KIND, it.key, it.subject, it.date.toString(), it.ordinal)) }
        // Nouvelle session : mêmes séances, autres `N`.
        val second = snapshot(session("18#a2", "2026-11-17", "MATHEMATIQUES"), session("18#b2", "2026-11-16", "ANGLAIS LV1"))
        assertTrue(NotebookCourses.eligible(second, settings, imports, subjects, today, courses).isEmpty())
        second.sessions.forEach { assertTrue(NotebookCourses.importState(it, imports, courses) is NotebookCourses.ImportState.View) }
        // Ceinture et bretelles : même sans entrée `notebookImports`, le cours existant suffit.
        assertTrue(NotebookCourses.eligible(second, settings, emptyMap(), subjects, today, courses).isEmpty())
        assertEquals(courses.first { it.source?.sessionKey == second.sessions[0].key }.id, (NotebookCourses.importState(second.sessions[0], emptyMap(), courses) as NotebookCourses.ImportState.View).courseId)
        assertEquals(0, NotebookCourses.backfill(second, emptyMap(), subjects, today, courses).count)
    }

    @Test
    fun migrationRewritesLegacyKeysOnceAndDropsOrphans() {
        val a = course("course-100", "2026-11-18", CourseSource(NotebookCourses.SOURCE_KIND, "18#old-a", "MATHEMATIQUES", "2026-11-17"))
        val b = course("course-200", "2026-11-18", CourseSource(NotebookCourses.SOURCE_KIND, "18#old-b", "Mathématiques", "2026-11-17"))
        val c = course("course-300", "2026-11-18", CourseSource(NotebookCourses.SOURCE_KIND, "18#old-c", "ANGLAIS LV1", "2026-11-16"))
        val typed = course("course-400", "2026-11-18", null)
        val imports = mapOf(
            "18#old-a" to NotebookImport(NotebookImport.CREATED, "course-100", "2026-11-18T11:24:49Z"),
            "18#old-b" to NotebookImport(NotebookImport.CREATED, "course-200", "2026-11-18T11:24:50Z"),
            "18#old-c" to NotebookImport(NotebookImport.DISMISSED, "course-300", "2026-11-18T11:30:00Z"),
            "18#gone" to NotebookImport(NotebookImport.DISMISSED, "course-999", "2026-11-18T11:05:43Z"),
            "pn1:2026-11-10|maths|1" to NotebookImport(NotebookImport.DISMISSED, "course-998", "2026-11-18T12:00:00Z"),
        )
        val document = AppDocument(courses = listOf(c, b, a, typed), notebookImports = imports)
        val migrated = DataMigrations.migrate(document)
        val sources = migrated.courses.associate { it.id to it.source }
        assertEquals("pn1:2026-11-17|mathematiques|1", sources["course-100"]?.sessionKey)
        assertEquals(1, sources["course-100"]?.ordinal)
        assertEquals("pn1:2026-11-17|mathematiques|2", sources["course-200"]?.sessionKey)
        assertEquals("pn1:2026-11-16|anglais lv1|1", sources["course-300"]?.sessionKey)
        assertNull(sources["course-400"])
        assertEquals(
            mapOf(
                "pn1:2026-11-10|maths|1" to imports.getValue("pn1:2026-11-10|maths|1"),
                "pn1:2026-11-17|mathematiques|1" to imports.getValue("18#old-a"),
                "pn1:2026-11-17|mathematiques|2" to imports.getValue("18#old-b"),
                "pn1:2026-11-16|anglais lv1|1" to imports.getValue("18#old-c"),
            ),
            migrated.notebookImports,
        )
        // Idempotente : une seconde passe ne change rien.
        assertSame(migrated, DataMigrations.migrateNotebookKeys(migrated))
    }

    @Test
    fun migrationCollisionKeepsTheOldestAndDeletesNothing() {
        // Doublons : même séance importée deux fois avec deux anciennes clés ; un cours déjà au nouveau format.
        val current = course("course-050", "2026-11-17", CourseSource(NotebookCourses.SOURCE_KIND, "pn1:2026-11-17|mathematiques|1", "MATHEMATIQUES", "2026-11-17", 1))
        val legacy = course("course-100", "2026-11-18", CourseSource(NotebookCourses.SOURCE_KIND, "18#old", "MATHEMATIQUES", "2026-11-17"))
        val imports = mapOf(
            "pn1:2026-11-17|mathematiques|1" to NotebookImport(NotebookImport.CREATED, "course-050", "x"),
            "18#old" to NotebookImport(NotebookImport.CREATED, "course-100", "y"),
        )
        val migrated = DataMigrations.migrateNotebookKeys(AppDocument(courses = listOf(legacy, current), notebookImports = imports))
        // Règles 37–38 : seuls les cours sans `ordinal` sont classés ; la clé commune désigne le plus ancien.
        assertEquals(2, migrated.courses.size)
        assertEquals("pn1:2026-11-17|mathematiques|1", migrated.courses.first { it.id == "course-100" }.source?.sessionKey)
        assertEquals(mapOf("pn1:2026-11-17|mathematiques|1" to imports.getValue("pn1:2026-11-17|mathematiques|1")), migrated.notebookImports)
    }
}
