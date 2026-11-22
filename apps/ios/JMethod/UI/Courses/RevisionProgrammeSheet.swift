import SwiftUI

struct RevisionProgrammeSheet: View {
    let app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selected = Set<String>()
    @State private var date: Date = SchoolDay.displayDate( SchoolDay.adding(1, to: SchoolDay.today()) ?? "") ?? Date()
    private var subjectId: String? { app.document.courses.first { selected.contains($0.id) }?.subjectId }
    private var rhythm: RevisionRhythm { app.document.settings.rhythm ?? .standard }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Sélectionne des feuilles de la même matière. Chaque J portera sur cette sélection ; une feuille ajoutée plus tard ne rejoindra pas ce programme.")
                    ForEach(app.document.courses) { course in
                        let pending = app.document.revisions.contains { $0.applies(to: course.id) && $0.status != .completed }
                        Toggle(isOn: Binding(get: { selected.contains(course.id) }, set: { checked in
                            if checked { selected.insert(course.id) } else { selected.remove(course.id) }
                        })) {
                            VStack(alignment: .leading) {
                                Text("\(course.displayReference) · \(course.title)")
                                Text(app.subject(id: course.subjectId)?.name ?? "").font(.caption)
                                if pending { Text("Programme déjà en cours").font(.caption) }
                            }
                        }.disabled(pending || (subjectId != nil && subjectId != course.subjectId))
                    }
                } header: { Text("Mes feuilles") }
                Section("Premier jour") { DatePicker("Date de J0", selection: $date, displayedComponents: .date).environment(\.timeZone, SchoolDay.timeZone).environment(\.calendar, SchoolDay.parisCalendar) }
                Section(rhythm.name) {
                    Text(app.document.settings.adaptiveEnabled ? "Adaptation aux scores activée" : "Dates fixes")
                    ForEach(rhythm.steps) { step in
                        LabeledContent("J\(step.offsetDays) · \(step.phase.rhythmLabel)", value: SchoolDay.adding(step.offsetDays, to: SchoolDay.today(now: date)) ?? "—")
                    }
                }
                Button("Créer les J") {
                    if app.startProgramme(courseIds: app.document.courses.filter { selected.contains($0.id) }.map(\.id), startDate: SchoolDay.today(now: date)) { dismiss() }
                }.disabled(selected.isEmpty)
            }
            .navigationTitle("Démarrer un programme")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } } }
        }
    }
}

extension Phase {
    var rhythmLabel: String {
        switch self { case .encodage: "Encodage"; case .consolidation: "Consolidation"; case .recuperation: "Récupération" }
    }
}
