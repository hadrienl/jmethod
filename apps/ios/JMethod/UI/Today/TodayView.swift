import SwiftUI

/// Onglet Aujourd'hui : le soir prépare le lendemain (`docs/evening-workflow.md`), l'en-tête garde la date réelle.
struct TodayView: View {
    let app: AppModel
    @State private var scoring: Revision?
    @State private var detail: Revision?

    var body: some View {
        let document = app.document
        let screen = TodayScreen(today: app.today, courses: document.courses, revisions: document.revisions,
                                 subjects: document.subjects, pronoteEvents: document.pronoteEvents)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if screen.allDoneBanner { AllDoneBanner() }
                    TodayHeader(date: screen.headerDate)
                    HeroCard(hero: screen.hero) { if let revision = screen.hero.revision { scoring = revision } }
                    ProgressCard(screen: screen)
                    PhaseGuide()
                    ProgrammeSection(screen: screen, onScore: { scoring = $0 }, onDetail: { detail = $0 })
                    TomorrowSection(screen: screen, hasSync: app.lastPronoteSync != nil)
                    Text("Pas besoin de tout apprendre en un jour. Juste un peu, chaque jour.")
                        .font(.footnote)
                        .foregroundStyle(Theme.textMuted)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 8)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 20)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.background)
            .toolbar(.hidden, for: .navigationBar)
        }
        .sheet(item: $scoring) { revision in
            ScoreSheet(app: app, revisionId: revision.id)
        }
        .sheet(item: $detail) { revision in
            RevisionDetailSheet(app: app, revisionId: revision.id)
        }
    }
}

private struct AllDoneBanner: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Toutes tes révisions sont terminées !", systemImage: "checkmark.seal.fill")
                .font(.title3.bold())
            Text("Tes révisions pour demain sont prêtes. Bonne soirée !").font(.subheadline)
        }
        .foregroundStyle(Color(hex: "#022C22"))
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.successSoft, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color(hex: "#A7F3D0")))
        .accessibilityElement(children: .combine)
    }
}

private struct TodayHeader: View {
    let date: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(date.uppercased())
                .font(.caption.weight(.bold))
                .tracking(1.5)
                .foregroundStyle(Theme.textMuted)
                .accessibilityLabel(date)
            (Text("Un petit pas, ") + Text("chaque jour.").foregroundColor(Theme.violet))
                .font(.largeTitle.weight(.heavy))
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
        ZStack(alignment: .topTrailing) {
            HeroArt()
                .offset(x: 30, y: 40)
                .opacity(0.25)
            VStack(alignment: .leading, spacing: 14) {
                Label("POUR DEMAIN", systemImage: "sparkles")
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.white.opacity(0.15), in: Capsule())
                Text(hero.title)
                    .font(.title.weight(.heavy))
                    .padding(.top, 8)
                    .accessibilityAddTraits(.isHeader)
                if let course = hero.course {
                    VStack(alignment: .leading, spacing: 4) {
                        (Text("Commence par ") + Text(hero.subjectLabel ?? "ton cours").bold() + Text("."))
                        Text(course.title)
                    }
                    .font(.callout)
                    .foregroundStyle(Theme.violetTrack)
                    Button(action: onStart) {
                        HStack(spacing: 12) {
                            Text("Faire le point sur ce cours")
                            Image(systemName: "arrow.right").accessibilityHidden(true)
                        }
                        .font(.body.weight(.bold))
                        .foregroundStyle(Theme.violetDeep)
                        .padding(.horizontal, 20)
                        .frame(minHeight: 48)
                        .background(Theme.lime, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 6)
                } else {
                    Text("Tes révisions pour demain sont à jour. Bonne soirée !")
                        .font(.callout)
                        .foregroundStyle(Theme.violetTrack)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(.white)
        .padding(24)
        .background(Theme.violet, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Illustration décorative du web (livre incliné et coche citron).
private struct HeroArt: View {
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Theme.violetTrack)
                .overlay(Image(systemName: "book.closed").font(.system(size: 52, weight: .light)).foregroundStyle(Theme.violet))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color(hex: "#C4B5FD"), lineWidth: 5))
                .frame(width: 112, height: 130)
                .rotationEffect(.degrees(-13))
                .padding(.leading, 25)
                .padding(.bottom, 20)
            Image(systemName: "checkmark")
                .font(.title2.bold())
                .foregroundStyle(Theme.violetDeep)
                .padding(12)
                .background(Theme.lime, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .rotationEffect(.degrees(10))
        }
        .accessibilityHidden(true)
    }
}

private struct ProgressCard: View {
    let screen: TodayScreen

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Tes petits progrès").font(.headline)
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text("\(screen.programme.completed.count)").font(.system(.largeTitle, design: .rounded).weight(.heavy))
                Text(screen.progressSuffix).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.violetTrack)
                    Capsule().fill(Color(hex: "#7C3AED")).frame(width: proxy.size.width * CGFloat(screen.progress) / 100)
                }
            }
            .frame(height: 12)
            .accessibilityElement()
            .accessibilityLabel("Séances terminées aujourd’hui")
            .accessibilityValue("\(screen.progress) %")
            Text(screen.progressMessage).font(.subheadline).foregroundStyle(Theme.textSecondary)
        }
        .surface()
    }
}

private struct PhaseGuide: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Phase.allCases, id: \.self) { phase in
                let config = Constants.todayPhase(phase)
                let colors = Theme.phase(phase)
                HStack(spacing: 12) {
                    Text(config.symbol)
                        .font(.subheadline.bold())
                        .foregroundStyle(colors.text)
                        .frame(width: 44, height: 44)
                        .background(colors.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(config.label).font(.subheadline.bold())
                        Text(config.hint).font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .accessibilityLabel("Comment réviser")
        .accessibilityElement(children: .contain)
    }
}

private struct ProgrammeSection: View {
    let screen: TodayScreen
    let onScore: (Revision) -> Void
    let onDetail: (Revision) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Dans ton programme").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                Text(screen.dueLabel).font(.subheadline).foregroundStyle(Theme.textMuted)
            }
            if let banner = screen.overdueBanner {
                Label(banner, systemImage: "clock.arrow.circlepath")
                    .font(.subheadline)
                    .foregroundStyle(Theme.warning)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.warningSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            if screen.emptyProgramme {
                VStack(spacing: 10) {
                    Image(systemName: "checkmark.circle").font(.largeTitle).foregroundStyle(Theme.success).accessibilityHidden(true)
                    Text("Tout est à jour").font(.headline)
                    Text("Aucune séance à préparer pour demain.").font(.subheadline).foregroundStyle(Theme.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 28)
                .surface()
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
            HStack(spacing: 12) {
                Image(systemName: "book")
                    .foregroundStyle(Color(hex: group.subject.color))
                    .frame(width: 44, height: 44)
                    .background(Theme.background, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.subject.name).font(.headline).accessibilityAddTraits(.isHeader)
                    Text(group.countLabel).font(.caption).foregroundStyle(Theme.textMuted)
                }
                Spacer()
                SubjectDot(color: group.subject.color)
            }
            .padding(16)
            ForEach(group.rows) { row in
                Divider()
                RevisionRow(row: row, onScore: onScore, onDetail: onDetail)
            }
        }
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.border))
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

private struct RevisionRow: View {
    let row: TodayScreen.Row
    let onScore: (Revision) -> Void
    let onDetail: (Revision) -> Void

    var body: some View {
        let colors = Theme.phase(row.revision.phase)
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    badge(colors)
                    Spacer()
                    doneLabel
                }
                VStack(alignment: .leading, spacing: 8) {
                    badge(colors)
                    doneLabel
                }
            }
            Button { onDetail(row.revision) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.courseLine).font(.caption).foregroundStyle(Theme.textMuted)
                    Text(row.title).font(.body.weight(.bold)).foregroundStyle(Theme.text).multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Ouvre le détail de la séance")
            if row.canValidate {
                Button { onScore(row.revision) } label: {
                    HStack {
                        Text("Faire le point")
                        Spacer()
                        Image(systemName: "arrow.right").accessibilityHidden(true)
                    }
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Theme.violetDark)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 48)
                    .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Faire le point : \(row.title)")
            }
        }
        .padding(16)
        .background(row.done ? Theme.background : Theme.surface)
    }

    private func badge(_ colors: (text: Color, background: Color)) -> some View {
        Text(row.badge)
            .font(.caption.weight(.bold))
            .foregroundStyle(colors.text)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(colors.background, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    @ViewBuilder private var doneLabel: some View {
        if let done = row.doneLabel {
            Label(done, systemImage: "checkmark")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Theme.success)
        }
    }
}

private struct TomorrowSection: View {
    let screen: TodayScreen
    let hasSync: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "calendar").font(.title2).foregroundStyle(Theme.violet).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Ta journée de cours de demain").font(.title3.weight(.heavy)).accessibilityAddTraits(.isHeader)
                    Text("\(screen.tomorrowDate) · Depuis Pronote").font(.subheadline).foregroundStyle(Theme.textSecondary)
                }
            }
            if screen.tomorrowEvents.isEmpty {
                Text("Aucun cours importé pour demain.").font(.subheadline).foregroundStyle(Theme.textSecondary)
            } else {
                if !hasSync {
                    Text("Exemple d’emploi du temps : connecte Pronote dans Réglages pour voir tes vrais cours.")
                        .font(.footnote).foregroundStyle(Theme.textMuted)
                }
                ForEach(screen.tomorrowEvents) { item in
                    TomorrowEventCard(item: item)
                }
            }
        }
        .surface()
    }
}

private struct TomorrowEventCard: View {
    let item: TodayScreen.TomorrowEvent

    private var time: String {
        item.event.endTime.isEmpty ? item.event.startTime : item.timeRange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(time).font(.caption).foregroundStyle(Theme.textSecondary)
                Spacer()
                if item.cancelled {
                    Label("Annulé", systemImage: "xmark.circle").font(.caption.weight(.semibold)).foregroundStyle(Theme.danger)
                } else if let room = item.trailing, !room.isEmpty {
                    Text(room).font(.caption).foregroundStyle(Theme.textSecondary)
                }
            }
            Text(item.subjectName)
                .font(.subheadline.weight(.bold))
                .strikethrough(item.cancelled)
            if let teacher = item.teacher {
                Text(teacher).font(.caption).foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(item.cancelled ? Theme.background : Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(item.cancelled ? Color(hex: "#CBD5E1") : Theme.border, style: StrokeStyle(lineWidth: 1, dash: item.cancelled ? [5, 4] : []))
        )
        .accessibilityElement(children: .combine)
    }
}
