import Foundation

/// Cours Pronote → créneaux `PronoteEvent` du web (même forme que `normalize_lessons` du connecteur local),
/// avec appariement de matière par `SubjectMatcher`.
enum LessonConverter {
    static func events(from lessons: [PronoteLesson], subjects: [Subject]) -> [PronoteEvent] {
        var seen = Set<PronoteEvent>()
        var events: [PronoteEvent] = []
        for lesson in lessons {
            guard SchoolDay.isValid(String(lesson.start.prefix(10))), lesson.start.count >= 16 else { continue }
            let date = String(lesson.start.prefix(10))
            let start = time(lesson.start)
            let end = lesson.end.count >= 16 ? time(lesson.end) : ""
            let event = PronoteEvent(
                id: "pronote-\(lesson.id)",
                subjectName: lesson.subject.isEmpty ? "Cours" : lesson.subject,
                matchedSubjectId: SubjectMatcher.match(lesson.subject, subjects: subjects)?.id,
                date: date,
                startTime: start,
                endTime: end,
                room: lesson.room,
                teacher: lesson.teacher,
                isCancelled: lesson.cancelled
            )
            // Seuls les doublons strictement identiques sont retirés ; les variantes et cours modifiés restent.
            if seen.insert(event).inserted { events.append(event) }
        }
        return events.enumerated().sorted { lhs, rhs in
            let a = lhs.element, b = rhs.element
            if a.date != b.date { return a.date < b.date }
            if a.startTime != b.startTime { return a.startTime < b.startTime }
            if a.id != b.id { return a.id < b.id }
            return lhs.offset < rhs.offset
        }.map(\.element)
    }

    private static func time(_ value: String) -> String {
        let start = value.index(value.startIndex, offsetBy: 11)
        return String(value[start..<value.index(start, offsetBy: 5)])
    }
}
