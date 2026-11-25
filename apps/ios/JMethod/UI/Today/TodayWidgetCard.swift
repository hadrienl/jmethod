import SwiftUI

struct TodayWidgetCard: View {
    let state: TodayWidgetState
    let document: AppDocument
    let offline: Bool
    let refreshing: Bool
    let onStart: (String) -> Void
    let onReview: (String) -> Void
    let onShowCourses: () -> Void
    let onPrepare: () -> Void
    let onShowProgramme: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    private var title: String {
        switch state.mode {
        case .rest: "Soirée tranquille."
        case .ready: "Un petit tour de révision ?"
        case .focus: "Une séance à la fois."
        case .progress: "Tu avances !"
        case .done: "C’est fait pour ce soir !"
        case .returned: "Content de te retrouver !"
        case .resume: "On reprend tranquillement ?"
        case .plan: "Reprenons une séance à la fois."
        case .planActive: state.completed > 0 ? "Tu as repris le rythme." : "Ton programme de reprise"
        case .planDone: "Ton programme de ce soir est terminé !"
        case .newWork: "Nouvelles séances disponibles"
        }
    }

    private func sessions(_ count: Int) -> String { "\(count) séance\(count > 1 ? "s" : "")" }

    private var message: String {
        switch state.mode {
        case .rest: "Aucun J prévu ce soir."
        case .returned where state.remaining == 0: "Aucun J prévu ce soir."
        case .done: "Toutes tes révisions sont terminées."
        case .resume: "\(sessions(state.overdue)) à reprendre."
        case .plan: "Tu as \(sessions(state.overdue)) en attente. Choisis ton programme de ce soir."
        case .progress, .planActive: "\(sessions(state.completed)) sur \(state.completed + state.remaining) terminées."
        case .planDone: "\(sessions(state.outsidePlan)) à reprendre hors de ta sélection."
        default: "\(sessions(state.remaining)) à faire ce soir."
        }
    }

    private var action: (String, () -> Void) {
        switch state.mode {
        case .plan: return ("Préparer ma reprise", onPrepare)
        case .planDone: return ("Voir la suite", onPrepare)
        case .done: return ("Voir mon programme", onShowProgramme)
        default:
            guard let id = state.next?.id else { return ("Voir mes cours", onShowCourses) }
            let title = switch state.mode {
            case .resume: "Reprendre"
            case .progress, .planActive: "Continuer"
            default: "Commencer"
            }
            return (title, { onStart(id) })
        }
    }

    var body: some View {
        let waitingForData = refreshing && state.remaining == 0 && (state.mode == .rest || state.mode == .returned)
        VStack(alignment: .leading, spacing: Spacing.m) {
            if offline || refreshing {
                Text(refreshing ? "Actualisation de ton programme…" : "Hors ligne · Programme enregistré sur cet appareil")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            HStack(alignment: .center, spacing: Spacing.m) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(waitingForData ? "Ton programme du soir" : title)
                        .font(.title2.bold()).foregroundStyle(Theme.violetDeep).accessibilityAddTraits(.isHeader)
                    if !waitingForData { Text(message).foregroundStyle(Theme.textSecondary) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !typeSize.isAccessibilitySize {
                    ViewThatFits(in: .horizontal) {
                        illustration.frame(width: 112, height: 112)
                        illustration.frame(width: 72, height: 72)
                    }
                }
            }
            if state.removedFromPlan > 0 {
                Text("Séances retirées de ta sélection car elles ne sont plus disponibles : \(state.removedFromPlan).")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            if state.completed > 0, state.remaining > 0, state.mode == .progress || state.mode == .planActive {
                ProgressView(value: Double(state.completed), total: Double(state.completed + state.remaining))
                    .tint(Theme.violet)
            }
            if let next = state.next, let course = document.courses.first(where: { $0.id == next.courseId }) {
                Text(widgetRevisionLabel(next, course: course, document: document)).font(.subheadline.weight(.semibold))
                Text(course.title).font(.callout)
            }
            Button(action.0, action: action.1).buttonStyle(.borderedProminent).tint(Theme.violet)
            if let next = state.next, let course = document.courses.first(where: { $0.id == next.courseId }),
               RevisionSession.hasDocuments(course) {
                Button("Revoir le cours") { onReview(next.id) }.buttonStyle(.bordered)
            }
            if state.mode == .planActive { Button("Modifier ma reprise", action: onPrepare) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .panel(padding: Spacing.l, fill: Theme.violetTrack)
    }

    private var illustration: some View {
        Image("widget_\(state.mode.art)").resizable().scaledToFit().accessibilityHidden(true)
    }
}

struct TodayResumeSheet: View {
    let programme: EveningProgramme
    let document: AppDocument
    let onSave: ([String]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected: [String]

    init(programme: EveningProgramme, document: AppDocument, onSave: @escaping ([String]) -> Void) {
        self.programme = programme
        self.document = document
        self.onSave = onSave
        let available = Set(programme.due.map(\.id))
        let existing = (document.todayWidgetVisit?.selection ?? []).filter(available.contains)
        _selected = State(initialValue: existing.isEmpty ? TodayWidgetState.suggested(programme) : existing)
    }

    var body: some View {
        let overdue = Set(programme.overdue.map(\.id))
        let available = Set(programme.due.map(\.id))
        let checked = selected.filter(available.contains)
        NavigationStack {
            List {
                Text("Choisis les séances à faire. Les autres resteront au programme.")
                Section("À reprendre") { rows(programme.overdue) }
                Section("Prévues ce soir") { rows(programme.due.filter { !overdue.contains($0.id) }) }
            }
            .navigationTitle("Préparer ma reprise")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer (\(checked.count))") { onSave(checked); dismiss() }.disabled(checked.isEmpty)
                }
            }
        }
    }

    @ViewBuilder private func rows(_ revisions: [Revision]) -> some View {
        ForEach(revisions, id: \.id) { revision in
            if let course = document.courses.first(where: { $0.id == revision.courseId }) {
                Toggle(isOn: Binding(
                    get: { selected.contains(revision.id) },
                    set: { enabled in
                        if enabled { selected.append(revision.id) }
                        else { selected.removeAll { $0 == revision.id } }
                    }
                )) {
                    VStack(alignment: .leading) {
                        Text(course.title)
                        Text(widgetRevisionLabel(revision, course: course, document: document)).font(.footnote)
                    }
                }
            }
        }
    }
}

private func widgetRevisionLabel(_ revision: Revision, course: Course, document: AppDocument) -> String {
    let subject = document.subjects.first(where: { $0.id == course.subjectId })?.name
    return [subject, revision.stepLabel, RevisionProgrammes.references(revision, courses: document.courses)]
        .compactMap { $0 }.joined(separator: " · ")
}
