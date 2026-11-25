import Foundation

// Schéma identique à `src/types/index.ts` : les clés JSON et les champs optionnels
// suivent le web pour que le document reste échangeable avec son export.

enum Phase: String, Codable, Sendable, CaseIterable {
    case encodage, consolidation, recuperation
}

struct Subject: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var name: String
    var color: String
    var description: String?
    var isCustom: Bool?
}

struct Course: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var subjectId: String
    var chapter: String
    var courseNumber: Int
    var title: String
    /// YYYY-MM-DD, date du cours (J0).
    var date: String
    var description: String?
    var createdAt: String
    /// Cours créé depuis une séance du cahier de textes (`notebook-to-course.md`) ; absent pour un cours saisi.
    var source: CourseSource? = nil
    /// Documents joints, copiés dans le stockage privé de l'app (métadonnées seulement dans le JSON).
    var documents: [CourseDocument]? = nil
    var reference: String? = nil
    var sources: [CourseSource]? = nil
    var displayReference: String { reference.flatMap { $0.isEmpty ? nil : $0 } ?? "C\(courseNumber)" }
}

/// Origine d'un cours créé depuis le cahier de textes.
struct CourseSource: Codable, Hashable, Sendable {
    static let notebookKind = "pronote-notebook"
    var kind: String
    /// Clé de dédoublonnage stable entre sessions Pronote : `pn1:<date>|<matière normalisée>|<rang>`.
    var sessionKey: String
    /// Matière telle que Pronote l'écrit.
    var subjectLabel: String
    /// `yyyy-MM-dd`, date de la séance.
    var date: String
    /// Rang de la séance parmi celles de même date et même matière (absent des données créées avant la clé `pn1:`).
    var ordinal: Int? = nil
}

/// Document d'un cours : fichier local `file` (relatif au dossier Application Support), jamais d'URL Pronote.
struct CourseDocument: Codable, Hashable, Sendable, Identifiable {
    var id: String
    var name: String
    var mime: String
    var size: Int
    /// `documents/<courseId>/<docId>.<ext>`
    var file: String
    var extractedTitle: String?
    /// Séance Pronote de ce fichier : deux séances peuvent employer le même nom.
    var sourceSessionKey: String? = nil
}

/// Suivi des séances du cahier de textes déjà transformées en cours (ou refusées) : jamais recréées automatiquement.
struct NotebookImport: Codable, Hashable, Sendable {
    enum Status: String, Codable, Sendable { case created, dismissed }
    var status: Status
    var courseId: String?
    /// Instant ISO 8601.
    var at: String
}

enum RevisionStatus: String, Codable, Sendable {
    case pending, completed, overdue
}

struct Revision: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var courseId: String
    var stepIndex: Int
    var stepLabel: String
    var scheduledDate: String
    var completedDate: String?
    var score: Int?
    var status: RevisionStatus
    var phase: Phase
    var adjustedIntervalDays: Int?
    var isEmergencySession: Bool?
    var programmeId: String? = nil
    var courseIds: [String]? = nil
    var rhythmStepId: String? = nil
    var nominalOffsetDays: Int? = nil
    var originRevisionId: String? = nil
    func applies(to id: String) -> Bool { (courseIds ?? [courseId]).contains(id) }
}

struct PronoteEvent: Codable, Identifiable, Hashable, Sendable {
    var id: String
    var subjectName: String
    var matchedSubjectId: String?
    var date: String
    var startTime: String
    var endTime: String
    var room: String?
    var teacher: String?
    var isCancelled: Bool
    var isCourseCreated: Bool?
}

struct AppSettings: Codable, Hashable, Sendable {
    var intervals: [Int]
    var sensitivity: Double
    var emergencyThreshold: Double
    var pronoteIcalUrl: String
    var lastPronoteSync: String?
    var soundEnabled: Bool
    var userName: String
    var userGrade: String
    /// « Créer les cours automatiquement » depuis le cahier de textes (absent = désactivé).
    var notebookAutoCreate: Bool?
    /// `yyyy-MM-dd` : jour d'activation de la création automatique (séances éligibles à partir de ce jour).
    var notebookAutoCreateSince: String?
    var rhythm: RevisionRhythm? = nil
    var adaptiveEnabled: Bool = true

    var autoCreatesCourses: Bool { notebookAutoCreate == true }

    init(intervals: [Int], sensitivity: Double, emergencyThreshold: Double, pronoteIcalUrl: String,
         lastPronoteSync: String? = nil, soundEnabled: Bool, userName: String, userGrade: String,
         notebookAutoCreate: Bool? = nil, notebookAutoCreateSince: String? = nil) {
        self.intervals = intervals
        self.sensitivity = sensitivity
        self.emergencyThreshold = emergencyThreshold
        self.pronoteIcalUrl = pronoteIcalUrl
        self.lastPronoteSync = lastPronoteSync
        self.soundEnabled = soundEnabled
        self.userName = userName
        self.userGrade = userGrade
        self.notebookAutoCreate = notebookAutoCreate
        self.notebookAutoCreateSince = notebookAutoCreateSince
    }

    /// Comme `loadSettings` du web (`{ ...DEFAULT_SETTINGS, ...saved }`) : chaque clé absente reprend la valeur par défaut.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = Constants.defaultSettings
        intervals = try container.decodeIfPresent([Int].self, forKey: .intervals) ?? defaults.intervals
        sensitivity = try container.decodeIfPresent(Double.self, forKey: .sensitivity) ?? defaults.sensitivity
        emergencyThreshold = try container.decodeIfPresent(Double.self, forKey: .emergencyThreshold) ?? defaults.emergencyThreshold
        pronoteIcalUrl = try container.decodeIfPresent(String.self, forKey: .pronoteIcalUrl) ?? defaults.pronoteIcalUrl
        lastPronoteSync = try container.decodeIfPresent(String.self, forKey: .lastPronoteSync)
        soundEnabled = try container.decodeIfPresent(Bool.self, forKey: .soundEnabled) ?? defaults.soundEnabled
        userName = try container.decodeIfPresent(String.self, forKey: .userName) ?? defaults.userName
        userGrade = try container.decodeIfPresent(String.self, forKey: .userGrade) ?? defaults.userGrade
        notebookAutoCreate = try container.decodeIfPresent(Bool.self, forKey: .notebookAutoCreate)
        notebookAutoCreateSince = try container.decodeIfPresent(String.self, forKey: .notebookAutoCreateSince)
        rhythm = try container.decodeIfPresent(RevisionRhythm.self, forKey: .rhythm)
        adaptiveEnabled = try container.decodeIfPresent(Bool.self, forKey: .adaptiveEnabled) ?? true
    }
}

/// Document unique de l'app. Un export JSON du web (`exportAllData`) se décode aussi :
/// ses clés `version` et `exportDate` sont ignorées, les clés absentes reprennent les valeurs du web.
struct AppDocument: Codable, Equatable, Sendable {
    var settings: AppSettings
    var subjects: [Subject]
    var courses: [Course]
    var revisions: [Revision]
    var pronoteEvents: [PronoteEvent]
    var initialized: Bool
    /// Séances du cahier de textes déjà importées ou refusées, par `sessionKey`.
    var notebookImports: [String: NotebookImport] = [:]
    var programmes: [RevisionProgramme] = []
    var schemaVersion: Int = 1
    var streakState: StreakState? = nil
    var todayWidgetVisit: TodayWidgetVisit? = nil

    private enum CodingKeys: String, CodingKey {
        case settings, subjects, courses, revisions, pronoteEvents, initialized, notebookImports, programmes, schemaVersion, streakState, todayWidgetVisit
    }

    init(settings: AppSettings, subjects: [Subject], courses: [Course], revisions: [Revision], pronoteEvents: [PronoteEvent], initialized: Bool,
         notebookImports: [String: NotebookImport] = [:]) {
        self.settings = settings
        self.subjects = subjects
        self.courses = courses
        self.revisions = revisions
        self.pronoteEvents = pronoteEvents
        self.initialized = initialized
        self.notebookImports = notebookImports
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        settings = try container.decodeIfPresent(AppSettings.self, forKey: .settings) ?? Constants.defaultSettings
        subjects = try container.decodeIfPresent([Subject].self, forKey: .subjects) ?? Constants.defaultSubjects
        courses = try container.decodeIfPresent([Course].self, forKey: .courses) ?? []
        revisions = try container.decodeIfPresent([Revision].self, forKey: .revisions) ?? []
        pronoteEvents = try container.decodeIfPresent([PronoteEvent].self, forKey: .pronoteEvents) ?? []
        // Un fichier existant contient des données : il est considéré comme initialisé.
        initialized = try container.decodeIfPresent(Bool.self, forKey: .initialized) ?? true
        notebookImports = (try? container.decodeIfPresent([String: NotebookImport].self, forKey: .notebookImports)) ?? [:]
        programmes = try container.decodeIfPresent([RevisionProgramme].self, forKey: .programmes) ?? []
        // Un journal illisible ou incohérent ne doit pas coûter tout le document : un nouveau suivi démarre à la prochaine ouverture.
        streakState = (try? container.decodeIfPresent(StreakState.self, forKey: .streakState)) ?? nil
        if let streakState, (try? RevisionStreak.validate(streakState)) == nil { self.streakState = nil }
        todayWidgetVisit = try? container.decodeIfPresent(TodayWidgetVisit.self, forKey: .todayWidgetVisit)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        guard (1...3).contains(schemaVersion) else { throw DecodingError.dataCorruptedError(forKey: .schemaVersion, in: container, debugDescription: "Cette sauvegarde nécessite une version plus récente de J-Method.") }
    }

    /// `notebookImports` n'est écrit que s'il contient des séances (fichier identique à celui du web sinon).
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(settings, forKey: .settings)
        try container.encode(subjects, forKey: .subjects)
        try container.encode(courses, forKey: .courses)
        try container.encode(revisions, forKey: .revisions)
        try container.encode(pronoteEvents, forKey: .pronoteEvents)
        try container.encode(initialized, forKey: .initialized)
        if !notebookImports.isEmpty { try container.encode(notebookImports, forKey: .notebookImports) }
        if !programmes.isEmpty { try container.encode(programmes, forKey: .programmes) }
        if schemaVersion > 1 { try container.encode(schemaVersion, forKey: .schemaVersion) }
        try container.encodeIfPresent(streakState, forKey: .streakState)
        try container.encodeIfPresent(todayWidgetVisit, forKey: .todayWidgetVisit)
    }
}
