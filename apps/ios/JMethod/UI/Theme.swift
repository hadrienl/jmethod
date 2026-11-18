import SwiftUI

/// Palette claire violet et citron (`docs/design-direction.md`), valeurs Tailwind du web.
/// L'app assume un thème clair (Info.plist `UIUserInterfaceStyle = Light`) : mêmes couleurs que le web et Android.
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

/// Espacements : uniquement 4, 8, 12, 16, 24 et 32 points.
enum Spacing {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let s: CGFloat = 12
    static let m: CGFloat = 16
    static let l: CGFloat = 24
    static let xl: CGFloat = 32
    /// Marge latérale des écrans.
    static let screen: CGFloat = 16
    /// Largeur maximale du contenu (iPad, paysage).
    static let readable: CGFloat = 680
}

/// Rayons d'arrondi (continus) : carte, panneau intérieur, contrôle, étiquette.
enum Radius {
    static let card: CGFloat = 20
    static let panel: CGFloat = 14
    static let control: CGFloat = 12
    static let tag: CGFloat = 8
}

/// Hiérarchie typographique : styles système, donc Dynamic Type.
extension Font {
    /// Titre de section dans un écran.
    static let sectionTitle = Font.title3.weight(.bold)
    /// Titre d'une carte ou d'une ligne principale.
    static let cardTitle = Font.headline
    /// Étiquettes et sur-titres.
    static let tagLabel = Font.caption.weight(.semibold)
    /// Grand chiffre (score, compteur).
    static let metric = Font.system(.title, design: .rounded).weight(.bold)
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

// MARK: - Surfaces

/// Carte blanche à bord fin, comme `.surface` du web.
struct SurfaceModifier: ViewModifier {
    var padding: CGFloat = Spacing.m
    var highlighted = false
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: shape)
            .overlay(shape.strokeBorder(highlighted ? Theme.violet : Theme.border, lineWidth: highlighted ? 2 : 1))
    }
}

/// Panneau intérieur (dans une carte ou une feuille) : fond gris très clair, sans bord.
struct PanelModifier: ViewModifier {
    var padding: CGFloat = Spacing.s
    var fill: Color = Theme.background
    var stroke: Color?
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.panel, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: shape)
            .overlay { if let stroke { shape.strokeBorder(stroke) } }
    }
}

extension View {
    func surface(padding: CGFloat = Spacing.m, highlighted: Bool = false) -> some View {
        modifier(SurfaceModifier(padding: padding, highlighted: highlighted))
    }

    func panel(padding: CGFloat = Spacing.s, fill: Color = Theme.background, stroke: Color? = nil) -> some View {
        modifier(PanelModifier(padding: padding, fill: fill, stroke: stroke))
    }

    /// Contenu d'un onglet : marges et largeur lisible communes.
    func screenContent() -> some View {
        self
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
            .frame(maxWidth: Spacing.readable)
            .frame(maxWidth: .infinity)
    }
}

/// Champ de saisie : fond doux, bord fin (rouge si la valeur est invalide), cible de 48 pt.
struct InputFieldModifier: ViewModifier {
    var fill: Color = Theme.background
    var invalid = false
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.control, style: .continuous)
        content
            .font(.body)
            .foregroundStyle(Theme.text)
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xs)
            .frame(minHeight: 48)
            .background(fill, in: shape)
            .overlay(shape.strokeBorder(invalid ? Theme.danger : Theme.border))
    }
}

extension View {
    func inputField(fill: Color = Theme.background, invalid: Bool = false) -> some View {
        modifier(InputFieldModifier(fill: fill, invalid: invalid))
    }
}

// MARK: - Boutons

/// Bouton principal violet, cible d'au moins 48 pt.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.xs)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(configuration.isPressed ? Theme.violetDark : Theme.violet, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
            .opacity(isEnabled ? 1 : 0.45)
            .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
    }
}

/// Bouton secondaire à bord fin, cible d'au moins 44 pt.
struct SecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Theme.violetDark)
            .multilineTextAlignment(.center)
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.xs)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(configuration.isPressed ? Theme.violetTrack : Theme.surface, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(Theme.violetLight))
            .opacity(isEnabled ? 1 : 0.45)
            .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
    }
}

/// Bouton teinté (fond violet très clair) : actions de ligne (« Faire le point », « Ajouter ce cours », pièces jointes).
struct TintedButtonStyle: ButtonStyle {
    var fullWidth = true
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.violetDark)
            .padding(.horizontal, fullWidth ? Spacing.m : Spacing.s)
            .padding(.vertical, Spacing.xs)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 44, alignment: fullWidth ? .leading : .center)
            .background(configuration.isPressed ? Theme.violetTrack : Theme.violetSoft, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .opacity(isEnabled ? 1 : 0.45)
            .contentShape(RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
    }
}

/// Bouton destructif discret (texte rouge, cible de 44 pt).
struct DestructiveTextButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Theme.danger)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(configuration.isPressed ? Theme.dangerSoft : Color.clear, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
            .contentShape(Rectangle())
    }
}

// MARK: - Éléments partagés

/// Pastille de couleur de matière (décorative : le nom est toujours écrit).
struct SubjectDot: View {
    let color: String
    var size: CGFloat = 10
    var body: some View {
        Circle().fill(Color(hex: color)).frame(width: size, height: size).accessibilityHidden(true)
    }
}

/// Étiquette courte (étape, jalon, état) : texte foncé sur fond clair.
struct Tag: View {
    let text: String
    var foreground: Color = Theme.violetDark
    var background: Color = Theme.violetSoft
    var systemImage: String?

    var body: some View {
        HStack(spacing: Spacing.xxs) {
            if let systemImage { Image(systemName: systemImage).imageScale(.small).accessibilityHidden(true) }
            Text(text)
        }
        .font(.tagLabel)
        .foregroundStyle(foreground)
        .padding(.horizontal, Spacing.xs)
        .padding(.vertical, Spacing.xxs)
        .background(background, in: RoundedRectangle(cornerRadius: Radius.tag, style: .continuous))
    }
}

/// Icône dans un carré arrondi (en-tête de carte, lien de navigation).
struct IconTile: View {
    let systemName: String
    var tint: Color = Theme.violet
    var background: Color = Theme.violetTrack
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: systemName)
            .font(.body.weight(.semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(background, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// Titre de section d'un écran, avec sous-titre et complément facultatifs.
struct SectionHeader: View {
    let title: String
    var subtitle: String?
    var trailing: String?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            // Très grandes tailles : le complément passe sous le titre plutôt que de le couper.
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xxs))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Spacing.xs))
            layout {
                Text(title).font(.sectionTitle).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                    .layoutPriority(1)
                if let trailing {
                    if !typeSize.isAccessibilitySize { Spacer(minLength: Spacing.xs) }
                    Text(trailing).font(.subheadline).foregroundStyle(Theme.textMuted)
                }
            }
            if let subtitle {
                Text(subtitle).font(.subheadline).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// État vide ou de résultat : icône, titre, message, action facultative.
struct EmptyState<Actions: View>: View {
    let systemImage: String
    let title: String
    let message: String
    var tint: Color = Theme.violet
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(spacing: Spacing.s) {
            IconTile(systemName: systemImage, tint: tint, background: tint.opacity(0.1), size: 48)
            VStack(spacing: Spacing.xxs) {
                Text(title).font(.cardTitle).foregroundStyle(Theme.text)
                Text(message).font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            .multilineTextAlignment(.center)
            .accessibilityElement(children: .combine)
            actions
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, Spacing.m)
        .surface()
    }
}

extension EmptyState where Actions == EmptyView {
    init(systemImage: String, title: String, message: String, tint: Color = Theme.violet) {
        self.init(systemImage: systemImage, title: title, message: message, tint: tint) { EmptyView() }
    }
}
