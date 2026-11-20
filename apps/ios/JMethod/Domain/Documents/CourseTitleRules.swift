import Foundation

/// Titre, chapitre et description d'un cours créé depuis une séance du cahier de textes
/// (`notebook-to-course.md` §5, implémentation de référence `native/shared/documents/reference.py`).
enum CourseTitleRules {
    static let noChapter = "Sans chapitre"
    static let maximumTitleLength = 120
    static let maximumDescriptionLength = 2000

    // MARK: Normalisation

    /// Blancs (tabulation, saut, espaces fines et insécables…) repliés en une espace.
    static let whitespace: Set<UInt32> = [0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x2009, 0x202F]

    static func isRemoved(_ value: UInt32) -> Bool {
        (0x00...0x08).contains(value) || (0x0E...0x1F).contains(value) || (0x7F...0x9F).contains(value)
            || value == 0x200E || value == 0x200F || (0x202A...0x202E).contains(value) || (0x2066...0x2069).contains(value) || value == 0xFEFF
    }

    private static let prefixes = ["Microsoft Word - ", "Microsoft PowerPoint - "]
    private static let suffixes = [".docx", ".odt", ".pdf", ".pptx"]
    private static let quotes = Set("\"'«»“”„‘’‹›".unicodeScalars.map(\.value))
    private static let trailingPunctuation = Set(".:;-–—".unicodeScalars.map(\.value))

    /// Comme `matchSubject` : minuscules, NFD, suppression de U+0300–U+036F.
    static func fold(_ text: String) -> String { SubjectMatcher.normalize(text) }

    /// NFC, caractères de contrôle et de direction retirés, blancs repliés, rognage des espaces.
    static func cleanWhitespace(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        var pendingSpace = false
        for scalar in text.precomposedStringWithCanonicalMapping.unicodeScalars {
            if isRemoved(scalar.value) && !whitespace.contains(scalar.value) { continue }
            if whitespace.contains(scalar.value) {
                pendingSpace = true
                continue
            }
            if pendingSpace && !scalars.isEmpty { scalars.append(" ") }
            pendingSpace = false
            scalars.append(scalar)
        }
        return String(scalars)
    }

    /// Au plus `limit` caractères, coupe avant le dernier blanc si la limite tombe dans un mot ;
    /// coupe nette si les `limit` premiers caractères n'ont aucun blanc.
    static func cutWords(_ text: String, limit: Int, spaces: Set<UInt32> = [0x20]) -> String {
        let scalars = Array(text.unicodeScalars)
        guard scalars.count > limit else { return text }
        func trimmedEnd(_ slice: ArraySlice<Unicode.Scalar>) -> String {
            var end = slice.endIndex
            while end > slice.startIndex, spaces.contains(slice[end - 1].value) { end -= 1 }
            return String(String.UnicodeScalarView(slice[slice.startIndex..<end]))
        }
        if spaces.contains(scalars[limit].value) { return trimmedEnd(scalars[0..<limit]) }
        let head = scalars[0..<limit]
        let cut = head.lastIndex { spaces.contains($0.value) } ?? -1
        return cut > 0 ? trimmedEnd(scalars[0..<cut]) : trimmedEnd(head)
    }

    /// Espaces aux extrémités, guillemets en tête, guillemets et ponctuation de fin retirés, jusqu'à stabilité.
    static func stripEnds(_ text: String) -> String {
        var scalars = Array(text.unicodeScalars)
        var previous: [Unicode.Scalar]?
        while previous != scalars {
            previous = scalars
            while scalars.first?.value == 0x20 { scalars.removeFirst() }
            while scalars.last?.value == 0x20 { scalars.removeLast() }
            while let first = scalars.first, quotes.contains(first.value) { scalars.removeFirst() }
            while let last = scalars.last, quotes.contains(last.value) || trailingPunctuation.contains(last.value) { scalars.removeLast() }
        }
        return String(String.UnicodeScalarView(scalars))
    }

    static func normalize(_ raw: String, fileName: Bool = false) -> String {
        var text = raw
        if fileName {
            text = text.replacingOccurrences(of: "_+", with: " ", options: .regularExpression)
            text = text.replacingOccurrences(of: "-{2,}", with: " ", options: .regularExpression)
        }
        text = cleanWhitespace(text)
        for prefix in prefixes where text.lowercased().hasPrefix(prefix.lowercased()) {
            text = String(text.unicodeScalars.dropFirst(prefix.unicodeScalars.count))
            while text.hasPrefix(" ") { text.removeFirst() }
        }
        for suffix in suffixes where text.lowercased().hasSuffix(suffix) {
            text = String(String.UnicodeScalarView(text.unicodeScalars.dropLast(suffix.unicodeScalars.count)))
            while text.hasSuffix(" ") { text.removeLast() }
        }
        text = stripEnds(text)
        if text.unicodeScalars.count > maximumTitleLength {
            text = stripEnds(cutWords(text, limit: maximumTitleLength))
        }
        return text
    }

    // MARK: Rejet

    enum Rejection: String, Equatable, Sendable {
        case empty, numberOrDate, tooFewLetters, adminHeader, generic, subjectName, fileName
    }

    struct Context: Sendable {
        var subjects: Set<String>
        var fileName: String?

        init(subjectNames: [String], fileName: String?) {
            subjects = Set(subjectNames.compactMap { name in
                let value = CourseTitleRules.normalize(name)
                return name.isEmpty || value.isEmpty ? nil : CourseTitleRules.fold(value)
            })
            self.fileName = fileName.map(CourseTitleRules.fold)
        }
    }

    private static func regex(_ pattern: String) -> NSRegularExpression { try! NSRegularExpression(pattern: pattern) }
    private static let number = regex(#"^[+-]?\d+([.,]\d+)?$"#)
    private static let numericDate = regex(#"^\d{1,4}[/.-]\d{1,2}([/.-]\d{1,4})?$"#)
    private static let textDate = regex(#"^((lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\s+)?(\d{1,2}|1er)\s+(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)(\s+\d{4})?$"#)
    private static let admin = regex(#"^(nom|prenom|classe|date)\s*:"#)
    private static let generic = regex(#"^(sans titre|untitled|document\s*\d*|presentation\s*\d*|diapositive\s*\d+|cours|seance|exercices?|devoirs?)$"#)

    private static func matches(_ expression: NSRegularExpression, _ text: String) -> Bool {
        expression.firstMatch(in: text, range: NSRange(location: 0, length: (text as NSString).length)) != nil
    }

    /// Motif de rejet (ordre de la référence) ou nil si le candidat est valable.
    static func rejection(_ value: String, raw: String, context: Context) -> Rejection? {
        guard !value.isEmpty else { return .empty }
        let folded = fold(value)
        if matches(number, folded) || matches(numericDate, folded) || matches(textDate, folded) { return .numberOrDate }
        if value.unicodeScalars.filter({ $0.properties.isAlphabetic }).count < 3 { return .tooFewLetters }
        if matches(admin, folded) { return .adminHeader }
        if matches(generic, folded) { return .generic }
        if context.subjects.contains(folded) { return .subjectName }
        if let fileName = context.fileName, fileName == folded || fileName == fold(cleanWhitespace(raw)) { return .fileName }
        return nil
    }

    // MARK: Candidats

    enum Source: String, Equatable, Sendable {
        case metadata, firstHeading, pronoteTitle, fileName, dateFallback
    }

    struct Candidate: Equatable, Sendable {
        var source: Source
        var raw: String
        var value: String
        var rejection: Rejection?
        var accepted: Bool { rejection == nil }
    }

    static func candidate(_ source: Source, _ raw: String, context: Context) -> Candidate {
        let value = normalize(raw, fileName: source == .fileName)
        return Candidate(source: source, raw: raw, value: value, rejection: rejection(value, raw: raw, context: context))
    }

    // MARK: Chapitre

    private static let chapterPattern = regex(
        #"^(?i:(chapitre|chap\.?|ch\.?|s[eéEÉ]quence|s[eéEÉ]q\.?|th[eèéEÈÉ]me|partie|unit[eéEÉ]))\s*([0-9]+|[IVXLC]+)(?![0-9A-Za-zÀ-ÖØ-öø-ÿ])\s*[:\-–—.)]?\s*(.*)$"#
    )
    private static let chapterLabels = ["chapitre": "Chapitre", "chap": "Chapitre", "ch": "Chapitre", "sequence": "Séquence",
                                        "seq": "Séquence", "theme": "Thème", "partie": "Partie", "unite": "Unité"]

    /// Intitulé de chapitre en tête de ligne : (« Chapitre 3 », reste de la ligne). Numéro romain en capitales seulement.
    static func chapterOf(_ value: String) -> (chapter: String, rest: String)? {
        let text = value as NSString
        guard let match = chapterPattern.firstMatch(in: value, range: NSRange(location: 0, length: text.length)) else { return nil }
        var key = fold(text.substring(with: match.range(at: 1)))
        while key.hasSuffix(".") { key.removeLast() }
        guard let label = chapterLabels[key] else { return nil }
        let rest = match.range(at: 3).location == NSNotFound ? "" : text.substring(with: match.range(at: 3))
        return ("\(label) \(text.substring(with: match.range(at: 2)))", rest)
    }

    private static func validRest(_ rest: String, context: Context) -> String? {
        let value = normalize(rest)
        return rejection(value, raw: rest, context: context) == nil ? value : nil
    }

    struct Choice: Equatable, Sendable {
        var title: String?
        var chapter: String
        /// Indices des candidats qui ont donné le titre et le chapitre.
        var titleFrom: Int?
        var chapterFrom: Int?
    }

    /// §5.3 appliqué aux candidats ordonnés.
    static func choose(_ candidates: [Candidate], context: Context) -> Choice {
        let accepted = candidates.indices.filter { candidates[$0].accepted }
        guard let first = accepted.first else { return Choice(title: nil, chapter: noChapter, titleFrom: nil, chapterFrom: nil) }
        if let found = chapterOf(candidates[first].value) {
            var title = validRest(found.rest, context: context)
            var titleFrom: Int? = title == nil ? nil : first
            if title == nil {
                for index in accepted.dropFirst() {
                    guard let sub = chapterOf(candidates[index].value) else {
                        title = candidates[index].value
                        titleFrom = index
                        break
                    }
                    if let rest = validRest(sub.rest, context: context) {
                        title = rest
                        titleFrom = index
                        break
                    }
                }
            }
            return Choice(title: title, chapter: found.chapter, titleFrom: titleFrom, chapterFrom: first)
        }
        for (index, candidate) in candidates.enumerated()
        where [.metadata, .firstHeading, .pronoteTitle].contains(candidate.source) && !candidate.value.isEmpty {
            if let found = chapterOf(candidate.value) {
                return Choice(title: candidates[first].value, chapter: found.chapter, titleFrom: first, chapterFrom: index)
            }
        }
        return Choice(title: candidates[first].value, chapter: noChapter, titleFrom: first, chapterFrom: nil)
    }

    // MARK: Replis

    private static let months = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"]

    /// « Cours du 18 novembre » (le 1er : « Cours du 1er novembre »).
    static func dateFallback(_ day: String) -> String {
        guard let parts = SchoolDay.components(day) else { return "Cours" }
        return "Cours du \(parts.day == 1 ? "1er" : String(parts.day)) \(months[parts.month - 1])"
    }

    /// Nom du fichier sans extension (1 à 5 caractères alphanumériques).
    static func fileStem(_ name: String) -> String {
        let text = name as NSString
        guard let match = regex(#"^(.*)\.[A-Za-z0-9]{1,5}$"#).firstMatch(in: name, range: NSRange(location: 0, length: text.length)) else { return name }
        let stem = text.substring(with: match.range(at: 1))
        return stem.isEmpty ? name : stem
    }

    // MARK: Pipeline

    /// Candidats propres au document (métadonnées, premier titre ; pour un PDF, ligne la plus grande non rejetée).
    static func documentCandidates(_ extraction: DocumentExtraction, context: Context) -> [Candidate] {
        var result: [Candidate] = []
        if let metadata = extraction.metadata { result.append(candidate(.metadata, metadata, context: context)) }
        let heading = extraction.pdfLines.map { pdfHeading($0, context: context) } ?? extraction.firstHeading
        if let heading { result.append(candidate(.firstHeading, heading, context: context)) }
        return result
    }

    /// Première ligne, par taille décroissante puis ordre du flux, qui n'est pas rejetée ; à défaut la première de cet ordre.
    static func pdfHeading(_ lines: [PDFLine], context: Context) -> String? {
        let ordered = lines.enumerated().sorted { $0.element.size != $1.element.size ? $0.element.size > $1.element.size : $0.offset < $1.offset }.map(\.element)
        return ordered.first { candidate(.firstHeading, $0.text, context: context).accepted }?.text ?? ordered.first?.text
    }

    struct Pipeline: Equatable, Sendable {
        var candidates: [Candidate]
        var choice: Choice
    }

    /// Titre et chapitre d'une séance : document principal (s'il existe), titre Pronote, nom du fichier, repli daté.
    static func pipeline(principal: (name: String, extraction: DocumentExtraction)?, contentTitle: String?,
                         subjectNames: [String], sessionDate: String) -> Pipeline {
        let context = Context(subjectNames: subjectNames, fileName: principal?.name)
        var candidates = principal.map { documentCandidates($0.extraction, context: context) } ?? []
        if let contentTitle { candidates.append(candidate(.pronoteTitle, contentTitle, context: context)) }
        if let principal { candidates.append(candidate(.fileName, fileStem(principal.name), context: context)) }
        let fallback = dateFallback(sessionDate)
        candidates.append(Candidate(source: .dateFallback, raw: fallback, value: fallback, rejection: nil))
        return Pipeline(candidates: candidates, choice: choose(candidates, context: context))
    }

    /// `Course.documents[].extractedTitle` : §5.2–5.3 limités aux candidats du document.
    static func extractedTitle(_ extraction: DocumentExtraction, name: String, subjectNames: [String]) -> String? {
        let context = Context(subjectNames: subjectNames, fileName: name)
        let candidates = documentCandidates(extraction, context: context)
        return candidates.isEmpty ? nil : choose(candidates, context: context).title
    }

    // MARK: Description

    /// Pour chaque contenu : titre puis description, séparés par une ligne vide ; 2 000 caractères au plus (coupe au mot).
    static func description(_ contents: [NotebookContent]) -> String {
        let parts = contents.flatMap { [$0.title, $0.description] }.filter { !$0.isEmpty }
        return cutWords(parts.joined(separator: "\n\n"), limit: maximumDescriptionLength, spaces: whitespace)
    }
}
