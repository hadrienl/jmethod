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

    init(intervals: [Int], sensitivity: Double, emergencyThreshold: Double, pronoteIcalUrl: String,
         lastPronoteSync: String? = nil, soundEnabled: Bool, userName: String, userGrade: String) {
        self.intervals = intervals
        self.sensitivity = sensitivity
        self.emergencyThreshold = emergencyThreshold
        self.pronoteIcalUrl = pronoteIcalUrl
        self.lastPronoteSync = lastPronoteSync
        self.soundEnabled = soundEnabled
        self.userName = userName
        self.userGrade = userGrade
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

    init(settings: AppSettings, subjects: [Subject], courses: [Course], revisions: [Revision], pronoteEvents: [PronoteEvent], initialized: Bool) {
        self.settings = settings
        self.subjects = subjects
        self.courses = courses
        self.revisions = revisions
        self.pronoteEvents = pronoteEvents
        self.initialized = initialized
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
    }
}
