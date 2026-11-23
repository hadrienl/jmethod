import Foundation

/// Réglages, portés de `SettingsView.tsx` (suite `settingsUpdate`). Rien n'est enregistré avant « Enregistrer »,
/// sauf les couleurs des matières, enregistrées par « Enregistrer » de leur propre sélecteur (#8).
enum SettingsLogic {
    static let sensitivityRange = 0.1...0.8
    static let sensitivityStep = 0.05
    static let thresholdRange = 10.0...40.0
    static let thresholdStep = 5.0
    /// Un palier ne peut être retiré que s'il en reste plus de deux.
    static let removeRequiresMoreThan = 2

    /// `parseInt(value, 10)` de JavaScript : blancs initiaux ignorés, signe facultatif, préfixe de chiffres
    /// (`"12abc"` → 12, `"1.9"` → 1, `"1e3"` → 1). nil pour `NaN`.
    static func jsParseInt(_ value: String) -> Int? {
        var scalars = Substring(value).unicodeScalars.drop { $0.properties.isWhitespace || $0 == "\u{FEFF}" }
        var negative = false
        if let sign = scalars.first, sign == "+" || sign == "-" {
            negative = sign == "-"
            scalars = scalars.dropFirst()
        }
        let digits = scalars.prefix { $0.value >= 48 && $0.value <= 57 }
        guard !digits.isEmpty else { return nil }
        let trimmed = String(String.UnicodeScalarView(digits)).drop { $0 == "0" }
        guard trimmed.count <= 10, let number = Int(trimmed.isEmpty ? "0" : String(trimmed)) else { return nil }
        return negative ? -number : number
    }

    /// Ajout d'un palier : nombre `>= 0` absent de la liste, puis tri.
    static func addInterval(_ intervals: [Int], value: String) -> [Int] {
        guard let number = jsParseInt(value), number >= 0, number <= Int(Int32.max), !intervals.contains(number) else { return intervals }
        return (intervals + [number]).sorted()
    }

    /// Retrait d'un palier : seulement s'il en reste plus de deux.
    static func removeInterval(_ intervals: [Int], value: Int) -> [Int] {
        intervals.count > removeRequiresMoreThan ? intervals.filter { $0 != value } : intervals
    }

    /// J0 n'a pas de bouton de retrait.
    static func removeButtonShown(_ value: Int) -> Bool { value != 0 }

    static func resetIntervals() -> [Int] { Constants.defaultIntervals }

    /// « Enregistrer » : autres champs conservés, URL iCal nettoyée, paliers triés.
    static func save(_ settings: AppSettings, icalUrl: String, sensitivity: Double, emergencyThreshold: Double, intervals: [Int]) -> AppSettings {
        var result = settings
        result.pronoteIcalUrl = icalUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        result.sensitivity = sensitivity
        result.emergencyThreshold = emergencyThreshold
        result.intervals = intervals.sorted()
        return result
    }

    /// Valeur du curseur ramenée au pas de 0,05 (évite les imprécisions du curseur natif).
    static func snapSensitivity(_ value: Double) -> Double {
        let snapped = (value / sensitivityStep).rounded() * sensitivityStep
        return min(max((snapped * 100).rounded() / 100, sensitivityRange.lowerBound), sensitivityRange.upperBound)
    }

    static func snapThreshold(_ value: Double) -> Double {
        min(max((value / thresholdStep).rounded() * thresholdStep, thresholdRange.lowerBound), thresholdRange.upperBound)
    }

    /// `String(number)` de JavaScript pour une valeur du curseur.
    static func jsNumber(_ value: Double) -> String {
        if value.rounded() == value, abs(value) < 1e15 { return String(Int(value)) }
        return String(value)
    }

    static func sensitivityLabel(_ sensitivity: Double) -> String { "Impact max : ±\(jsRound(sensitivity * 100))%" }

    static func thresholdLabel(_ threshold: Double) -> String { "Seuil d'urgence (Rattrapage J+1) : < \(jsNumber(threshold))/100" }

    /// Seule la couleur change ; l'ordre et les autres champs sont conservés.
    static func subjectColor(_ subjects: [Subject], subjectId: String, color: String) -> [Subject] {
        subjects.map { subject in
            guard subject.id == subjectId else { return subject }
            var changed = subject
            changed.color = color
            return changed
        }
    }
}
