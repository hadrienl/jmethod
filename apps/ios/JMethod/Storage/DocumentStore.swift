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
        case agendaUnreadable
        case unwritable

        var errorDescription: String? {
            switch self {
            case .unreadable: "Tes données enregistrées n’ont pas pu être lues."
            case .agendaUnreadable: "Tes données de l’Agenda n’ont pas pu être lues."
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
    private var agendaDirectory: URL {
        url.deletingLastPathComponent().appendingPathComponent(url.deletingPathExtension().lastPathComponent + "-agenda", isDirectory: true)
    }
    private var agendaURL: URL { agendaDirectory.appendingPathComponent("local.json") }
    private var lastAgenda: [String: AgendaFollowUp]?
    private var unreadableAgenda = false
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
        var document: AppDocument
        do { document = try Self.decode(data) } catch { throw Failure.unreadable }
        do {
            let agendaData = try Data(contentsOf: agendaURL)
            document.agendaFollowUp = try JSONDecoder().decode([String: AgendaFollowUp].self, from: agendaData)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            // Installation neuve ou document sans suivi local.
        } catch {
            unreadableAgenda = true
            throw Failure.agendaUnreadable
        }
        unreadableAgenda = false
        lastAgenda = document.agendaFollowUp
        return document
    }

    func save(_ document: AppDocument, version: Int) throws {
        guard version > lastWrittenVersion else { return }
        guard !unreadableAgenda else { throw Failure.agendaUnreadable }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            var main = document
            main.agendaFollowUp = [:]
            try Self.encode(main).write(to: main.schemaVersion >= 3 ? v3URL : main.schemaVersion >= 2 ? v2URL : url,
                                        options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            if document.agendaFollowUp != lastAgenda {
                var directory = agendaDirectory
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try directory.setResourceValues(values)
                try JSONEncoder().encode(document.agendaFollowUp)
                    .write(to: agendaURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                lastAgenda = document.agendaFollowUp
            }
            lastWrittenVersion = version
        } catch {
            throw Failure.unwritable
        }
    }

    /// Met de côté un fichier illisible pour repartir d'un document neuf sans le détruire.
    func archiveUnreadable() throws {
        if unreadableAgenda {
            let destination = agendaDirectory.appendingPathComponent("illisible-\(UUID().uuidString).json")
            try FileManager.default.moveItem(at: agendaURL, to: destination)
            unreadableAgenda = false
            return
        }
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
