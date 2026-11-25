import Foundation

/// Export et import JSON compatibles avec le web (`exportAllData`, `importAllData`, suite `importExport`),
/// avec l'écart D13 : un fichier dont `courses`, `revisions` ou `subjects` ne sont pas des tableaux d'objets
/// du bon type est refusé avec un message clair. Les créneaux Pronote de l'appareil sont conservés.
enum Backup {
    static let errorTitle = "Erreur d’importation"
    static let invalidMessage = "Le fichier fourni est invalide."

    enum ImportResult: Equatable, Sendable {
        /// Données lues, à appliquer après confirmation.
        case accepted(Accepted)
        case rejected(title: String, message: String)
    }

    struct Accepted: Equatable, Sendable {
        var courses: [Course]
        var revisions: [Revision]
        /// nil : matières conservées (clé absente ou nulle).
        var subjects: [Subject]?
        /// nil : réglages conservés ; sinon stockés tels quels puis complétés par les valeurs par défaut.
        var settings: AppSettings?
        /// nil : suivi des imports du cahier de textes conservé (sauvegarde web ou ancienne).
        var notebookImports: [String: NotebookImport]? = nil
        var programmes: [RevisionProgramme] = []
        var schemaVersion = 1
        var streakState: StreakState? = nil
        var todayWidgetVisit: TodayWidgetVisit? = nil

        func apply(to document: AppDocument) -> AppDocument {
            var result = document
            result.courses = courses
            result.revisions = revisions
            if let subjects { result.subjects = subjects }
            if let notebookImports { result.notebookImports = notebookImports }
            if let settings { result.settings = settings }
            result.initialized = true
            result.programmes = programmes
            result.schemaVersion = schemaVersion
            result.streakState = streakState
            result.todayWidgetVisit = todayWidgetVisit
            return RevisionProgrammes.migrate(NotebookCourses.migratingKeys(result))
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
        guard let envelope = (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any] else {
            return .rejected(title: errorTitle, message: invalidMessage)
        }
        let root: [String: Any]
        if let payload = envelope["data"] {
            guard let outer = envelope["schemaVersion"] as? Int, (2...3).contains(outer), envelope["version"] as? String == "\(outer).0", let object = payload as? [String: Any], object["schemaVersion"] as? Int == outer else {
                return shapeError("cette version nécessite une app plus récente")
            }
            root = object
        } else { root = envelope }
        guard !falsy(root["courses"]), !falsy(root["revisions"]) else {
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
        var imports: [String: NotebookImport]?
        switch root["notebookImports"] {
        case nil, is NSNull: imports = nil
        case let object as [String: Any]:
            guard let encoded = try? JSONSerialization.data(withJSONObject: object),
                  let decoded = try? JSONDecoder().decode([String: NotebookImport].self, from: encoded) else {
                return shapeError("le suivi du cahier de textes est illisible")
            }
            imports = decoded
        default: return shapeError("le suivi du cahier de textes est illisible")
        }
        let schema: Int
        if let raw = root["schemaVersion"] {
            guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), let value = raw as? Int else { return shapeError("la version du document est illisible") }
            schema = value
        } else { schema = 1 }
        guard (1...3).contains(schema) else { return shapeError("cette version nécessite une app plus récente") }
        var programmes: [RevisionProgramme] = []
        var streak: StreakState?
        // Un document v3 sans journal (journal local abandonné) démarre un nouveau suivi ; un journal présent doit être valide.
        if schema >= 3, let raw = root["streakState"], !(raw is NSNull) {
            guard let data = try? JSONSerialization.data(withJSONObject: raw),
                  let decoded = try? JSONDecoder().decode(StreakState.self, from: data),
                  (try? RevisionStreak.validate(decoded)) != nil else { return shapeError("le journal de révision est illisible ou incohérent") }
            streak = decoded
        }
        if schema >= 2 {
            guard let raw = root["programmes"], let decoded = decodeList(RevisionProgramme.self, raw) else { return shapeError("les programmes sont illisibles") }
            programmes = decoded
            var candidate = AppDocument(settings: settings ?? Constants.defaultSettings, subjects: subjects ?? [], courses: courses, revisions: revisions, pronoteEvents: [], initialized: true)
            candidate.programmes = programmes; candidate.schemaVersion = schema; candidate.streakState = streak
            do { try RevisionProgrammes.validateDocument(candidate, checkSubjects: subjects != nil) }
            catch { return shapeError("les feuilles, programmes ou rythmes sont incohérents") }
        }
        let visit = root["todayWidgetVisit"]
            .flatMap { try? JSONSerialization.data(withJSONObject: $0, options: .fragmentsAllowed) }
            .flatMap { try? JSONDecoder().decode(TodayWidgetVisit.self, from: $0) }
        return .accepted(Accepted(courses: courses, revisions: revisions, subjects: subjects, settings: settings,
                                  notebookImports: imports, programmes: programmes, schemaVersion: schema,
                                  streakState: streak, todayWidgetVisit: visit))
    }

    // MARK: Export

    /// Texte identique à `JSON.stringify({ version, exportDate, courses, revisions, subjects, settings }, null, 2)`,
    /// ordre des clés du web ; `pronoteEvents` n'est pas exporté.
    static func export(_ document: AppDocument, now: Date) -> String {
        if document.schemaVersion >= 2 {
            // Même contrat Codable que le document local ; les fichiers joints restent locaux.
            guard let data = try? DocumentStore.encode(document),
                  var object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return "{}" }
            object.removeValue(forKey: "pronoteEvents")
            object.removeValue(forKey: "initialized")
            object.removeValue(forKey: "dataVersion")
            object.removeValue(forKey: "agendaFollowUp")
            object["version"] = document.schemaVersion >= 3 ? "3.0" : "2.0"; object["exportDate"] = isoMillis(now)
            if object["programmes"] == nil { object["programmes"] = [] }
            let envelope: [String: Any] = ["version": document.schemaVersion >= 3 ? "3.0" : "2.0", "schemaVersion": document.schemaVersion, "exportDate": isoMillis(now), "data": object]
            guard let output = try? JSONSerialization.data(withJSONObject: envelope, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]),
                  let text = String(data: output, encoding: .utf8) else { return "{}" }
            return text
        }
        let value: JSValue = .object([
            ("version", .string("1.0")),
            ("exportDate", .string(isoMillis(now))),
            ("courses", .array(document.courses.map(json))),
            ("revisions", .array(document.revisions.map(json))),
            ("subjects", .array(document.subjects.map(json))),
            ("settings", json(document.settings)),
        ] + (document.notebookImports.isEmpty ? [] : [("notebookImports", json(document.notebookImports))]))
        var output = ""
        value.write(to: &output, indent: "")
        return output
    }

    private static func json(_ course: Course) -> JSValue {
        .object([
            ("id", .string(course.id)), ("subjectId", .string(course.subjectId)), ("chapter", .string(course.chapter)),
            ("courseNumber", .number(Double(course.courseNumber))), ("title", .string(course.title)), ("date", .string(course.date)),
            ("description", course.description.map(JSValue.string)), ("createdAt", .string(course.createdAt)),
            ("source", course.source.map(json)), ("documents", course.documents.map { .array($0.map(json)) }),
        ].compactMap { key, value in value.map { (key, $0) } })
    }

    private static func json(_ source: CourseSource) -> JSValue {
        .object([("kind", .string(source.kind)), ("sessionKey", .string(source.sessionKey)),
                 ("subjectLabel", .string(source.subjectLabel)), ("date", .string(source.date))]
                + (source.ordinal.map { [("ordinal", JSValue.number(Double($0)))] } ?? []))
    }

    /// Métadonnées seulement : le contenu des fichiers ne quitte jamais l'appareil par l'export.
    private static func json(_ document: CourseDocument) -> JSValue {
        .object([
            ("id", .string(document.id)), ("name", .string(document.name)), ("mime", .string(document.mime)),
            ("size", .number(Double(document.size))), ("file", .string(document.file)),
            ("extractedTitle", document.extractedTitle.map(JSValue.string)),
        ].compactMap { key, value in value.map { (key, $0) } })
    }

    private static func json(_ imports: [String: NotebookImport]) -> JSValue {
        .object(imports.keys.sorted().compactMap { key in
            guard let item = imports[key] else { return nil }
            return (key, .object([
                ("status", .string(item.status.rawValue)), ("courseId", item.courseId.map(JSValue.string)), ("at", .string(item.at)),
            ].compactMap { name, value in value.map { (name, $0) } }))
        })
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
            ("notebookAutoCreate", settings.notebookAutoCreate.map(JSValue.bool)),
            ("notebookAutoCreateSince", settings.notebookAutoCreateSince.map(JSValue.string)),
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
