import SwiftUI

/// Création d'un cours (`AddCourseModal.tsx`) : matière, chapitre (numéro attribué automatiquement), titre, date J0, notes.
/// Les révisions initiales sont générées à la création avec les intervalles des réglages.
struct AddCourseSheet: View {
    @Bindable var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var existingTargetId: String?
    @FocusState private var focus: Field?

    private enum Field { case chapter, title, notes }

    var body: some View {
        let draft = app.courseDraft
        let courses = app.document.courses
        let number = (courses.filter { $0.subjectId == draft.subjectId }.map(\.courseNumber).max() ?? 0) + 1
        let suggestions = CourseCreation.chapterSuggestions(subjectId: draft.subjectId, chapter: draft.chapter, courses: courses)
        let canSubmit = app.document.subjects.contains { $0.id == draft.subjectId } && (draft.reference?.count ?? 0) <= 24

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    Text("Enregistre ta feuille, puis démarre son programme depuis Mes cours.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)

                    if let notebook = app.notebookDraft {
                        notebookSection(notebook)
                        Picker("Destination des ressources", selection: $existingTargetId) {
                            Text("Nouvelle feuille").tag(String?.none)
                            ForEach(courses.filter { $0.subjectId == draft.subjectId }) { course in
                                Text("Associer à \(course.displayReference) · \(course.title)").tag(Optional(course.id))
                            }
                        }
                        if existingTargetId != nil { Text("La référence, les notes et les révisions de cette feuille seront conservées.").font(.footnote) }
                    }

                    section("Matière") {
                        if app.document.subjects.isEmpty {
                            Text("Aucune matière disponible.").font(.subheadline).foregroundStyle(Theme.danger)
                        }
                        FlowRow(spacing: Spacing.xs) {
                            ForEach(app.document.subjects) { subject in
                                FilterChip(title: subject.name, color: subject.color, selected: draft.subjectId == subject.id) {
                                    app.courseDraft.subjectId = subject.id
                                }
                            }
                        }
                    }

                    section("Référence personnelle") {
                        TextField("C\(number)", text: Binding(get: { app.courseDraft.reference ?? "" }, set: { app.courseDraft.reference = $0 }))
                            .textFieldStyle(.roundedBorder)
                        Text("Ton numéro de feuille, unique dans cette matière. Vide : attribution automatique.").font(.footnote)
                    }
                    section("Chapitre (facultatif)") {
                        HStack(alignment: .top, spacing: Spacing.s) {
                            TextField("Chapitre", text: $app.courseDraft.chapter, prompt: Text("ex : Arithmétique, Guerre froide…"))
                                .textInputAutocapitalization(.sentences)
                                .focused($focus, equals: .chapter)
                                .submitLabel(.next)
                                .onSubmit { focus = .title }
                                .accessibilityIdentifier("course-chapter")
                                .inputField()
                            VStack(spacing: 2) {
                                Text(draft.reference.flatMap { $0.isEmpty ? nil : $0 } ?? "C\(number)").lineLimit(1).font(.headline.monospacedDigit()).foregroundStyle(Theme.violetDark)
                                Text("Numéro").font(.caption2).foregroundStyle(Theme.textMuted)
                            }
                            .frame(minWidth: 64, minHeight: 48)
                            .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("Référence : \(draft.reference.flatMap { $0.isEmpty ? nil : $0 } ?? "C\(number)")")
                        }
                        if !suggestions.isEmpty {
                            Text("Chapitres existants :").font(.footnote).foregroundStyle(Theme.textSecondary)
                            FlowRow(spacing: Spacing.xs) {
                                ForEach(suggestions, id: \.self) { chapter in
                                    FilterChip(title: chapter, selected: false) { app.courseDraft.chapter = chapter }
                                        .accessibilityHint("Reprend ce chapitre")
                                }
                            }
                        }
                    }

                    section("Titre de la leçon (facultatif)") {
                        TextField("Titre de la leçon", text: $app.courseDraft.title, prompt: Text("ex : Algorithme d’Euclide et PGCD"))
                            .textInputAutocapitalization(.sentences)
                            .focused($focus, equals: .title)
                            .submitLabel(.next)
                            .onSubmit { focus = .notes }
                            .accessibilityIdentifier("course-title")
                            .inputField()
                    }

                    section("Date de la feuille") {
                        DatePicker("Date du cours", selection: dateBinding, displayedComponents: .date)
                            .datePickerStyle(.compact)
                            .environment(\.locale, Locale(identifier: "fr_FR"))
                            .environment(\.timeZone, SchoolDay.timeZone)
                            .environment(\.calendar, SchoolDay.parisCalendar)
                            .tint(Theme.violet)
                            .frame(minHeight: 44)
                        Text("Les J seront créés lorsque tu démarreras un programme.")
                            .font(.footnote).foregroundStyle(Theme.textSecondary)
                    }

                    section("Notes ou notions clés (facultatif)") {
                        TextField("Notes ou notions clés", text: $app.courseDraft.description, prompt: Text("ex : Définitions p. 45, exercices 12 et 14…"), axis: .vertical)
                            .lineLimit(2...6)
                            .focused($focus, equals: .notes)
                            .accessibilityIdentifier("course-notes")
                            .inputField()
                    }
                }
                .padding(.horizontal, Spacing.screen)
                .padding(.top, Spacing.xs)
                .padding(.bottom, Spacing.l)
            }
            .onChange(of: app.courseDraft.subjectId) { _, _ in existingTargetId = nil }
            .scrollDismissesKeyboard(.interactively)
            .background(Theme.surface)
            // Action principale toujours visible, au-dessus du clavier.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Button {
                        if let id = existingTargetId { if app.associateNotebook(to: id) { dismiss() } }
                        else if app.createCourse() { dismiss() }
                    } label: {
                        Label(existingTargetId == nil ? "Enregistrer la feuille" : "Associer les ressources", systemImage: "plus")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canSubmit)
                    if !canSubmit {
                        Text("Choisis une matière et une référence de 24 caractères maximum.")
                            .font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.horizontal, Spacing.screen)
                .padding(.vertical, Spacing.s)
                .frame(maxWidth: .infinity)
                .background(.bar)
                .overlay(alignment: .top) { Divider() }
            }
            .onDisappear { app.discardNotebookDraft() }
            .navigationTitle("Nouveau cours")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
        }
    }

    /// Séance du cahier de textes : documents joints cochés par défaut, fichiers non téléchargés signalés.
    private func notebookSection(_ notebook: NotebookCourseDraft) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Label("Depuis le cahier de textes : séance de \(notebook.source.subjectLabel) du \(FrenchDate.format(notebook.source.date, "d MMMM"))",
                  systemImage: "text.book.closed")
                .font(.footnote.weight(.semibold)).foregroundStyle(Theme.violetDark)
            if notebook.form.subjectId == nil {
                Label("Matière « \(notebook.source.subjectLabel) » non reconnue : choisis-la ci-dessous.", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(Theme.warning)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !notebook.documents.isEmpty {
                Text("Documents joints").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                    .accessibilityAddTraits(.isHeader)
                ForEach(notebook.documents) { item in
                    Toggle(isOn: Binding(get: { item.selected }, set: { app.setNotebookDocument(item.id, selected: $0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).font(.subheadline).foregroundStyle(Theme.text).lineLimit(2)
                            Text("\(item.kind.label) · \(ByteCountFormatter.string(fromByteCount: Int64(item.size), countStyle: .file))")
                                .font(.caption).foregroundStyle(Theme.textSecondary)
                            if let title = item.extractedTitle, !title.isEmpty {
                                Text("Titre lu : \(title)").font(.caption).foregroundStyle(Theme.textSecondary)
                            }
                        }
                    }
                    .tint(Theme.violet)
                    .frame(minHeight: 44)
                }
            }
            if !notebook.failedFiles.isEmpty {
                Label(notebook.failedFiles.count > 1
                      ? "\(notebook.failedFiles.count) documents n’ont pas pu être téléchargés : \(notebook.failedFiles.joined(separator: ", ")). Tu pourras les ouvrir depuis le cahier de textes."
                      : "Le document « \(notebook.failedFiles[0]) » n’a pas pu être téléchargé. Tu pourras l’ouvrir depuis le cahier de textes.",
                      systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(Theme.warning)
            }
            if notebook.skippedFiles > 0 {
                Text("Au-delà de 10 documents, les suivants restent consultables dans le cahier de textes.")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
        }
        .panel(padding: Spacing.m, fill: Theme.violetSoft)
    }

    private var dateBinding: Binding<Date> {
        Binding(
            get: { SchoolDay.displayDate(app.courseDraft.date) ?? SchoolDay.displayDate(app.today) ?? Date() },
            set: { app.courseDraft.date = SchoolDay.day(from: $0) }
        )
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }
}
