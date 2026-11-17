import SwiftUI

/// Détail d'une séance (`RevisionDetailModal.tsx`) : contenu à revoir, séances précédentes, validation confirmée.
struct RevisionDetailSheet: View {
    let app: AppModel
    let revisionId: String
    @Environment(\.dismiss) private var dismiss
    @State private var score = Double(Constants.initialScore)
    @State private var confirming = false
    @State private var historyExpanded = false

    var body: some View {
        NavigationStack {
            Group {
                if let revision = app.document.revisions.first(where: { $0.id == revisionId }),
                   let course = app.course(id: revision.courseId) {
                    content(revision: revision, course: course, subject: app.subject(id: course.subjectId))
                } else {
                    ContentUnavailableView("Séance introuvable", systemImage: "questionmark.circle", description: Text("Cette séance n’existe plus."))
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }.accessibilityLabel("Fermer le détail de la séance")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// Séances terminées du même cours, antérieures à celle-ci si elle est terminée, de la plus récente à la plus ancienne.
    static func history(of revision: Revision, in revisions: [Revision]) -> [Revision] {
        let reference = revision.completedDate ?? revision.scheduledDate
        return revisions.enumerated()
            .filter { _, item in
                item.courseId == revision.courseId && item.id != revision.id && item.status == .completed
                    && (revision.status != .completed || (item.completedDate ?? item.scheduledDate) <= reference)
            }
            .sorted { lhs, rhs in
                let a = lhs.element.completedDate ?? lhs.element.scheduledDate
                let b = rhs.element.completedDate ?? rhs.element.scheduledDate
                if a != b { return a > b }
                if lhs.element.stepIndex != rhs.element.stepIndex { return lhs.element.stepIndex > rhs.element.stepIndex }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    private func content(revision: Revision, course: Course, subject: Subject?) -> some View {
        let history = Self.history(of: revision, in: app.document.revisions)
        let phase = Constants.todayPhase(revision.phase)
        let value = Int(score)

        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        SubjectDot(color: subject?.color ?? "#94A3B8")
                        Text(subject?.name ?? "Matière").font(.subheadline.bold())
                    }
                    Text("C\(course.courseNumber) · \(course.title)").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                    Text("Chapitre : \(course.chapter)").font(.subheadline).foregroundStyle(Theme.textSecondary)
                }

                FlowRow(spacing: 12) {
                    Text(revision.stepLabel).font(.title3.weight(.heavy)).foregroundStyle(Theme.violet)
                    Text(phase.label).font(.body.weight(.semibold))
                    Text("Prévue le \(SchoolDay.fullLabel(revision.scheduledDate))").font(.subheadline).foregroundStyle(Theme.textSecondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.border))
                .accessibilityElement(children: .combine)

                if let description = course.description, !description.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("À revoir").font(.subheadline.bold()).accessibilityAddTraits(.isHeader)
                        Text(description).font(.subheadline).foregroundStyle(Theme.text)
                    }
                }

                DisclosureGroup(isExpanded: $historyExpanded) {
                    if history.isEmpty {
                        Text("Aucune séance terminée avant celle-ci.").font(.subheadline).foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                    } else {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(history) { item in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(item.stepLabel) · \(Constants.todayPhase(item.phase).label)").font(.subheadline.bold())
                                    Text("\(SchoolDay.shortLabel(item.completedDate ?? item.scheduledDate)) · \(item.score.map { "\($0)/100" } ?? "Sans note")")
                                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                                }
                                .padding(.vertical, 10)
                                .accessibilityElement(children: .combine)
                                Divider()
                            }
                        }
                        .padding(.top, 8)
                    }
                } label: {
                    Text("Séances précédentes (\(history.count))").font(.body.weight(.semibold)).frame(minHeight: 44)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.border))

                if revision.status == .completed {
                    Label {
                        Text("Séance terminée\(revision.completedDate.map { " le \(SchoolDay.shortLabel($0))" } ?? "") · \(revision.score.map(String.init) ?? "—")/100")
                    } icon: {
                        Image(systemName: "checkmark.circle.fill")
                    }
                    .foregroundStyle(Theme.success)
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.successSoft, in: RoundedRectangle(cornerRadius: 14))
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Ta mémorisation : \(value)/100").font(.subheadline.weight(.semibold))
                        Slider(value: $score, in: 0...100, step: Double(Constants.scoreStep))
                            .tint(Theme.violet)
                            .accessibilityLabel("Ta mémorisation")
                            .accessibilityValue("\(value) sur 100")
                            .onChange(of: score) { _, _ in confirming = false }
                        Text("50 conserve le rythme prévu. Ta note ajuste les prochaines révisions.")
                            .font(.caption).foregroundStyle(Theme.textSecondary)
                    }
                    .padding(16)
                    .background(Theme.background, in: RoundedRectangle(cornerRadius: 16))

                    if confirming {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Terminer cette séance avec \(value)/100 ?").font(.headline)
                            Text("Elle sera enregistrée comme faite aujourd’hui et les prochaines dates seront ajustées.")
                                .font(.subheadline).foregroundStyle(Theme.textSecondary)
                            Button("Confirmer la validation") {
                                app.validate(revisionId: revision.id, score: value)
                                dismiss()
                            }
                            .buttonStyle(PrimaryButtonStyle())
                            Button("Revenir") { confirming = false }.buttonStyle(SecondaryButtonStyle())
                        }
                        .padding(16)
                        .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 16))
                        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.violetLight))
                    } else {
                        Button { confirming = true } label: {
                            Label("Valider la séance", systemImage: "checkmark.circle")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
            .padding(20)
        }
        .background(Theme.surface)
    }
}
