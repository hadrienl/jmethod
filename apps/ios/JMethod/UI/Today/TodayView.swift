import SwiftUI

/// Onglet Aujourd'hui : le soir prépare le lendemain (`docs/evening-workflow.md`), l'en-tête garde la date réelle.
struct TodayView: View {
    let app: AppModel

    var body: some View {
        let document = app.document
        let screen = TodayScreen(today: app.today, courses: document.courses, revisions: document.revisions,
                                 subjects: document.subjects, pronoteEvents: document.pronoteEvents)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    if screen.allDoneBanner { AllDoneBanner() }
                    TodayHeader(date: screen.headerDate)
                    HeroCard(hero: screen.hero) { if let revision = screen.hero.revision { app.presented = .score(revisionId: revision.id) } }
                    ProgressCard(screen: screen)
                    PhaseGuide()
                    ProgrammeSection(screen: screen, hasCourses: !document.courses.isEmpty, onScore: { app.presented = .score(revisionId: $0.id) },
                                     onDetail: { app.presented = .revision(revisionId: $0.id) })
                    TomorrowSection(screen: screen) { app.openAddCourse(from: $0) }
                    Text("Pas besoin de tout apprendre en un jour. Juste un peu, chaque jour.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
                .padding(.top, Spacing.s)
                .screenContent()
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

private struct AllDoneBanner: View {
    var body: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            Image(systemName: "checkmark.seal.fill").font(.title2).foregroundStyle(Theme.success).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xxs) {
                Text("Toutes tes révisions sont terminées !").font(.headline)
                Text("Tes révisions pour demain sont prêtes. Bonne soirée !").font(.subheadline)
            }
        }
        .foregroundStyle(Color(hex: "#022C22"))
        .panel(padding: Spacing.m, fill: Theme.successSoft, stroke: Color(hex: "#A7F3D0"))
        .accessibilityElement(children: .combine)
    }
}

private struct TodayHeader: View {
    let date: String

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(date.capitalizedFirst)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.textMuted)
            (Text("Un petit pas, ") + Text("chaque jour.").foregroundColor(Theme.violet))
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(Theme.text)
                .accessibilityAddTraits(.isHeader)
            Text("Ce soir, prépare tes révisions pour demain.")
                .font(.body)
                .foregroundStyle(Theme.textSecondary)
        }
    }
}

private struct HeroCard: View {
    let hero: TodayScreen.Hero
    let onStart: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Label("Pour demain", systemImage: "sparkles")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.violetDeep)
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, Spacing.xxs + 2)
                .background(Theme.lime, in: Capsule())
            Text(hero.title)
                .font(.title.weight(.bold))
                .padding(.top, Spacing.xxs)
                .accessibilityAddTraits(.isHeader)
            if let course = hero.course {
                VStack(alignment: .leading, spacing: 2) {
                    (Text("Commence par ") + Text(hero.subjectLabel ?? "ton cours").bold() + Text("."))
                    Text(course.title)
                }
                .font(.callout)
                .foregroundStyle(Theme.violetTrack)
                Button(action: onStart) {
                    HStack(spacing: Spacing.s) {
                        Text("Faire le point sur ce cours")
                        Image(systemName: "arrow.right").accessibilityHidden(true)
                    }
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.violetDeep)
                    .padding(.horizontal, Spacing.l - 4)
                    .padding(.vertical, Spacing.xs)
                    .frame(minHeight: 50)
                    .background(Theme.lime, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                    .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                }
                .buttonStyle(.plain)
                .padding(.top, Spacing.xs)
            } else {
                Text("Tes révisions pour demain sont à jour. Bonne soirée !")
                    .font(.callout)
                    .foregroundStyle(Theme.violetTrack)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(.white)
        .padding(Spacing.l)
        .background(alignment: .bottomTrailing) { HeroArt().offset(x: 36, y: 28).opacity(0.22) }
        .background(LinearGradient(colors: [Color(hex: "#7C3AED"), Theme.violet], startPoint: .topLeading, endPoint: .bottomTrailing))
        .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}

/// Illustration décorative du web (livre incliné et coche citron), en bas à droite de la carte.
private struct HeroArt: View {
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Theme.violetTrack)
                .overlay(Image(systemName: "book.closed").font(.system(size: 44, weight: .light)).foregroundStyle(Theme.violet))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color(hex: "#C4B5FD"), lineWidth: 4))
                .frame(width: 96, height: 112)
                .rotationEffect(.degrees(-13))
                .padding(.leading, 24)
                .padding(.bottom, 16)
            Image(systemName: "checkmark")
                .font(.title3.bold())
                .foregroundStyle(Theme.violetDeep)
                .padding(Spacing.s)
                .background(Theme.lime, in: RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                .rotationEffect(.degrees(10))
        }
        .accessibilityHidden(true)
    }
}

private struct ProgressCard: View {
    let screen: TodayScreen

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text("Tes petits progrès").font(.cardTitle).foregroundStyle(Theme.text)
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                Text("\(screen.programme.completed.count)").font(.metric).foregroundStyle(Theme.violet)
                Text(screen.progressSuffix).font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            ProgressBar(percent: screen.progress, height: 10, tint: Color(hex: "#7C3AED"), label: "Séances terminées aujourd’hui")
            Text(screen.progressMessage).font(.footnote).foregroundStyle(Theme.textSecondary)
        }
        .surface()
    }
}

/// Les trois étapes, en une carte compacte (vocabulaire du web).
private struct PhaseGuide: View {
    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text("Comment réviser").font(.cardTitle).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
            ForEach(Phase.allCases, id: \.self) { phase in
                let config = Constants.todayPhase(phase)
                let colors = Theme.phase(phase)
                HStack(alignment: .top, spacing: Spacing.s) {
                    Text(config.symbol)
                        .font(.footnote.weight(.bold).monospacedDigit())
                        .foregroundStyle(colors.text)
                        .lineLimit(1)
                        .fixedSize()
                        .frame(minWidth: 32, minHeight: 32)
                        .background(colors.background, in: RoundedRectangle(cornerRadius: Radius.tag, style: .continuous))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(config.label).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                        Text(config.hint).font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .surface()
        .accessibilityElement(children: .contain)
    }
}

private struct ProgrammeSection: View {
    let screen: TodayScreen
    let hasCourses: Bool
    let onScore: (Revision) -> Void
    let onDetail: (Revision) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader(title: "Dans ton programme", trailing: screen.dueLabel)
            if let banner = screen.overdueBanner {
                Label(banner, systemImage: "clock.arrow.circlepath")
                    .font(.subheadline)
                    .foregroundStyle(Theme.warning)
                    .panel(padding: Spacing.s, fill: Theme.warningSoft)
            }
            if screen.emptyProgramme {
                EmptyState(systemImage: "checkmark", title: "Tout est à jour",
                           message: hasCourses ? "Aucune séance à préparer pour demain." : "Aucun cours enregistré pour l’instant : rien à préparer ce soir.",
                           tint: Theme.success)
            } else {
                ForEach(screen.groups) { group in
                    SubjectGroupCard(group: group, onScore: onScore, onDetail: onDetail)
                }
            }
        }
    }
}

private struct SubjectGroupCard: View {
    let group: TodayScreen.Group
    let onScore: (Revision) -> Void
    let onDetail: (Revision) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                SubjectDot(color: group.subject.color, size: 12)
                    .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
                Text(group.subject.name).font(.cardTitle).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                    .layoutPriority(1)
                Spacer(minLength: Spacing.xs)
                Text(group.countLabel).font(.footnote).foregroundStyle(Theme.textMuted).multilineTextAlignment(.trailing)
            }
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.s)
            .accessibilityElement(children: .combine)
            ForEach(group.rows) { row in
                Divider()
                RevisionRow(row: row, onScore: onScore, onDetail: onDetail)
            }
        }
        .background(Theme.surface)
        .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Theme.border))
    }
}

/// Séance du programme : étape, titre, cours ; une ligne compacte, l'action « Faire le point » à portée de pouce.
private struct RevisionRow: View {
    let row: TodayScreen.Row
    let onScore: (Revision) -> Void
    let onDetail: (Revision) -> Void

    var body: some View {
        let colors = Theme.phase(row.revision.phase)
        VStack(alignment: .leading, spacing: Spacing.s) {
            Button { onDetail(row.revision) } label: {
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    FlowRow(spacing: Spacing.xs) {
                        Tag(text: row.badge, foreground: colors.text, background: colors.background)
                        if let done = row.doneLabel {
                            Label(done, systemImage: "checkmark")
                                .font(.tagLabel)
                                .foregroundStyle(Theme.success)
                                .padding(.vertical, Spacing.xxs)
                        }
                    }
                    Text(row.title).font(.body.weight(.semibold)).foregroundStyle(row.done ? Theme.textSecondary : Theme.text)
                        .multilineTextAlignment(.leading)
                        .padding(.top, 2)
                    Text(row.courseLine).font(.footnote).foregroundStyle(Theme.textMuted)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Ouvre le détail de la séance")
            if row.canValidate {
                Button { onScore(row.revision) } label: {
                    HStack(spacing: Spacing.xs) {
                        Text("Faire le point")
                        Image(systemName: "arrow.right").font(.footnote.weight(.bold)).accessibilityHidden(true)
                    }
                }
                .buttonStyle(TintedButtonStyle(fullWidth: false))
                .accessibilityLabel("Faire le point : \(row.title)")
            }
        }
        .padding(Spacing.m)
        .background(row.done ? Theme.background : Theme.surface)
    }
}

private struct TomorrowSection: View {
    let screen: TodayScreen
    let onAddCourse: (PronoteEvent) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader(title: "Ta journée de cours de demain", subtitle: "\(screen.tomorrowDate.capitalizedFirst) · Depuis Pronote")
            if screen.tomorrowEvents.isEmpty {
                EmptyState(systemImage: "calendar", title: "Aucun cours importé pour demain",
                           message: "Les créneaux de ton emploi du temps Pronote s’affichent ici.")
            } else {
                VStack(spacing: 0) {
                    ForEach(screen.tomorrowEvents) { item in
                        TomorrowEventRow(item: item, onAddCourse: onAddCourse)
                        if item.id != screen.tomorrowEvents.last?.id { Divider().padding(.leading, Spacing.m) }
                    }
                }
                .background(Theme.surface)
                .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Theme.border))
            }
        }
    }
}

/// Créneau de demain : heure, matière, professeur et salle ; « Ajouter » compact à droite.
private struct TomorrowEventRow: View {
    let item: TodayScreen.TomorrowEvent
    let onAddCourse: (PronoteEvent) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    private var time: String {
        item.event.endTime.isEmpty ? item.event.startTime : item.timeRange
    }

    private var details: String {
        [item.teacher, item.cancelled ? nil : item.trailing].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    var body: some View {
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(alignment: .center, spacing: Spacing.s))
        layout {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Spacing.xs) {
                    Text(time).font(.footnote.weight(.semibold).monospacedDigit()).foregroundStyle(Theme.textSecondary)
                    if item.cancelled {
                        Tag(text: "Annulé", foreground: Theme.danger, background: Theme.dangerSoft, systemImage: "xmark")
                    }
                }
                Text(item.subjectName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(item.cancelled ? Theme.textMuted : Theme.text)
                    .strikethrough(item.cancelled)
                if !details.isEmpty {
                    Text(details).font(.footnote).foregroundStyle(Theme.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if item.canAddCourse {
                AddCourseFromSlotButton(subjectName: item.subjectName, compact: true) { onAddCourse(item.event) }
            }
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
        .background(item.cancelled ? Theme.background : Theme.surface)
    }
}
