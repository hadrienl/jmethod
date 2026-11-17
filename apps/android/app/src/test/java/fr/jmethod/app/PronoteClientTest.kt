package fr.jmethod.app

import fr.jmethod.app.model.DEFAULT_SUBJECTS
import fr.jmethod.app.pronote.Credentials
import fr.jmethod.app.pronote.PronoteClient
import fr.jmethod.app.pronote.PronoteEventMapper
import fr.jmethod.app.pronote.PronoteException
import fr.jmethod.app.pronote.PronotePolicy
import fr.jmethod.app.pronote.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class PronoteClientTest {
    private val today = LocalDate.of(2026, 11, 17)

    private fun qrCredentials(server: FakePronoteServer) = PronotePolicy.credentialsFromQr(server.qr("4321"), "4321")

    @Test
    fun qrLoginOnSecuredAccountReconnectsWithRotatedToken() {
        val server = FakePronoteServer(token = "jeton-qr", secureMode = 1)
        val saved = mutableListOf<Pair<String, List<String>>>()
        val client = PronoteClient(server.transport, qrCredentials(server)) { saved += it.password to server.calls.toList() }
        client.login(PronoteClient.Mode.QR)
        // Première connexion (QR) puis seconde connexion par jeton imposée par securisation.mode.
        assertEquals(2, server.calls.count { it == "Authentification" })
        assertEquals("jeton-rotation-2", client.credentials.password)
        assertEquals("jeton-rotation-2", saved.last().first)
        // Le jeton renouvelé est enregistré avant toute lecture qui suit l’authentification.
        val first = saved.first()
        assertEquals("jeton-rotation-1", first.first)
        assertEquals("Authentification", first.second.last())
        assertEquals("nav-test", client.credentials.clientIdentifier)
        assertEquals("Élève Test", client.accountName)
    }

    @Test
    fun tokenLoginReadsFourteenDaysWithGridEndAndCancellation() {
        val server = FakePronoteServer(token = "jeton-enregistre")
            .lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES", end = "18/11/2026 09:20:00")
            .lesson("c2", "18/11/2026 09:30:00", "ANGLAIS", cancelled = true, place = 1, duration = 2)
            .lesson("c3", "30/11/2026 08:30:00", "FRANCAIS", end = "30/11/2026 09:20:00")
            .lesson("old", "16/11/2026 08:30:00", "HISTOIRE", end = "16/11/2026 09:20:00")
            .lesson("far", "01/12/2026 08:30:00", "SVT", end = "01/12/2026 09:20:00")
        val credentials = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, "jeton-enregistre", "uuid-test")
        val client = PronoteClient(server.transport, credentials)
        client.login(PronoteClient.Mode.TOKEN)
        val lessons = client.timetable(today, 14)
        assertEquals(listOf("MATHEMATIQUES", "ANGLAIS", "FRANCAIS"), lessons.map { it.subject })
        assertEquals("2026-11-18T10:15", lessons[1].end)
        assertTrue(lessons[1].cancelled)
        assertEquals(3, server.calls.count { it == "PageEmploiDuTemps" })
        assertTrue(server.timetableMembers.all { it == null })

        val events = PronoteEventMapper.toEvents(lessons, DEFAULT_SUBJECTS)
        assertEquals(listOf("maths", "anglais", "francais"), events.map { it.matchedSubjectId })
        assertEquals(listOf("08:30", "09:30", "08:30"), events.map { it.startTime })
        assertEquals(listOf("09:20", "10:15", "09:20"), events.map { it.endTime })
        assertEquals(listOf(false, true, false), events.map { it.isCancelled })
        assertEquals("2026-11-30", events[2].date)
    }

    @Test
    fun parentSelectsChildInSignatureAndResource() {
        val server = FakePronoteServer(token = "jeton-parent", parent = true).lesson("c1", "18/11/2026 08:30:00", "MATHEMATIQUES")
        val credentials = Credentials("${server.origin}/pronote/mobile.parent.html?fd=1", server.username, "jeton-parent", "uuid-test")
        val client = PronoteClient(server.transport, credentials)
        client.login(PronoteClient.Mode.TOKEN)
        assertEquals(listOf("enfant-a", "enfant-b"), client.children.map { it.id })
        assertEquals("enfant-a", client.selectedChildId)
        client.selectChild("enfant-b")
        client.timetable(today, 1)
        assertEquals("enfant-b", server.timetableMembers.last())
        assertEquals("enfant-b", server.timetableResources.last())
    }

    @Test
    fun rejectedTokenAndServerErrorsAreTyped() {
        val server = FakePronoteServer(token = "jeton-valide")
        val wrong = Credentials("${server.origin}/pronote/mobile.eleve.html?fd=1", server.username, "jeton-perime", "uuid-test")
        try {
            PronoteClient(server.transport, wrong).login(PronoteClient.Mode.TOKEN)
            fail("connexion refusée attendue")
        } catch (error: PronoteException) {
            assertEquals("login", error.code)
        }
        server.failures["Identification"] = 25
        try {
            PronoteClient(server.transport, wrong.copy(password = "jeton-valide")).login(PronoteClient.Mode.TOKEN)
            fail("erreur 25 attendue")
        } catch (error: PronoteException) {
            assertEquals("pronote-25", error.code)
        }
    }

    @Test
    fun writesAreRefusedBeforeAnyRequest() {
        val server = FakePronoteServer(token = "jeton")
        val client = PronoteClient(server.transport, Credentials("${server.origin}/pronote/mobile.eleve.html", "u", "p", "id"))
        for (function in listOf("SaisieTAFFaitEleve", "SaisieMessage", "SecurisationCompteDoubleAuth", "JetonAppliMobile")) {
            try {
                client.call(function)
                fail(function)
            } catch (error: PronoteException) {
                assertEquals("readonly", error.code)
            }
        }
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun mapperDeduplicatesAndSorts() {
        val lesson = Lesson("1", "Physique-Chimie", "2026-11-18T10:00", "2026-11-18T11:00", "", "", false)
        val events = PronoteEventMapper.toEvents(
            listOf(lesson.copy(id = "2", start = "2026-11-18T08:00", subject = ""), lesson, lesson, lesson.copy(id = "3", end = "")),
            DEFAULT_SUBJECTS,
        )
        assertEquals(listOf("pronote-2", "pronote-1", "pronote-3"), events.map { it.id })
        assertEquals("Cours", events[0].subjectName)
        assertNull(events[0].matchedSubjectId)
        assertEquals("physique-chimie", events[1].matchedSubjectId)
        assertEquals("", events[2].endTime)
    }
}
