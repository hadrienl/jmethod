import SwiftUI

/// Destination poussée dans la navigation interne d'une fiche (séance ou cours) : le retour ramène à la fiche.
enum SheetRoute: Hashable {
    /// Autoévaluation « Faire le point » de la séance affichée.
    case score
    /// Document enregistré d'un cours.
    case document(CourseDocument)
    /// Pièce jointe du cahier téléchargée dans le cache privé (jamais d'adresse Pronote).
    case file(name: String, url: URL)
}

/// Visionneuse d'une destination document de la fiche.
struct SheetDocumentView: View {
    let app: AppModel
    let route: SheetRoute

    var body: some View {
        switch route {
        case .document(let document):
            if let url = app.documentURL(document), app.documentExists(document) {
                DocumentViewer(document: document, url: url)
            } else {
                ContentUnavailableView("Document introuvable", systemImage: "doc.questionmark",
                                       description: Text("Ce fichier n’est plus sur cet iPhone."))
            }
        case .file(let name, let url):
            DocumentViewer(document: CourseDocument(id: "preview", name: name, mime: DocumentText.mime(name: name, kind: nil),
                                                    size: 0, file: "", extractedTitle: nil), url: url)
        case .score:
            EmptyView()
        }
    }
}

/// Contenu du cours partagé par la fiche de séance (§8.1, points 2 et 3) et la fiche d'un cours (§8.4) :
/// description repliable, documents, documents proches de la même matière (§8.5), séance du cahier de textes.
struct CourseMaterialSections: View {
    let app: AppModel
    let notebook: NotebookModel
    let importer: NotebookCourseImporter
    let course: Course
    let open: (SheetRoute) -> Void

    @State private var descriptionExpanded = false
    @State private var showAllNearby = false
    @State private var busy: String?
    @State private var message: String?
    @State private var webLink: WebLink?

    private var subjectName: String { app.subject(id: course.subjectId)?.name ?? "cette matière" }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.l) {
            courseSection
            nearbySection
            notebookSection
            if let message {
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(Theme.warning)
                    .panel(padding: Spacing.s, fill: Theme.warningSoft)
                    .accessibilityElement(children: .combine)
            }
        }
        .sheet(item: $webLink) { SafariView(url: $0.url).ignoresSafeArea() }
        .task { if course.source != nil { notebook.ensureRead() } }
    }

    // MARK: Le cours

    private var courseSection: some View {
        let documents = course.documents ?? []
        return VStack(alignment: .leading, spacing: Spacing.s) {
            Text("Le cours").font(.sectionTitle).accessibilityAddTraits(.isHeader)
            if let description = course.description, !description.isEmpty {
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    Text(description).font(.subheadline).foregroundStyle(Theme.text)
                        .lineLimit(descriptionExpanded ? nil : RevisionSession.descriptionLines)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if description.split(whereSeparator: \.isNewline).count > RevisionSession.descriptionLines || description.count > 280 {
                        Button(descriptionExpanded ? "Afficher moins" : "Afficher plus") { descriptionExpanded.toggle() }
                            .font(.footnote.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                }
                .panel(padding: Spacing.m)
            }
            if documents.isEmpty {
                Text(course.source != nil ? "Aucun document joint à cette séance dans Pronote." : "Aucun document pour ce cours.")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            } else {
                if let principal = documents.first, app.documentExists(principal) {
                    Button { open(.document(principal)) } label: {
                        Label("Revoir le cours", systemImage: "book")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityHint("Ouvre \(principal.name)")
                }
                listPanel {
                    ForEach(documents) { document in
                        documentRow(document)
                        if document.id != documents.last?.id { Divider().padding(.leading, 56) }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func documentRow(_ document: CourseDocument) -> some View {
        let kind = DocumentKind.detect(name: document.name, data: nil)
        let origin = RevisionSession.documentSourceLine(document)
        if app.documentExists(document) {
            Button { open(.document(document)) } label: {
                row(icon: Self.icon(kind), name: document.name, detail: document.extractedTitle,
                    footnote: ["\(kind.label) · \(ByteCountFormatter.string(fromByteCount: Int64(document.size), countStyle: .file))", origin].compactMap { $0 }.joined(separator: " · "), chevron: true)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Ouvre le document")
        } else {
            HStack(spacing: Spacing.s) {
                row(icon: Self.icon(kind), name: document.name, detail: nil, footnote: ["À retélécharger", origin].compactMap { $0 }.joined(separator: " · "), chevron: false, warning: true)
                if busy == document.id {
                    ProgressView().padding(.trailing, Spacing.s)
                } else {
                    Button("Retélécharger") { redownload(document) }
                        .buttonStyle(TintedButtonStyle(fullWidth: false))
                        .disabled(busy != nil)
                        .padding(.trailing, Spacing.s)
                        .accessibilityLabel("Retélécharger \(document.name) depuis le cahier de textes")
                }
            }
        }
    }

    // MARK: Documents proches (§8.5)

    @ViewBuilder
    private var nearbySection: some View {
        let nearby = RevisionSession.nearbyDocuments(for: course, courses: app.document.courses, snapshot: notebook.snapshot,
                                                     subjects: app.document.subjects, fileExists: app.documentExists)
        if !nearby.isEmpty {
            let visible = showAllNearby ? nearby : Array(nearby.prefix(RevisionSession.nearbyVisible))
            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Documents proches en \(subjectName)").font(.sectionTitle).accessibilityAddTraits(.isHeader)
                listPanel {
                    ForEach(visible) { item in
                        nearbyRow(item)
                        if item.id != visible.last?.id { Divider().padding(.leading, 56) }
                    }
                }
                if nearby.count > RevisionSession.nearbyVisible && !showAllNearby {
                    Button("Afficher tout (\(nearby.count))") { showAllNearby = true }
                        .font(.footnote.weight(.semibold))
                        .frame(minHeight: 44)
                }
            }
        }
    }

    private func nearbyRow(_ item: RevisionSession.NearbyDocument) -> some View {
        let kind = DocumentKind.detect(name: item.name, data: nil)
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Button { openNearby(item) } label: {
                row(icon: Self.icon(kind), name: item.name, detail: item.extractedTitle, footnote: item.dateLine, chevron: busy != item.id)
                    .overlay(alignment: .trailing) { if busy == item.id { ProgressView().padding(.trailing, Spacing.s) } }
            }
            .buttonStyle(.plain)
            .disabled(busy != nil)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Ouvre le document")
            Button("Joindre à ce cours") { attach(item) }
                .buttonStyle(TintedButtonStyle(fullWidth: false))
                .disabled(busy != nil)
                .padding(.leading, 56)
                .padding(.bottom, Spacing.s)
                .accessibilityLabel("Joindre \(item.name) à ce cours")
        }
    }

    // MARK: Dans le cahier de textes (§8.1, point 3)

    @ViewBuilder
    private var notebookSection: some View {
        if let part = RevisionSession.notebookPart(for: course, snapshot: notebook.snapshot) {
            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Dans le cahier de textes").font(.sectionTitle).accessibilityAddTraits(.isHeader)
                Label(part.sourceLine, systemImage: "text.book.closed").font(.footnote).foregroundStyle(Theme.textSecondary)
                if let contents = part.contents {
                    ForEach(Array(contents.enumerated()), id: \.offset) { _, content in
                        contentPanel(content)
                    }
                } else if notebook.loading {
                    HStack(spacing: Spacing.xs) {
                        ProgressView()
                        Text("Lecture du cahier de textes…").font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
    }

    private func contentPanel(_ content: NotebookContent) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            if !content.title.isEmpty {
                Text(content.title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            }
            if !content.description.isEmpty {
                Text(content.description).font(.subheadline).foregroundStyle(Theme.text)
            }
            ForEach(Array(content.links.enumerated()), id: \.offset) { _, link in
                if let url = Self.webURL(link.url) {
                    Button { webLink = WebLink(url: url) } label: {
                        Label(link.label.isEmpty ? "Ouvrir le lien" : link.label, systemImage: "link")
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .font(.subheadline)
                }
            }
            ForEach(content.attachments) { attachment in
                switch attachment.kind {
                case .file:
                    Button { openAttachment(attachment) } label: {
                        HStack(spacing: Spacing.xs) {
                            Label(attachment.name, systemImage: "paperclip").multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                            if busy == attachment.id { ProgressView() }
                        }
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .font(.subheadline)
                    .disabled(busy != nil)
                    .accessibilityHint("Télécharge et ouvre la pièce jointe")
                case .link:
                    if let url = attachment.url.flatMap(Self.webURL) {
                        Button { webLink = WebLink(url: url) } label: {
                            Label(attachment.name, systemImage: "link").frame(minHeight: 44, alignment: .leading)
                        }
                        .font(.subheadline)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(padding: Spacing.m)
    }

    // MARK: Actions

    private func redownload(_ document: CourseDocument) {
        busy = document.id
        message = nil
        Task {
            do {
                try await importer.redownload(document, of: course)
            } catch {
                message = (error as? LocalizedError)?.errorDescription ?? NotebookRules.downloadUnavailable
            }
            busy = nil
        }
    }

    private func openNearby(_ item: RevisionSession.NearbyDocument) {
        switch item.origin {
        case .course(_, let document): open(.document(document))
        case .notebook(let attachment): openAttachment(attachment)
        }
    }

    private func openAttachment(_ attachment: NotebookAttachment) {
        busy = attachment.id
        message = nil
        Task {
            do {
                let url = try await notebook.fetch(attachment)
                open(.file(name: attachment.name, url: url))
            } catch is CancellationError {
            } catch {
                message = NotebookModel.text(for: error, fallback: NotebookRules.downloadUnavailable)
            }
            busy = nil
        }
    }

    /// Copie le fichier dans les documents de ce cours, sans toucher à l'autre cours ni au titre.
    private func attach(_ item: RevisionSession.NearbyDocument) {
        busy = item.id
        message = nil
        let courseId = course.id
        let names = [subjectName]
        Task {
            do {
                switch item.origin {
                case .course(let ownerId, let document):
                    guard let url = app.documentURL(document), app.documentExists(document) else { throw CocoaError(.fileNoSuchFile) }
                    try app.attachDocument(from: url, name: document.name, mime: document.mime, extractedTitle: document.extractedTitle, to: courseId, sourceSessionKey: document.sourceSessionKey ?? app.course(id: ownerId).flatMap { RevisionSession.documentSourceKey(document, course: $0) })
                case .notebook(let attachment):
                    let url = try await notebook.fetch(attachment)
                    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
                    let title = await Task.detached(priority: .userInitiated) { () -> String? in
                        guard let data = try? Data(contentsOf: url) else { return nil }
                        let extraction = DocumentText.extract(name: attachment.name, data: data)
                        return extraction.kind == nil ? nil : CourseTitleRules.extractedTitle(extraction, name: attachment.name, subjectNames: names)
                    }.value
                    try app.attachDocument(from: url, name: attachment.name, mime: nil, extractedTitle: title, to: courseId, sourceSessionKey: item.sourceSessionKey)
                }
            } catch is CancellationError {
            } catch {
                message = (error as? AppModel.AttachFailure)?.errorDescription
                    ?? NotebookModel.text(for: error, fallback: "Le document n’a pas pu être joint à ce cours.")
            }
            busy = nil
        }
    }

    // MARK: Présentation

    private func listPanel<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(Theme.border))
            .clipShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
    }

    private func row(icon: String, name: String, detail: String?, footnote: String, chevron: Bool, warning: Bool = false) -> some View {
        HStack(spacing: Spacing.s) {
            IconTile(systemName: icon, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(2).multilineTextAlignment(.leading)
                if let detail, !detail.isEmpty, detail != name {
                    Text(detail).font(.footnote).foregroundStyle(Theme.text).lineLimit(2).multilineTextAlignment(.leading)
                }
                Text(footnote).font(.footnote).foregroundStyle(warning ? Theme.warning : Theme.textSecondary)
            }
            Spacer(minLength: Spacing.xs)
            if chevron {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textMuted).accessibilityHidden(true)
            }
        }
        .padding(Spacing.s)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    static func icon(_ kind: DocumentKind) -> String {
        switch kind {
        case .pdf: "doc.richtext"
        case .png, .jpg, .jpeg, .gif, .heic, .webp: "photo"
        case .pptx, .odp: "rectangle.on.rectangle"
        case .xlsx: "tablecells"
        default: "doc.text"
        }
    }

    /// Liens du cahier : `http(s)` seulement, ouverts dans le navigateur intégré.
    static func webURL(_ raw: String) -> URL? {
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http", url.host != nil else { return nil }
        return url
    }
}
