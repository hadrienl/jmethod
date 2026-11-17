import SwiftUI

/// Palette claire violet et citron (`docs/design-direction.md`), valeurs Tailwind du web.
enum Theme {
    static let violet = Color(hex: "#6D28D9")        // violet-700
    static let violetDark = Color(hex: "#5B21B6")    // violet-800
    static let violetDeep = Color(hex: "#2E1065")    // violet-950
    static let violetSoft = Color(hex: "#F5F3FF")    // violet-50
    static let violetTrack = Color(hex: "#EDE9FE")   // violet-100
    static let violetLight = Color(hex: "#DDD6FE")   // violet-200
    static let lime = Color(hex: "#D9F99D")          // lime-200
    static let background = Color(hex: "#F8FAFC")    // slate-50
    static let surface = Color.white
    static let border = Color(hex: "#E2E8F0")        // slate-200
    static let text = Color(hex: "#0F172A")          // slate-900
    static let textSecondary = Color(hex: "#475569") // slate-600
    static let textMuted = Color(hex: "#64748B")     // slate-500
    static let success = Color(hex: "#065F46")       // emerald-800
    static let successSoft = Color(hex: "#ECFDF5")   // emerald-50
    static let warning = Color(hex: "#78350F")       // amber-900
    static let warningSoft = Color(hex: "#FFFBEB")   // amber-50
    static let danger = Color(hex: "#9F1239")        // rose-800
    static let dangerSoft = Color(hex: "#FFF1F2")    // rose-50

    /// Couleurs des badges d'étape (texte foncé sur fond clair, contraste AA).
    static func phase(_ phase: Phase) -> (text: Color, background: Color) {
        switch phase {
        case .encodage: (Color(hex: "#1E40AF"), Color(hex: "#EFF6FF"))
        case .consolidation: (Color(hex: "#9A3412"), Color(hex: "#FFF7ED"))
        case .recuperation: (Color(hex: "#065F46"), Color(hex: "#ECFDF5"))
        }
    }

    static func scoreColor(_ band: ScorePreview.Band) -> Color {
        switch band {
        case .rose: Color(hex: "#BE123C")
        case .amber: Color(hex: "#B45309")
        case .sky: Color(hex: "#0369A1")
        case .emerald: Color(hex: "#047857")
        case .indigo: Color(hex: "#4338CA")
        }
    }

    static func previewColors(_ tone: ScorePreview.Tone) -> (text: Color, background: Color) {
        switch tone {
        case .emergency: (Color(hex: "#BE123C"), Color(hex: "#FFF1F2"))
        case .shortened: (Color(hex: "#B45309"), Color(hex: "#FFFBEB"))
        case .exact: (Color(hex: "#0369A1"), Color(hex: "#F0F9FF"))
        case .expanded: (Color(hex: "#047857"), Color(hex: "#ECFDF5"))
        }
    }
}

extension Color {
    /// `#RRGGBB` ; une valeur invalide donne un gris neutre.
    init(hex: String) {
        let digits = hex.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        let value = digits.count == 6 ? UInt32(digits, radix: 16) : nil
        let rgb = value ?? 0x94A3B8
        self.init(.sRGB, red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

/// Carte blanche à bord fin, comme `.surface` du web.
struct SurfaceModifier: ViewModifier {
    var padding: CGFloat = 20
    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.border))
    }
}

extension View {
    func surface(padding: CGFloat = 20) -> some View { modifier(SurfaceModifier(padding: padding)) }
}

/// Bouton principal violet, cible d'au moins 48 pt.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.bold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 16)
            .background(configuration.isPressed ? Theme.violetDark : Theme.violet, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(isEnabled ? 1 : 0.45)
    }
}

/// Bouton secondaire à bord fin, cible d'au moins 44 pt.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Theme.violetDark)
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, 14)
            .background(configuration.isPressed ? Theme.violetTrack : Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.violetLight))
            .opacity(isEnabled ? 1 : 0.45)
    }
}

/// Pastille de couleur de matière (décorative : le nom est toujours écrit).
struct SubjectDot: View {
    let color: String
    var size: CGFloat = 12
    var body: some View {
        Circle().fill(Color(hex: color)).frame(width: size, height: size).accessibilityHidden(true)
    }
}
