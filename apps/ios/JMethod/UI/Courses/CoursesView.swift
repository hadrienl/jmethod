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
                        VStack(spacing: Spacing.m) {
                            EmptyCourses(app: app, onShowPlanning: onShowPlanning)
                            NotebookCard { app.presented = .notebook(subjectId: nil) }
                        }
                        .screenContent()
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: Spacing.m) {
                            SubjectChips(subjects: document.subjects, selection: $subjectId)
                            NotebookCard { app.presented = .notebook(subjectId: nil) }
                            if list.empty {
                                NoMatch { search = ""; subjectId = nil }
                            } else {
                                ForEach(list.groups) { group in
                                    SubjectCoursesCard(group: group) { app.presented = .course(courseId: $0.id) }
                                }
                            }
                        }
                        .screenContent()
                    }
                    .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Cours, chapitre, C1, C14…")
                }
            }
            .background(Theme.background)
            .navigationTitle(list.header)
            .navigationBarTitleDisplayMode(.large)
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
            HStack(spacing: Spacing.s) {
                IconTile(systemName: "text.book.closed")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cahier de textes").font(.cardTitle).foregroundStyle(Theme.text)
                    Text("Contenus de séance et devoirs publiés dans Pronote").font(.footnote).foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: Spacing.xs)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textMuted).accessibilityHidden(true)
            }
            .surface(padding: Spacing.s)
            .contentShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
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
        VStack(alignment: .leading, spacing: Spacing.m) {
            VStack(alignment: .leading, spacing: Spacing.s) {
                IconTile(systemName: "books.vertical", size: 56)
                Text("Ajoute ton premier cours").font(.title2.weight(.bold)).accessibilityAddTraits(.isHeader)
                Text("Après chaque cours, note son chapitre et son titre : J-Method programme aussitôt tes séances Découvrir, S’entraîner et Se tester.")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                Button { app.openAddCourse() } label: {
                    Label("Créer mon premier cours", systemImage: "plus")
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .surface()

            VStack(alignment: .leading, spacing: Spacing.s) {
                Text("Depuis ton emploi du temps").font(.cardTitle).accessibilityAddTraits(.isHeader)
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
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(spacing: Spacing.xs) {
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
        .panel()
    }
}

private struct SubjectChips: View {
    let subjects: [Subject]
    @Binding var selection: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.xs) {
                FilterChip(title: "Toutes", selected: selection == nil) { selection = nil }
                ForEach(subjects) { subject in
                    FilterChip(title: subject.name, color: subject.color, selected: selection == subject.id) { selection = subject.id }
                }
            }
            .padding(.vertical, 2)
        }
        .scrollClipDisabled()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Filtrer par matière")
    }
}

private struct NoMatch: View {
    let onReset: () -> Void

    var body: some View {
        EmptyState(systemImage: "magnifyingglass", title: "Aucun cours trouvé", message: "Essaie de modifier ta recherche ou tes filtres.") {
            Button("Effacer la recherche et les filtres", action: onReset).buttonStyle(SecondaryButtonStyle())
        }
    }
}

private struct SubjectCoursesCard: View {
    let group: CoursesList.Group
    let onOpen: (Course) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xxs))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Spacing.xs))
            layout {
                HStack(spacing: Spacing.xs) {
                    SubjectDot(color: group.subject.color, size: 12)
                    Text(group.subject.name).font(.sectionTitle).foregroundStyle(Theme.text)
                }
                Text(group.countLabel).font(.subheadline).foregroundStyle(Theme.textMuted)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            ForEach(group.chapters) { chapter in
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    (Text("Chapitre : ").foregroundColor(Theme.textMuted) + Text(chapter.chapter).foregroundColor(Theme.textSecondary).bold())
                        .font(.footnote)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(chapter.cards) { card in
                        CourseCard(card: card) { onOpen(card.course) }
                    }
                }
            }
        }
        .surface()
    }
}

private struct CourseCard: View {
    let card: CoursesList.Card
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                    Tag(text: card.badge)
                    Text(card.course.title).font(.body.weight(.semibold)).foregroundStyle(Theme.text).multilineTextAlignment(.leading)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textMuted)
                }
                FlowRow(spacing: Spacing.s) {
                    Label(card.dateLine, systemImage: "calendar")
                    if let score = card.lastScoreLine {
                        Text(score).foregroundStyle(Theme.success).fontWeight(.semibold)
                    }
                }
                .font(.footnote)
                .foregroundStyle(Theme.textSecondary)
                HStack(spacing: Spacing.s) {
                    ProgressBar(percent: card.progress, height: 6)
                    Text(card.counter).font(.footnote.weight(.semibold).monospacedDigit()).foregroundStyle(Theme.text).fixedSize()
                }
                if let next = card.next {
                    HStack {
                        Label(next.label, systemImage: "clock").foregroundStyle(Theme.textSecondary)
                        Spacer(minLength: Spacing.xs)
                        Text(next.date).foregroundStyle(Theme.textMuted)
                    }
                    .font(.footnote)
                }
            }
            .panel()
            .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
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
