import Foundation

/// Constantes portées de `src/lib/constants.ts`.
enum Constants {
    static let defaultSubjects: [Subject] = [
        .init(id: "maths", name: "Mathématiques", color: "#38BDF8", description: "Algèbre, Géométrie, Arithmétique, Probabilités"),
        .init(id: "francais", name: "Français", color: "#EF4444", description: "Grammaire, Littérature, Rédaction, Orthographe"),
        .init(id: "physique-chimie", name: "Physique-chimie", color: "#6366F1", description: "Énergie, Mouvement, Organisation de la matière"),
        .init(id: "histoire-geo", name: "Histoire-géographie", color: "#94A3B8", description: "Histoire, Géographie, EMC"),
        .init(id: "espagnol", name: "Espagnol", color: "#1D4ED8", description: "Vocabulaire, Grammaire, Conjugaison"),
        .init(id: "musique", name: "Musique", color: "#84CC16", description: "Éducation musicale et chant"),
        .init(id: "svt", name: "S.V.T.", color: "#14B8A6", description: "Sciences de la Vie et de la Terre, Génétique"),
        .init(id: "anglais", name: "Anglais", color: "#8B5CF6", description: "Grammar, Vocabulary, Reading & Listening"),
        .init(id: "techno", name: "Technologie", color: "#B45309", description: "Design, Informatique, Systèmes automatisés"),
        .init(id: "arts-plastiques", name: "Arts plastiques", color: "#EC4899", description: "Pratiques artistiques, Histoire des arts"),
    ]

    static let defaultIntervals = [0, 1, 2, 4, 7, 14, 25, 45, 60, 90]

    static let defaultSettings = AppSettings(
        intervals: defaultIntervals,
        sensitivity: 0.4,
        emergencyThreshold: 30,
        pronoteIcalUrl: "",
        soundEnabled: true,
        userName: "Élève",
        userGrade: "3ème"
    )

    struct PhaseConfig: Sendable {
        let label: String
        let color: String
        let description: String
    }

    static func phaseConfig(_ phase: Phase) -> PhaseConfig {
        switch phase {
        case .encodage: .init(label: "Découvrir", color: "#2563EB", description: "Apprendre et comprendre un nouveau cours (J0)")
        case .consolidation: .init(label: "S’entraîner", color: "#F97316", description: "Revoir pour fixer la mémorisation (J1, J2, J4)")
        case .recuperation: .init(label: "Se tester", color: "#10B981", description: "Rappel actif sans regarder le cours (J7 à J90)")
        }
    }
}

extension Constants {
    /// Étapes affichées dans Aujourd'hui (`DashboardView.tsx`).
    struct TodayPhase: Sendable {
        let label: String
        let hint: String
        let symbol: String
    }

    static func todayPhase(_ phase: Phase) -> TodayPhase {
        switch phase {
        case .encodage: .init(label: "Découvrir", hint: "Comprends et reformule ton cours.", symbol: "01")
        case .consolidation: .init(label: "S’entraîner", hint: "Entraîne-toi à retrouver les idées clés.", symbol: "02")
        case .recuperation: .init(label: "Se tester", hint: "Essaie de te rappeler sans regarder.", symbol: "03")
        }
    }

    struct ScorePreset: Sendable, Identifiable {
        let value: Int
        let sub: String
        var id: Int { value }
    }

    /// Préréglages de l'autoévaluation (`ScoreModal.tsx`) ; curseur de 0 à 100 par pas de 5, valeur initiale 50.
    static let scorePresets: [ScorePreset] = [
        .init(value: 0, sub: "Pas encore"), .init(value: 25, sub: "Difficile"), .init(value: 50, sub: "Ça revient"),
        .init(value: 75, sub: "Maîtrisé"), .init(value: 100, sub: "Parfait"),
    ]
    static let scoreStep = 5
    static let initialScore = 50
}

extension Subject {
    init(id: String, name: String, color: String, description: String?) {
        self.init(id: id, name: name, color: color, description: description, isCustom: nil)
    }
}
