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
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.violetDark)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
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
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark").font(.caption.weight(.bold)).accessibilityHidden(true)
                } else if let color {
                    SubjectDot(color: color, size: 8)
                }
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            .foregroundStyle(selected ? Theme.violetDeep : Theme.textSecondary)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(selected ? Theme.violetTrack : Theme.surface, in: Capsule())
            .overlay(Capsule().strokeBorder(selected ? Theme.violet : Theme.border, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Badge d'étape (Découvrir / S'entraîner / Se tester) aux couleurs du web.
struct PhaseBadge: View {
    let text: String
    let phase: Phase

    var body: some View {
        let colors = Theme.phase(phase)
        Text(text)
            .font(.caption.weight(.bold))
            .foregroundStyle(colors.text)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(colors.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

/// Barre de progression décorative ; la valeur est portée par l'élément d'accessibilité parent.
struct ProgressBar: View {
    let percent: Int
    var height: CGFloat = 6

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Theme.violetTrack)
                Capsule().fill(Theme.violet).frame(width: proxy.size.width * CGFloat(min(max(percent, 0), 100)) / 100)
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
    }
}
