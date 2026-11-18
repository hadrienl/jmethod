import SwiftUI

/// Onglet Mes progrès (`StatsView.tsx`) : quatre indicateurs, matières à retravailler, progression par matière.
struct StatsView: View {
    let app: AppModel
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let document = app.document
        let model = StatsScreen(courses: document.courses, revisions: document.revisions, subjects: document.subjects, today: app.today)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Text("Ta régularité, tes scores et ce qui reste à consolider.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    let columns = Array(repeating: GridItem(.flexible(), spacing: Spacing.s, alignment: .top), count: typeSize.isAccessibilitySize ? 1 : 2)
                    LazyVGrid(columns: columns, spacing: Spacing.s) {
                        Metric(title: "Taux de réalisation", value: "\(model.completionRate) %", line: model.completionLine, icon: "checkmark.circle")
                        Metric(title: "Score moyen", value: model.overallAvgScore.map { "\($0)/100" } ?? model.overallAvgLabel, line: model.balanceLabel,
                               icon: "gauge.with.dots.needle.50percent",
                               spoken: model.overallAvgScore.map { "Score moyen : \($0) sur 100" } ?? "Score moyen : pas encore de note")
                        Metric(title: "Régularité", value: model.streakLabel, line: model.streakLine, icon: "flame",
                               spoken: "Régularité : \(model.streakDays) \(model.streakDays > 1 ? "jours consécutifs" : "jour")")
                        Metric(title: "Cours suivis", value: "\(model.coursesCount)", line: model.subjectsLine, icon: "books.vertical")
                    }

                    if !model.weakSubjects.isEmpty {
                        WeakSubjects(rows: model.weakSubjects)
                    }

                    SectionHeader(title: "Par matière").padding(.top, Spacing.xs)
                    if model.subjects.isEmpty {
                        EmptyState(systemImage: "chart.bar", title: "Pas encore de progrès par matière",
                                   message: "Tes progrès par matière apparaîtront ici dès que tu auras ajouté un cours.")
                    } else {
                        VStack(spacing: 0) {
                            ForEach(model.subjects) { row in
                                SubjectBar(row: row)
                                if row.id != model.subjects.last?.id { Divider().padding(.leading, Spacing.m) }
                            }
                        }
                        .background(Theme.surface)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Theme.border))
                    }
                }
                .screenContent()
            }
            .background(Theme.background)
            .navigationTitle("Mes progrès")
        }
    }
}

private struct Metric: View {
    let title: String
    let value: String
    let line: String
    let icon: String
    var spoken: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .center, spacing: Spacing.xs) {
                Image(systemName: icon).font(.footnote.weight(.semibold)).foregroundStyle(Theme.violet).accessibilityHidden(true)
                Text(title).font(.footnote.weight(.semibold)).foregroundStyle(Theme.textSecondary)
            }
            Text(value).font(.metric).foregroundStyle(Theme.text)
                .minimumScaleFactor(0.7).lineLimit(1)
            Text(line).font(.footnote).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 108, alignment: .topLeading)
        .surface(padding: Spacing.m)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(spoken ?? "\(title) : \(value)"). \(line)")
    }
}

private struct WeakSubjects: View {
    let rows: [StatsScreen.SubjectRow]

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Label("Matières à retravailler", systemImage: "arrow.uturn.backward.circle")
                .font(.headline).foregroundStyle(Theme.warning).accessibilityAddTraits(.isHeader)
            Text("Leur score moyen est sous l’équilibre (50/100). Les prochaines séances de ces matières sont déjà rapprochées pour t’aider.")
                .font(.subheadline).foregroundStyle(Theme.warning)
            FlowRow(spacing: Spacing.xs) {
                ForEach(rows) { row in
                    HStack(spacing: Spacing.xxs + 2) {
                        SubjectDot(color: row.subject.color, size: 8)
                        Text(row.subject.name).font(.subheadline.weight(.semibold))
                        Text("\(row.avgScore ?? 0)/100").font(.subheadline.monospacedDigit())
                    }
                    .foregroundStyle(Theme.warning)
                    .padding(.horizontal, Spacing.s)
                    .padding(.vertical, Spacing.xs - 2)
                    .background(Color(hex: "#FEF3C7"), in: Capsule())
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .panel(padding: Spacing.m, fill: Theme.warningSoft, stroke: Color(hex: "#FCD34D"))
    }
}

private struct SubjectBar: View {
    let row: StatsScreen.SubjectRow

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                SubjectDot(color: row.subject.color)
                Text(row.subject.name).font(.cardTitle).foregroundStyle(Theme.text)
                Spacer(minLength: Spacing.xs)
                Text("\(row.rate) %").font(.subheadline.weight(.bold).monospacedDigit()).foregroundStyle(Theme.violetDark)
            }
            ProgressBar(percent: row.rate, height: 6, tint: Color(hex: row.subject.color), track: Color(hex: "#F1F5F9"))
            HStack(alignment: .firstTextBaseline) {
                Text(row.line).font(.footnote).foregroundStyle(Theme.textSecondary)
                Spacer(minLength: Spacing.xs)
                if let average = row.avgScore {
                    Text("Moyenne : \(average)/100").font(.footnote.weight(.semibold))
                        .foregroundStyle(row.averageTone == .good ? Theme.success : Theme.warning)
                }
            }
        }
        .padding(Spacing.m)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.accessibilityLabel)
    }
}
