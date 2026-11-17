import Foundation

/// Export et import JSON compatibles avec le web (`exportAllData`, `importAllData`, suite `importExport`),
/// avec l'écart D13 : un fichier dont `courses`, `revisions` ou `subjects` ne sont pas des tableaux d'objets
/// du bon type est refusé avec un message clair. Les créneaux Pronote de l'appareil sont conservés.
enum Backup {
    static let errorTitle = "Erreur d’importation"
    static let invalidMessage = "Le fichier fourni est invalide."

    enum ImportResult: Equatable {
        /// Données lues, à appliquer après confirmation.
        case accepted(Accepted)
        case rejected(title: String, message: String)
    }

    struct Accepted: Equatable {
        var courses: [Course]
        var revisions: [Revision]
        /// nil : matières conservées (clé absente ou nulle).
        var subjects: [Subject]?
        /// nil : réglages conservés ; sinon stockés tels quels puis complétés par les valeurs par défaut.
        var settings: AppSettings?

        func apply(to document: AppDocument) -> AppDocument {
            var result = document
            result.courses = courses
            result.revisions = revisions
            if let subjects { result.subjects = subjects }
            if let settings { result.settings = settings }
            result.initialized = true
            return result
        }
    }

    /// `Date.prototype.toISOString` : toujours trois décimales, en UTC.
    static func isoMillis(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func fileName(today: String) -> String { "jmethod-sauvegarde-\(today).json" }

    // MARK: Import

    /// `!value` de JavaScript pour une valeur JSON.
    private static func falsy(_ value: Any?) -> Bool {
        switch value {
        case nil, is NSNull: return true
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return !number.boolValue }
            return number.doubleValue == 0 || number.doubleValue.isNaN
        case let text as String: return text.isEmpty
        default: return false
        }
    }

    private static func shapeError(_ what: String) -> ImportResult {
        .rejected(title: errorTitle, message: "Ce fichier n’est pas une sauvegarde J-Method : \(what).")
    }

    private static func decodeList<T: Decodable>(_ type: T.Type, _ value: Any) -> [T]? {
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode([T].self, from: data)
    }

    static func parse(_ data: Data) -> ImportResult {
        guard let root = (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any],
              !falsy(root["courses"]), !falsy(root["revisions"]) else {
            return .rejected(title: errorTitle, message: invalidMessage)
        }
        // D13 : validation de forme avant tout remplacement.
        guard let rawCourses = root["courses"] as? [Any], rawCourses.allSatisfy({ $0 is [String: Any] }) else {
            return shapeError("la liste des cours est illisible")
        }
        guard let courses = decodeList(Course.self, rawCourses) else { return shapeError("un cours est incomplet ou mal formé") }
        guard let rawRevisions = root["revisions"] as? [Any], rawRevisions.allSatisfy({ $0 is [String: Any] }) else {
            return shapeError("la liste des séances est illisible")
        }
        guard let revisions = decodeList(Revision.self, rawRevisions) else { return shapeError("une séance est incomplète ou mal formée") }
        var subjects: [Subject]?
        switch root["subjects"] {
        case nil, is NSNull: subjects = nil
        case let list as [Any]:
            guard list.allSatisfy({ $0 is [String: Any] }) else { return shapeError("la liste des matières est illisible") }
            guard let decoded = decodeList(Subject.self, list) else { return shapeError("une matière est incomplète ou mal formée") }
            subjects = decoded
        default: return shapeError("la liste des matières est illisible")
        }
        var settings: AppSettings?
        switch root["settings"] {
        case nil, is NSNull: settings = nil
        case let object as [String: Any]:
            guard let encoded = try? JSONSerialization.data(withJSONObject: object),
                  let decoded = try? JSONDecoder().decode(AppSettings.self, from: encoded) else { return shapeError("les réglages sont illisibles") }
            settings = decoded
        default: return shapeError("les réglages sont illisibles")
        }
        return .accepted(Accepted(courses: courses, revisions: revisions, subjects: subjects, settings: settings))
    }

    // MARK: Export

    /// Texte identique à `JSON.stringify({ version, exportDate, courses, revisions, subjects, settings }, null, 2)`,
    /// ordre des clés du web ; `pronoteEvents` n'est pas exporté.
    static func export(_ document: AppDocument, now: Date) -> String {
        let value: JSValue = .object([
            ("version", .string("1.0")),
            ("exportDate", .string(isoMillis(now))),
            ("courses", .array(document.courses.map(json))),
            ("revisions", .array(document.revisions.map(json))),
            ("subjects", .array(document.subjects.map(json))),
            ("settings", json(document.settings)),
        ])
        var output = ""
        value.write(to: &output, indent: "")
        return output
    }

    private static func json(_ course: Course) -> JSValue {
        .object([
            ("id", .string(course.id)), ("subjectId", .string(course.subjectId)), ("chapter", .string(course.chapter)),
            ("courseNumber", .number(Double(course.courseNumber))), ("title", .string(course.title)), ("date", .string(course.date)),
            ("description", course.description.map(JSValue.string)), ("createdAt", .string(course.createdAt)),
        ].compactMap { key, value in value.map { (key, $0) } })
    }

    /// Ordre du stockage web (création, rattrapage, puis validation), identique à Android.
    private static func json(_ revision: Revision) -> JSValue {
        .object([
            ("id", .string(revision.id)), ("courseId", .string(revision.courseId)), ("stepIndex", .number(Double(revision.stepIndex))),
            ("stepLabel", .string(revision.stepLabel)), ("scheduledDate", .string(revision.scheduledDate)),
            ("status", .string(revision.status.rawValue)), ("phase", .string(revision.phase.rawValue)),
            ("isEmergencySession", revision.isEmergencySession.map(JSValue.bool)),
            ("adjustedIntervalDays", revision.adjustedIntervalDays.map { .number(Double($0)) }),
            ("completedDate", revision.completedDate.map(JSValue.string)), ("score", revision.score.map { .number(Double($0)) }),
        ].compactMap { key, value in value.map { (key, $0) } })
    }

    private static func json(_ subject: Subject) -> JSValue {
        .object([
            ("id", .string(subject.id)), ("name", .string(subject.name)), ("color", .string(subject.color)),
            ("description", subject.description.map(JSValue.string)), ("isCustom", subject.isCustom.map(JSValue.bool)),
        ].compactMap { key, value in value.map { (key, $0) } })
    }

    private static func json(_ settings: AppSettings) -> JSValue {
        .object([
            ("intervals", .array(settings.intervals.map { .number(Double($0)) })), ("sensitivity", .number(settings.sensitivity)),
            ("emergencyThreshold", .number(settings.emergencyThreshold)), ("pronoteIcalUrl", .string(settings.pronoteIcalUrl)),
            ("soundEnabled", .bool(settings.soundEnabled)), ("userName", .string(settings.userName)), ("userGrade", .string(settings.userGrade)),
            ("lastPronoteSync", settings.lastPronoteSync.map(JSValue.string)),
        ].compactMap { key, value in value.map { (key, $0) } })
    }
}

/// Arbre JSON ordonné, écrit comme `JSON.stringify(value, null, 2)` de JavaScript.
indirect enum JSValue: Equatable {
    case object([(String, JSValue)])
    case array([JSValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    static func == (lhs: JSValue, rhs: JSValue) -> Bool {
        var a = "", b = ""
        lhs.write(to: &a, indent: "")
        rhs.write(to: &b, indent: "")
        return a == b
    }

    func write(to out: inout String, indent: String) {
        switch self {
        case .object(let entries):
            guard !entries.isEmpty else { out += "{}"; return }
            let inner = indent + "  "
            out += "{\n"
            for (index, entry) in entries.enumerated() {
                out += inner
                Self.quote(entry.0, to: &out)
                out += ": "
                entry.1.write(to: &out, indent: inner)
                if index < entries.count - 1 { out += "," }
                out += "\n"
            }
            out += indent + "}"
        case .array(let items):
            guard !items.isEmpty else { out += "[]"; return }
            let inner = indent + "  "
            out += "[\n"
            for (index, item) in items.enumerated() {
                out += inner
                item.write(to: &out, indent: inner)
                if index < items.count - 1 { out += "," }
                out += "\n"
            }
            out += indent + "]"
        case .string(let text): Self.quote(text, to: &out)
        case .number(let value): out += Self.number(value)
        case .bool(let value): out += value ? "true" : "false"
        case .null: out += "null"
        }
    }

    /// `String(number)` de JavaScript pour les valeurs du document (entiers et décimaux courts).
    static func number(_ value: Double) -> String {
        guard value.isFinite else { return "null" }
        if value.rounded() == value, abs(value) < 1e21 { return String(Int64(value)) }
        return String(value)
    }

    static func quote(_ text: String, to out: inout String) {
        out += "\""
        let units = Array(text.utf16)
        var index = 0
        while index < units.count {
            let unit = units[index]
            switch unit {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x0C: out += "\\f"
            case 0x0A: out += "\\n"
            case 0x0D: out += "\\r"
            case 0x09: out += "\\t"
            case 0..<0x20: out += String(format: "\\u%04x", unit)
            case 0xD800...0xDBFF where index + 1 < units.count && (0xDC00...0xDFFF).contains(units[index + 1]):
                out += String(utf16CodeUnits: [unit, units[index + 1]], count: 2)
                index += 1
            case 0xD800...0xDFFF: out += String(format: "\\u%04x", unit)
            default: out += String(utf16CodeUnits: [unit], count: 1)
            }
            index += 1
        }
        out += "\""
    }
}
