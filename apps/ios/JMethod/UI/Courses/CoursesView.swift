import SwiftUI

/// Onglet Mes cours (`CoursesView.tsx`) : cours classés par matière puis chapitre, recherche, filtre par matière.
struct CoursesView: View {
    let app: AppModel
    let onShowPlanning: () -> Void
    @State private var search = ""
    @State private var subjectId: String?

    var body: some View {
        let document = app.document
        let list = CoursesList(courses: document.courses, revisions: document.revisions, subjects: document.subjects,
                               search: search, subjectId: subjectId)
        NavigationStack {
            Group {
                if document.courses.isEmpty {
                    ScrollView {
                        VStack(spacing: 16) {
                            NotebookCard { app.presented = .notebook(subjectId: nil) }
                            EmptyCourses(app: app, onShowPlanning: onShowPlanning)
                        }
                            .padding(16)
                            .frame(maxWidth: 720)
                            .frame(maxWidth: .infinity)
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            NotebookCard { app.presented = .notebook(subjectId: nil) }
                            SubjectChips(subjects: document.subjects, selection: $subjectId)
                            if list.empty {
                                NoMatch { search = ""; subjectId = nil }
                            } else {
                                ForEach(list.groups) { group in
                                    SubjectCoursesCard(group: group) { app.presented = .course(courseId: $0.id) }
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .frame(maxWidth: 720)
                        .frame(maxWidth: .infinity)
                    }
                    .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Cours, chapitre, C1, C14…")
                }
            }
            .background(Theme.background)
            .navigationTitle(list.header)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { app.openAddCourse() } label: {
                        Label("Nouveau cours", systemImage: "plus")
                    }
                    .accessibilityLabel("Nouveau cours")
                }
            }
        }
    }
}

/// Accès au cahier de textes Pronote (lecture seule).
private struct NotebookCard: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: "text.book.closed")
                    .font(.title3)
                    .foregroundStyle(Theme.violet)
                    .frame(width: 44, height: 44)
                    .background(Theme.violetTrack, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cahier de textes").font(.headline).foregroundStyle(Theme.text)
                    Text("Contenus de séance et devoirs publiés dans Pronote").font(.caption).foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textMuted).accessibilityHidden(true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.border))
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Ouvre le cahier de textes en lecture seule")
    }
}

/// Premier lancement : aucun cours. Invite à créer le premier ou à partir d'un créneau de l'emploi du temps.
private struct EmptyCourses: View {
    let app: AppModel
    let onShowPlanning: () -> Void

    private var upcoming: [PronoteEvent] {
        Array(app.document.pronoteEvents.filter { $0.date >= app.today && !$0.isCancelled }.prefix(6))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: "books.vertical")
                    .font(.system(size: 34, weight: .regular))
                    .foregroundStyle(Theme.violet)
                    .frame(width: 64, height: 64)
                    .background(Theme.violetTrack, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .accessibilityHidden(true)
                Text("Ajoute ton premier cours").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                Text("Après chaque cours, note son chapitre et son titre : J-Method programme aussitôt tes séances Découvrir, S’entraîner et Se tester.")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                Button { app.openAddCourse() } label: {
                    Label("Créer mon premier cours", systemImage: "plus")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .surface()

            VStack(alignment: .leading, spacing: 12) {
                Text("Depuis ton emploi du temps").font(.headline).accessibilityAddTraits(.isHeader)
                if upcoming.isEmpty {
                    Text("Aucun créneau Pronote à venir pour l’instant. Mets à jour l’emploi du temps dans Réglages.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                } else {
                    Text("Touche « Ajouter ce cours » : la matière, la date et le titre sont déjà remplis.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    ForEach(upcoming) { event in
                        UpcomingSlot(event: event, subject: event.matchedSubjectId.flatMap(app.subject(id:))) {
                            app.openAddCourse(from: event)
                        }
                    }
                }
                Button("Voir le planning", action: onShowPlanning).buttonStyle(SecondaryButtonStyle())
            }
            .surface()
        }
    }
}

private struct UpcomingSlot: View {
    let event: PronoteEvent
    let subject: Subject?
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                SubjectDot(color: subject?.color ?? "#CBD5E1", size: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(event.subjectName).font(.subheadline.weight(.bold))
                    Text("\(FrenchDate.format(event.date, "EEEE d MMMM").capitalizedFirst) · \(event.startTime)")
                        .font(.caption).foregroundStyle(Theme.textSecondary)
                }
            }
            .accessibilityElement(children: .combine)
            AddCourseFromSlotButton(subjectName: event.subjectName, action: onAdd)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct SubjectChips: View {
    let subjects: [Subject]
    @Binding var selection: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(title: "Toutes", selected: selection == nil) { selection = nil }
                ForEach(subjects) { subject in
                    FilterChip(title: subject.name, color: subject.color, selected: selection == subject.id) { selection = subject.id }
                }
            }
            .padding(.vertical, 2)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Filtrer par matière")
    }
}

private struct NoMatch: View {
    let onReset: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.largeTitle).foregroundStyle(Theme.textMuted).accessibilityHidden(true)
            Text("Aucun cours trouvé").font(.headline)
            Text("Essaie de modifier ta recherche ou tes filtres.").font(.subheadline).foregroundStyle(Theme.textSecondary)
            Button("Effacer la recherche et les filtres", action: onReset).buttonStyle(SecondaryButtonStyle())
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .surface()
    }
}

private struct SubjectCoursesCard: View {
    let group: CoursesList.Group
    let onOpen: (Course) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4)) : AnyLayout(HStackLayout(spacing: 10))
            layout {
                HStack(spacing: 10) {
                    SubjectDot(color: group.subject.color, size: 14)
                    Text(group.subject.name).font(.title3.weight(.heavy))
                }
                Text(group.countLabel).font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            ForEach(group.chapters) { chapter in
                VStack(alignment: .leading, spacing: 8) {
                    (Text("Chapitre : ").foregroundColor(Theme.textSecondary) + Text(chapter.chapter).bold())
                        .font(.subheadline)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(chapter.cards) { card in
                        CourseCard(card: card) { onOpen(card.course) }
                    }
                }
            }
        }
        .surface(padding: 14)
    }
}

private struct CourseCard: View {
    let card: CoursesList.Card
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(card.badge)
                        .font(.caption.weight(.bold).monospacedDigit())
                        .foregroundStyle(Theme.violetDark)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 6))
                    Spacer(minLength: 8)
                    if let score = card.lastScoreLine {
                        Text(score).font(.caption.weight(.semibold)).foregroundStyle(Theme.success)
                    }
                }
                Text(card.course.title).font(.body.weight(.bold)).foregroundStyle(Theme.text).multilineTextAlignment(.leading)
                Label(card.dateLine, systemImage: "calendar").font(.caption).foregroundStyle(Theme.textSecondary)
                HStack {
                    Text("Progression").font(.caption).foregroundStyle(Theme.textSecondary)
                    Spacer()
                    Text(card.counter).font(.caption.weight(.bold).monospacedDigit()).foregroundStyle(Theme.text)
                }
                ProgressBar(percent: card.progress)
                if let next = card.next {
                    HStack {
                        Label(next.label, systemImage: "clock").font(.caption).foregroundStyle(Theme.textSecondary)
                        Spacer()
                        Text(next.date).font(.caption).foregroundStyle(Theme.textMuted)
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border))
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint("Ouvre la fiche du cours")
        .accessibilityAddTraits(.isButton)
    }

    private var accessibilityText: String {
        var parts = ["\(card.badge) \(card.course.title)", card.dateLine, "\(card.progress) % des séances faites"]
        if let score = card.lastScoreLine { parts.append(score) }
        if let next = card.next { parts.append("\(next.label) le \(next.date)") }
        return parts.joined(separator: ", ")
    }
}
