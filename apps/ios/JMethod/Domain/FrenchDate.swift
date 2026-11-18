import Foundation

/// Formats de date de date-fns (`locale: fr`) utilisés par le Planning et Mes cours, recopiés à l'identique.
/// Les noms sont tabulés plutôt que lus dans ICU, pour que le résultat ne dépende ni de la version d'iOS
/// ni de celle d'Android (« févr. », « juil. », « lun. »).
enum FrenchDate {
    /// Lundi en premier, comme `weekStartsOn: 1`.
    static let weekdaysLong = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
    static let weekdaysShort = ["lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim."]
    static let monthsLong = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"]
    static let monthsShort = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."]

    /// Motif date-fns limité aux jetons `EEEE`, `EEE`, `d`, `dd`, `MMMM`, `MMM`, `MM`, `yyyy` ; le reste est recopié.
    /// Une date invalide est rendue telle quelle.
    static func format(_ day: String, _ pattern: String) -> String {
        guard SchoolDay.isValid(day), let parts = SchoolDay.components(day), let weekday = SchoolDay.weekdayIndex(day) else { return day }
        var output = ""
        let characters = Array(pattern)
        var index = 0
        while index < characters.count {
            let letter = characters[index]
            var run = 1
            while index + run < characters.count && characters[index + run] == letter { run += 1 }
            switch (letter, run) {
            case ("E", 4...): output += weekdaysLong[weekday]
            case ("E", _): output += weekdaysShort[weekday]
            case ("d", 1): output += String(parts.day)
            case ("d", _): output += String(format: "%02d", parts.day)
            case ("M", 4...): output += monthsLong[parts.month - 1]
            case ("M", 3): output += monthsShort[parts.month - 1]
            case ("M", 2): output += String(format: "%02d", parts.month)
            case ("M", _): output += String(parts.month)
            case ("y", _): output += String(format: "%04d", parts.year)
            default: output += String(repeating: letter, count: run)
            }
            index += run
        }
        return output
    }
}

extension SchoolDay {
    /// 0 pour lundi … 6 pour dimanche.
    static func weekdayIndex(_ day: String) -> Int? {
        guard let parts = components(day) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day)) else { return nil }
        // Calendar : 1 = dimanche … 7 = samedi.
        return (calendar.component(.weekday, from: date) + 5) % 7
    }

    /// `startOfWeek(date, { weekStartsOn: 1 })` : le lundi de la semaine (un dimanche appartient à la semaine commencée le lundi précédent).
    static func startOfWeek(_ day: String) -> String? {
        guard let weekday = weekdayIndex(day) else { return nil }
        return adding(-weekday, to: day)
    }

    /// `addMonths` de date-fns : même jour, ramené au dernier jour du mois si besoin (31 janvier + 1 mois = 28 février).
    static func addingMonths(_ months: Int, to day: String) -> String? {
        guard isValid(day), let parts = components(day) else { return nil }
        let total = parts.year * 12 + (parts.month - 1) + months
        let year = Int((Double(total) / 12).rounded(.down))
        let month = total - year * 12 + 1
        let value = min(parts.day, daysInMonth(year: year, month: month))
        return format(year: year, month: month, day: value)
    }

    static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2: return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    /// Jour (`yyyy-MM-dd`) d'une date choisie dans un sélecteur, lue au fuseau Europe/Paris.
    static func day(from date: Date) -> String { today(now: date) }
}

extension String {
    /// Majuscule initiale seulement (typographie française, écart D11) : « lundi 30 mars » → « Lundi 30 mars ».
    var capitalizedFirst: String {
        guard let first else { return self }
        return first.uppercased() + dropFirst()
    }
}
