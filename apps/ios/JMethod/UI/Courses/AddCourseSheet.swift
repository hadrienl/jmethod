import SwiftUI

/// Création d'un cours (`AddCourseModal.tsx`) : matière, chapitre (numéro attribué automatiquement), titre, date J0, notes.
/// Les révisions initiales sont générées à la création avec les intervalles des réglages.
struct AddCourseSheet: View {
    @Bindable var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focus: Field?

    private enum Field { case chapter, title, notes }

    var body: some View {
        let draft = app.courseDraft
        let courses = app.document.courses
        let number = CourseCreation.courseNumber(subjectId: draft.subjectId, chapter: draft.chapter, courses: courses)
        let suggestions = CourseCreation.chapterSuggestions(subjectId: draft.subjectId, chapter: draft.chapter, courses: courses)
        let canSubmit = CourseCreation.canSubmit(draft)
        let milestones = app.document.settings.intervals.count

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("J-Method programme automatiquement \(milestones > 1 ? "les \(milestones) échéances" : "l’échéance") de révision de ce cours.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)

                    section("Matière") {
                        if app.document.subjects.isEmpty {
                            Text("Aucune matière disponible.").font(.subheadline).foregroundStyle(Theme.danger)
                        }
                        FlowRow(spacing: 8) {
                            ForEach(app.document.subjects) { subject in
                                FilterChip(title: subject.name, color: subject.color, selected: draft.subjectId == subject.id) {
                                    app.courseDraft.subjectId = subject.id
                                }
                            }
                        }
                    }

                    section("Chapitre") {
                        HStack(alignment: .top, spacing: 12) {
                            TextField("Chapitre", text: $app.courseDraft.chapter, prompt: Text("ex : Arithmétique, Guerre froide…"))
                                .textInputAutocapitalization(.sentences)
                                .focused($focus, equals: .chapter)
                                .submitLabel(.next)
                                .onSubmit { focus = .title }
                                .fieldStyle()
                            VStack(spacing: 2) {
                                Text("C\(number)").font(.headline.monospacedDigit()).foregroundStyle(Theme.violetDark)
                                Text("Numéro").font(.caption2).foregroundStyle(Theme.textMuted)
                            }
                            .frame(minWidth: 64, minHeight: 48)
                            .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("Numéro attribué automatiquement : C\(number)")
                        }
                        if !suggestions.isEmpty {
                            Text("Chapitres existants :").font(.caption).foregroundStyle(Theme.textSecondary)
                            FlowRow(spacing: 8) {
                                ForEach(suggestions, id: \.self) { chapter in
                                    FilterChip(title: chapter, selected: false) { app.courseDraft.chapter = chapter }
                                        .accessibilityHint("Reprend ce chapitre")
                                }
                            }
                        }
                    }

                    section("Titre de la leçon") {
                        TextField("Titre de la leçon", text: $app.courseDraft.title, prompt: Text("ex : Algorithme d’Euclide et PGCD"))
                            .textInputAutocapitalization(.sentences)
                            .focused($focus, equals: .title)
                            .submitLabel(.next)
                            .onSubmit { focus = .notes }
                            .fieldStyle()
                    }

                    section("Date du cours (J0)") {
                        DatePicker("Date du cours", selection: dateBinding, displayedComponents: .date)
                            .datePickerStyle(.compact)
                            .environment(\.locale, Locale(identifier: "fr_FR"))
                            .environment(\.timeZone, SchoolDay.timeZone)
                            .environment(\.calendar, SchoolDay.parisCalendar)
                            .tint(Theme.violet)
                            .frame(minHeight: 44)
                        Text("La première séance (Découvrir, J0) est programmée ce jour-là.")
                            .font(.caption).foregroundStyle(Theme.textSecondary)
                    }

                    section("Notes ou notions clés (facultatif)") {
                        TextField("Notes ou notions clés", text: $app.courseDraft.description, prompt: Text("ex : Définitions p. 45, exercices 12 et 14…"), axis: .vertical)
                            .lineLimit(2...6)
                            .focused($focus, equals: .notes)
                            .fieldStyle()
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Button {
                            if app.createCourse() { dismiss() }
                        } label: {
                            Label("Créer le cours C\(number)", systemImage: "plus")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!canSubmit)
                        if !canSubmit {
                            Text("Indique une matière, un chapitre et un titre pour créer le cours.")
                                .font(.caption).foregroundStyle(Theme.textSecondary)
                        }
                    }
                }
                .padding(20)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Theme.surface)
            .navigationTitle("Nouveau cours")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Annuler") { dismiss() }
                }
            }
        }
    }

    private var dateBinding: Binding<Date> {
        Binding(
            get: { SchoolDay.displayDate(app.courseDraft.date) ?? SchoolDay.displayDate(app.today) ?? Date() },
            set: { app.courseDraft.date = SchoolDay.day(from: $0) }
        )
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.caption.weight(.bold)).tracking(0.8).foregroundStyle(Theme.textSecondary)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }
}

private extension View {
    func fieldStyle() -> some View {
        self
            .font(.body)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 48)
            .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.border))
    }
}
