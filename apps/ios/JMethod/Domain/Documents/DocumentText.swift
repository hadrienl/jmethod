import Foundation

/// Bloc de la vue « Lecture » (titres, paragraphes, éléments de liste avec leur profondeur).
struct ReadingBlock: Equatable, Sendable {
    enum Kind: String, Sendable { case heading, paragraph, listItem }
    var kind: Kind
    var level: Int?
    var text: String
}

/// Ligne de la première page d'un PDF : texte et taille de police.
struct PDFLine: Equatable, Sendable {
    var text: String
    var size: Double
}

/// Résultat de l'extraction d'un document (`notebook-to-course.md` §5, `reference.py` `extract`).
struct DocumentExtraction: Equatable, Sendable {
    enum Kind: String, Sendable, CaseIterable { case pdf, odt, odp, docx, pptx, txt, md }
    enum DetectedBy: String, Sendable { case `extension`, signature }

    var kind: Kind?
    var detectedBy: DetectedBy?
    var metadata: String?
    /// `pdfInfoTitle`, `xmpDcTitle`, `metaXmlDcTitle` ou `coreXmlDcTitle`.
    var metadataDetail: String?
    var firstHeading: String?
    /// PDF seulement : lignes de la première page.
    var pdfLines: [PDFLine]?
    var reading: [ReadingBlock]?
    /// `unreadable` si le document ne peut pas être lu (PDF chiffré ou abîmé, zip invalide…).
    var error: String?

    static let unreadable = "unreadable"
}

/// Arbre XML minimal (noms qualifiés, attributs, enfants et texte dans l'ordre), sans entité externe.
final class XMLElementNode {
    enum Child {
        case element(XMLElementNode)
        case text(String)
    }

    let name: String
    let attributes: [String: String]
    var children: [Child] = []

    init(name: String, attributes: [String: String]) {
        self.name = name
        self.attributes = attributes
    }

    var elements: [XMLElementNode] {
        children.compactMap { if case .element(let node) = $0 { node } else { nil } }
    }

    func first(_ name: String) -> XMLElementNode? { elements.first { $0.name == name } }

    func path(_ names: String...) -> XMLElementNode? {
        var node: XMLElementNode? = self
        for name in names { node = node?.first(name) }
        return node
    }

    /// Le nœud et tous ses descendants, dans l'ordre du document (comme `Element.iter`).
    func iterate(_ visit: (XMLElementNode) -> Void) {
        visit(self)
        for child in elements { child.iterate(visit) }
    }

    func all(_ name: String) -> [XMLElementNode] {
        var result: [XMLElementNode] = []
        iterate { if $0.name == name { result.append($0) } }
        return result
    }

    var directText: String {
        children.reduce(into: "") { output, child in if case .text(let value) = child { output += value } }
    }

    /// Analyse bornée (16 Mo), sans entité externe ; nil si le XML est invalide.
    static func parse(_ data: Data) -> XMLElementNode? {
        guard data.count <= 16 * 1024 * 1024 else { return nil }
        let builder = Builder()
        let parser = XMLParser(data: data)
        parser.shouldResolveExternalEntities = false
        parser.shouldProcessNamespaces = false
        parser.delegate = builder
        return parser.parse() ? builder.root : nil
    }

    private final class Builder: NSObject, XMLParserDelegate {
        var root: XMLElementNode?
        private var stack: [XMLElementNode] = []

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
            let node = XMLElementNode(name: elementName, attributes: attributes)
            if let parent = stack.last { parent.children.append(.element(node)) } else { root = node }
            stack.append(node)
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
            _ = stack.popLast()
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            stack.last?.children.append(.text(string))
        }
    }
}

/// Détection du type et extraction des titres et du texte structuré (PDF, ODT, ODP, DOCX, PPTX, TXT, MD).
enum DocumentText {
    // MARK: Type

    static func fileExtension(_ name: String) -> String? {
        let text = name as NSString
        guard let match = try! NSRegularExpression(pattern: #"\.([A-Za-z0-9]{1,5})$"#)
            .firstMatch(in: name, range: NSRange(location: 0, length: text.length)) else { return nil }
        return text.substring(with: match.range(at: 1)).lowercased()
    }

    /// Extension parmi les types extractibles, sinon signature (`%PDF-`, zip ODF ou OOXML).
    static func detect(name: String, data: Data) -> (kind: DocumentExtraction.Kind, by: DocumentExtraction.DetectedBy)? {
        if let ext = fileExtension(name), let kind = DocumentExtraction.Kind(rawValue: ext) { return (kind, .extension) }
        if data.starts(with: Array("%PDF-".utf8)) { return (.pdf, .signature) }
        if data.starts(with: [0x50, 0x4B, 0x03, 0x04]), let archive = try? ZipArchive(data) {
            if archive.contains("mimetype") {
                let mimetype = (try? archive.read("mimetype")).flatMap { String(data: $0, encoding: .ascii) }?
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if mimetype == "application/vnd.oasis.opendocument.text" { return (.odt, .signature) }
                if mimetype == "application/vnd.oasis.opendocument.presentation" { return (.odp, .signature) }
            } else if archive.contains("[Content_Types].xml") {
                if archive.contains("word/document.xml") { return (.docx, .signature) }
                if archive.contains("ppt/presentation.xml") { return (.pptx, .signature) }
            }
        }
        return nil
    }

    static let mimeTypes: [String: String] = [
        "pdf": "application/pdf", "odt": "application/vnd.oasis.opendocument.text",
        "odp": "application/vnd.oasis.opendocument.presentation",
        "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "txt": "text/plain", "md": "text/markdown", "png": "image/png", "jpg": "image/jpeg",
        "jpeg": "image/jpeg", "gif": "image/gif", "heic": "image/heic", "webp": "image/webp",
    ]

    static func mime(name: String, kind: DocumentExtraction.Kind?) -> String {
        mimeTypes[kind?.rawValue ?? fileExtension(name) ?? ""] ?? "application/octet-stream"
    }

    // MARK: Extraction

    static func extract(name: String, data: Data) -> DocumentExtraction {
        guard let detected = detect(name: name, data: data) else { return DocumentExtraction() }
        var result: DocumentExtraction
        switch detected.kind {
        case .pdf: result = PDFText.extract(data: data)
        case .txt, .md: result = extractText(data: data, markdown: detected.kind == .md)
        case .odt, .odp, .docx, .pptx:
            guard let archive = try? ZipArchive(data) else { return unreadable(detected) }
            switch detected.kind {
            case .odt: result = extractODT(archive)
            case .odp: result = extractODP(archive)
            case .docx: result = extractDOCX(archive)
            default: result = extractPPTX(archive)
            }
            if result.error == nil, result.metadata != nil {
                result.metadataDetail = detected.kind == .odt || detected.kind == .odp ? "metaXmlDcTitle" : "coreXmlDcTitle"
            }
        }
        if result.error != nil { return unreadable(detected) }
        result.kind = detected.kind
        result.detectedBy = detected.by
        return result
    }

    private static func unreadable(_ detected: (kind: DocumentExtraction.Kind, by: DocumentExtraction.DetectedBy)) -> DocumentExtraction {
        DocumentExtraction(kind: detected.kind, detectedBy: detected.by, error: DocumentExtraction.unreadable)
    }

    static func block(_ kind: ReadingBlock.Kind, _ text: String, level: Int? = nil) -> ReadingBlock? {
        let cleaned = CourseTitleRules.cleanWhitespace(text)
        return cleaned.isEmpty ? nil : ReadingBlock(kind: kind, level: level, text: cleaned)
    }

    private static func dcTitle(_ archive: ZipArchive, _ path: String) -> (title: String?, ok: Bool) {
        guard archive.contains(path) else { return (nil, true) }
        guard let data = try? archive.read(path), let root = XMLElementNode.parse(data) else { return (nil, false) }
        var found: XMLElementNode?
        root.iterate { if found == nil, $0.name == "dc:title" { found = $0 } }
        return (found.map(\.directText), true)
    }

    // MARK: Texte brut

    static func decodeText(_ data: Data) -> String {
        var bytes = data
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes = bytes.dropFirst(3) }
        return String(data: bytes, encoding: .utf8) ?? String(data: bytes, encoding: .windowsCP1252) ?? String(decoding: bytes, as: UTF8.self)
    }

    static func extractText(data: Data, markdown: Bool) -> DocumentExtraction {
        let text = decodeText(data)
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        for raw in lines {
            var line = raw
            if markdown {
                line = line.replacingOccurrences(of: #"^\s{0,3}#{1,6}(?=\s|$)"#, with: "", options: .regularExpression)
            }
            if !CourseTitleRules.cleanWhitespace(line).isEmpty { return DocumentExtraction(firstHeading: line) }
        }
        return DocumentExtraction()
    }

    /// Vue « Lecture » d'un texte brut ou Markdown (affichage seulement).
    static func plainReading(_ data: Data, markdown: Bool) -> [ReadingBlock] {
        decodeText(data).replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n").compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            if markdown, line.hasPrefix("#") {
                let level = line.prefix { $0 == "#" }.count
                return block(.heading, String(line.dropFirst(level)), level: min(level, 6))
            }
            if markdown, line.hasPrefix("- ") || line.hasPrefix("* ") { return block(.listItem, String(line.dropFirst(2)), level: 1) }
            return block(.paragraph, line)
        }
    }

    // MARK: OpenDocument

    /// Texte d'un élément ODF : `text:s` → espaces, tabulation et saut → espace, notes et annotations ignorées.
    static func odfText(_ node: XMLElementNode) -> String {
        var output = ""
        for child in node.children {
            switch child {
            case .text(let value): output += value
            case .element(let element):
                switch element.name {
                case "text:s": output += String(repeating: " ", count: Int(element.attributes["text:c"] ?? "1") ?? 1)
                case "text:tab", "text:line-break": output += " "
                case "text:note", "office:annotation": break
                default: output += odfText(element)
                }
            }
        }
        return output
    }

    private static func titleStyles(_ root: XMLElementNode) -> Set<String> {
        guard let automatic = root.first("office:automatic-styles") else { return [] }
        return Set(automatic.elements.filter { $0.name == "style:style" }.compactMap { style in
            ["title", "titre"].contains((style.attributes["style:parent-style-name"] ?? "").lowercased()) ? style.attributes["style:name"] : nil
        })
    }

    private static func isTitleParagraph(_ node: XMLElementNode, _ automatic: Set<String>) -> Bool {
        let style = node.attributes["text:style-name"] ?? ""
        return ["title", "titre"].contains(style.lowercased()) || automatic.contains(style)
    }

    private static let odfContainers: Set<String> = ["text:section", "table:table", "table:table-row", "table:table-cell", "table:table-rows", "table:table-header-rows"]

    static func odfBlocks(_ container: XMLElementNode, _ automatic: Set<String>, depth: Int = 0) -> [ReadingBlock] {
        var output: [ReadingBlock] = []
        for child in container.elements {
            var item: ReadingBlock?
            switch child.name {
            case "text:h":
                item = block(.heading, odfText(child), level: Int(child.attributes["text:outline-level"] ?? "1") ?? 1)
            case "text:p":
                if depth > 0 {
                    item = block(.listItem, odfText(child), level: depth)
                } else if isTitleParagraph(child, automatic) {
                    item = block(.heading, odfText(child), level: 1)
                } else {
                    item = block(.paragraph, odfText(child))
                }
            case "text:list":
                for entry in child.elements where entry.name == "text:list-item" || entry.name == "text:list-header" {
                    output += odfBlocks(entry, automatic, depth: depth + 1)
                }
            default:
                if odfContainers.contains(child.name) { output += odfBlocks(child, automatic, depth: depth) }
            }
            if let item { output.append(item) }
        }
        return output
    }

    static func extractODT(_ archive: ZipArchive) -> DocumentExtraction {
        guard let data = try? archive.read("content.xml"), let root = XMLElementNode.parse(data),
              let body = root.path("office:body", "office:text") else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let meta = dcTitle(archive, "meta.xml")
        guard meta.ok else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let automatic = titleStyles(root)
        let level: (XMLElementNode) -> Int = { Int($0.attributes["text:outline-level"] ?? "1") ?? 1 }
        let minimum = body.all("text:h").filter { !CourseTitleRules.cleanWhitespace(odfText($0)).isEmpty }.map(level).min()
        var first: String?
        body.iterate { node in
            guard first == nil else { return }
            let candidate = (node.name == "text:h" && level(node) == minimum) || (node.name == "text:p" && isTitleParagraph(node, automatic))
            if candidate {
                let text = odfText(node)
                if !CourseTitleRules.cleanWhitespace(text).isEmpty { first = text }
            }
        }
        return DocumentExtraction(metadata: meta.title, firstHeading: first, reading: odfBlocks(body, automatic))
    }

    static func extractODP(_ archive: ZipArchive) -> DocumentExtraction {
        guard let data = try? archive.read("content.xml"), let root = XMLElementNode.parse(data),
              let presentation = root.path("office:body", "office:presentation") else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let meta = dcTitle(archive, "meta.xml")
        guard meta.ok else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let automatic = titleStyles(root)
        var first: String?
        var blocks: [ReadingBlock] = []
        for (index, page) in presentation.elements.filter({ $0.name == "draw:page" }).enumerated() {
            for frame in page.elements where frame.name == "draw:frame" {
                guard let box = frame.first("draw:text-box") else { continue }
                if frame.attributes["presentation:class"] == "title" {
                    let text = box.all("text:p").map(odfText).joined(separator: " ")
                    if index == 0 && first == nil { first = text }
                    if let item = block(.heading, text, level: 1) { blocks.append(item) }
                } else {
                    blocks += odfBlocks(box, automatic)
                }
            }
        }
        return DocumentExtraction(metadata: meta.title, firstHeading: first, reading: blocks)
    }

    // MARK: Word

    private static let skippedWord: Set<String> = ["w:del", "w:moveFrom", "w:instrText", "w:delText"]
    private static let wordTitleStyles: Set<String> = ["title", "titre", "heading1", "titre1"]

    static func wordText(_ node: XMLElementNode) -> String {
        var output = ""
        for child in node.elements where !skippedWord.contains(child.name) {
            switch child.name {
            case "w:t": output += child.directText
            case "w:tab", "w:br", "w:cr": output += " "
            default: output += wordText(child)
            }
        }
        return output
    }

    private static func wordStyle(_ paragraph: XMLElementNode) -> String {
        paragraph.path("w:pPr", "w:pStyle")?.attributes["w:val"] ?? ""
    }

    static func wordBlocks(_ container: XMLElementNode) -> [ReadingBlock] {
        var output: [ReadingBlock] = []
        let heading = try! NSRegularExpression(pattern: #"^(?i)(heading|titre)([1-9])$"#)
        for child in container.elements {
            if child.name == "w:p" {
                let style = wordStyle(child)
                let text = wordText(child)
                let match = heading.firstMatch(in: style, range: NSRange(location: 0, length: (style as NSString).length))
                let numbering = child.path("w:pPr", "w:numPr")
                var item: ReadingBlock?
                if ["title", "titre"].contains(style.lowercased()) {
                    item = block(.heading, text, level: 1)
                } else if let match {
                    item = block(.heading, text, level: Int((style as NSString).substring(with: match.range(at: 2))) ?? 1)
                } else if let numbering {
                    let level = numbering.first("w:ilvl").flatMap { Int($0.attributes["w:val"] ?? "0") }.map { $0 + 1 } ?? 1
                    item = block(.listItem, text, level: level)
                } else {
                    item = block(.paragraph, text)
                }
                if let item { output.append(item) }
            } else if ["w:tbl", "w:tr", "w:tc", "w:sdt", "w:sdtContent"].contains(child.name) {
                output += wordBlocks(child)
            }
        }
        return output
    }

    static func extractDOCX(_ archive: ZipArchive) -> DocumentExtraction {
        guard let data = try? archive.read("word/document.xml"), let root = XMLElementNode.parse(data),
              let body = root.first("w:body") else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let meta = dcTitle(archive, "docProps/core.xml")
        guard meta.ok else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        var first: String?
        for paragraph in body.all("w:p") where wordTitleStyles.contains(wordStyle(paragraph).lowercased()) {
            let text = wordText(paragraph)
            if !CourseTitleRules.cleanWhitespace(text).isEmpty { first = text; break }
        }
        return DocumentExtraction(metadata: meta.title, firstHeading: first, reading: wordBlocks(body))
    }

    // MARK: PowerPoint

    /// Diapositives dans l'ordre de `presentation.xml` (`sldIdLst`), pas dans l'ordre des fichiers.
    static func slidePaths(_ archive: ZipArchive) -> [String]? {
        guard let presentationData = try? archive.read("ppt/presentation.xml"), let presentation = XMLElementNode.parse(presentationData),
              let relsData = try? archive.read("ppt/_rels/presentation.xml.rels"), let rels = XMLElementNode.parse(relsData) else { return nil }
        var targets: [String: String] = [:]
        for relation in rels.elements where relation.name == "Relationship" {
            if let id = relation.attributes["Id"] { targets[id] = relation.attributes["Target"] ?? "" }
        }
        return (presentation.first("p:sldIdLst")?.elements ?? []).map { slide in
            let target = targets[slide.attributes["r:id"] ?? ""] ?? ""
            return target.hasPrefix("/") ? String(target.drop { $0 == "/" }) : "ppt/" + target
        }
    }

    private static func slideParagraphs(_ shape: XMLElementNode) -> [(level: Int, noBullet: Bool, text: String)] {
        guard let body = shape.first("p:txBody") else { return [] }
        return body.elements.filter { $0.name == "a:p" }.map { paragraph in
            let properties = paragraph.first("a:pPr")
            let level = Int(properties?.attributes["lvl"] ?? "0") ?? 0
            let noBullet = properties?.first("a:buNone") != nil
            var text = ""
            for child in paragraph.elements {
                if child.name == "a:r" || child.name == "a:fld" { text += child.first("a:t")?.directText ?? "" }
                else if child.name == "a:br" { text += " " }
            }
            return (level, noBullet, text)
        }
    }

    static func extractPPTX(_ archive: ZipArchive) -> DocumentExtraction {
        guard let paths = slidePaths(archive) else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        let meta = dcTitle(archive, "docProps/core.xml")
        guard meta.ok else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        var first: String?
        var blocks: [ReadingBlock] = []
        for (index, path) in paths.enumerated() {
            guard let data = try? archive.read(path), let root = XMLElementNode.parse(data),
                  let tree = root.path("p:cSld", "p:spTree") else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
            for shape in tree.all("p:sp") {
                let placeholder = shape.path("p:nvSpPr", "p:nvPr", "p:ph")
                let kind = placeholder.map { $0.attributes["type"] ?? "obj" }
                let paragraphs = slideParagraphs(shape)
                if kind == "title" || kind == "ctrTitle" {
                    let text = paragraphs.map(\.text).joined(separator: " ")
                    if index == 0 && first == nil { first = text }
                    if let item = block(.heading, text, level: 1) { blocks.append(item) }
                    continue
                }
                for paragraph in paragraphs {
                    let item = (kind == "body" || kind == "obj") && !paragraph.noBullet
                        ? block(.listItem, paragraph.text, level: paragraph.level + 1)
                        : block(.paragraph, paragraph.text)
                    if let item { blocks.append(item) }
                }
            }
        }
        return DocumentExtraction(metadata: meta.title, firstHeading: first, reading: blocks)
    }
}
