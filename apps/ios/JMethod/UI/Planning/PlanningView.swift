import SwiftUI

/// Onglet Planning (`PlanningView.tsx`) : jour, semaine (jours empilés), mois (calendrier puis séances du jour choisi),
/// filtres matière et étape, séances de révision et créneaux Pronote.
struct PlanningView: View {
    let app: AppModel
    @State private var mode: PlanningScreen.Mode = .week
    /// Date d'ancrage `yyyy-MM-dd` ; nil = aujourd'hui (suit le changement de jour).
    @State private var anchor: String?
    @State private var subjectId: String?
    @State private var phase: Phase?
    @Environment(\.dynamicTypeSize) private var typeSize

    private var date: String { anchor ?? app.today }

    var body: some View {
        let document = app.document
        let screen = PlanningScreen(mode: mode, date: date, subjectId: subjectId, phase: phase, today: app.today,
                                    courses: document.courses, revisions: document.revisions,
                                    subjects: document.subjects, pronoteEvents: document.pronoteEvents)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    let headerLayout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 8))
                    headerLayout {
                        Picker("Période affichée", selection: $mode) {
                            ForEach(PlanningScreen.Mode.allCases) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        Button("Aujourd’hui") { anchor = nil }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.violetDark)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .background(Theme.violetSoft, in: Capsule())
                            .accessibilityHint("Revient à la période d’aujourd’hui")
                    }

                    PeriodBar(period: screen.period.capitalizedFirst, onPrevious: { move(-1) }, onNext: { move(1) })

                    FiltersBar(subjects: document.subjects, subjectId: $subjectId, phase: $phase)

                    if document.courses.isEmpty {
                        NoCourseHint()
                    }

                    switch mode {
                    case .day, .week:
                        ForEach(screen.days) { day in
                            PlanningDaySection(day: day, timetableInitiallyExpanded: mode == .day, app: app)
                                .id("\(mode.rawValue)-\(day.date)")
                        }
                    case .month:
                        if typeSize.isAccessibilitySize {
                            // Très grandes tailles : jours du mois empilés plutôt qu'une grille illisible.
                            ForEach(screen.days.filter { $0.outsideMonth != true }) { day in
                                PlanningDaySection(day: day, timetableInitiallyExpanded: false, app: app)
                            }
                        } else {
                            MonthGrid(days: screen.days, selected: date) { anchor = $0 == app.today ? nil : $0 }
                            if let selected = PlanningScreen(mode: .day, date: date, subjectId: subjectId, phase: phase, today: app.today,
                                                             courses: document.courses, revisions: document.revisions,
                                                             subjects: document.subjects, pronoteEvents: document.pronoteEvents).days.first {
                                PlanningDaySection(day: selected, timetableInitiallyExpanded: true, app: app)
                                    .id(selected.date)
                            }
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.background)
            .navigationTitle("Planning")
            .navigationBarTitleDisplayMode(.inline)
        }
        #if DEBUG
        .onAppear {
            // Vérification visuelle (Debug) : `-JMethodPreviewPlanningMode day|week|month`.
            if let raw = UserDefaults.standard.string(forKey: "JMethodPreviewPlanningMode"), let preview = PlanningScreen.Mode(rawValue: raw) { mode = preview }
        }
        #endif
    }

    private func move(_ direction: Int) {
        let next = PlanningScreen.navigate(mode: mode, date: date, direction: direction)
        anchor = next == app.today ? nil : next
    }
}

private struct PeriodBar: View {
    let period: String
    let onPrevious: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onPrevious) {
                Image(systemName: "chevron.left").font(.body.weight(.semibold)).frame(width: 44, height: 44)
            }
            .foregroundStyle(Theme.violetDark)
            .accessibilityLabel("Période précédente")
            Text(period)
                .font(.headline)
                .foregroundStyle(Theme.text)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.85)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits([.isHeader, .updatesFrequently])
            Button(action: onNext) {
                Image(systemName: "chevron.right").font(.body.weight(.semibold)).frame(width: 44, height: 44)
            }
            .foregroundStyle(Theme.violetDark)
            .accessibilityLabel("Période suivante")
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border))
    }
}

private struct FiltersBar: View {
    let subjects: [Subject]
    @Binding var subjectId: String?
    @Binding var phase: Phase?

    private var subjectLabel: String { subjects.first { $0.id == subjectId }?.name ?? "Toutes" }
    private var phaseLabel: String { phase.map { Constants.phaseConfig($0).label } ?? "Toutes" }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { subjectMenu; phaseMenu }
            VStack(alignment: .leading, spacing: 8) { subjectMenu; phaseMenu }
        }
    }

    private var subjectMenu: some View {
        Menu {
            Picker("Matière", selection: $subjectId) {
                Text("Toutes les matières").tag(String?.none)
                ForEach(subjects) { Text($0.name).tag(Optional($0.id)) }
            }
        } label: {
            menuLabel(title: "Matière", value: subjectLabel, active: subjectId != nil)
        }
        .accessibilityLabel("Matière : \(subjectId == nil ? "toutes les matières" : subjectLabel)")
    }

    private var phaseMenu: some View {
        Menu {
            Picker("Étape", selection: $phase) {
                Text("Toutes les étapes").tag(Phase?.none)
                ForEach(Phase.allCases, id: \.self) { Text(Constants.phaseConfig($0).label).tag(Optional($0)) }
            }
        } label: {
            menuLabel(title: "Étape", value: phaseLabel, active: phase != nil)
        }
        .accessibilityLabel("Étape : \(phase == nil ? "toutes les étapes" : phaseLabel)")
    }

    private func menuLabel(title: String, value: String, active: Bool) -> some View {
        HStack(spacing: 6) {
            Text("\(title) :").foregroundStyle(Theme.textSecondary)
            Text(value).fontWeight(.semibold).foregroundStyle(active ? Theme.violetDeep : Theme.text).lineLimit(1)
            Image(systemName: "chevron.up.chevron.down").font(.caption).foregroundStyle(Theme.textMuted)
        }
        .font(.subheadline)
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(active ? Theme.violetTrack : Theme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(active ? Theme.violet : Theme.border))
    }
}

private struct NoCourseHint: View {
    var body: some View {
        Label {
            Text("Aucun cours pour l’instant : ajoute ton premier cours dans Mes cours, ou depuis un créneau de l’emploi du temps.")
        } icon: {
            Image(systemName: "lightbulb")
        }
        .font(.subheadline)
        .foregroundStyle(Theme.violetDeep)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

/// Un jour : en-tête, emploi du temps repliable (avec « Ajouter ce cours »), tuiles de séance.
struct PlanningDaySection: View {
    let day: PlanningScreen.Day
    let app: AppModel
    @State private var timetableExpanded: Bool

    init(day: PlanningScreen.Day, timetableInitiallyExpanded: Bool, app: AppModel) {
        self.day = day
        self.app = app
        _timetableExpanded = State(initialValue: timetableInitiallyExpanded)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(day.heading.capitalizedFirst)
                    .font(.headline)
                    .foregroundStyle(day.outsideMonth == true ? Theme.textMuted : Theme.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Text(day.countLabel)
                    .font(.caption.weight(day.isToday ? .bold : .regular))
                    .foregroundStyle(day.isToday ? Theme.violetDark : Theme.textSecondary)
            }

            if let summary = day.timetableSummary {
                DisclosureGroup(isExpanded: $timetableExpanded) {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(day.events) { item in
                            TimetableRow(item: item, subject: item.subjectId.flatMap(app.subject(id:))) {
                                app.openAddCourse(from: item.event)
                            }
                        }
                    }
                    .padding(.top, 8)
                } label: {
                    Label(summary, systemImage: "calendar")
                        .font(.subheadline)
                        .foregroundStyle(Theme.textSecondary)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .tint(Theme.violetDark)
            }

            if let empty = day.empty {
                Text(empty).font(.subheadline).foregroundStyle(Theme.textMuted)
            } else {
                ForEach(day.tiles) { tile in
                    PlanningTile(tile: tile) { app.presented = .revision(revisionId: tile.revision.id) }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(day.isToday ? Theme.violet : Theme.border, lineWidth: day.isToday ? 2 : 1)
        )
    }
}

private struct TimetableRow: View {
    let item: PlanningScreen.Event
    let subject: Subject?
    let onAdd: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                label
                Spacer(minLength: 8)
                if item.canAddCourse { AddCourseFromSlotButton(subjectName: item.event.subjectName, compact: true, action: onAdd) }
            }
            VStack(alignment: .leading, spacing: 8) {
                label
                if item.canAddCourse { AddCourseFromSlotButton(subjectName: item.event.subjectName, action: onAdd) }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var label: some View {
        HStack(spacing: 8) {
            SubjectDot(color: subject?.color ?? "#CBD5E1", size: 10)
            Text(item.label)
                .font(.subheadline)
                .foregroundStyle(item.event.isCancelled ? Theme.textMuted : Theme.text)
                .strikethrough(item.event.isCancelled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Tuile de séance : matière, Jn, étape · Cn, titre, chapitre, état.
private struct PlanningTile: View {
    let tile: PlanningScreen.Tile
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 0) {
                Rectangle().fill(Color(hex: tile.subject.color)).frame(width: 5).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(tile.subject.name).font(.subheadline.weight(.bold)).foregroundStyle(Theme.text)
                        Spacer(minLength: 8)
                        Text(tile.revision.stepLabel)
                            .font(.caption.weight(.bold))
                            .foregroundStyle(Theme.violetDark)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 6))
                    }
                    PhaseBadge(text: tile.phaseLine, phase: tile.revision.phase)
                    Text(tile.course.title).font(.body.weight(.bold)).foregroundStyle(Theme.text).multilineTextAlignment(.leading)
                    Text(tile.course.chapter).font(.caption).foregroundStyle(Theme.textSecondary)
                    if tile.done {
                        Label(tile.footer, systemImage: "checkmark.circle.fill")
                            .font(.caption.weight(.semibold)).foregroundStyle(Theme.success)
                    } else {
                        Text(tile.footer).font(.caption.weight(.semibold)).foregroundStyle(Theme.violetDark)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(tile.done ? Theme.background : Theme.surface)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border))
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tile.accessibilityLabel)
        .accessibilityHint("Ouvre la séance")
        .accessibilityAddTraits(.isButton)
    }
}

/// Calendrier du mois : 7 colonnes du lundi au dimanche ; toucher un jour affiche ses séances sous la grille.
private struct MonthGrid: View {
    let days: [PlanningScreen.Day]
    let selected: String
    let onSelect: (String) -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 7)
    private let initials = ["L", "M", "M", "J", "V", "S", "D"]

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 4) {
                ForEach(0..<7, id: \.self) { index in
                    Text(initials[index]).font(.caption.weight(.semibold)).foregroundStyle(Theme.textMuted).frame(maxWidth: .infinity)
                }
            }
            .accessibilityHidden(true)
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(days) { day in
                    MonthCell(day: day, selected: day.date == selected) { onSelect(day.date) }
                }
            }
        }
        .padding(10)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Theme.border))
    }
}

private struct MonthCell: View {
    let day: PlanningScreen.Day
    let selected: Bool
    let action: () -> Void

    private var number: String { String(Int(day.date.suffix(2)) ?? 0) }
    private var pending: Int { day.tiles.filter { !$0.done }.count }
    private var done: Int { day.tiles.filter(\.done).count }

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Text(number)
                    .font(.subheadline.weight(day.isToday || selected ? .bold : .regular))
                    .foregroundStyle(selected ? .white : day.outsideMonth == true ? Theme.textMuted : Theme.text)
                HStack(spacing: 2) {
                    // Pastilles : pleines = à faire, contour = terminées (l'information n'est pas portée que par la couleur).
                    ForEach(0..<min(pending, 3), id: \.self) { _ in
                        Circle().fill(selected ? Color.white : Theme.violet).frame(width: 5, height: 5)
                    }
                    ForEach(0..<min(done, max(0, 3 - min(pending, 3))), id: \.self) { _ in
                        Circle().strokeBorder(selected ? Color.white : Theme.success, lineWidth: 1).frame(width: 5, height: 5)
                    }
                }
                .frame(height: 5)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(selected ? Theme.violet : day.isToday ? Theme.violetTrack : Color.clear,
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .opacity(day.outsideMonth == true && !selected ? 0.6 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(FrenchDate.format(day.date, "EEEE d MMMM")), \(day.countLabel)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
