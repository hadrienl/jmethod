import Foundation

/// Jours calendaires `YYYY-MM-DD`, comme le web. L'arithmétique se fait sur des jours entiers
/// (calendrier grégorien en UTC), donc indépendamment des changements d'heure.
/// Le « jour courant » est lu au fuseau Europe/Paris.
enum SchoolDay {
    static let timeZone = TimeZone(identifier: "Europe/Paris")!

    static var parisCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.locale = Locale(identifier: "fr_FR")
        return calendar
    }

    private static var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    /// Jour courant au fuseau Europe/Paris.
    static func today(now: Date = Date()) -> String {
        let parts = parisCalendar.dateComponents([.year, .month, .day], from: now)
        return format(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    static func components(_ day: String) -> (year: Int, month: Int, day: Int)? {
        let parts = day.prefix(10).split(separator: "-")
        guard day.count >= 10, parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]), let value = Int(parts[2]),
              (1...12).contains(month), (1...31).contains(value) else { return nil }
        return (year, month, value)
    }

    /// Minuit UTC du jour : sert uniquement aux calculs de différence de jours.
    private static func utcDate(_ day: String) -> Date? {
        guard let parts = components(day) else { return nil }
        let date = utcCalendar.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day))
        // Refuse les dates impossibles (31 novembre) que Calendar normaliserait.
        guard let date, format(utcCalendar.dateComponents([.year, .month, .day], from: date)) == String(day.prefix(10)) else { return nil }
        return date
    }

    /// Équivalent de `format(addDays(parseISO(day), n), 'yyyy-MM-dd')`. Retourne nil pour une date invalide.
    static func adding(_ days: Int, to day: String) -> String? {
        guard let date = utcDate(day), let result = utcCalendar.date(byAdding: .day, value: days, to: date) else { return nil }
        return format(utcCalendar.dateComponents([.year, .month, .day], from: result))
    }

    /// Nombre de jours calendaires de `from` à `to`.
    static func daysBetween(_ from: String, _ to: String) -> Int? {
        guard let start = utcDate(from), let end = utcDate(to) else { return nil }
        return utcCalendar.dateComponents([.day], from: start, to: end).day
    }

    static func isValid(_ day: String) -> Bool { day.count == 10 && utcDate(day) != nil }

    /// Midi heure de Paris du jour : point d'ancrage stable pour l'affichage.
    static func displayDate(_ day: String) -> Date? {
        guard let parts = components(day) else { return nil }
        return parisCalendar.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day, hour: 12))
    }

    /// « mardi 17 novembre »
    static func longLabel(_ day: String) -> String { formatted(day, "EEEE d MMMM") }
    /// « 17 novembre 2026 »
    static func fullLabel(_ day: String) -> String { formatted(day, "d MMMM yyyy") }
    /// « 17/11/2026 »
    static func shortLabel(_ day: String) -> String { formatted(day, "dd/MM/yyyy") }

    private static func formatted(_ day: String, _ pattern: String) -> String {
        guard let date = displayDate(day) else { return day }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.timeZone = timeZone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    private static func format(_ parts: DateComponents) -> String {
        format(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    static func format(year: Int, month: Int, day: Int) -> String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }
}

/// `Math.round` de JavaScript : arrondi au plus proche, égalité vers +infini.
func jsRound(_ value: Double) -> Int {
    let floor = value.rounded(.down)
    return Int(value - floor >= 0.5 ? floor + 1 : floor)
}
