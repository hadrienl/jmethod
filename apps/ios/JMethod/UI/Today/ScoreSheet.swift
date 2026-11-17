import SwiftUI

/// Autoévaluation 0–100 (`ScoreModal.tsx`) avec aperçu de la replanification.
struct ScoreSheet: View {
    let app: AppModel
    let revisionId: String
    @Environment(\.dismiss) private var dismiss
    @State private var score = Double(Constants.initialScore)

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
                    Button("Annuler") { dismiss() }.accessibilityLabel("Fermer l’autoévaluation")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }

    private func content(revision: Revision, course: Course, subject: Subject?) -> some View {
        let value = Int(score)
        let settings = app.document.settings
        let preview = ScorePreview(score: value, sensitivity: settings.sensitivity, emergencyThreshold: settings.emergencyThreshold)
        let phase = Constants.phaseConfig(revision.phase)
        let phaseColors = Theme.phase(revision.phase)
        let toneColors = Theme.previewColors(preview.tone)

        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 10) {
                    FlowRow {
                        HStack(spacing: 6) {
                            SubjectDot(color: subject?.color ?? "#94A3B8", size: 10)
                            Text((subject?.name ?? "Matière").uppercased()).font(.caption.weight(.semibold))
                        }
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Theme.background, in: Capsule())
                        .overlay(Capsule().strokeBorder(Theme.border))
                        Text("\(phase.label) • \(revision.stepLabel)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(phaseColors.text)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(phaseColors.background, in: RoundedRectangle(cornerRadius: 6))
                    }
                    Text("C\(course.courseNumber) : \(course.title)").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text("Chapitre : \(course.chapter)").font(.footnote).foregroundStyle(Theme.textSecondary)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("De quoi te souviens-tu sans regarder ton cours ?").font(.subheadline.weight(.medium))
                    (Text("Avec ") + Text("50/100").bold().foregroundColor(Theme.scoreColor(.sky)) + Text(" on garde ton rythme actuel."))
                        .font(.footnote).foregroundStyle(Theme.textSecondary)
                }

                VStack(spacing: 14) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text("\(value)").font(.system(.largeTitle, design: .monospaced).weight(.black)).foregroundStyle(Theme.scoreColor(preview.band))
                        Text("/ 100").font(.headline).foregroundStyle(Theme.textMuted)
                    }
                    .accessibilityHidden(true)
                    Slider(value: $score, in: 0...100, step: Double(Constants.scoreStep))
                        .tint(Theme.violet)
                        .accessibilityLabel("Ton niveau de mémorisation sur 100")
                        .accessibilityValue("\(value) sur 100")
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 62), spacing: 8)], spacing: 8) {
                        ForEach(Constants.scorePresets) { preset in
                            let selected = value == preset.value
                            Button { score = Double(preset.value) } label: {
                                VStack(spacing: 2) {
                                    Text("\(preset.value)").font(.subheadline.bold())
                                    Text(preset.sub).font(.caption2).lineLimit(2).multilineTextAlignment(.center)
                                }
                                .foregroundStyle(selected ? Theme.text : Theme.textSecondary)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .padding(.horizontal, 2)
                                .background(selected ? Color(hex: "#E0F2FE") : Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(selected ? Color(hex: "#38BDF8") : Theme.border, lineWidth: selected ? 2 : 1))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(preset.value) sur 100, \(preset.sub)")
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
                .padding(20)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.border))

                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: icon(preview.tone)).foregroundStyle(toneColors.text).accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(preview.title.uppercased()).font(.caption.weight(.bold))
                        Text(preview.description).font(.footnote)
                    }
                    .foregroundStyle(toneColors.text)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(toneColors.background, in: RoundedRectangle(cornerRadius: 14))
                .accessibilityElement(children: .combine)

                Button {
                    app.validate(revisionId: revision.id, score: value)
                    dismiss()
                } label: {
                    Label("Valider la révision", systemImage: "checkmark.circle")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(20)
        }
        .background(Theme.surface)
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

/// Rangée qui passe à la ligne quand la place manque (grandes tailles de texte).
struct FlowRow: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: min(maxX, width), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
