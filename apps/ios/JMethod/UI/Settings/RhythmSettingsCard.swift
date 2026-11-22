import SwiftUI

struct RhythmSettingsCard: View {
    let app: AppModel
    @State private var steps: [RhythmStep] = []
    @State private var adaptive = true
    @State private var day = ""
    @State private var message: String?
    @State private var showingPreview = false
    private var base: RevisionRhythm { app.document.settings.rhythm ?? .standard }
    private var rhythm: RevisionRhythm {
        var result = base
        result.steps = steps
        if steps != base.steps { result.revision += 1 }
        return result
    }
    private var valid: Bool { (try? RevisionProgrammes.validate(rhythm)) != nil }
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text("Rythme de révision").font(.headline)
            Text("Les jours sont comptés depuis J0. Chaque programme conserve son rythme et son mode.").font(.subheadline)
            ForEach(steps) { step in
                HStack {
                    Text("J\(step.offsetDays)")
                    Spacer()
                    Picker("Phase de J\(step.offsetDays)", selection: Binding(get: { step.phase }, set: { phase in
                        if let index = steps.firstIndex(where: { $0.id == step.id }) { steps[index].phase = phase }
                    })) {
                        ForEach(Phase.allCases, id: \.self) { Text($0.rhythmLabel).tag($0) }
                    }.labelsHidden()
                    Button("Retirer") { steps.removeAll { $0.id == step.id } }.disabled(step.offsetDays == 0)
                }
            }
            HStack {
                TextField("Nouveau jour J", text: $day).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                Button("Ajouter") {
                    guard let value = Int(day), (1...3650).contains(value), !steps.contains(where: { $0.offsetDays == value }) else {
                        message = "Saisis un jour unique entre 1 et 3650."; return
                    }
                    steps.append(RhythmStep(id: "day-\(value)", offsetDays: value, phase: value <= 3 ? .encodage : value <= 25 ? .consolidation : .recuperation))
                    steps.sort { $0.offsetDays < $1.offsetDays }; day = ""; message = nil
                }
            }
            Toggle("Adapter les dates aux scores des nouveaux programmes", isOn: $adaptive)
            if let message { Text(message).foregroundStyle(Theme.danger) }
            Button("Rétablir J0 à J100") { steps = RevisionRhythm.standard.steps }
            Button("Enregistrer pour les nouveaux programmes") { app.saveRhythm(rhythm, adaptive: adaptive, applyToOngoing: false) }.disabled(!valid)
            Button("Appliquer aux programmes en cours…") { showingPreview = true }
                .disabled(!valid || !app.document.revisions.contains { $0.status != .completed })
        }
        .surface()
        .onAppear(perform: reset)
        .onChange(of: app.document.settings) { _, _ in reset() }
        .sheet(isPresented: $showingPreview) { preview }
    }
    private func reset() { steps = base.steps; adaptive = app.document.settings.adaptiveEnabled }
    private var preview: some View {
        let document = app.document
        let result = try? RevisionProgrammes.reapply(document, programmeIds: Set(document.programmes.map(\.id)), rhythm: rhythm)
        return NavigationStack {
            List {
                Text("Les séances terminées et les rattrapages restent inchangés. Les dates sont recalculées depuis le J0 du programme ; une date passée devient une révision en retard. Chaque programme garde son mode fixe ou adaptatif.")
                ForEach(document.programmes.filter { p in document.revisions.contains { $0.programmeId == p.id && $0.status != .completed } }) { programme in
                    let old = document.revisions.filter { $0.programmeId == programme.id && $0.status != .completed && $0.isEmergencySession != true }
                    let updated = result?.revisions.filter { $0.programmeId == programme.id && $0.status != .completed && $0.isEmergencySession != true } ?? []
                    Section(document.courses.filter { programme.courseIds.contains($0.id) }.map(\.displayReference).joined(separator: " + ")) {
                        ForEach(old.filter { r in !updated.contains { $0.nominalOffsetDays == r.nominalOffsetDays } }) { revision in
                            Text("\(revision.stepLabel) · \(revision.scheduledDate) → retirée")
                        }
                        ForEach(updated) { revision in
                            let previous = old.first { $0.nominalOffsetDays == revision.nominalOffsetDays }?.scheduledDate ?? "nouvelle"
                            Text("\(revision.stepLabel) · \(previous) → \(revision.scheduledDate) · \(revision.phase.rhythmLabel)")
                        }
                    }
                }
            }
            .navigationTitle("Vérifier les dates")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { showingPreview = false } }
                ToolbarItem(placement: .confirmationAction) { Button("Appliquer") {
                    app.saveRhythm(rhythm, adaptive: adaptive, applyToOngoing: true); showingPreview = false
                }.disabled(result == nil) }
            }
        }
    }
}
