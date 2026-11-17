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
                VStack(alignment: .leading, spacing: 16) {
                    Text("Ta régularité, tes scores et ce qui reste à consolider.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    let columns = Array(repeating: GridItem(.flexible(), spacing: 10, alignment: .top), count: typeSize.isAccessibilitySize ? 1 : 2)
                    LazyVGrid(columns: columns, spacing: 10) {
                        Metric(title: "Taux de réalisation", value: "\(model.completionRate) %", line: model.completionLine,
                               color: Theme.text, icon: "checkmark.circle")
                        Metric(title: "Score moyen", value: model.overallAvgScore.map { "\($0)/100" } ?? model.overallAvgLabel, line: model.balanceLabel,
                               color: Theme.scoreColor(.sky), icon: "rosette",
                               spoken: model.overallAvgScore.map { "Score moyen : \($0) sur 100" } ?? "Score moyen : pas encore de note")
                        Metric(title: "Régularité", value: model.streakLabel, line: model.streakLine, color: Color(hex: "#9A3412"), icon: "flame",
                               spoken: "Régularité : \(model.streakDays) \(model.streakDays > 1 ? "jours consécutifs" : "jour")")
                        Metric(title: "Cours suivis", value: "\(model.coursesCount)", line: model.subjectsLine, color: Color(hex: "#4338CA"), icon: "books.vertical")
                    }

                    if !model.weakSubjects.isEmpty {
                        WeakSubjects(rows: model.weakSubjects)
                    }

                    Text("Par matière").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader).padding(.top, 4)
                    if model.subjects.isEmpty {
                        Text("Tes progrès par matière apparaîtront ici dès que tu auras ajouté un cours.")
                            .font(.subheadline).foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .surface()
                    } else {
                        ForEach(model.subjects) { SubjectBar(row: $0) }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
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
    let color: Color
    let icon: String
    var spoken: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                Text(title.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(Theme.textSecondary)
                Spacer(minLength: 4)
                Image(systemName: icon).font(.caption).foregroundStyle(color).accessibilityHidden(true)
            }
            Text(value).font(.system(.title, design: .rounded).weight(.black)).foregroundStyle(color)
                .minimumScaleFactor(0.7).lineLimit(1)
            Text(line).font(.caption).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.border))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(spoken ?? "\(title) : \(value)"). \(line)")
    }
}

private struct WeakSubjects: View {
    let rows: [StatsScreen.SubjectRow]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Matières à retravailler", systemImage: "exclamationmark.triangle")
                .font(.headline).foregroundStyle(Theme.warning).accessibilityAddTraits(.isHeader)
            Text("Leur score moyen est sous l’équilibre (50/100). Les prochaines séances de ces matières sont déjà rapprochées pour t’aider.")
                .font(.subheadline).foregroundStyle(Theme.warning)
            FlowRow(spacing: 8) {
                ForEach(rows) { row in
                    HStack(spacing: 6) {
                        Text(row.subject.name).font(.subheadline.weight(.bold))
                        Text("(\(row.avgScore ?? 0)/100)").font(.subheadline.monospacedDigit()).foregroundStyle(Theme.text)
                    }
                    .foregroundStyle(Theme.warning)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color(hex: "#FEF3C7"), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.warningSoft, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Color(hex: "#FCD34D")))
    }
}

private struct SubjectBar: View {
    let row: StatsScreen.SubjectRow

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                SubjectDot(color: row.subject.color)
                Text(row.subject.name).font(.headline)
                Spacer(minLength: 8)
                Text("\(row.rate) %").font(.subheadline.weight(.bold).monospacedDigit()).foregroundStyle(Theme.violetDark)
            }
            HStack(alignment: .firstTextBaseline) {
                Text(row.line).font(.caption).foregroundStyle(Theme.textSecondary)
                Spacer(minLength: 8)
                if let average = row.avgScore {
                    Text("Moyenne : \(average)/100").font(.caption.weight(.bold))
                        .foregroundStyle(row.averageTone == .good ? Theme.success : Theme.warning)
                }
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(hex: "#F1F5F9"))
                    Capsule().fill(Color(hex: row.subject.color)).frame(width: proxy.size.width * CGFloat(row.rate) / 100)
                }
            }
            .frame(height: 8)
        }
        .padding(16)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.border))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.accessibilityLabel)
    }
}
