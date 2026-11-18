import Foundation

/// Fichiers des cours créés depuis le cahier de textes : `Application Support/documents/<courseId>/<docId>.<ext>`,
/// stockage privé non cache, exclu des sauvegardes iCloud et iTunes. Le document JSON n'en garde que les métadonnées.
struct CourseDocumentStore: Sendable {
    /// Au plus 10 fichiers par cours ; les suivants restent consultables dans le cahier de textes.
    static let maximumDocuments = 10

    /// Dossier Application Support (parent de `documents/`).
    let root: URL

    static var live: CourseDocumentStore {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return CourseDocumentStore(root: base)
    }

    private var documentsDirectory: URL { root.appendingPathComponent("documents", isDirectory: true) }

    func url(for document: CourseDocument) -> URL? {
        // Chemin relatif contrôlé : `documents/<…>/<…>`, sans remontée.
        let parts = document.file.split(separator: "/").map(String.init)
        guard parts.count == 3, parts[0] == "documents", !parts.contains(".."), !parts.contains(where: \.isEmpty) else { return nil }
        return root.appendingPathComponent(document.file, isDirectory: false)
    }

    func exists(_ document: CourseDocument) -> Bool {
        url(for: document).map { FileManager.default.fileExists(atPath: $0.path) } ?? false
    }

    /// Copie un fichier téléchargé (cache) dans le stockage du cours et renvoie ses métadonnées.
    func store(_ source: URL, name: String, mime: String? = nil, courseId: String, extractedTitle: String?,
               documentId: String = "doc-\(UUID().uuidString.lowercased())") throws -> CourseDocument {
        let fileExtension = DocumentText.fileExtension(name) ?? ""
        let relative = "documents/\(Self.safeComponent(courseId))/\(documentId)\(fileExtension.isEmpty ? "" : "." + fileExtension)"
        let target = root.appendingPathComponent(relative, isDirectory: false)
        let manager = FileManager.default
        try prepare(directory: target.deletingLastPathComponent())
        if manager.fileExists(atPath: target.path) { try manager.removeItem(at: target) }
        try manager.copyItem(at: source, to: target)
        try? (target as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
        let size = (try? manager.attributesOfItem(atPath: target.path)[.size] as? Int) ?? 0
        return CourseDocument(id: documentId, name: name, mime: mime ?? DocumentText.mime(name: name, kind: nil), size: size, file: relative, extractedTitle: extractedTitle)
    }

    /// Remplace le fichier d'un document existant (retéléchargement après une restauration).
    func restore(_ source: URL, for document: CourseDocument) throws {
        guard let target = url(for: document) else { throw CocoaError(.fileWriteInvalidFileName) }
        try prepare(directory: target.deletingLastPathComponent())
        if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
        try FileManager.default.copyItem(at: source, to: target)
        try? (target as NSURL).setResourceValue(true, forKey: .isExcludedFromBackupKey)
    }

    /// Suppression d'un cours : tous ses fichiers.
    func removeAll(courseId: String) {
        try? FileManager.default.removeItem(at: documentsDirectory.appendingPathComponent(Self.safeComponent(courseId), isDirectory: true))
    }

    private func prepare(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var documents = documentsDirectory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? documents.setResourceValues(values)
    }

    /// Identifiant de cours sans séparateur de chemin.
    static func safeComponent(_ value: String) -> String {
        let cleaned = value.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" ? String($0) : "_" }.joined()
        return cleaned.isEmpty ? "cours" : cleaned
    }
}
