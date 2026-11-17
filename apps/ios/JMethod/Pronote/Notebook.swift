import Foundation

// Cahier de textes en lecture seule (`native/shared/notebook-reference.md`, vecteurs `notebook-vectors.json`) :
// fenêtre de 28 jours, dates, HTML vers texte sûr, liens, modèle. Aucune URL de téléchargement n'est conservée ici :
// seuls l'identifiant `N` et le nom d'une pièce jointe le sont.

struct NotebookLink: Equatable, Hashable, Sendable {
    var label: String
    var url: String
}

struct NotebookAttachment: Equatable, Hashable, Sendable, Identifiable {
    enum Kind: String, Sendable { case file, link }
    var kind: Kind
    var id: String
    /// Nom affiché (texte nettoyé, « Document » ou « Lien » par défaut).
    var name: String
    /// Lien seulement.
    var url: String?
    /// Fichier seulement : `L` brut de Pronote (même vide), seul utilisé pour construire l'URL `FichiersExternes`.
    var fileName: String = ""
}

struct NotebookContent: Equatable, Sendable {
    var title: String
    var description: String
    var links: [NotebookLink]
    var category: String
    var attachments: [NotebookAttachment]
}

struct NotebookSession: Equatable, Sendable, Identifiable {
    var id: String
    var lessonId: String
    /// `yyyy-MM-dd`
    var date: String
    var subject: String
    var contents: [NotebookContent]
}

struct NotebookHomework: Equatable, Sendable, Identifiable {
    var id: String
    /// `yyyy-MM-dd`
    var dueDate: String
    var subject: String
    var description: String
    var links: [NotebookLink]
    /// État Pronote affiché tel quel, jamais modifiable.
    var done: Bool
    var attachments: [NotebookAttachment]
}

struct NotebookSnapshot: Equatable, Sendable {
    var from: String
    var to: String
    var sessions: [NotebookSession]
    var homework: [NotebookHomework]
    var skippedEntries: Int
    var attachmentCount: Int
    /// Onglet 88 indisponible pour ce compte : contenus seuls.
    var homeworkAvailable = true
}

enum NotebookRules {
    static let contentsTab = 89
    static let homeworkTab = 88
    static let maxWeekSpan = 5
    static let maxDownloadBytes = 20_971_520
    static let downloadUnavailable = "Document indisponible. Actualise le cahier de textes puis réessaie."
    static let downloadTooLarge = "Ce document dépasse la limite de 20 Mo."
    static let unavailable = "Le cahier de textes n’est pas disponible pour ce compte."

    struct Window: Equatable, Sendable {
        var today: String
        var from: String
        var to: String
        var firstWeek: Int
        var lastWeek: Int
        var domaine: String { "[\(firstWeek)..\(lastWeek)]" }
        func contains(_ day: String) -> Bool { day >= from && day <= to }
    }

    /// `semaine(d) = 1 + trunc(jours(d − PremierLundi) / 7)`.
    static func week(_ day: String, firstMonday: String) -> Int? { TimetableMath.weekNumber(day: day, firstMonday: firstMonday) }

    /// 14 jours avant, le jour même, 13 jours après ; première semaine ramenée à au moins 1.
    static func window(today: String, firstMonday: String) -> Window? {
        guard let from = SchoolDay.adding(-14, to: today), let to = SchoolDay.adding(13, to: today),
              let start = week(from, firstMonday: firstMonday), let end = week(to, firstMonday: firstMonday) else { return nil }
        let first = max(1, start)
        return Window(today: today, from: from, to: to, firstWeek: first, lastWeek: max(first, end))
    }

    // MARK: Dates

    private static let dateFormats: [NSRegularExpression] = [
        #"^(\d{2})/(\d{2})/(\d{4})$"#,
        #"^(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2}):(\d{2})$"#,
        #"^(\d{2})/(\d{2})/(\d{2})$"#,
        #"^(\d{2})/(\d{2})/(\d{2}) (\d{2})h(\d{2})$"#,
    ].map { try! NSRegularExpression(pattern: $0) }

    /// Dates PRONOTE en correspondance exacte ; seule la partie date est gardée, sans fuseau.
    static func parseDate(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        let text = raw as NSString
        for format in dateFormats {
            guard let match = format.firstMatch(in: raw, range: NSRange(location: 0, length: text.length)) else { continue }
            let values = (1..<match.numberOfRanges).map { Int(text.substring(with: match.range(at: $0))) ?? -1 }
            let yearText = text.substring(with: match.range(at: 3))
            let year = yearText.count == 2 ? 2000 + values[2] : values[2]
            if values.count > 3 {
                if values[3] > 23 || values[4] > 59 { return nil }
                if values.count > 5 && values[5] > 59 { return nil }
            }
            let day = SchoolDay.format(year: year, month: values[1], day: values[0])
            return SchoolDay.isValid(day) ? day : nil
        }
        return nil
    }

    // MARK: Texte sûr

    private static func isRemoved(_ code: UInt32) -> Bool {
        code <= 0x1F || (0x7F...0x9F).contains(code) || (0x202A...0x202E).contains(code) || (0x2066...0x2069).contains(code) || code == 0xFEFF
    }

    private static func isBlank(_ code: UInt32) -> Bool {
        code == 0x09 || code == 0x0A || code == 0x0B || code == 0x0C || code == 0x0D || code == 0x20 || code == 0xA0
    }

    /// Blancs en espaces, caractères de contrôle et de direction retirés.
    static func cleanChars(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            if isBlank(scalar.value) { scalars.append(" ") } else if !isRemoved(scalar.value) { scalars.append(scalar) }
        }
        return String(scalars)
    }

    private static func collapseSpaces(_ text: String) -> String {
        var result = ""
        var previousSpace = false
        for character in text {
            if character == " " {
                if !previousSpace { result.append(" ") }
                previousSpace = true
            } else {
                result.append(character)
                previousSpace = false
            }
        }
        return result
    }

    private static func trimSpaces(_ text: String) -> String {
        String(text.drop { $0 == " " }.reversed().drop { $0 == " " }.reversed())
    }

    /// Texte brut (titres, matières, catégories, noms) : sans décodage d'entités, espaces réduits, rogné.
    static func plainText(_ raw: String?) -> String { trimSpaces(collapseSpaces(cleanChars(raw ?? ""))) }

    private static let entity = try! NSRegularExpression(pattern: "&(?:#([0-9]{1,7})|#[xX]([0-9A-Fa-f]{1,6})|([A-Za-z][A-Za-z0-9]{0,31}));")

    /// Entités HTML 4.01 + `apos` ; numériques invalides en U+FFFD ; inconnues laissées telles quelles.
    static func decodeEntities(_ text: String) -> String {
        let source = text as NSString
        var result = ""
        var last = 0
        for match in entity.matches(in: text, range: NSRange(location: 0, length: source.length)) {
            result += source.substring(with: NSRange(location: last, length: match.range.location - last))
            last = match.range.location + match.range.length
            let group: (Int) -> String? = { index in
                let range = match.range(at: index)
                return range.location == NSNotFound ? nil : source.substring(with: range)
            }
            let code: UInt32?
            if let decimal = group(1) {
                code = UInt32(decimal)
            } else if let hexadecimal = group(2) {
                code = UInt32(hexadecimal, radix: 16)
            } else if let name = group(3), let value = HTMLEntities.table[name] {
                code = value
            } else {
                result += source.substring(with: match.range)
                continue
            }
            if let code, code != 0, !(0xD800...0xDFFF).contains(code), code <= 0x10FFFF, let scalar = Unicode.Scalar(code) {
                result.unicodeScalars.append(scalar)
            } else {
                result += "\u{FFFD}"
            }
        }
        result += source.substring(from: last)
        return result
    }

    struct SafeText: Equatable {
        var text: String
        var links: [NotebookLink]
    }

    private static let rawTextElements: Set<String> = [
        "head", "iframe", "math", "noembed", "noframes", "noscript", "object", "script", "style", "svg", "template", "textarea", "title", "xmp",
    ]
    private static let blockElements: Set<String> = [
        "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt", "figcaption", "figure", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section",
        "table", "tbody", "tfoot", "thead", "tr", "ul",
    ]

    /// Unité UTF-16 d'un caractère ASCII.
    private static func u(_ scalar: Unicode.Scalar) -> UInt16 { UInt16(scalar.value) }

    private static func isAsciiLetter(_ unit: UInt16) -> Bool { (65...90).contains(unit) || (97...122).contains(unit) }
    private static func isAsciiDigit(_ unit: UInt16) -> Bool { (48...57).contains(unit) }
    private static func isAsciiBlank(_ unit: UInt16) -> Bool { unit == 0x20 || unit == 0x09 || unit == 0x0A || unit == 0x0B || unit == 0x0C || unit == 0x0D }

    private static func indexOf(_ needle: [UInt16], in haystack: [UInt16], from start: Int) -> Int? {
        guard !needle.isEmpty, start <= haystack.count - needle.count else { return nil }
        var index = max(0, start)
        while index <= haystack.count - needle.count {
            if haystack[index] == needle[0] && Array(haystack[index..<index + needle.count]) == needle { return index }
            index += 1
        }
        return nil
    }

    private static func lowerASCII(_ units: ArraySlice<UInt16>) -> String {
        String(decoding: units.map { (65...90).contains($0) ? $0 + 32 : $0 }, as: UTF16.self)
    }

    /// HTML des enseignants vers texte affiché comme texte brut (section 7) : balayage simple, sans exécution
    /// ni ressource distante. Les liens `http(s)` valides sont extraits à part.
    static func safeText(_ html: String?) -> SafeText {
        let input = Array((html ?? "").utf16)
        let n = input.count
        var out = ""
        var links: [NotebookLink] = []
        var linkStart: Int?
        var linkURL: String?
        var segment: [UInt16] = []

        func flush() {
            guard !segment.isEmpty else { return }
            out += cleanChars(decodeEntities(String(decoding: segment, as: UTF16.self)))
            segment.removeAll()
        }
        func softBreak() {
            let trimmed = out.reversed().drop { $0 == " " }
            if !trimmed.isEmpty && trimmed.first != "\n" { out += "\n" }
        }
        func closeLink() {
            if let start = linkStart, let url = linkURL {
                let units = Array(out.utf16)
                let raw = String(decoding: units[min(start, units.count)...], as: UTF16.self).replacingOccurrences(of: "\n", with: " ")
                let label = trimSpaces(collapseSpaces(raw))
                links.append(NotebookLink(label: label.isEmpty ? url : label, url: url))
            }
            linkStart = nil
            linkURL = nil
        }

        let lt = u("<"), gt = u(">"), slash = u("/")
        var i = 0
        scan: while i < n {
            let c = input[i]
            if c != lt {
                segment.append(c)
                i += 1
                continue
            }
            if i + 3 < n, input[i + 1] == u("!"), input[i + 2] == u("-"), input[i + 3] == u("-") {
                flush()
                i = indexOf(Array("-->".utf16), in: input, from: i + 4).map { $0 + 3 } ?? n
                continue
            }
            if i + 1 < n, input[i + 1] == u("!") || input[i + 1] == u("?") {
                flush()
                i = indexOf([gt], in: input, from: i + 2).map { $0 + 1 } ?? n
                continue
            }
            let closing = i + 1 < n && input[i + 1] == slash
            let nameStart = closing ? i + 2 : i + 1
            if nameStart >= n || !isAsciiLetter(input[nameStart]) {
                segment.append(c)
                i += 1
                continue
            }
            // Balise : nom, puis fin au premier `>` hors valeur entre guillemets (après `=`).
            var j = nameStart
            while j < n, isAsciiLetter(input[j]) || isAsciiDigit(input[j]) || input[j] == u("-") || input[j] == u(":") { j += 1 }
            let name = lowerASCII(input[nameStart..<j])
            let attributeStart = j
            var afterEquals = false
            var end: Int?
            while j < n {
                let ch = input[j]
                if ch == gt {
                    end = j
                    break
                } else if ch == u("=") {
                    afterEquals = true
                    j += 1
                } else if isAsciiBlank(ch) {
                    j += 1
                } else if (ch == u("\"") || ch == u("'")) && afterEquals {
                    guard let close = indexOf([ch], in: input, from: j + 1) else { j = n; break }
                    j = close + 1
                    afterEquals = false
                } else {
                    afterEquals = false
                    j += 1
                }
            }
            flush()
            // Balise non terminée : tout le reste est ignoré.
            guard let tagEnd = end else { break scan }
            let attributes = Array(input[attributeStart..<tagEnd])
            i = tagEnd + 1
            if !closing && rawTextElements.contains(name) {
                // Contenu supprimé jusqu'à `</nom` suivi d'un blanc, `/`, `>` ou de la fin.
                let nameUnits = Array(name.utf16)
                var k = i
                var resume = n
                while let at = indexOf([lt, slash], in: input, from: k) {
                    let after = at + 2 + nameUnits.count
                    let candidate = after <= n && lowerASCII(input[(at + 2)..<after]) == name
                    if candidate && (after >= n || isAsciiBlank(input[after]) || input[after] == slash || input[after] == gt) {
                        resume = indexOf([gt], in: input, from: after).map { $0 + 1 } ?? n
                        break
                    }
                    k = at + 2
                }
                i = resume
                continue
            }
            if name == "br" {
                out += "\n"
            } else if blockElements.contains(name) {
                softBreak()
                if name == "li" && !closing { out += "• " }
            } else if name == "td" || name == "th" {
                out += " "
            } else if name == "a" {
                closeLink()
                if !closing {
                    linkStart = out.utf16.count
                    linkURL = href(attributes).flatMap { validLinkURL(decodeEntities($0)) }
                }
            }
        }
        flush()
        closeLink()
        return SafeText(text: normalize(out), links: links)
    }

    /// Premier attribut `href` (nom insensible à la casse), valeur entre guillemets ou jusqu'au blanc ou `>`.
    private static func href(_ attributes: [UInt16]) -> String? {
        let n = attributes.count
        let slash = u("/"), equals = u("=")
        var i = 0
        while i < n {
            while i < n && (isAsciiBlank(attributes[i]) || attributes[i] == slash) { i += 1 }
            let start = i
            while i < n && !isAsciiBlank(attributes[i]) && attributes[i] != equals && attributes[i] != slash { i += 1 }
            let name = lowerASCII(attributes[start..<i])
            while i < n && isAsciiBlank(attributes[i]) { i += 1 }
            var value: String?
            if i < n && attributes[i] == equals {
                i += 1
                while i < n && isAsciiBlank(attributes[i]) { i += 1 }
                if i < n && (attributes[i] == u("\"") || attributes[i] == u("'")) {
                    let quote = attributes[i]
                    let close = indexOf([quote], in: attributes, from: i + 1) ?? n
                    value = String(decoding: attributes[(i + 1)..<close], as: UTF16.self)
                    i = close + 1
                } else {
                    let valueStart = i
                    while i < n && !isAsciiBlank(attributes[i]) { i += 1 }
                    value = String(decoding: attributes[valueStart..<i], as: UTF16.self)
                }
            }
            if name == "href" { return value ?? "" }
            if start == i && value == nil { i += 1 }
        }
        return nil
    }

    private static func normalize(_ text: String) -> String {
        let lines = collapseSpaces(text).components(separatedBy: "\n").map(trimSpaces)
        var joined = lines.joined(separator: "\n")
        while joined.contains("\n\n\n") { joined = joined.replacingOccurrences(of: "\n\n\n", with: "\n\n") }
        let edge: (Character) -> Bool = { $0 == "\n" || $0 == " " }
        return String(joined.drop(while: edge).reversed().drop(while: edge).reversed())
    }

    private static let host = try! NSRegularExpression(pattern: #"^(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::([0-9]{1,5}))?$"#)

    /// URL de lien acceptée (section 8) : `http(s)`, autorité `hôte[:port]` ASCII, aucun blanc ni contrôle.
    static func validLinkURL(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let blank: (Unicode.Scalar) -> Bool = { [" ", "\t", "\n", "\u{0B}", "\u{0C}", "\r"].contains($0) }
        var scalars = Substring(raw).unicodeScalars.drop(while: blank)
        while let last = scalars.last, blank(last) { scalars = scalars.dropLast() }
        let url = String(String.UnicodeScalarView(scalars))
        let lower = url.lowercased()
        let rest: Substring
        if lower.hasPrefix("https://") { rest = url.dropFirst(8) } else if lower.hasPrefix("http://") { rest = url.dropFirst(7) } else { return nil }
        if url.unicodeScalars.contains(where: { $0.value <= 0x20 || (0x7F...0x9F).contains($0.value) }) { return nil }
        let authority = String(rest.prefix { $0 != "/" && $0 != "?" && $0 != "#" })
        guard !authority.isEmpty, !authority.contains("@"), !authority.contains("\\") else { return nil }
        let range = NSRange(location: 0, length: (authority as NSString).length)
        guard let match = host.firstMatch(in: authority, range: range) else { return nil }
        let portRange = match.range(at: 1)
        if portRange.location != NSNotFound, let port = Int((authority as NSString).substring(with: portRange)), port > 65535 { return nil }
        return url
    }

    // MARK: Modèle

    private static func value(_ object: [String: Any], _ key: String) -> Any? {
        guard let raw = object[key] else { return nil }
        if let wrapped = raw as? [String: Any], let inner = wrapped["V"] { return inner }
        return raw
    }

    private static func text(_ object: [String: Any], _ key: String) -> String? {
        switch value(object, key) {
        case nil, is NSNull: return nil
        case let string as String: return string
        case let number as NSNumber: return number.stringValue
        default: return nil
        }
    }

    private static func list(_ object: [String: Any], _ key: String) -> [[String: Any]] {
        (value(object, key) as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }

    private static func label(_ object: [String: Any], _ key: String) -> String? {
        guard let inner = value(object, key) as? [String: Any], let label = inner["L"] as? String, !label.isEmpty else { return nil }
        return label
    }

    private static func identifier(_ raw: Any?) -> String? {
        let text = ProtocolJSON.string(raw)
        return text.isEmpty ? nil : text
    }

    static func attachments(_ item: [String: Any]) -> [NotebookAttachment] {
        list(item, "ListePieceJointe").compactMap { raw in
            guard let id = identifier(raw["N"]) else { return nil }
            let name = plainText(raw["L"] as? String)
            switch ProtocolJSON.string(raw["G"]) {
            case "0":
                let target = (raw["url"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (raw["L"] as? String ?? "")
                guard let url = validLinkURL(target) else { return nil }
                return NotebookAttachment(kind: .link, id: id, name: name.isEmpty ? "Lien" : name, url: url)
            case "1":
                return NotebookAttachment(kind: .file, id: id, name: name.isEmpty ? "Document" : name, url: nil, fileName: raw["L"] as? String ?? "")
            default:
                return nil
            }
        }
    }

    /// Réponses `PageCahierDeTexte` (partie `data`) vers le modèle natif.
    /// - Parameters:
    ///   - homework: nil si l'onglet 88 n'est pas autorisé pour ce compte.
    ///   - timetableSubjects: matière d'un cours de l'emploi du temps, par identifiant `cours.V.N`.
    static func parse(window: Window, contents: [String: Any]?, homework: [String: Any]?, timetableSubjects: [String: String] = [:]) -> NotebookSnapshot {
        var skipped = 0
        var sessions: [NotebookSession] = []
        for entry in list(contents ?? [:], "ListeCahierDeTextes") {
            guard let date = parseDate(text(entry, "Date")) else {
                skipped += 1
                continue
            }
            guard window.contains(date) else { continue }
            let lessonId = identifier((value(entry, "cours") as? [String: Any])?["N"]) ?? ""
            var subject = plainText(label(entry, "Matiere"))
            if subject.isEmpty { subject = plainText(timetableSubjects[lessonId]) }
            if subject.isEmpty { subject = "Cours" }
            let items: [NotebookContent] = list(entry, "listeContenus").compactMap { content in
                let title = plainText(content["L"] as? String)
                let description = safeText(text(content, "descriptif"))
                let files = attachments(content)
                guard !title.isEmpty || !description.text.isEmpty || !files.isEmpty else { return nil }
                return NotebookContent(title: title, description: description.text, links: description.links,
                                       category: plainText(label(content, "categorie")), attachments: files)
            }
            guard !items.isEmpty else { continue }
            sessions.append(NotebookSession(id: identifier(entry["N"]) ?? "cours:\(lessonId)@\(date)", lessonId: lessonId, date: date, subject: subject, contents: items))
        }
        var tasks: [NotebookHomework] = []
        for item in list(homework ?? [:], "ListeTravauxAFaire") {
            guard let due = parseDate(text(item, "PourLe")) else {
                skipped += 1
                continue
            }
            guard window.contains(due) else { continue }
            let description = safeText(text(item, "descriptif"))
            let subject = plainText(label(item, "Matiere"))
            tasks.append(NotebookHomework(
                id: identifier(item["N"]) ?? "", dueDate: due, subject: subject.isEmpty ? "Cours" : subject,
                description: description.text, links: description.links, done: ProtocolJSON.bool(item["TAFFait"]), attachments: attachments(item)
            ))
        }
        // Tris stables : séances par date décroissante, devoirs par échéance croissante.
        let sortedSessions = sessions.enumerated().sorted { $0.element.date != $1.element.date ? $0.element.date > $1.element.date : $0.offset < $1.offset }.map(\.element)
        let sortedTasks = tasks.enumerated().sorted { $0.element.dueDate != $1.element.dueDate ? $0.element.dueDate < $1.element.dueDate : $0.offset < $1.offset }.map(\.element)
        let count = sortedSessions.reduce(0) { $0 + $1.contents.reduce(0) { $0 + $1.attachments.count } } + sortedTasks.reduce(0) { $0 + $1.attachments.count }
        return NotebookSnapshot(from: window.from, to: window.to, sessions: sortedSessions, homework: sortedTasks,
                                skippedEntries: skipped, attachmentCount: count, homeworkAvailable: homework != nil)
    }

    // MARK: Téléchargement

    /// Identifiant accepté : ASCII imprimable sans espace, `"` ni `\`.
    static func isDownloadableId(_ id: String) -> Bool {
        !id.isEmpty && id.unicodeScalars.allSatisfy { $0.value >= 0x21 && $0.value <= 0x7E && $0 != "\"" && $0 != "\\" }
    }

    static func plaintext(_ id: String) -> Data { Data("{\"N\":\"\(id)\",\"Actif\":true}".utf8) }

    /// PKCS#7 sur des blocs de 16 octets.
    static func pkcs7(_ data: Data) -> Data {
        let pad = 16 - data.count % 16
        return data + Data(repeating: UInt8(pad), count: pad)
    }

    /// Identifiant chiffré de `FichiersExternes` : pronotepy remplit deux fois (PKCS#7, puis AES qui remplit à nouveau).
    /// `doublePadding` faux donne la variante simple (point incertain du protocole).
    static func encryptedId(_ id: String, key: Data, iv: Data, doublePadding: Bool = true) throws -> String {
        let clear = doublePadding ? pkcs7(plaintext(id)) : plaintext(id)
        return ProtocolCrypto.hex(try ProtocolCrypto.encrypt(clear, key: key, iv: iv))
    }

    /// `encodeURIComponent` : UTF-8, `%XX` majuscules, non encodés `A–Z a–z 0–9 - _ . ! ~ * ' ( )`.
    static func encodeURIComponent(_ text: String) -> String {
        let unreserved = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()".utf8)
        return text.utf8.map { unreserved.contains($0) ? String(UnicodeScalar($0)) : String(format: "%%%02X", $0) }.joined()
    }

    /// Construite dans la couche transport au moment du `GET`, jamais exposée ni conservée.
    static func downloadURL(root: String, encryptedId: String, name: String, session: Int) -> String {
        "\(root)/FichiersExternes/\(encryptedId)/\(encodeURIComponent(name))?Session=\(session)"
    }

    /// Nom d'enregistrement : dernier segment, caractères réservés remplacés, contrôles retirés, rogné.
    static func saveAsName(_ name: String) -> String {
        let last = name.split(omittingEmptySubsequences: false) { $0 == "/" || $0 == "\\" }.last.map(String.init) ?? name
        var scalars = String.UnicodeScalarView()
        for scalar in last.unicodeScalars {
            if "<>:\"|?*".unicodeScalars.contains(scalar) { scalars.append("_") } else if !isRemoved(scalar.value) { scalars.append(scalar) }
        }
        let edge: (Character) -> Bool = { $0 == " " || $0 == "." }
        let trimmed = String(String(scalars).drop(while: edge).reversed().drop(while: edge).reversed())
        return trimmed.isEmpty ? "Document" : trimmed
    }
}
