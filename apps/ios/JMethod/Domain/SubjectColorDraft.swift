import Foundation

/// Brouillon du sélecteur de couleur d'une matière (#8) : rien n'est enregistré avant « Enregistrer ».
/// La sélection HSV est continue (les gestes ne repassent pas par l'hexadécimal) ; `hex` est le dernier code valide
/// (aperçu), `text` le contenu du champ, laissé tel quel pendant la saisie.
struct SubjectColorDraft: Equatable, Sendable {
    /// Repli d'une couleur enregistrée illisible : gris neutre de `Color(hex:)`.
    static let fallback = "#94A3B8"
    /// Flèches du clavier : 1° ou 1 % par appui, 10 fois plus avec Maj.
    static let keyHueStep = 1.0
    static let keyLevelStep = 0.01
    static let shiftMultiplier = 10.0
    /// Ajustements VoiceOver : 5° (la teinte boucle de 359 à 0) et 5 %.
    static let voiceOverHueStep = 5.0
    static let voiceOverLevelStep = 0.05
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

    /// Flèches et VoiceOver : la teinte boucle (359 ↔ 0), saturation et luminosité sont bornées à [0, 1].
    mutating func adjustHue(by degrees: Double) { setHue(color.hue + degrees) }
    mutating func adjustSaturation(by delta: Double) { setSaturation(Self.snapped(color.saturation, delta), value: color.value) }
    mutating func adjustValue(by delta: Double) { setSaturation(color.saturation, value: Self.snapped(color.value, delta)) }

    /// Pas d'une flèche du clavier, multiplié par 10 avec Maj.
    static func keyStep(_ base: Double, shift: Bool) -> Double { shift ? base * shiftMultiplier : base }

    private mutating func apply(_ next: ColorWheel.HSV) {
        color = next
        hex = ColorWheel.hex(next)
        text = hex
    }

    /// Valeur ramenée sur la grille du pas avant l'ajout, pour que l'annonce tombe sur une valeur ronde.
    private static func snapped(_ level: Double, _ delta: Double) -> Double {
        let step = abs(delta)
        guard step > 0 else { return level }
        let grid = (level / step).rounded() * step
        return min(1, max(0, ((grid + delta) * 1e6).rounded() / 1e6))
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
    /// Nom commun annoncé par VoiceOver (même table que TalkBack).
    var colorName: String { ColorWheel.name(color) }
    var saturationPercent: Int { Int((color.saturation * 100).rounded()) }
    var valuePercent: Int { Int((color.value * 100).rounded()) }
}
