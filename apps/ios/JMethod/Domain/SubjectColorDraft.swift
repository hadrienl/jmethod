import Foundation

/// Brouillon du sélecteur de couleur d'une matière (#8) : rien n'est enregistré avant « Enregistrer ».
/// La sélection HSV est continue (les gestes ne repassent pas par l'hexadécimal) ; `hex` est le dernier code valide
/// (aperçu), `text` le contenu du champ, laissé tel quel pendant la saisie.
struct SubjectColorDraft: Equatable, Sendable {
    /// Repli d'une couleur enregistrée illisible : gris neutre de `Color(hex:)`.
    static let fallback = "#94A3B8"
    /// Pas des flèches et des ajustements VoiceOver.
    static let hueStep = 5.0
    static let levelStep = 0.05
    static let invalidMessage = "Code attendu : 6 chiffres hexadécimaux, par exemple #2563EB."

    let subjectId: String
    /// Couleur enregistrée à l'ouverture, normalisée.
    let original: String
    private(set) var color: ColorWheel.HSV
    private(set) var hex: String
    private(set) var text: String

    init(subject: Subject) {
        subjectId = subject.id
        original = ColorWheel.normalized(subject.color) ?? Self.fallback
        color = ColorWheel.hsv(original) ?? ColorWheel.HSV(hue: 0, saturation: 0, value: 0.5)
        hex = original
        text = original
    }

    /// Code du champ prêt à enregistrer, ou nil s'il est incomplet ou invalide.
    var candidate: String? { ColorWheel.normalized(text) }
    var canSave: Bool { candidate != nil }
    var textError: String? { canSave ? nil : Self.invalidMessage }
    var isModified: Bool { candidate != original }

    // MARK: Gestes et ajustements (le champ suit)

    mutating func setHue(_ hue: Double) {
        apply(ColorWheel.HSV(hue: hue, saturation: color.saturation, value: color.value))
    }

    mutating func setSaturation(_ saturation: Double, value: Double) {
        apply(ColorWheel.HSV(hue: color.hue, saturation: saturation, value: value))
    }

    /// Flèches et VoiceOver : la teinte fait le tour, saturation et luminosité sont bornées.
    mutating func adjustHue(steps: Int) { setHue(color.hue + Double(steps) * Self.hueStep) }
    mutating func adjustSaturation(steps: Int) { setSaturation(Self.snapped(color.saturation, steps), value: color.value) }
    mutating func adjustValue(steps: Int) { setSaturation(color.saturation, value: Self.snapped(color.value, steps)) }

    private mutating func apply(_ next: ColorWheel.HSV) {
        color = next
        hex = ColorWheel.hex(next)
        text = hex
    }

    /// Pas de 5 % aligné sur la grille, pour que l'annonce tombe sur une valeur ronde.
    private static func snapped(_ level: Double, _ steps: Int) -> Double {
        let grid = (level / levelStep).rounded() * levelStep
        return min(1, max(0, grid + Double(steps) * levelStep))
    }

    // MARK: Champ hexadécimal

    /// Saisie : le texte n'est pas réécrit ; un code complet valide actualise la roue, sinon le dernier aperçu reste.
    mutating func editText(_ value: String) {
        text = value
        guard let valid = ColorWheel.normalized(value), valid != hex,
              let next = ColorWheel.hsv(valid, keeping: color) else { return }
        color = next
        hex = valid
    }

    /// Validation du champ (Entrée, sortie du champ) : un code valide est normalisé en `#RRGGBB`.
    mutating func commitText() {
        if let valid = candidate { text = valid }
    }

    // MARK: Annonces

    var hueDegrees: Int { Int(color.hue.rounded()) % 360 }
    /// Nom approximatif de la teinte, pour VoiceOver.
    var hueName: String {
        switch color.hue {
        case ..<15: "rouge"
        case ..<45: "orange"
        case ..<70: "jaune"
        case ..<165: "vert"
        case ..<195: "cyan"
        case ..<255: "bleu"
        case ..<285: "violet"
        case ..<335: "rose"
        default: "rouge"
        }
    }
    var saturationPercent: Int { Int((color.saturation * 100).rounded()) }
    var valuePercent: Int { Int((color.value * 100).rounded()) }
}
