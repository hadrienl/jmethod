import SwiftUI

/// État du sélecteur de couleur d'une matière : brouillon local et enregistrement confirmé (#8).
/// Rien n'est écrit avant `confirm()` ; un échec garde le brouillon et explique comment réessayer.
@Observable
@MainActor
final class SubjectColorEditor {
    let subjectName: String
    var draft: SubjectColorDraft {
        // L'erreur d'écriture précédente s'efface dès que la couleur change.
        didSet { if draft.hex != oldValue.hex || draft.candidate != oldValue.candidate { saveError = nil } }
    }
    private(set) var saving = false
    private(set) var saveError: String?
    /// Annuler, la croix, Échap ou la fermeture de la feuille : plus aucune écriture possible.
    private(set) var cancelled = false
    @ObservationIgnored private let app: AppModel

    init(app: AppModel, subject: Subject) {
        self.app = app
        subjectName = subject.name
        draft = SubjectColorDraft(subject: subject)
    }

    /// Faux si la matière a été supprimée pendant l'édition (restauration) : elle ne sera pas recréée.
    var subjectExists: Bool { app.subject(id: draft.subjectId) != nil }
    var canConfirm: Bool { draft.canSave && subjectExists && !saving && !cancelled }
    /// Message sous l'aperçu : matière supprimée (permanent) ou échec de la dernière écriture.
    var message: String? { subjectExists ? saveError : AppModel.SubjectColorFailure.subjectMissing.errorDescription }

    /// Enregistrer, ou Entrée (champ, anneau, carré). Vrai après une écriture réussie : la feuille peut se fermer.
    /// Un code invalide n'est jamais envoyé : rien ne se passe et l'erreur du champ reste affichée.
    func confirm() async -> Bool {
        guard !saving, !cancelled else { return false }
        draft.commitText()
        guard let color = draft.candidate, subjectExists else { return false }
        saving = true
        saveError = nil
        defer { saving = false }
        do {
            try await app.setSubjectColor(subjectId: draft.subjectId, color: color)
            return true
        } catch {
            saveError = (error as? LocalizedError)?.errorDescription ?? AppModel.SubjectColorFailure.unwritable.errorDescription
            return false
        }
    }

    /// Abandon du brouillon ; sans effet pendant une écriture (la fermeture est alors désactivée).
    func cancel() {
        guard !saving else { return }
        cancelled = true
    }
}

/// Feuille « Couleur de la matière » : anneau de teinte, carré saturation/luminosité, code hexadécimal, aperçu.
/// Annuler (« Fermer » si la matière a été supprimée), la croix, Échap et le glissement vers le bas abandonnent le brouillon.
struct SubjectColorPicker: View {
    enum Control: Hashable { case ring, square, hex }

    @State private var editor: SubjectColorEditor
    @FocusState private var focus: Control?
    @Environment(\.dismiss) private var dismiss

    init(app: AppModel, subject: Subject) {
        _editor = State(initialValue: SubjectColorEditor(app: app, subject: subject))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    HStack(spacing: Spacing.xs) {
                        SubjectDot(color: editor.draft.original, size: 14)
                        Text(editor.subjectName).font(.title3.weight(.semibold)).foregroundStyle(Theme.text)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Matière : \(editor.subjectName)")
                    ColorWheelControl(draft: $editor.draft, focus: $focus, onConfirm: save)
                    selectedColor
                    SubjectColorPreview(subjectName: editor.subjectName, color: editor.draft.hex)
                    if let error = editor.message {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline).foregroundStyle(Theme.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .panel(fill: Theme.dangerSoft)
                    }
                    actions
                }
                .padding(Spacing.m)
                .frame(maxWidth: Spacing.readable)
                .frame(maxWidth: .infinity)
                .onKeyPress(.escape) {
                    guard !editor.saving else { return .ignored }
                    close()
                    return .handled
                }
            }
            .background(Theme.surface)
            .navigationTitle("Couleur de la matière")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: close) { Image(systemName: "xmark").frame(minWidth: 44, minHeight: 44) }
                        .disabled(editor.saving)
                        .accessibilityLabel("Fermer sans enregistrer")
                }
            }
        }
        .onChange(of: focus) { previous, current in
            // Sortie du champ : un code valide est normalisé, une saisie incomplète reste telle quelle.
            if previous == .hex, current != .hex { editor.draft.commitText() }
        }
        // Glissement vers le bas : le brouillon est abandonné comme avec Annuler.
        .onDisappear { editor.cancel() }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(editor.saving)
    }

    private var selectedColor: some View {
        let error = editor.draft.textError
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Text("Couleur choisie").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            HStack(spacing: Spacing.s) {
                RoundedRectangle(cornerRadius: Radius.control, style: .continuous)
                    .subjectFill(editor.draft.hex, border: Theme.border)
                    .frame(width: 48, height: 48)
                    .accessibilityHidden(true)
                TextField("Code hexadécimal", text: Binding(get: { editor.draft.text }, set: { editor.draft.editText($0) }), prompt: Text("#RRGGBB"))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .submitLabel(.done)
                    .font(.body.monospaced())
                    .inputField(invalid: error != nil)
                    .focused($focus, equals: .hex)
                    // Entrée : enregistre et ferme si le code est valide ; sinon rien, l'erreur reste affichée.
                    .onSubmit { editor.draft.commitText(); save() }
                    .accessibilityLabel("Code hexadécimal de la couleur")
                    .accessibilityHint(error ?? "Six chiffres hexadécimaux, avec ou sans dièse")
            }
            if let error {
                Label(error, systemImage: "exclamationmark.circle")
                    .font(.footnote).foregroundStyle(Theme.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var actions: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.s) { cancelButton; saveButton }
            VStack(spacing: Spacing.s) { saveButton; cancelButton }
        }
    }

    private var cancelButton: some View {
        Button(editor.subjectExists ? "Annuler" : "Fermer", action: close)
            .buttonStyle(SecondaryButtonStyle())
            .keyboardShortcut(.cancelAction)
            .disabled(editor.saving)
            .accessibilityHint("Ferme sans changer la couleur")
    }

    private var saveButton: some View {
        Button(action: save) {
            HStack(spacing: Spacing.xs) {
                if editor.saving { ProgressView().tint(.white) }
                Text(editor.saving ? "Enregistrement…" : "Enregistrer")
            }
        }
        .buttonStyle(PrimaryButtonStyle())
        .keyboardShortcut(.defaultAction)
        .disabled(!editor.canConfirm)
        .accessibilityHint(editor.draft.canSave ? "Applique cette couleur à \(editor.subjectName)" : "Corrige le code couleur pour enregistrer")
    }

    private func close() {
        editor.cancel()
        dismiss()
    }

    private func save() {
        guard editor.canConfirm else { return }
        Task {
            if await editor.confirm() {
                dismiss()
            } else if let error = editor.saveError {
                AccessibilityNotification.Announcement(error).post()
            }
        }
    }
}

// MARK: - Roue

/// Anneau de teinte et carré saturation/luminosité. Chaque contrôle a sa propre zone de geste : un geste reste
/// attaché au contrôle où il a commencé, et un appui hors de l'anneau et du carré est ignoré.
private struct ColorWheelControl: View {
    @Binding var draft: SubjectColorDraft
    var focus: FocusState<SubjectColorPicker.Control?>.Binding
    let onConfirm: () -> Void

    private static let hues = stride(from: 0.0, through: 360, by: 30).map { Color(hue: $0 / 360, saturation: 1, brightness: 1) }

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let thickness = max(32, side * 0.13)
            // Carré inscrit dans le trou de l'anneau, avec une marge.
            let square = max(0, (side / 2 - thickness) * 2.squareRoot() - Spacing.m)
            ZStack {
                ring(side: side, thickness: thickness)
                saturationSquare(side: square)
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .frame(maxWidth: 320)
        .frame(maxWidth: .infinity)
    }

    // MARK: Anneau

    private func ring(side: CGFloat, thickness: CGFloat) -> some View {
        let radius = side / 2 - thickness / 2
        let direction = ColorWheel.ringDirection(hue: draft.color.hue)
        let focused = focus.wrappedValue == .ring
        return ZStack {
            Circle()
                .strokeBorder(AngularGradient(colors: Self.hues, center: .center, startAngle: .degrees(-90), endAngle: .degrees(270)), lineWidth: thickness)
            if focused {
                Circle().strokeBorder(Theme.violet, lineWidth: 2).padding(-Spacing.xxs)
            }
            Knob(color: Color(hue: draft.color.hue / 360, saturation: 1, brightness: 1), size: thickness - 4)
                .position(x: side / 2 + direction.dx * radius, y: side / 2 + direction.dy * radius)
        }
        .frame(width: side, height: side)
        .contentShape(RingShape(thickness: thickness), eoFill: true)
        .gesture(DragGesture(minimumDistance: 0).onChanged { value in
            if let hue = ColorWheel.hue(dx: value.location.x - side / 2, dy: value.location.y - side / 2) { draft.setHue(hue) }
        })
        .focusable(interactions: .edit)
        .focused(focus, equals: .ring)
        .focusEffectDisabled()
        .onKeyPress(keys: [.leftArrow, .rightArrow, .upArrow, .downArrow]) { press in
            let step = SubjectColorDraft.keyStep(SubjectColorDraft.keyHueStep, shift: press.modifiers.contains(.shift))
            draft.adjustHue(by: press.key == .rightArrow || press.key == .upArrow ? step : -step)
            return .handled
        }
        .onKeyPress(.return) { confirm() }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Teinte")
        .accessibilityValue("\(draft.hueDegrees) degrés, \(draft.colorName)")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: draft.adjustHue(by: SubjectColorDraft.voiceOverHueStep)
            case .decrement: draft.adjustHue(by: -SubjectColorDraft.voiceOverHueStep)
            @unknown default: break
            }
        }
    }

    // MARK: Carré

    private func saturationSquare(side: CGFloat) -> some View {
        let point = ColorWheel.squarePoint(saturation: draft.color.saturation, value: draft.color.value, width: side, height: side)
        let focused = focus.wrappedValue == .square
        return ZStack {
            Rectangle().fill(Color(hue: draft.color.hue / 360, saturation: 1, brightness: 1))
            Rectangle().fill(LinearGradient(colors: [.white, .white.opacity(0)], startPoint: .leading, endPoint: .trailing))
            Rectangle().fill(LinearGradient(colors: [.black.opacity(0), .black], startPoint: .top, endPoint: .bottom))
        }
        .overlay(Rectangle().strokeBorder(focused ? Theme.violet : Theme.border, lineWidth: focused ? 2 : 1))
        .overlay {
            Knob(color: Color(hex: draft.hex), size: 26).position(x: point.x, y: point.y)
        }
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0).onChanged { value in
            let picked = ColorWheel.square(x: value.location.x, y: value.location.y, width: side, height: side)
            draft.setSaturation(picked.saturation, value: picked.value)
        })
        .focusable(interactions: .edit)
        .focused(focus, equals: .square)
        .focusEffectDisabled()
        .onKeyPress(keys: [.leftArrow, .rightArrow, .upArrow, .downArrow]) { press in
            // Un seul arrêt de Tab : gauche/droite pour la saturation, haut/bas pour la luminosité.
            let step = SubjectColorDraft.keyStep(SubjectColorDraft.keyLevelStep, shift: press.modifiers.contains(.shift))
            switch press.key {
            case .leftArrow: draft.adjustSaturation(by: -step)
            case .rightArrow: draft.adjustSaturation(by: step)
            case .upArrow: draft.adjustValue(by: step)
            default: draft.adjustValue(by: -step)
            }
            return .handled
        }
        .onKeyPress(.return) { confirm() }
        // VoiceOver : deux réglages séparés, la saturation et la luminosité.
        .accessibilityElement(children: .contain)
        .accessibilityChildren {
            VStack(spacing: 0) {
                Rectangle()
                    .accessibilityLabel("Saturation")
                    .accessibilityValue("\(draft.saturationPercent) %, \(draft.colorName)")
                    .accessibilityAdjustableAction { direction in
                        let step = SubjectColorDraft.voiceOverLevelStep
                        draft.adjustSaturation(by: direction == .increment ? step : -step)
                    }
                Rectangle()
                    .accessibilityLabel("Luminosité")
                    .accessibilityValue("\(draft.valuePercent) %, \(draft.colorName)")
                    .accessibilityAdjustableAction { direction in
                        let step = SubjectColorDraft.voiceOverLevelStep
                        draft.adjustValue(by: direction == .increment ? step : -step)
                    }
            }
        }
    }

    private func confirm() -> KeyPress.Result {
        guard draft.canSave else { return .ignored }
        onConfirm()
        return .handled
    }
}

/// Zone de geste de l'anneau (remplissage pair-impair : le trou central n'en fait pas partie).
private struct RingShape: Shape {
    let thickness: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addEllipse(in: rect)
        path.addEllipse(in: rect.insetBy(dx: thickness, dy: thickness))
        return path
    }
}

/// Curseur à double contour (sombre à l'extérieur, blanc à l'intérieur) : visible sur blanc, noir et couleurs saturées.
private struct Knob: View {
    let color: Color
    let size: CGFloat

    var body: some View {
        Circle().fill(color)
            .overlay(Circle().inset(by: 1.5).strokeBorder(.white, lineWidth: 3))
            .overlay(Circle().strokeBorder(Theme.text, lineWidth: 1.5))
            .frame(width: size, height: size)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

// MARK: - Aperçu

/// Séance fictive affichée avec les composants réels (filet et pastille de matière, badge d'étape) : aucune donnée
/// n'est créée. Le nom reste en couleur de texte neutre, comme partout dans l'app ; la couleur brute sert d'accent.
private struct SubjectColorPreview: View {
    let subjectName: String
    let color: String

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.panel, style: .continuous)
        let detail = "C1 + C2 · \(Phase.encodage.rhythmLabel)"
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text("Aperçu").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            HStack(spacing: 0) {
                SubjectColorBar(color: color)
                FlowRow(spacing: Spacing.xs) {
                    PhaseBadge(text: "J3", phase: .encodage)
                    HStack(spacing: Spacing.xs) {
                        SubjectDot(color: color, size: 12)
                        Text(subjectName).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                    }
                    .padding(.vertical, Spacing.xxs)
                    Text(detail).font(.footnote).foregroundStyle(Theme.textSecondary)
                        .padding(.vertical, Spacing.xxs)
                }
                .padding(Spacing.s)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(Theme.surface)
            .clipShape(shape)
            .overlay(shape.strokeBorder(Theme.border))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Aperçu : J3, \(subjectName), \(detail), couleur \(color)")
        }
        .panel()
    }
}
