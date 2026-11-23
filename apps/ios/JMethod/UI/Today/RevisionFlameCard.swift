import SwiftUI

/// Présentation statique : état et compteur accessibles sans couleur ni animation.
struct RevisionFlameCard: View {
    let summary: RevisionStreak.Summary
    var body: some View {
        HStack(alignment: .center, spacing: Spacing.m) {
            Image(systemName: summary.status == "earned" ? "flame.fill" : "flame")
                .font(.system(size: 36)).foregroundStyle(summary.status == "earned" ? Theme.warning : Theme.textMuted)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text("\(RevisionStreak.days(summary.count)) de révision").font(.headline)
                Text(summary.title).font(.subheadline.weight(.semibold))
                Text(summary.message).font(.footnote).foregroundStyle(Theme.textSecondary)
                Text("Record : \(RevisionStreak.days(summary.record))").font(.footnote).foregroundStyle(Theme.textSecondary)
                Text("La flamme suit le programme de ta première ouverture du jour.").font(.caption).foregroundStyle(Theme.textMuted)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .surface()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(RevisionStreak.days(summary.count)) de révision. \(summary.title). \(summary.message) Record : \(RevisionStreak.days(summary.record)).")
    }
}
