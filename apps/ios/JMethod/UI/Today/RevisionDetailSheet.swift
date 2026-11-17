import SwiftUI

/// Détail d'une séance (`RevisionDetailModal.tsx`), ouvert depuis Aujourd'hui et le Planning :
/// matière, étape, contenu à revoir, sessions précédentes repliables, validation confirmée.
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
                if let detail = RevisionDetail(revisionId: revisionId, revisions: app.document.revisions,
                                               courses: app.document.courses, subjects: app.document.subjects) {
                    content(detail)
                } else {
                    ContentUnavailableView("Séance introuvable", systemImage: "questionmark.circle",
                                           description: Text("Cette séance, son cours ou sa matière n’existe plus."))
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

    private func content(_ detail: RevisionDetail) -> some View {
        let value = Int(score)
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        SubjectDot(color: detail.subject.color)
                        Text(detail.subject.name).font(.subheadline.bold())
                    }
                    Text(detail.title).font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                    Text(detail.chapterLine).font(.subheadline).foregroundStyle(Theme.textSecondary)
                }

                FlowRow(spacing: 12) {
                    Text(detail.revision.stepLabel).font(.title3.weight(.heavy)).foregroundStyle(Theme.violet)
                    PhaseBadge(text: detail.phaseLabel, phase: detail.revision.phase)
                    Text(detail.plannedLine).font(.subheadline).foregroundStyle(Theme.textSecondary)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.border))
                .accessibilityElement(children: .combine)

                if let description = detail.description, !description.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("À revoir").font(.subheadline.bold()).accessibilityAddTraits(.isHeader)
                        Text(description).font(.subheadline).foregroundStyle(Theme.text)
                    }
                }

                DisclosureGroup(isExpanded: $historyExpanded) {
                    if let empty = detail.historyEmpty {
                        Text(empty).font(.subheadline).foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                    } else {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(detail.history) { item in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.label).font(.subheadline.bold())
                                    Text(item.detail).font(.subheadline).foregroundStyle(Theme.textSecondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .accessibilityElement(children: .combine)
                                if item.id != detail.history.last?.id { Divider() }
                            }
                        }
                        .padding(.top, 8)
                    }
                } label: {
                    Text(detail.historyTitle).font(.body.weight(.semibold)).foregroundStyle(Theme.text).frame(minHeight: 44)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.border))

                if let completed = detail.completedLine {
                    Label(completed, systemImage: "checkmark.circle.fill")
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
                            Text("Terminer cette séance avec \(value)/100 ?").font(.headline).accessibilityAddTraits(.isHeader)
                            Text("Elle sera enregistrée comme faite aujourd’hui et les prochaines dates seront ajustées.")
                                .font(.subheadline).foregroundStyle(Theme.textSecondary)
                            Button("Confirmer la validation") {
                                app.validate(revisionId: detail.revision.id, score: value)
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
