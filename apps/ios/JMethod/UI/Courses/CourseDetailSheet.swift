import SwiftUI

/// Fiche d'un cours (`CourseDetailModal.tsx`) : progression, calendrier des J, validation, suppression confirmée.
struct CourseDetailSheet: View {
    let app: AppModel
    let courseId: String
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingDelete = false

    var body: some View {
        NavigationStack {
            Group {
                if let course = app.course(id: courseId) {
                    content(CourseDetail(course: course, subjects: app.document.subjects,
                                         revisions: app.document.revisions.filter { $0.courseId == courseId }))
                } else {
                    ContentUnavailableView("Cours introuvable", systemImage: "questionmark.circle", description: Text("Ce cours n’existe plus."))
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }.accessibilityLabel("Fermer la fiche du cours")
                }
            }
            .navigationTitle("Fiche du cours")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDragIndicator(.visible)
    }

    private func content(_ detail: CourseDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    HStack(spacing: Spacing.xs) {
                        SubjectDot(color: detail.subject?.color ?? "#94A3B8")
                        Text(detail.subjectLabel).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                        Text("·").foregroundStyle(Theme.textMuted).accessibilityHidden(true)
                        Text(detail.course.chapter).font(.subheadline).foregroundStyle(Theme.textSecondary).lineLimit(2)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(detail.subjectLabel), chapitre : \(detail.course.chapter)")
                    (Text("C\(detail.course.courseNumber) ").foregroundColor(Theme.violet) + Text(detail.course.title))
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Theme.text)
                        .accessibilityAddTraits(.isHeader)
                    VStack(alignment: .leading, spacing: Spacing.xxs) {
                        Label(detail.courseDateLine, systemImage: "calendar")
                        Label(detail.createdLine, systemImage: "square.and.pencil")
                        if let average = detail.averageLine {
                            Label(average, systemImage: "chart.bar").foregroundStyle(Theme.text)
                        }
                    }
                    .font(.footnote)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.top, Spacing.xxs)
                }

                VStack(alignment: .leading, spacing: Spacing.s) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Cycle des révisions").font(.subheadline.weight(.semibold))
                        Spacer(minLength: Spacing.xs)
                        Text(detail.progressLine).font(.footnote.weight(.semibold)).monospacedDigit().foregroundStyle(Theme.violetDark)
                    }
                    ProgressBar(percent: detail.progressPercent, height: 8)
                }
                .panel(padding: Spacing.m)
                .accessibilityElement(children: .combine)

                if let notes = detail.notes, !notes.isEmpty {
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        Label("Notes de cours", systemImage: "note.text").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                            .accessibilityAddTraits(.isHeader)
                        Text(notes).font(.subheadline).foregroundStyle(Theme.text)
                    }
                    .panel(padding: Spacing.m)
                }

                if let subject = detail.subject {
                    Button { app.presented = .notebook(subjectId: subject.id) } label: {
                        HStack(spacing: Spacing.s) {
                            IconTile(systemName: "text.book.closed", size: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Cahier de textes").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                                Text(subject.name).font(.footnote).foregroundStyle(Theme.textSecondary)
                            }
                            Spacer(minLength: Spacing.xs)
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textMuted).accessibilityHidden(true)
                        }
                        .padding(Spacing.s)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(Theme.border))
                        .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Voir le cahier de textes de \(subject.name)")
                    .accessibilityAddTraits(.isButton)
                }

                VStack(alignment: .leading, spacing: Spacing.s) {
                    Text("Calendrier personnalisé des J").font(.sectionTitle).accessibilityAddTraits(.isHeader)
                    if detail.timeline.isEmpty {
                        Text("Aucune séance programmée pour ce cours.").font(.subheadline).foregroundStyle(Theme.textSecondary)
                    }
                    let nextId = detail.timeline.first { $0.state != .completed }?.id
                    VStack(spacing: 0) {
                        ForEach(detail.timeline) { item in
                            TimelineRow(item: item, isNext: item.id == nextId) { app.presented = .score(revisionId: item.revision.id) }
                            if item.id != detail.timeline.last?.id { Divider().padding(.leading, 64) }
                        }
                    }
                    .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(Theme.border))
                    .clipShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                }

                Button(role: .destructive) { confirmingDelete = true } label: {
                    Label("Supprimer ce cours", systemImage: "trash")
                }
                .buttonStyle(DestructiveTextButtonStyle())
            }
            .padding(.horizontal, Spacing.screen)
            .padding(.top, Spacing.xs)
            .padding(.bottom, Spacing.l)
        }
        .background(Theme.surface)
        .confirmationDialog("Supprimer ce cours ?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Supprimer définitivement", role: .destructive) {
                app.deleteCourse(id: detail.course.id)
                dismiss()
            }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("\(detail.deleteConfirm) Toutes ses séances seront aussi supprimées.")
        }
    }
}

private struct TimelineRow: View {
    let item: CourseDetail.Item
    /// Prochaine séance à faire : bouton mis en avant ; les suivantes restent accessibles, plus discrètes.
    let isNext: Bool
    let onValidate: () -> Void

    var body: some View {
        let colors = Theme.phase(item.revision.phase)
        HStack(spacing: Spacing.s) {
            Text(item.revision.isEmergencySession == true ? String(item.revision.stepLabel.prefix { $0 != " " }) : item.revision.stepLabel)
                .font(.caption.weight(.bold).monospacedDigit())
                .foregroundStyle(colors.text)
                .frame(minWidth: 40, minHeight: 28)
                .padding(.horizontal, Spacing.xxs)
                .background(colors.background, in: RoundedRectangle(cornerRadius: Radius.tag, style: .continuous))
                .accessibilityLabel(item.revision.stepLabel)
            VStack(alignment: .leading, spacing: 2) {
                FlowRow(spacing: Spacing.xs - 2) {
                    Text(item.phaseLabel).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                    if item.emergencyBadge {
                        Tag(text: "Rattrapage", foreground: Theme.danger, background: Theme.dangerSoft)
                    }
                    if item.state == .overdue {
                        Tag(text: "En retard", foreground: Theme.warning, background: Theme.warningSoft)
                    }
                }
                Text(item.plannedLine).font(.footnote).foregroundStyle(Theme.textSecondary)
                if let done = item.doneLine {
                    Text(done).font(.footnote).foregroundStyle(Theme.success)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: Spacing.xs)
            if item.state == .completed {
                Label(item.right, systemImage: "checkmark.circle.fill")
                    .font(.footnote.weight(.semibold).monospacedDigit())
                    .foregroundStyle(Theme.success)
                    .accessibilityLabel("Faite, \(item.right)")
            } else if isNext {
                Button(item.right, action: onValidate)
                    .buttonStyle(TintedButtonStyle(fullWidth: false))
                    .accessibilityLabel("Valider \(item.revision.stepLabel)")
            } else {
                Button(item.right, action: onValidate)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.violetDark)
                    .frame(minWidth: 44, minHeight: 44)
                    .accessibilityLabel("Valider \(item.revision.stepLabel)")
            }
        }
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, Spacing.xs)
        .background(item.state == .overdue ? Theme.warningSoft : item.state == .completed ? Theme.background : Theme.surface)
    }
}
