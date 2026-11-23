import Foundation

/// Persistance du document utilisée par `AppModel` ; `DocumentStore` en production, un double scripté en test.
protocol DocumentPersistence: Actor {
    func load() async throws -> AppDocument?
    func save(_ document: AppDocument, version: Int) async throws
    func archiveUnreadable() async throws
}

/// Lecture et écriture atomique du document JSON dans Application Support.
/// Les écritures sont sérialisées par l'acteur ; un numéro de version écarte une sauvegarde plus ancienne arrivée en retard.
actor DocumentStore: DocumentPersistence {
    enum Failure: Error, LocalizedError, Equatable {
        case unreadable
        case unwritable

        var errorDescription: String? {
            switch self {
            case .unreadable: "Tes données enregistrées n’ont pas pu être lues."
            case .unwritable: "Tes dernières modifications n’ont pas pu être enregistrées."
            }
        }
    }

    nonisolated let url: URL
    private var v2URL: URL { url.deletingLastPathComponent().appendingPathComponent(url.deletingPathExtension().lastPathComponent + "-v2.json") }
    private var v3URL: URL { url.deletingLastPathComponent().appendingPathComponent(url.deletingPathExtension().lastPathComponent + "-v3.json") }
    private var readableURL: URL {
        if FileManager.default.fileExists(atPath: v3URL.path) { return v3URL }
        return FileManager.default.fileExists(atPath: v2URL.path) ? v2URL : url
    }
    private var lastWrittenVersion = 0

    init(url: URL) { self.url = url }

    static func defaultURL() throws -> URL {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        return directory.appendingPathComponent("jmethod-data.json", isDirectory: false)
    }

    /// Retourne nil si aucun document n'existe encore. Toute autre erreur est remontée : le fichier n'est jamais écrasé
    /// par des données de démonstration lorsqu'il est seulement illisible (appareil verrouillé, fichier abîmé).
    func load() throws -> AppDocument? {
        let selected = readableURL
        guard FileManager.default.fileExists(atPath: selected.path) else { return nil }
        let data: Data
        do { data = try Data(contentsOf: selected) } catch { throw Failure.unreadable }
        do { return try Self.decode(data) } catch { throw Failure.unreadable }
    }

    func save(_ document: AppDocument, version: Int) throws {
        guard version > lastWrittenVersion else { return }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Self.encode(document).write(to: document.schemaVersion >= 3 ? v3URL : document.schemaVersion >= 2 ? v2URL : url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            lastWrittenVersion = version
        } catch {
            throw Failure.unwritable
        }
    }

    /// Met de côté un fichier illisible pour repartir d'un document neuf sans le détruire.
    func archiveUnreadable() throws {
        let selected = readableURL
        guard FileManager.default.fileExists(atPath: selected.path) else { return }
        let stamp = Int(Date().timeIntervalSince1970)
        let destination = url.deletingLastPathComponent().appendingPathComponent("jmethod-data-illisible-\(stamp).json")
        try FileManager.default.moveItem(at: selected, to: destination)
    }

    static func encode(_ document: AppDocument) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        return try encoder.encode(document)
    }

    static func decode(_ data: Data) throws -> AppDocument {
        try JSONDecoder().decode(AppDocument.self, from: data)
    }
}
