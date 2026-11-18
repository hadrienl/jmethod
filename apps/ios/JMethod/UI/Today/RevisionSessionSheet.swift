import SwiftUI

/// Fiche de séance « Réviser » (`notebook-to-course.md` §8.1, §8.2), ouverte depuis Aujourd'hui, le Planning ou la fiche
/// d'un cours. Navigation interne : documents, pièces jointes du cahier et « Faire le point » s'ouvrent dans la feuille,
/// le retour ramène à la fiche. Après une validation, « Séance suivante » remplace le contenu par la séance suivante.
struct RevisionSessionSheet: View {
    enum Start: Equatable { case detail, score, review }

    let app: AppModel
    let notebook: NotebookModel
    let importer: NotebookCourseImporter
    let start: Start
    @State private var revisionId: String
    @State private var path: [SheetRoute] = []
    @State private var started = false
    @State private var outcome: Outcome?
    @State private var historyExpanded = false
    @Environment(\.dismiss) private var dismiss

    /// Résultat affiché après la validation d'une note.
    struct Outcome: Equatable {
        var title: String
        var message: String
        var next: Revision?
        var nextLabel: String?
    }

    init(app: AppModel, notebook: NotebookModel, importer: NotebookCourseImporter, revisionId: String, start: Start = .detail) {
        self.app = app
        self.notebook = notebook
        self.importer = importer
        self.start = start
        _revisionId = State(initialValue: revisionId)
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if let outcome {
                    result(outcome)
                } else if let detail = RevisionDetail(revisionId: revisionId, revisions: app.document.revisions,
                                                      courses: app.document.courses, subjects: app.document.subjects) {
                    content(detail)
                } else {
                    ContentUnavailableView("Séance introuvable", systemImage: "questionmark.circle",
                                           description: Text("Cette séance, son cours ou sa matière n’existe plus."))
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }.accessibilityLabel("Fermer la fiche de la séance")
                }
            }
            .navigationTitle(outcome == nil ? "Réviser" : "Séance enregistrée")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: SheetRoute.self) { route in
                switch route {
                case .score:
                    ScoreForm(app: app, revisionId: revisionId) { validate(score: $0) }
                case .document, .file:
                    SheetDocumentView(app: app, route: route)
                }
            }
        }
        .presentationDragIndicator(.visible)
        .onAppear(perform: applyStart)
    }

    // MARK: Fiche

    private func content(_ detail: RevisionDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                header(detail)
                CourseMaterialSections(app: app, notebook: notebook, importer: importer, course: detail.course) { path.append($0) }
                    .id(detail.course.id)
                history(detail)
                if let completed = detail.completedLine {
                    Label(completed, systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.success)
                        .panel(padding: Spacing.m, fill: Theme.successSoft)
                }
            }
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
        }
        .background(Theme.surface)
        // Action principale toujours visible.
        .safeAreaInset(edge: .bottom) {
            if detail.canValidate {
                Button { path.append(.score) } label: {
                    Label("Faire le point", systemImage: "checkmark")
                }
                .buttonStyle(PrimaryButtonStyle())
                .accessibilityLabel("Faire le point sur \(detail.course.title)")
                .padding(.horizontal, Spacing.screen)
                .padding(.vertical, Spacing.s)
                .background(.bar)
            }
        }
    }

    private func header(_ detail: RevisionDetail) -> some View {
        let revision = detail.revision
        let colors = Theme.phase(revision.phase)
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            FlowRow(spacing: Spacing.xs) {
                HStack(spacing: Spacing.xs) {
                    SubjectDot(color: detail.subject.color)
                    Text(detail.subject.name).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                }
                .accessibilityElement(children: .combine)
                Tag(text: "\(revision.stepLabel) · \(detail.phaseLabel)", foreground: colors.text, background: colors.background)
            }
            Text(detail.course.title).font(.title2.weight(.bold)).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
            Text("\(detail.course.chapter) · C\(detail.course.courseNumber)").font(.subheadline).foregroundStyle(Theme.textSecondary)
            if let late = RevisionSession.lateLabel(revision, today: app.today) {
                Label(late, systemImage: "clock.arrow.circlepath").font(.footnote.weight(.semibold)).foregroundStyle(Theme.warning)
            } else if detail.completedLine == nil {
                Text(detail.plannedLine).font(.footnote).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func history(_ detail: RevisionDetail) -> some View {
        DisclosureGroup(isExpanded: $historyExpanded) {
            if let empty = detail.historyEmpty {
                Text(empty).font(.subheadline).foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.top, Spacing.xs)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(detail.history) { item in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.label).font(.subheadline.weight(.semibold))
                            Text(item.detail).font(.subheadline).foregroundStyle(Theme.textSecondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, Spacing.s)
                        .accessibilityElement(children: .combine)
                        if item.id != detail.history.last?.id { Divider() }
                    }
                }
                .padding(.top, Spacing.xxs)
            }
        } label: {
            Text(detail.historyTitle).font(.body.weight(.semibold)).foregroundStyle(Theme.text).frame(minHeight: 44)
        }
        .tint(Theme.violetDark)
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.xxs)
        .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(Theme.border))
    }

    // MARK: Enchaîner (§8.2)

    private func result(_ outcome: Outcome) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Label(outcome.title, systemImage: outcome.next == nil ? "checkmark.seal.fill" : "checkmark.circle.fill")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(Theme.success)
                        .accessibilityAddTraits(.isHeader)
                    Text(outcome.message).font(.subheadline).foregroundStyle(Theme.text)
                }
                .panel(padding: Spacing.m, fill: Theme.successSoft)
                .accessibilityElement(children: .combine)
                if let next = outcome.next, let label = outcome.nextLabel {
                    Button { showNext(next) } label: {
                        Label(label, systemImage: "arrow.right").multilineTextAlignment(.leading)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button("Revenir à Aujourd’hui") { dismiss() }
                        .buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.s)
        }
        .background(Theme.surface)
    }

    private func validate(score: Int) {
        let validated = revisionId
        let explanation = app.validate(revisionId: validated, score: score)
        let next = RevisionSession.nextDue(revisions: app.document.revisions, courses: app.document.courses,
                                           subjects: app.document.subjects, today: app.today, excluding: validated)
        let message = explanation?.message ?? "Ta séance est enregistrée."
        if let next {
            outcome = Outcome(title: "Séance validée · \(score)/100", message: message, next: next,
                              nextLabel: RevisionSession.nextLabel(next, courses: app.document.courses, subjects: app.document.subjects))
            path = []
        } else {
            // Programme terminé : la feuille se ferme, le feu d'artifice existant prend le relais.
            outcome = Outcome(title: "Programme terminé", message: message, next: nil, nextLabel: nil)
            path = []
            Task {
                try? await Task.sleep(for: .seconds(1.4))
                dismiss()
            }
        }
    }

    private func showNext(_ next: Revision) {
        revisionId = next.id
        outcome = nil
        historyExpanded = false
        path = []
        AccessibilityNotification.Announcement(RevisionSession.nextLabel(next, courses: app.document.courses, subjects: app.document.subjects) ?? "Séance suivante").post()
    }

    private func applyStart() {
        guard !started else { return }
        started = true
        switch start {
        case .detail: break
        case .score:
            if app.document.revisions.first(where: { $0.id == revisionId })?.status != .completed { path = [.score] }
        case .review:
            let course = app.document.revisions.first { $0.id == revisionId }.flatMap { app.course(id: $0.courseId) }
            if let principal = RevisionSession.principalDocument(course), app.documentExists(principal) { path = [.document(principal)] }
        }
    }
}
