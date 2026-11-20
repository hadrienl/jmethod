import SwiftUI

/// Autoévaluation 0–100 (`ScoreModal.tsx`) avec aperçu de la replanification, poussée dans la fiche de séance (§8.1, point 5).
/// La validation est confiée à la fiche, qui enchaîne sur la séance suivante (§8.2).
struct ScoreForm: View {
    let app: AppModel
    let revisionId: String
    let onValidate: (Int) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var score = Double(Constants.initialScore)
    @State private var submitted = false

    var body: some View {
        Group {
            if let revision = app.document.revisions.first(where: { $0.id == revisionId }),
               let course = app.course(id: revision.courseId) {
                content(revision: revision, course: course, subject: app.subject(id: course.subjectId))
            } else {
                ContentUnavailableView("Séance introuvable", systemImage: "questionmark.circle", description: Text("Cette séance n’existe plus."))
            }
        }
        .navigationTitle("Faire le point")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func content(revision: Revision, course: Course, subject: Subject?) -> some View {
        let value = Int(score)
        let settings = app.document.settings
        let preview = ScorePreview(score: value, sensitivity: settings.sensitivity, emergencyThreshold: settings.emergencyThreshold)
        let phase = Constants.phaseConfig(revision.phase)
        let phaseColors = Theme.phase(revision.phase)
        let toneColors = Theme.previewColors(preview.tone)

        return ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    FlowRow {
                        HStack(spacing: Spacing.xxs + 2) {
                            SubjectDot(color: subject?.color ?? "#94A3B8", size: 8)
                            Text(subject?.name ?? "Matière")
                        }
                        .font(.tagLabel)
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.horizontal, Spacing.xs)
                        .padding(.vertical, Spacing.xxs)
                        .background(Theme.background, in: RoundedRectangle(cornerRadius: Radius.tag, style: .continuous))
                        .accessibilityElement(children: .combine)
                        Tag(text: "\(phase.label) · \(revision.stepLabel)", foreground: phaseColors.text, background: phaseColors.background)
                    }
                    Text("C\(course.courseNumber) : \(course.title)").font(.title2.weight(.bold)).foregroundStyle(Theme.text)
                        .accessibilityAddTraits(.isHeader)
                    Text("Chapitre : \(course.chapter)").font(.subheadline).foregroundStyle(Theme.textSecondary)
                }

                VStack(spacing: Spacing.m) {
                    VStack(spacing: Spacing.xxs) {
                        Text("De quoi te souviens-tu sans regarder ton cours ?").font(.headline).foregroundStyle(Theme.text)
                        (Text("Avec ") + Text("50/100").bold() + Text(" on garde ton rythme actuel."))
                            .font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                    .multilineTextAlignment(.center)
                    HStack(alignment: .firstTextBaseline, spacing: Spacing.xxs) {
                        Text("\(value)")
                            .font(.system(size: 56, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(Theme.scoreColor(preview.band))
                            .contentTransition(.numericText(value: Double(value)))
                        Text("/ 100").font(.title3.weight(.semibold)).foregroundStyle(Theme.textMuted)
                    }
                    .accessibilityHidden(true)
                    Slider(value: $score, in: 0...100, step: Double(Constants.scoreStep))
                        .tint(Theme.violet)
                        .accessibilityLabel("Ton niveau de mémorisation sur 100")
                        .accessibilityValue("\(value) sur 100")
                    let columns = typeSize.isAccessibilitySize ? 2 : Constants.scorePresets.count
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Spacing.xs), count: columns), spacing: Spacing.xs) {
                        ForEach(Constants.scorePresets) { preset in
                            let selected = value == preset.value
                            Button { score = Double(preset.value) } label: {
                                VStack(spacing: 2) {
                                    Text("\(preset.value)").font(.subheadline.weight(.bold)).monospacedDigit()
                                    Text(preset.sub).font(.caption2).lineLimit(2).minimumScaleFactor(0.85).multilineTextAlignment(.center)
                                }
                                .foregroundStyle(selected ? Theme.violetDeep : Theme.textSecondary)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .padding(.horizontal, 2)
                                .background(selected ? Theme.violetTrack : Theme.surface, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                                .overlay(RoundedRectangle(cornerRadius: Radius.control, style: .continuous)
                                    .strokeBorder(selected ? Theme.violet : Theme.border, lineWidth: selected ? 1.5 : 1))
                                .contentShape(RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(preset.value) sur 100, \(preset.sub)")
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
                .panel(padding: Spacing.m)

                HStack(alignment: .top, spacing: Spacing.s) {
                    Image(systemName: icon(preview.tone)).font(.body.weight(.semibold)).foregroundStyle(toneColors.text).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: Spacing.xxs) {
                        Text(preview.title).font(.subheadline.weight(.semibold))
                        Text(preview.description).font(.footnote)
                    }
                    .foregroundStyle(toneColors.text)
                }
                .panel(padding: Spacing.m, fill: toneColors.background)
                .accessibilityElement(children: .combine)

                Button {
                    // Une seule validation, même sur un double toucher.
                    guard !submitted else { return }
                    submitted = true
                    onValidate(value)
                } label: {
                    Label("Valider la révision", systemImage: "checkmark")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(submitted || revision.status == .completed)
            }
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
        }
        .background(Theme.surface)
        .sensoryFeedback(.selection, trigger: value)
    }

    private func icon(_ tone: ScorePreview.Tone) -> String {
        switch tone {
        case .emergency: "exclamationmark.triangle"
        case .shortened: "chart.line.downtrend.xyaxis"
        case .exact: "scope"
        case .expanded: "chart.line.uptrend.xyaxis"
        }
    }
}
