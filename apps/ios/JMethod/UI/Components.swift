import SwiftUI

/// Bouton « Ajouter ce cours » d'un créneau Pronote (Aujourd'hui et Planning) : ouvre le formulaire prérempli.
struct AddCourseFromSlotButton: View {
    let subjectName: String
    /// Libellé court « Ajouter » pour une ligne d'emploi du temps ; VoiceOver garde le libellé complet.
    var compact = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(compact ? "Ajouter" : "Ajouter ce cours", systemImage: "plus.circle")
        }
        .buttonStyle(TintedButtonStyle(fullWidth: false))
        .accessibilityLabel("Ajouter ce cours : \(subjectName)")
        .accessibilityHint("Ouvre le formulaire de création prérempli")
    }
}

/// Puce de filtre (matière, « Toutes ») avec état sélectionné lisible sans la couleur.
struct FilterChip: View {
    let title: String
    var color: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.xs) {
                if selected {
                    Image(systemName: "checkmark").font(.caption.weight(.bold)).accessibilityHidden(true)
                } else if let color {
                    SubjectDot(color: color, size: 8)
                }
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .foregroundStyle(selected ? Theme.violetDeep : Theme.textSecondary)
            .padding(.horizontal, Spacing.s)
            .frame(minHeight: 44)
            .background(selected ? Theme.violetTrack : Theme.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? Theme.violet : Theme.border, lineWidth: selected ? 1.5 : 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .sensoryFeedback(.selection, trigger: selected)
    }
}

/// Badge d'étape (Découvrir / S'entraîner / Se tester) aux couleurs du web.
struct PhaseBadge: View {
    let text: String
    let phase: Phase

    var body: some View {
        let colors = Theme.phase(phase)
        Tag(text: text, foreground: colors.text, background: colors.background)
    }
}

/// Barre de progression décorative ; la valeur est portée par l'élément d'accessibilité parent.
struct ProgressBar: View {
    let percent: Int
    var height: CGFloat = 8
    var tint: Color = Theme.violet
    var track: Color = Theme.violetTrack
    /// Libellé VoiceOver ; sans libellé, la barre est décorative.
    var label: String?

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(tint)
                    .frame(width: max(percent > 0 ? height : 0, proxy.size.width * CGFloat(min(max(percent, 0), 100)) / 100))
            }
        }
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label ?? "")
        .accessibilityValue("\(percent) %")
        .accessibilityHidden(label == nil)
    }
}

/// Rangée qui passe à la ligne quand la place manque (grandes tailles de texte).
struct FlowRow: Layout {
    var spacing: CGFloat = Spacing.xs

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width + 0.5 { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        // Largeur proposée entière : le placement retrouve exactement les mêmes retours à la ligne.
        let proposed = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        return CGSize(width: proposed.map { max($0, maxX) } ?? maxX, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            // Tolérance d'un demi-point : un arrondi de mise en page ne doit pas créer une ligne non mesurée.
            if x > bounds.minX && x + size.width > bounds.maxX + 0.5 { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Logo J-Method (même image que l'écran de lancement) et nom.
struct BrandMark: View {
    var size: CGFloat = 48
    var showsName = true

    var body: some View {
        HStack(spacing: Spacing.s) {
            Image("BrandLogo")
                .resizable()
                .interpolation(.high)
                .frame(width: size, height: size)
                .accessibilityHidden(true)
            if showsName {
                Text("J-Method").font(.title2.weight(.bold)).foregroundStyle(Theme.text)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("J-Method")
    }
}
