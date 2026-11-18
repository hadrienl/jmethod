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
            .navigationTitle("Séance")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDragIndicator(.visible)
    }

    private func content(_ detail: RevisionDetail) -> some View {
        let value = Int(score)
        return ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    HStack(spacing: Spacing.xs) {
                        SubjectDot(color: detail.subject.color)
                        Text(detail.subject.name).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                    Text(detail.title).font(.title2.weight(.bold)).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                    Text(detail.chapterLine).font(.subheadline).foregroundStyle(Theme.textSecondary)
                }

                FlowRow(spacing: Spacing.s) {
                    Text(detail.revision.stepLabel).font(.title3.weight(.bold)).monospacedDigit().foregroundStyle(Theme.violet)
                    PhaseBadge(text: detail.phaseLabel, phase: detail.revision.phase)
                    Text(detail.plannedLine).font(.subheadline).foregroundStyle(Theme.textSecondary)
                }
                .panel(padding: Spacing.m)
                .accessibilityElement(children: .combine)

                if let description = detail.description, !description.isEmpty {
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Text("À revoir").font(.headline).accessibilityAddTraits(.isHeader)
                        Text(description).font(.subheadline).foregroundStyle(Theme.text)
                    }
                }

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

                if let completed = detail.completedLine {
                    Label(completed, systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.success)
                        .panel(padding: Spacing.m, fill: Theme.successSoft)
                } else {
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        HStack(alignment: .firstTextBaseline) {
                            Text("Ta mémorisation").font(.subheadline.weight(.semibold))
                            Spacer(minLength: Spacing.xs)
                            Text("\(value)/100").font(.headline).monospacedDigit().foregroundStyle(Theme.violetDark)
                                .contentTransition(.numericText(value: Double(value)))
                        }
                        .accessibilityHidden(true)
                        Slider(value: $score, in: 0...100, step: Double(Constants.scoreStep))
                            .tint(Theme.violet)
                            .accessibilityLabel("Ta mémorisation")
                            .accessibilityValue("\(value) sur 100")
                            .onChange(of: score) { _, _ in confirming = false }
                        Text("50 conserve le rythme prévu. Ta note ajuste les prochaines révisions.")
                            .font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                    .panel(padding: Spacing.m)
                    .sensoryFeedback(.selection, trigger: value)

                    if confirming {
                        VStack(alignment: .leading, spacing: Spacing.s) {
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
                        .panel(padding: Spacing.m, fill: Theme.violetSoft, stroke: Theme.violetLight)
                        .transition(.opacity)
                    } else {
                        Button { confirming = true } label: {
                            Label("Valider la séance", systemImage: "checkmark")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                }
            }
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
            .animation(.easeOut(duration: 0.2), value: confirming)
        }
        .background(Theme.surface)
    }
}
