import Foundation

/// Fichier joint d'une séance, téléchargé dans un dossier de préparation avant la création du cours.
struct StagedDocument: Identifiable, Equatable, Sendable {
    var id: String
    /// Nom affiché (et nom d'enregistrement), jamais une URL Pronote.
    var name: String
    var url: URL
    var size: Int
    var kind: DocumentKind
    var mime: String
    var extractedTitle: String?
    /// Cochés par défaut dans le formulaire.
    var selected = true
}

/// Séance du cahier de textes prête à devenir un cours : formulaire prérempli, source, documents préparés.
struct NotebookCourseDraft: Equatable, Sendable {
    var form: CourseDraft
    var source: CourseSource
    var documents: [StagedDocument]
    /// Fichiers qui n'ont pas pu être téléchargés (message dans le formulaire).
    var failedFiles: [String] = []
    /// Fichiers au-delà de la limite de 10 : consultables dans le cahier de textes.
    var skippedFiles = 0
    var stagingDirectory: URL?

    /// Dossier de préparation (Caches, hors du cache du cahier effacé à chaque lecture).
    static var stagingRoot: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("notebook-staging", isDirectory: true)
    }

    static func newStagingDirectory() -> URL { stagingRoot.appendingPathComponent(UUID().uuidString, isDirectory: true) }

    func removeStagedFiles() {
        if let stagingDirectory { try? FileManager.default.removeItem(at: stagingDirectory) }
    }
}
