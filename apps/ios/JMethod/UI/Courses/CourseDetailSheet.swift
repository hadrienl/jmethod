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
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func content(_ detail: CourseDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 6) {
                            SubjectDot(color: detail.subject?.color ?? "#94A3B8", size: 10)
                            Text(detail.subjectLabel.uppercased()).font(.caption.weight(.semibold))
                        }
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Theme.background, in: Capsule())
                        .overlay(Capsule().strokeBorder(Theme.border))
                        .accessibilityElement(children: .combine)
                        (Text("Chapitre : ").foregroundColor(Theme.textSecondary) + Text(detail.course.chapter).bold())
                            .font(.caption)
                    }
                    (Text("C\(detail.course.courseNumber) ").foregroundColor(Theme.violet) + Text(detail.course.title))
                        .font(.title2.weight(.heavy))
                        .accessibilityAddTraits(.isHeader)
                    VStack(alignment: .leading, spacing: 4) {
                        Label(detail.courseDateLine, systemImage: "calendar")
                        Label(detail.createdLine, systemImage: "square.and.pencil")
                        if let average = detail.averageLine {
                            Label(average, systemImage: "sparkles").foregroundStyle(Theme.text)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(Theme.textSecondary)
                }

                if let notes = detail.notes, !notes.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("Notes de cours", systemImage: "book").font(.subheadline.bold()).foregroundStyle(Theme.textSecondary)
                            .accessibilityAddTraits(.isHeader)
                        Text(notes).font(.subheadline)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.background, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.border))
                }

                if let subject = detail.subject {
                    Button { app.presented = .notebook(subjectId: subject.id) } label: {
                        Label("Voir le cahier de textes de \(subject.name)", systemImage: "text.book.closed")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("Cycle des révisions").font(.subheadline.weight(.semibold))
                        Spacer(minLength: 8)
                        Text(detail.progressLine).font(.caption.weight(.bold).monospacedDigit()).foregroundStyle(Theme.violetDark)
                    }
                    ProgressBar(percent: detail.progressPercent, height: 10)
                }
                .padding(14)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.border))
                .accessibilityElement(children: .combine)

                VStack(alignment: .leading, spacing: 10) {
                    Text("Calendrier personnalisé des J").font(.headline).accessibilityAddTraits(.isHeader)
                    if detail.timeline.isEmpty {
                        Text("Aucune séance programmée pour ce cours.").font(.subheadline).foregroundStyle(Theme.textSecondary)
                    }
                    ForEach(detail.timeline) { item in
                        TimelineRow(item: item) { app.presented = .score(revisionId: item.revision.id) }
                    }
                }

                Button(role: .destructive) { confirmingDelete = true } label: {
                    Label("Supprimer ce cours", systemImage: "trash")
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .foregroundStyle(Theme.danger)
                .padding(.top, 8)
            }
            .padding(20)
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
    let onValidate: () -> Void

    var body: some View {
        let colors = Theme.phase(item.revision.phase)
        HStack(spacing: 12) {
            Text(item.revision.isEmergencySession == true ? String(item.revision.stepLabel.prefix { $0 != " " }) : item.revision.stepLabel)
                .font(.caption.weight(.bold).monospacedDigit())
                .foregroundStyle(colors.text)
                .frame(minWidth: 44, minHeight: 28)
                .padding(.horizontal, 4)
                .background(colors.background, in: RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel(item.revision.stepLabel)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(item.phaseLabel).font(.subheadline.weight(.semibold))
                    if item.emergencyBadge {
                        Text("Rattrapage").font(.caption2.weight(.semibold)).foregroundStyle(Theme.danger)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Theme.dangerSoft, in: RoundedRectangle(cornerRadius: 4))
                    }
                    if item.state == .overdue {
                        Text("En retard").font(.caption2.weight(.semibold)).foregroundStyle(Theme.warning)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Theme.warningSoft, in: RoundedRectangle(cornerRadius: 4))
                    }
                }
                Text(item.plannedLine).font(.caption).foregroundStyle(Theme.textSecondary)
                if let done = item.doneLine {
                    Text(done).font(.caption).foregroundStyle(Theme.success)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            if item.state == .completed {
                Label(item.right, systemImage: "checkmark.circle.fill")
                    .font(.caption.weight(.bold).monospacedDigit())
                    .foregroundStyle(Theme.success)
                    .accessibilityLabel("Faite, \(item.right)")
            } else {
                Button(item.right, action: onValidate)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.violetDark)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityLabel("Valider \(item.revision.stepLabel)")
            }
        }
        .padding(10)
        .background(item.state == .overdue ? Theme.dangerSoft : item.state == .completed ? Theme.background : Theme.surface,
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border))
    }
}
