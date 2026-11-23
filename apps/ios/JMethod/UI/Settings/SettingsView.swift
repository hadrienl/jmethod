import SwiftUI
import UniformTypeIdentifiers

/// Onglet Réglages (`SettingsView.tsx`) : compte Pronote, algorithme, paliers, couleurs des matières,
/// sauvegarde et restauration compatibles avec le web, À propos. Rien n'est appliqué avant « Enregistrer »,
/// sauf les couleurs des matières, appliquées par « Enregistrer » de leur sélecteur, indépendamment de ce brouillon.
struct SettingsView: View {
    let app: AppModel
    let pronote: PronoteSession
    let importer: NotebookCourseImporter

    /// Modifications non enregistrées de l'algorithme et des paliers.
    struct Draft: Equatable {
        var base: AppSettings
        var sensitivity: Double
        var threshold: Double
        var intervals: [Int]
        var newInterval = ""

        init(_ settings: AppSettings) {
            base = settings
            sensitivity = settings.sensitivity
            threshold = settings.emergencyThreshold
            intervals = settings.intervals
        }

        var dirty: Bool { sensitivity != base.sensitivity || threshold != base.emergencyThreshold || intervals != base.intervals }
    }

    @State private var draft: Draft?
    @State private var colorFor: Subject?
    @State private var exporting: JSONExportDocument?
    @State private var importing = false
    @State private var pendingImport: Backup.Accepted?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var current: Draft {
        // Un brouillon périmé (réglages changés ailleurs, restauration) repart des réglages enregistrés.
        let saved = app.document.settings
        if let draft, draft.base.sensitivity == saved.sensitivity, draft.base.emergencyThreshold == saved.emergencyThreshold,
           draft.base.intervals == saved.intervals { return draft }
        return Draft(app.document.settings)
    }

    private func edit(_ change: (inout Draft) -> Void) {
        var value = current
        change(&value)
        draft = value
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    PronoteAccountSection(app: app, pronote: pronote)
                    notebookCourses
                    algorithm
                    RhythmSettingsCard(app: app).disabled(current.dirty)
                    colors
                    backup
                    about
                }
                .screenContent()
            }
            .background(Theme.background)
            .navigationTitle("Réglages")
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if current.dirty {
                    unsavedBar.transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.2), value: current.dirty)
        }
        .sheet(item: $colorFor) { subject in
            SubjectColorPicker(app: app, subject: subject)
        }
        .fileExporter(isPresented: Binding(get: { exporting != nil }, set: { if !$0 { exporting = nil } }),
                      document: exporting, contentType: .json, defaultFilename: app.exportFileName) { result in
            switch result {
            case .success: app.showToast("Export réussi", "Fichier de sauvegarde enregistré.")
            case .failure: app.showToast("Export impossible", "Le fichier n’a pas pu être écrit. Choisis un autre emplacement.", warning: true)
            }
            exporting = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            handleImport(result)
        }
        .confirmationDialog(
            "Importer aussi les \(importer.backfillOffer?.count ?? 0) séance\((importer.backfillOffer?.count ?? 0) > 1 ? "s" : "") des 14 derniers jours ?",
            isPresented: Binding(get: { importer.backfillOffer != nil }, set: { if !$0 { importer.answerBackfill(importOlder: false) } }),
            titleVisibility: .visible
        ) {
            Button("Importer") { importer.answerBackfill(importOlder: true) }
            Button("Seulement à partir d’aujourd’hui", role: .cancel) { importer.answerBackfill(importOlder: false) }
        } message: {
            Text("Un cours est créé pour chaque séance publiée dans le cahier de textes, avec ses documents.")
        }
        .alert("Remplacer tes données ?", isPresented: Binding(get: { pendingImport != nil }, set: { if !$0 { pendingImport = nil } })) {
            Button("Remplacer mes données", role: .destructive) {
                if let accepted = pendingImport { app.restore(accepted) }
                pendingImport = nil
                draft = nil
            }
            Button("Annuler", role: .cancel) { pendingImport = nil }
        } message: {
            Text(importMessage)
        }
    }

    // MARK: Sections

    /// « Cahier de textes » : création automatique des cours (désactivée par défaut).
    private var notebookCourses: some View {
        section("Cahier de textes", subtitle: "Chaque séance publiée dans Pronote peut devenir un cours, avec ses documents.") {
            Toggle(isOn: Binding(
                get: { app.document.settings.autoCreatesCourses },
                set: { enabled in
                    if enabled { Task { await importer.activate() } } else { importer.deactivate() }
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Créer les cours automatiquement").font(.subheadline.weight(.semibold))
                    Text(app.document.settings.autoCreatesCourses
                         ? "Après chaque synchronisation, les nouvelles séances de tes matières deviennent des cours. Un cours supprimé n’est jamais recréé."
                         : "Désactivé : utilise « Créer un cours » sur une séance du cahier de textes.")
                        .font(.footnote).foregroundStyle(Theme.textSecondary)
                }
            }
            .tint(Theme.violet)
            .frame(minHeight: 44)
            .disabled(importer.running)
            if importer.running {
                HStack(spacing: Spacing.xs) {
                    ProgressView()
                    Text("Création des cours depuis le cahier de textes…").font(.footnote).foregroundStyle(Theme.textSecondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private var algorithm: some View {
        let value = current
        return section("Algorithme adaptatif", subtitle: "Point pivot à 50 : règle l’espacement des séances et le seuil de rattrapage.") {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Sensibilité de l’espacement : \(SettingsLogic.jsNumber(value.sensitivity))").font(.subheadline.weight(.semibold))
                    Spacer(minLength: Spacing.xs)
                    Text(SettingsLogic.sensitivityLabel(value.sensitivity)).font(.caption.monospacedDigit()).foregroundStyle(Theme.textSecondary)
                }
                Slider(value: Binding(get: { value.sensitivity }, set: { newValue in edit { $0.sensitivity = SettingsLogic.snapSensitivity(newValue) } }),
                       in: SettingsLogic.sensitivityRange, step: SettingsLogic.sensitivityStep)
                    .tint(Theme.violet)
                    .accessibilityLabel("Sensibilité de l’espacement")
                    .accessibilityValue("\(SettingsLogic.jsNumber(value.sensitivity)), \(SettingsLogic.sensitivityLabel(value.sensitivity))")
                HStack {
                    Text("Doux ±10%")
                    Spacer()
                    Text("Équilibré ±40%").foregroundStyle(Theme.violetDark)
                    Spacer()
                    Text("Agressif ±80%")
                }
                .font(.caption2).foregroundStyle(Theme.textSecondary)
                .accessibilityHidden(true)
            }
            .panel()

            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(SettingsLogic.thresholdLabel(value.threshold)).font(.subheadline.weight(.semibold))
                Slider(value: Binding(get: { value.threshold }, set: { newValue in edit { $0.threshold = SettingsLogic.snapThreshold(newValue) } }),
                       in: SettingsLogic.thresholdRange, step: SettingsLogic.thresholdStep)
                    .tint(Theme.danger)
                    .accessibilityLabel("Seuil de rattrapage")
                    .accessibilityValue("Moins de \(SettingsLogic.jsNumber(value.threshold)) sur 100")
                Text("Toute note inférieure à ce seuil programme une séance de rattrapage le lendemain (J+1).")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            .panel()
        }
    }

    private var intervals: some View {
        let value = current
        let canRemove = value.intervals.count > SettingsLogic.removeRequiresMoreThan
        return section("Intervalles des J", subtitle: "Paliers de révision appliqués à chaque nouveau cours. Les cours existants gardent leur calendrier.") {
            FlowRow(spacing: Spacing.xs) {
                ForEach(value.intervals, id: \.self) { interval in
                    HStack(spacing: 0) {
                        Text("J\(interval)").font(.subheadline.weight(.semibold).monospacedDigit()).padding(.leading, Spacing.s)
                            .padding(.trailing, SettingsLogic.removeButtonShown(interval) ? 0 : Spacing.s)
                        if SettingsLogic.removeButtonShown(interval) {
                            Button { edit { $0.intervals = SettingsLogic.removeInterval($0.intervals, value: interval) } } label: {
                                Image(systemName: "xmark").font(.caption2.weight(.bold)).foregroundStyle(Theme.textMuted).frame(width: 36, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .disabled(!canRemove)
                            .accessibilityLabel("Retirer le palier J\(interval)")
                        }
                    }
                    .frame(minHeight: 44)
                    .background(Theme.background, in: Capsule())
                    .overlay(Capsule().strokeBorder(Theme.border))
                }
            }
            if !canRemove {
                Text("Il faut garder au moins deux paliers.").font(.caption).foregroundStyle(Theme.textSecondary)
            }
            HStack(spacing: Spacing.xs) {
                TextField("Nouveau J", text: Binding(get: { current.newInterval }, set: { text in edit { $0.newInterval = text } }), prompt: Text("ex. : 120"))
                    .keyboardType(.numberPad)
                    .inputField()
                Button("Ajouter") {
                    // Comme le web : le champ n'est vidé que si le palier est ajouté.
                    edit { draft in
                        let updated = SettingsLogic.addInterval(draft.intervals, value: draft.newInterval)
                        if updated != draft.intervals {
                            draft.intervals = updated
                            draft.newInterval = ""
                        }
                    }
                }
                .buttonStyle(SecondaryButtonStyle())
                .frame(maxWidth: 120)
            }
            Button("Rétablir les valeurs initiales") { edit { $0.intervals = SettingsLogic.resetIntervals() } }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.violetDark)
                .frame(minHeight: 44)
        }
    }

    private var colors: some View {
        section("Les couleurs de tes matières", subtitle: "Choisis les couleurs qui t’aident à te repérer. Une couleur s’applique quand tu appuies sur « Enregistrer » dans son sélecteur, sans toucher à tes autres réglages.") {
            if app.document.subjects.isEmpty {
                Text("Aucune matière.").font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            VStack(spacing: 0) {
                ForEach(app.document.subjects) { subject in
                    Button { colorFor = subject } label: {
                        HStack(spacing: Spacing.s) {
                            Circle().subjectFill(subject.color, border: .black.opacity(0.08)).frame(width: 24, height: 24)
                            Text(subject.name).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                            Spacer(minLength: Spacing.xs)
                            Text(subject.color.uppercased()).font(.caption.monospaced()).foregroundStyle(Theme.textMuted)
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textMuted)
                        }
                        .padding(.horizontal, Spacing.s)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(subject.name), couleur \(subject.color)")
                    .accessibilityHint("Ouvre le sélecteur de couleur")
                    if subject.id != app.document.subjects.last?.id { Divider().padding(.leading, 48) }
                }
            }
            .background(Theme.background, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
        }
    }

    private var backup: some View {
        section("Sauvegarde et export", subtitle: "Tes données sont enregistrées uniquement sur cet iPhone. Exporte une copie pour les garder ou les ouvrir dans J-Method sur le web.") {
            Text("\(app.document.courses.count) cours et \(app.document.revisions.count) séance\(app.document.revisions.count > 1 ? "s" : "") enregistré\(app.document.revisions.count > 1 ? "es" : "e").")
                .font(.footnote).foregroundStyle(Theme.textSecondary)
            Button {
                exporting = JSONExportDocument(text: app.exportText())
            } label: {
                Label("Exporter mes données (JSON)", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(PrimaryButtonStyle())
            Button { importing = true } label: {
                Label("Restaurer une sauvegarde", systemImage: "square.and.arrow.down")
            }
            .buttonStyle(SecondaryButtonStyle())
            Text("Une restauration remplace tes cours et tes séances ; une confirmation est demandée avant. Ton emploi du temps Pronote est conservé.")
                .font(.footnote).foregroundStyle(Theme.textSecondary)
        }
    }

    private var about: some View {
        section("À propos") {
            HStack(spacing: Spacing.s) {
                BrandMark(size: 40, showsName: false)
                VStack(alignment: .leading, spacing: 2) {
                    Text("J-Method").font(.cardTitle)
                    Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")")
                        .font(.footnote).foregroundStyle(Theme.textSecondary)
                }
            }
            .accessibilityElement(children: .combine)
            Text("J-Method lit Pronote en lecture seule : emploi du temps et cahier de textes. Rien n’est jamais modifié ni envoyé dans Pronote.")
                .font(.subheadline).foregroundStyle(Theme.textSecondary)
            Text("Tes cours, tes séances et tes réglages restent sur cet iPhone, sans compte ni serveur J-Method. Tes identifiants Pronote sont chiffrés dans le trousseau de l’iPhone, sans iCloud.")
                .font(.subheadline).foregroundStyle(Theme.textSecondary)
        }
    }

    private var unsavedBar: some View {
        HStack(spacing: Spacing.s) {
            Text("Modifications non enregistrées").font(.subheadline.weight(.semibold))
            Spacer(minLength: Spacing.xs)
            Button("Annuler") { draft = nil }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.violetDark)
                .frame(minHeight: 44)
            Button("Enregistrer") {
                let value = current
                app.saveSettings(sensitivity: value.sensitivity, emergencyThreshold: value.threshold, intervals: value.intervals)
                draft = nil
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, Spacing.m)
            .frame(minHeight: 44)
            .background(Theme.violet, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.xs)
        .background(.regularMaterial)
        .overlay(alignment: .top) { Divider() }
        .accessibilityElement(children: .contain)
    }

    private func section<Content: View>(_ title: String, subtitle: String? = nil, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader(title: title, subtitle: subtitle)
            content()
        }
        .surface()
    }

    // MARK: Restauration

    private var importMessage: String {
        guard let pending = pendingImport else { return "" }
        let courses = app.document.courses.count, revisions = app.document.revisions.count
        var text = "Tes \(courses) cours et \(revisions) séance\(revisions > 1 ? "s" : "") seront remplacés par ceux de la sauvegarde (\(pending.courses.count) cours, \(pending.revisions.count) séance\(pending.revisions.count > 1 ? "s" : ""))."
        if pending.subjects != nil { text += " Les matières seront aussi remplacées." }
        if pending.settings != nil { text += " Les réglages seront remplacés." }
        return text + " Ton emploi du temps Pronote est conservé. Cette action est définitive."
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else {
            app.showToast(Backup.errorTitle, Backup.invalidMessage, warning: true)
            return
        }
        // Lecture et analyse hors du fil principal : un gros fichier ne fige pas l'interface.
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Backup.ImportResult in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                guard size <= 20 * 1024 * 1024, let data = try? Data(contentsOf: url) else {
                    return .rejected(title: Backup.errorTitle, message: Backup.invalidMessage)
                }
                return Backup.parse(data)
            }.value
            switch result {
            case .accepted(let accepted): pendingImport = accepted
            case .rejected(let title, let message): app.showToast(title, message, warning: true)
            }
        }
    }
}

/// Fichier d'export (texte exact d'`exportAllData`).
struct JSONExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Compte Pronote, en tête des Réglages.
private struct PronoteAccountSection: View {
    let app: AppModel
    let pronote: PronoteSession
    @State private var confirmingDisconnect = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader(title: "Compte Pronote")
            progressPanel
            status
            if let children = pronote.account?.children, children.count > 1 {
                Picker("Élève suivi", selection: Binding(
                    get: { pronote.selectedChildId ?? children.first?.id ?? "" },
                    set: { pronote.selectChild($0) }
                )) {
                    ForEach(children) { Text($0.name).tag($0.id) }
                }
                .pickerStyle(.menu)
                .disabled(pronote.isWorking)
                .frame(minHeight: 44)
            }
            if let sync = app.lastPronoteSync {
                Text("Dernière mise à jour : \(sync.formatted(.dateTime.locale(Locale(identifier: "fr_FR")).weekday(.wide).day().month(.wide).hour().minute()))")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                Text("\(app.document.pronoteEvents.count) cours sur les 14 prochains jours")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            if let message = pronote.errorMessage, !pronote.isWorking, pronote.access != .connected {
                Label(message, systemImage: "exclamationmark.circle")
                    .font(.subheadline).foregroundStyle(Theme.danger)
                    .accessibilityLabel("Erreur : \(message)")
            }
            Button {
                if pronote.access == .connected { pronote.synchronize() } else { pronote.resumeSavedConnection() }
            } label: {
                HStack(spacing: 10) {
                    if pronote.access == .connected {
                        Label("Actualiser", systemImage: "arrow.clockwise")
                    } else {
                        if pronote.isWorking { ProgressView().tint(.white) }
                        Text(pronote.isWorking ? pronote.phase.progressLabel : "Réessayer la connexion")
                    }
                }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(pronote.isWorking)
            .accessibilityHint(pronote.access == .connected ? "Relit ton emploi du temps des 14 prochains jours" : "")
            Button("Se déconnecter", role: .destructive) { confirmingDisconnect = true }
                .buttonStyle(DestructiveTextButtonStyle())
        }
        .surface()
        .confirmationDialog("Se déconnecter de Pronote ?", isPresented: $confirmingDisconnect, titleVisibility: .visible) {
            Button("Se déconnecter", role: .destructive) { Task { await pronote.disconnect(forgetLogin: false) } }
            Button("Se déconnecter et oublier mes identifiants", role: .destructive) { Task { await pronote.disconnect(forgetLogin: true) } }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("« Se déconnecter » garde ton établissement, ton identifiant et ton mot de passe enregistrés sur cet iPhone pour te reconnecter plus vite. L’autre choix efface aussi ton identifiant et ton mot de passe. Dans les deux cas, ton emploi du temps importé et les documents du cahier de textes sont effacés ; tes cours et tes révisions restent enregistrés.")
        }
    }

    /// Connecté : synchronisation en cours (barre de progression) ou interrompue, comme Android.
    @ViewBuilder private var progressPanel: some View {
        if pronote.access == .connected, pronote.isWorking || pronote.errorMessage != nil {
            let failed = !pronote.isWorking
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(failed ? "Synchronisation interrompue" : "Synchronisation")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(failed ? Theme.danger : Theme.violetDark)
                Text(failed ? (pronote.errorMessage ?? "") : pronote.phase.progressLabel)
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !failed {
                    ProgressView()
                        .progressViewStyle(.linear)
                        .tint(Theme.violet)
                        .padding(.top, Spacing.xxs)
                        .accessibilityLabel("Synchronisation en cours")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .panel(padding: Spacing.m, fill: failed ? Theme.dangerSoft : Theme.violetSoft)
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder private var status: some View {
        if pronote.access == .connected {
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                Label("Connecté", systemImage: "checkmark.circle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.success)
                if let name = pronote.account?.name, !name.isEmpty { Text(name).font(.headline) }
            }
        } else {
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                Label("Hors ligne", systemImage: "wifi.slash").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.warning)
                Text("Pronote n’a pas pu être joint. Tes données enregistrées restent disponibles.")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
        }
    }
}
