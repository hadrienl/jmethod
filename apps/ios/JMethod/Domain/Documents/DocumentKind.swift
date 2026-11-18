import Foundation

/// Type d'un document joint, déterminé par l'extension du nom puis par la signature (`notebook-to-course.md`, 5.1).
enum DocumentKind: String, Equatable, Sendable, CaseIterable {
    case pdf, odt, odp, docx, pptx, xlsx, txt, md, png, jpg, jpeg, gif, heic, webp, other

    var isImage: Bool { [.png, .jpg, .jpeg, .gif, .heic, .webp].contains(self) }
    /// Formats Office qu'iOS sait prévisualiser (QuickLook).
    var isOffice: Bool { [.docx, .pptx, .xlsx].contains(self) }
    var isODF: Bool { [.odt, .odp].contains(self) }

    var fileExtension: String { self == .other ? "" : rawValue }

    /// Libellé court affiché dans la liste des documents.
    var label: String {
        switch self {
        case .pdf: "PDF"
        case .odt: "Texte OpenDocument"
        case .odp: "Présentation OpenDocument"
        case .docx: "Word"
        case .pptx: "PowerPoint"
        case .xlsx: "Excel"
        case .txt: "Texte"
        case .md: "Markdown"
        case .png, .jpg, .jpeg, .gif, .heic, .webp: "Image"
        case .other: "Document"
        }
    }

    static func fileExtension(of name: String) -> String? {
        let base = name.split(separator: "/").last.map(String.init) ?? name
        guard let dot = base.lastIndex(of: "."), dot != base.startIndex else { return nil }
        let value = base[base.index(after: dot)...].lowercased()
        return value.isEmpty || value.count > 8 ? nil : value
    }

    /// Extension du nom, sinon signature des octets (`%PDF-`, zip ODF ou OOXML).
    static func detect(name: String, data: Data?) -> DocumentKind {
        if let ext = fileExtension(of: name), let kind = DocumentKind(rawValue: ext), kind != .other { return kind }
        guard let data else { return .other }
        if data.starts(with: Array("%PDF-".utf8)) { return .pdf }
        if data.starts(with: [0x50, 0x4B, 0x03, 0x04]), let archive = try? ZipArchive(data) {
            if let mimetype = try? archive.read("mimetype"), let text = String(data: mimetype, encoding: .utf8) {
                if text.hasPrefix("application/vnd.oasis.opendocument.text") { return .odt }
                if text.hasPrefix("application/vnd.oasis.opendocument.presentation") { return .odp }
            }
            if let types = try? archive.read("[Content_Types].xml"), let text = String(data: types, encoding: .utf8) {
                if text.contains("wordprocessingml") { return .docx }
                if text.contains("presentationml") { return .pptx }
                if text.contains("spreadsheetml") { return .xlsx }
            }
        }
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return .png }
        if data.starts(with: [0xFF, 0xD8, 0xFF]) { return .jpg }
        if data.starts(with: Array("GIF8".utf8)) { return .gif }
        return .other
    }

}

/// Lecteur zip minimal (répertoire central, entrées stockées ou DEFLATE brut), sans dépendance tierce.
/// Borné : 4 000 entrées au plus, 16 Mo décompressés par entrée (limite de `ProtocolCrypto.inflateRaw`).
struct ZipArchive {
    struct Entry: Equatable {
        var name: String
        var method: UInt16
        var compressedSize: Int
        var uncompressedSize: Int
        var localHeaderOffset: Int
    }

    enum Failure: Error { case notZip, corrupted, unsupported }

    let entries: [Entry]
    private let bytes: [UInt8]

    init(_ data: Data) throws {
        bytes = [UInt8](data)
        guard bytes.count >= 22 else { throw Failure.notZip }
        // Fin du répertoire central : recherchée dans les 64 Ko finaux (commentaire compris).
        var end = -1
        var index = bytes.count - 22
        let lowest = max(0, bytes.count - 22 - 65_535)
        while index >= lowest {
            if Self.u32(bytes, index) == 0x0605_4B50 { end = index; break }
            index -= 1
        }
        guard end >= 0 else { throw Failure.notZip }
        let count = Int(Self.u16(bytes, end + 10))
        let directoryOffset = Int(Self.u32(bytes, end + 16))
        guard count <= 4000, directoryOffset < bytes.count else { throw Failure.corrupted }
        var list: [Entry] = []
        var cursor = directoryOffset
        for _ in 0..<count {
            guard cursor + 46 <= bytes.count, Self.u32(bytes, cursor) == 0x0201_4B50 else { throw Failure.corrupted }
            let method = Self.u16(bytes, cursor + 10)
            let compressed = Int(Self.u32(bytes, cursor + 20))
            let uncompressed = Int(Self.u32(bytes, cursor + 24))
            let nameLength = Int(Self.u16(bytes, cursor + 28))
            let extraLength = Int(Self.u16(bytes, cursor + 30))
            let commentLength = Int(Self.u16(bytes, cursor + 32))
            let offset = Int(Self.u32(bytes, cursor + 42))
            guard cursor + 46 + nameLength <= bytes.count else { throw Failure.corrupted }
            let name = String(decoding: bytes[(cursor + 46)..<(cursor + 46 + nameLength)], as: UTF8.self)
            list.append(Entry(name: name, method: method, compressedSize: compressed, uncompressedSize: uncompressed, localHeaderOffset: offset))
            cursor += 46 + nameLength + extraLength + commentLength
        }
        entries = list
    }

    func contains(_ name: String) -> Bool { entries.contains { $0.name == name } }

    /// Contenu d'une entrée, ou nil si elle n'existe pas.
    func read(_ name: String) throws -> Data? {
        guard let entry = entries.first(where: { $0.name == name }) else { return nil }
        let header = entry.localHeaderOffset
        guard header + 30 <= bytes.count, Self.u32(bytes, header) == 0x0403_4B50 else { throw Failure.corrupted }
        let start = header + 30 + Int(Self.u16(bytes, header + 26)) + Int(Self.u16(bytes, header + 28))
        guard entry.compressedSize >= 0, start + entry.compressedSize <= bytes.count else { throw Failure.corrupted }
        let payload = Data(bytes[start..<(start + entry.compressedSize)])
        switch entry.method {
        case 0: return payload
        case 8: return try ProtocolCrypto.inflateRaw(payload)
        default: throw Failure.unsupported
        }
    }

    private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        guard offset + 2 <= bytes.count else { return 0 }
        return UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        guard offset + 4 <= bytes.count else { return 0 }
        return UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}
