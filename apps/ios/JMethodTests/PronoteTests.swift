import XCTest
import CoreImage
import UIKit
@testable import JMethod

final class PronoteTests: XCTestCase {
    // MARK: Adresse

    func testAddressNormalization() {
        let host = "school.index-education.net"
        XCTAssertEqual(PronoteURL.normalized(host), "https://\(host)/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.normalized("  \(host)/  "), "https://\(host)/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.normalized("http://\(host)/pronote/"), "https://\(host)/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.normalized("HTTPS://SCHOOL.index-education.net/pronote"), "https://\(host)/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.normalized("https://\(host)/pronote/mobile.eleve.html?fd=1#x"), "https://\(host)/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.normalized("\(host)/pronote/parent.html?login=true"), "https://\(host)/pronote/parent.html")
        XCTAssertEqual(PronoteURL.normalized("\(host)/pronote/mobile.parent.html"), "https://\(host)/pronote/parent.html")
        XCTAssertEqual(PronoteURL.normalized(""), "")
    }

    func testSchoolCodeAndAccountTypeResolution() {
        XCTAssertEqual(PronoteURL.resolve("abcd1234", account: .student).address, "https://abcd1234.index-education.net/pronote/eleve.html")
        XCTAssertEqual(PronoteURL.resolve(" ABCD 1234 ", account: .parent).address, "https://abcd1234.index-education.net/pronote/parent.html")
        XCTAssertEqual(PronoteURL.resolve("abcd1234", account: .parent).account, .parent)
        XCTAssertEqual(PronoteURL.resolve("abcd1234.index-education.net", account: .parent).address, "https://abcd1234.index-education.net/pronote/parent.html")
        XCTAssertEqual(PronoteURL.resolve("abcd1234.index-education.net/pronote/", account: .student).address, "https://abcd1234.index-education.net/pronote/eleve.html")
        // Une adresse complète impose son espace et met le choix à jour.
        let full = PronoteURL.resolve("https://abcd1234.index-education.net/pronote/eleve.html?login=true", account: .parent)
        XCTAssertEqual(full.address, "https://abcd1234.index-education.net/pronote/eleve.html")
        XCTAssertEqual(full.account, .student)
        let mobileParent = PronoteURL.resolve("abcd1234.index-education.net/pronote/mobile.parent.html", account: .student)
        XCTAssertEqual(mobileParent.address, "https://abcd1234.index-education.net/pronote/parent.html")
        XCTAssertEqual(mobileParent.account, .parent)
        XCTAssertEqual(PronoteURL.resolve("   ", account: .student).address, "")
        XCTAssertNoThrow(try PronoteURL.validated(PronoteURL.resolve("abcd1234", account: .student).address))
        // Un code avec des caractères inattendus n'est pas complété en hôte Index Education.
        XCTAssertThrowsError(try PronoteURL.validated(PronoteURL.resolve("abcd_1234", account: .student).address))
    }

    func testAddressValidationRejectsForeignOrigins() throws {
        XCTAssertNoThrow(try PronoteURL.validated("https://school.index-education.net/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("https://index-education.net.evil.test/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("http://school.index-education.net/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("https://user:pass@school.index-education.net/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("https://school.index-education.net:8443/pronote/eleve.html"))
        XCTAssertThrowsError(try PronoteURL.validated("https://school.index-education.net/pronote/professeur.html"))
        let a = try XCTUnwrap(URL(string: "https://school.index-education.net/pronote/eleve.html"))
        XCTAssertTrue(PronoteURL.sameOrigin(a, try XCTUnwrap(URL(string: "https://SCHOOL.index-education.net:443/x"))))
        XCTAssertFalse(PronoteURL.sameOrigin(a, try XCTUnwrap(URL(string: "https://other.index-education.net/x"))))
        XCTAssertEqual(PronoteURL.webPage(fromMobile: "https://school.index-education.net/pronote/mobile.eleve.html?fd=1"), "https://school.index-education.net/pronote/eleve.html")
    }

    // MARK: Semaines et fins de cours

    func testWeekNumbersAcrossYearAndDaylightSavingChanges() {
        let monday = "2026-08-31"
        XCTAssertEqual(TimetableMath.weekNumber(day: "2026-08-31", firstMonday: monday), 1)
        XCTAssertEqual(TimetableMath.weekNumber(day: "2026-09-06", firstMonday: monday), 1)
        XCTAssertEqual(TimetableMath.weekNumber(day: "2026-09-07", firstMonday: monday), 2)
        // Passage à l'heure d'hiver le 25 octobre 2026 : le lundi suivant reste un début de semaine.
        XCTAssertEqual(TimetableMath.weekNumber(day: "2026-10-25", firstMonday: monday), 8)
        XCTAssertEqual(TimetableMath.weekNumber(day: "2026-10-26", firstMonday: monday), 9)
        // Changement d'année civile.
        XCTAssertEqual(TimetableMath.weekNumber(day: "2027-01-03", firstMonday: monday), 18)
        XCTAssertEqual(TimetableMath.weekNumber(day: "2027-01-04", firstMonday: monday), 19)
        // Passage à l'heure d'été le 28 mars 2027.
        XCTAssertEqual(TimetableMath.weekNumber(day: "2027-03-28", firstMonday: monday), 30)
        XCTAssertEqual(TimetableMath.weekNumber(day: "2027-03-29", firstMonday: monday), 31)
        XCTAssertNil(TimetableMath.weekNumber(day: "2027-02-30", firstMonday: monday))
    }

    func testEndTimeFromGrid() {
        let grid: [[String: Any]] = [
            ["G": 0, "L": "08h55"], ["G": 1, "L": "09h50"], ["G": 2, "L": "10h55"], ["G": 3, "L": "11h50"], ["G": 4, "L": "12h45"],
        ]
        XCTAssertEqual(TimetableMath.endTime(place: 0, duration: 1, grid: grid), "08:55:00")
        XCTAssertEqual(TimetableMath.endTime(place: 1, duration: 2, grid: grid), "10:55:00")
        // Place d'un autre jour (modulo count - 1).
        XCTAssertEqual(TimetableMath.endTime(place: 5, duration: 1, grid: grid), "09:50:00")
        XCTAssertNil(TimetableMath.endTime(place: 0, duration: 1, grid: [["G": 0, "L": "08h55"]]))
        XCTAssertNil(TimetableMath.endTime(place: 0, duration: 0, grid: grid))
        XCTAssertNil(TimetableMath.endTime(place: 0, duration: 1, grid: [["G": 0, "L": "8:55"], ["G": 1, "L": "09h50"]]))
    }

    func testLessonParsingKeepsVariantsAndCancellation() throws {
        let grid: [[String: Any]] = [["G": 0, "L": "08h55"], ["G": 1, "L": "09h50"], ["G": 2, "L": "10h55"]]
        let raw: [String: Any] = [
            "N": "lesson-1", "P": 2, "place": 0, "duree": 2,
            "DateDuCours": ["_T": 7, "V": "18/11/2026 08:00:00"],
            "estAnnule": true,
            "ListeContenus": ["_T": 24, "V": [
                ["G": 16, "L": "MATHEMATIQUES"], ["G": 3, "L": "Professeur A"], ["G": 17, "L": "Salle 101"], ["G": 2, "L": "Groupe 1"],
            ]],
        ]
        let lesson = try XCTUnwrap(PronoteClient.lesson(raw, grid: grid))
        XCTAssertEqual(lesson.id, "lesson-1-2026-11-18T08:00:00-2")
        XCTAssertEqual(lesson.start, "2026-11-18T08:00:00")
        XCTAssertEqual(lesson.end, "2026-11-18T09:50:00")
        XCTAssertEqual(lesson.subject, "MATHEMATIQUES")
        XCTAssertEqual(lesson.teacher, "Professeur A")
        XCTAssertEqual(lesson.room, "Salle 101")
        XCTAssertTrue(lesson.cancelled)

        var withEnd = raw
        withEnd["DateDuCoursFin"] = ["_T": 7, "V": "18/11/2026 09:00:00"]
        withEnd["estAnnule"] = false
        XCTAssertEqual(PronoteClient.lesson(withEnd, grid: [])?.end, "2026-11-18T09:00:00")
        XCTAssertEqual(PronoteClient.lesson(withEnd, grid: [])?.cancelled, false)
        // Grille absente et pas de fin : aucune durée inventée.
        XCTAssertEqual(PronoteClient.lesson(raw, grid: [])?.end, "")
        XCTAssertNil(PronoteClient.lesson(["N": "x"], grid: grid))
    }

    func testLessonsBecomeWebPronoteEvents() {
        let lessons = [
            PronoteLesson(id: "b-2026-11-18T10:00:00-0", subject: "ANGLAIS LV1", start: "2026-11-18T10:00:00", end: "2026-11-18T11:00:00", teacher: "", room: "", cancelled: true),
            PronoteLesson(id: "a-2026-11-18T08:00:00-0", subject: "MATHEMATIQUES", start: "2026-11-18T08:00:00", end: "", teacher: "Professeur A", room: "Salle 101", cancelled: false),
            PronoteLesson(id: "a-2026-11-18T08:00:00-0", subject: "MATHEMATIQUES", start: "2026-11-18T08:00:00", end: "", teacher: "Professeur A", room: "Salle 101", cancelled: false),
            PronoteLesson(id: "c-2026-11-18T08:00:00-1", subject: "ED.PHYSIQUE & SPORT.", start: "2026-11-18T08:00:00", end: "2026-11-18T09:00:00", teacher: "", room: "", cancelled: false),
        ]
        let events = LessonConverter.events(from: lessons, subjects: Constants.defaultSubjects)
        XCTAssertEqual(events.map(\.id), ["pronote-a-2026-11-18T08:00:00-0", "pronote-c-2026-11-18T08:00:00-1", "pronote-b-2026-11-18T10:00:00-0"])
        XCTAssertEqual(events[0].matchedSubjectId, "maths")
        XCTAssertEqual(events[0].subjectName, "MATHEMATIQUES")
        XCTAssertEqual(events[0].startTime, "08:00")
        XCTAssertEqual(events[0].endTime, "")
        XCTAssertNil(events[1].matchedSubjectId, "l'EPS ne devient pas de la physique-chimie")
        XCTAssertEqual(events[2].matchedSubjectId, "anglais")
        XCTAssertTrue(events[2].isCancelled)
        XCTAssertEqual(events[2].endTime, "11:00")
        XCTAssertNil(events[2].isCourseCreated)
    }

    // MARK: QR

    func testNativeQRDecodeAndLimits() throws {
        let message = Data(#"{"url":"https://school.index-education.net/pronote/eleve.html","login":"abcd","jeton":"0123"}"#.utf8)
        XCTAssertEqual(try PronoteWebFlow.decode(image: qrPNG(message, scale: 8)).login, "abcd")
        XCTAssertEqual(try PronoteWebFlow.decode(dataURL: "data:image/png;base64," + qrPNG(message, scale: 8).base64EncodedString()).jeton, "0123")
        XCTAssertThrowsError(try PronoteWebFlow.decode(image: Data([0, 1, 2])))
        XCTAssertThrowsError(try PronoteWebFlow.decode(dataURL: "data:image/jpeg;base64,AAAA"))
        XCTAssertThrowsError(try PronoteWebFlow.decode(image: Data(count: PronoteWebFlow.maximumImageBytes + 1)))
        // QR étranger à Pronote.
        let foreign = Data(#"{"url":"https://example.org/pronote/eleve.html","login":"a","jeton":"b"}"#.utf8)
        XCTAssertThrowsError(try PronoteWebFlow.decode(image: qrPNG(foreign, scale: 8)))
        XCTAssertThrowsError(try PronoteWebFlow.decode(image: qrPNG(Data("bonjour".utf8), scale: 8)))
    }

    private func qrPNG(_ message: Data, scale: CGFloat) throws -> Data {
        let generator = try XCTUnwrap(CIFilter(name: "CIQRCodeGenerator"))
        generator.setValue(message, forKey: "inputMessage")
        let output = try XCTUnwrap(generator.outputImage).transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let image = try XCTUnwrap(CIContext().createCGImage(output, from: output.extent))
        return try XCTUnwrap(UIImage(cgImage: image).pngData())
    }

    // MARK: Erreurs

    func testFailureClassification() {
        XCTAssertTrue(PronoteFailure.invalid("pronote-10", "").isExpiredSession)
        XCTAssertTrue(PronoteFailure.invalid("pronote-22", "").isExpiredSession)
        XCTAssertFalse(PronoteFailure.invalid("pronote-25", "").isExpiredSession)
        XCTAssertTrue(PronoteFailure.invalid("pronote-25", "").isTooManyAttempts)
    }
}
