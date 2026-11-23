import SwiftUI

/// Onglet Aujourd'hui : le soir prépare le lendemain (`docs/evening-workflow.md`), l'en-tête garde la date réelle.
/// Ordre : carte « Pour demain » (commencer, enchaîner), emploi du temps de demain, puis une seule section repliable
/// « Dans ton programme » (progrès, étapes, séances à reprendre et liste), repliée à chaque lancement.
struct TodayView: View {
    let app: AppModel
    /// Section du programme dépliée : mémorisée le temps de la session seulement.
    @State private var programmeExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let document = app.document
        let screen = TodayScreen(today: app.today, courses: document.courses, revisions: document.revisions,
                                 subjects: document.subjects, pronoteEvents: document.pronoteEvents)
        let hasCourses = !document.courses.isEmpty
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    TodayHeader(date: screen.headerDate,
                                flame: RevisionStreak.summary(document.streakState, date: app.today))
                    HeroCard(hero: screen.hero, hasCourses: hasCourses, canReview: RevisionSession.hasDocuments(screen.hero.course),
                             onStart: { if let revision = screen.hero.revision { app.presented = .score(revisionId: revision.id) } },
                             onReview: { if let revision = screen.hero.revision { app.presented = .review(revisionId: revision.id) } })
                    TomorrowSection(screen: screen, courseFor: { RevisionSession.courseForSlot($0, courses: document.courses, subjects: document.subjects) },
                                    onAddCourse: { app.openAddCourse(from: $0) }, onOpenCourse: { app.presented = .course(courseId: $0.id) })
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        ProgrammeToggle(summary: screen.programme.due.isEmpty ? "Tout est fait" : screen.dueLabel, expanded: programmeExpanded) {
                            if reduceMotion {
                                programmeExpanded.toggle()
                            } else {
                                withAnimation(.easeInOut(duration: 0.2)) { programmeExpanded.toggle() }
                            }
                        }
                        if programmeExpanded {
                            VStack(alignment: .leading, spacing: Spacing.l) {
                                if screen.allDoneBanner { AllDoneBanner() }
                                // Aucun cours : pas de compteur de progrès (comme Android).
                                if hasCourses { ProgressCard(screen: screen) }
                                PhaseGuide()
                                ProgrammeSection(screen: screen, hasCourses: hasCourses, onScore: { app.presented = .score(revisionId: $0.id) },
                                                 onDetail: { app.presented = .revision(revisionId: $0.id) })
                            }
                            .transition(.opacity)
                        }
                    }
                }
                .padding(.top, Spacing.s)
                .screenContent()
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

/// En-tête pleine largeur de la section repliable : titre, résumé (« N à faire » ou « Tout est fait ») et chevron.
private struct ProgrammeToggle: View {
    let summary: String
    let expanded: Bool
    let action: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: Spacing.s) {
                let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xxs))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Spacing.xs))
                layout {
                    Text("Dans ton programme").font(.sectionTitle).foregroundStyle(Theme.text).layoutPriority(1)
                    if !typeSize.isAccessibilitySize { Spacer(minLength: Spacing.xs) }
                    Text(summary).font(.subheadline).foregroundStyle(Theme.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.violetDark)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.m)
            .padding(.vertical, Spacing.s)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Theme.border))
            .contentShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Dans ton programme, \(summary)")
        .accessibilityValue(expanded ? "déplié" : "replié")
        .accessibilityHint("Affiche le détail du programme")
        .accessibilityAddTraits([.isButton, .isHeader])
        .accessibilityIdentifier("today-programme-toggle")
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
    let flame: RevisionStreak.Summary
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ViewThatFits(in: .horizontal) {
            if !typeSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: Spacing.l) {
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        dateLabel
                        intro
                    }
                    .frame(minWidth: 230, maxWidth: .infinity, alignment: .leading)
                    RevisionFlameCard(summary: flame)
                        .frame(minWidth: 300, maxWidth: .infinity)
                }
            }
            VStack(alignment: .leading, spacing: Spacing.m) {
                dateLabel
                RevisionFlameCard(summary: flame)
                intro
            }
        }
    }

    private var dateLabel: some View {
        Text(date.capitalizedFirst)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Theme.textSecondary)
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
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
    /// Aucun cours enregistré : « Rien à préparer ce soir. » et comment en ajouter un (comme Android).
    let hasCourses: Bool
    /// Le cours a au moins un document : « Revoir le cours » ouvre le document principal dans la fiche (§8.3).
    let canReview: Bool
    let onStart: () -> Void
    let onReview: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Label("Pour demain", systemImage: "sparkles")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.violetDeep)
                .padding(.horizontal, Spacing.s)
                .padding(.vertical, Spacing.xxs + 2)
                .background(Theme.lime, in: Capsule())
            Text(hasCourses ? hero.title : "Rien à préparer ce soir.")
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
                if canReview {
                    Button(action: onReview) {
                        Label("Revoir le cours", systemImage: "book")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, Spacing.l - 4)
                            .padding(.vertical, Spacing.xs)
                            .frame(minHeight: 44)
                            .overlay(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous).strokeBorder(.white.opacity(0.7), lineWidth: 1.5))
                            .contentShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Ouvre le document principal du cours")
                }
            } else {
                Text(hasCourses ? "Tes révisions pour demain sont à jour. Bonne soirée !"
                     : "Aucun cours n’est enregistré sur ce téléphone. Ajoute ton premier cours depuis ton emploi du temps de demain, juste en dessous, ou depuis Mes cours.")
                    .font(.callout)
                    .foregroundStyle(Theme.violetTrack)
                    .fixedSize(horizontal: false, vertical: true)
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
            if let banner = screen.overdueBanner {
                Label(banner, systemImage: "clock.arrow.circlepath")
                    .font(.subheadline)
                    .foregroundStyle(Theme.warning)
                    .panel(padding: Spacing.s, fill: Theme.warningSoft)
            }
            if screen.emptyProgramme {
                EmptyState(systemImage: "checkmark", title: hasCourses ? "Tout est à jour" : "Ton programme est vide",
                           message: hasCourses
                               ? "Aucune séance à faire pour l’instant. Tu peux ajouter un cours depuis ton emploi du temps ci-dessus."
                               : "Ajoute un cours depuis ton emploi du temps ci-dessus : ses séances apparaîtront ici.",
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
                        if RevisionSession.hasDocuments(row.course) {
                            Image(systemName: "paperclip")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(Theme.textSecondary)
                                .padding(.vertical, Spacing.xxs)
                                .accessibilityLabel("Documents du cours disponibles")
                        }
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
            .accessibilityHint("Ouvre la fiche de la séance : cours, documents et cahier de textes")
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
    let courseFor: (PronoteEvent) -> Course?
    let onAddCourse: (PronoteEvent) -> Void
    let onOpenCourse: (Course) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            SectionHeader(title: "Ta journée de cours de demain", subtitle: "\(screen.tomorrowDate.capitalizedFirst) · Depuis Pronote")
            if screen.tomorrowEvents.isEmpty {
                EmptyState(systemImage: "calendar", title: "Aucun cours importé pour demain",
                           message: "Les créneaux de ton emploi du temps Pronote s’affichent ici.")
            } else {
                VStack(spacing: 0) {
                    ForEach(screen.tomorrowEvents) { item in
                        TomorrowEventRow(item: item, course: courseFor(item.event), onAddCourse: onAddCourse, onOpenCourse: onOpenCourse)
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
    /// Cours déjà créé pour cette matière et ce jour : « Voir le cours » au lieu d'« Ajouter ce cours » (§8.3).
    let course: Course?
    let onAddCourse: (PronoteEvent) -> Void
    let onOpenCourse: (Course) -> Void
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
            if let course {
                Button { onOpenCourse(course) } label: {
                    Text("Voir le cours")
                }
                .buttonStyle(TintedButtonStyle(fullWidth: false))
                .accessibilityLabel("Voir le cours de \(item.subjectName) : \(course.title)")
            } else if item.canAddCourse {
                AddCourseFromSlotButton(subjectName: item.subjectName, compact: true) { onAddCourse(item.event) }
            }
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
        .background(item.cancelled ? Theme.background : Theme.surface)
    }
}
