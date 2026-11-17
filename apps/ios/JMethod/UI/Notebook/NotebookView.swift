import QuickLook
import SwiftUI

/// Cahier de textes en lecture seule (contenus de séance, devoirs, pièces jointes et liens), ouvert depuis Mes cours
/// ou la fiche d'un cours. Rien n'est modifié dans Pronote. Les liens s'ouvrent dans le navigateur, les pièces jointes
/// sont téléchargées dans le cache privé puis présentées par QuickLook.
struct NotebookView: View {
    let app: AppModel
    @Bindable var model: NotebookModel
    let subjectId: String?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    private var subjects: [Subject] { app.document.subjects }
    private var subjectName: String? { model.subjectId.flatMap { id in subjects.first { $0.id == id }?.name } }

    private var sessions: [NotebookSession] {
        (model.snapshot?.sessions ?? []).filter { model.subjectId == nil || NotebookModel.matches($0.subject, subjectId: model.subjectId!, subjects: subjects) }
    }

    private var homework: [NotebookHomework] {
        (model.snapshot?.homework ?? []).filter { model.subjectId == nil || NotebookModel.matches($0.subject, subjectId: model.subjectId!, subjects: subjects) }
    }

    private var attachmentCount: Int {
        guard model.subjectId != nil else { return model.snapshot?.attachmentCount ?? 0 }
        return sessions.reduce(0) { $0 + $1.contents.reduce(0) { $0 + $1.attachments.count } } + homework.reduce(0) { $0 + $1.attachments.count }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Contenus de séance, devoirs et pièces jointes publiés dans Pronote, en lecture seule. Rien n’est modifié dans Pronote et aucun cours n’est créé automatiquement.")
                        .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    if let subjectName {
                        Button { model.subjectId = nil } label: {
                            HStack(spacing: 6) {
                                Text(subjectName).font(.subheadline.weight(.semibold))
                                Image(systemName: "xmark").font(.caption.weight(.bold))
                            }
                            .foregroundStyle(Theme.violetDeep)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Theme.violetTrack, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Matière : \(subjectName)")
                        .accessibilityHint("Affiche toutes les matières")
                    }
                    if let error = model.error {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(error).font(.subheadline).foregroundStyle(Theme.danger)
                            Button("Réessayer") { model.refresh() }.buttonStyle(SecondaryButtonStyle())
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.dangerSoft, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    if model.loading {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Lecture du cahier de textes…").font(.subheadline)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if let snapshot = model.snapshot {
                        summary(snapshot)
                        Picker("Affichage", selection: $model.tab) {
                            Text("Cours publiés (\(sessions.count))").tag(NotebookModel.Tab.contents)
                            Text("Devoirs (\(homework.count))").tag(NotebookModel.Tab.homework)
                        }
                        .pickerStyle(.segmented)
                        if model.tab == .contents {
                            if sessions.isEmpty { empty("Aucun contenu de cours publié sur cette période.") }
                            ForEach(sessions) { SessionCard(session: $0, model: model, openLink: open) }
                        } else {
                            if homework.isEmpty { empty("Aucun devoir publié sur cette période.") }
                            ForEach(homework) { HomeworkCard(item: $0, model: model, openLink: open) }
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.background)
            .navigationTitle("Cahier de textes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Fermer") { dismiss() }.accessibilityLabel("Fermer le cahier de textes")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button { model.refresh() } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(model.loading)
                        .accessibilityLabel("Actualiser le cahier de textes")
                }
            }
        }
        .quickLookPreview($model.preview)
        .alert("Cahier de textes", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) { model.message = nil }
        } message: {
            Text(model.message ?? "")
        }
        .onAppear { model.open(subjectId: subjectId) }
    }

    private func open(_ url: String) {
        // Navigateur externe, sans les cookies de la session Pronote.
        if let target = URL(string: url), NotebookRules.validLinkURL(url) != nil { openURL(target) }
    }

    private func summary(_ snapshot: NotebookSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(sessions.count) \(sessions.count > 1 ? "séances publiées" : "séance publiée") · \(homework.count) \(homework.count > 1 ? "devoirs" : "devoir") · \(attachmentCount) \(attachmentCount > 1 ? "pièces jointes et liens" : "pièce jointe ou lien")")
                .font(.subheadline.weight(.bold))
            Text("Du \(FrenchDate.format(snapshot.from, "dd/MM/yyyy")) au \(FrenchDate.format(snapshot.to, "dd/MM/yyyy"))")
                .font(.caption).foregroundStyle(Theme.textSecondary)
            if snapshot.skippedEntries > 0 {
                Text(snapshot.skippedEntries > 1
                     ? "\(snapshot.skippedEntries) entrées sans date exploitable n’ont pas été affichées."
                     : "1 entrée sans date exploitable n’a pas été affichée.")
                    .font(.caption).foregroundStyle(Theme.warning)
            }
            if !snapshot.homeworkAvailable {
                Text("Le travail à faire n’est pas disponible pour ce compte.").font(.caption).foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.border))
        .accessibilityElement(children: .combine)
    }

    private func empty(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(24)
    }
}

/// « Lundi 16 novembre »
private func dayLabel(_ day: String) -> String { FrenchDate.format(day, "EEEE d MMMM").capitalizedFirst }

private struct NotebookCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.border))
    }
}

private struct SessionCard: View {
    let session: NotebookSession
    let model: NotebookModel
    let openLink: (String) -> Void

    var body: some View {
        NotebookCard {
            HStack(alignment: .firstTextBaseline) {
                Text(session.subject).font(.headline)
                Spacer(minLength: 8)
                Text(dayLabel(session.date)).font(.caption).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            ForEach(Array(session.contents.enumerated()), id: \.offset) { _, content in
                VStack(alignment: .leading, spacing: 6) {
                    if !content.category.isEmpty {
                        Text(content.category).font(.caption.weight(.semibold)).foregroundStyle(Theme.violetDark)
                    }
                    Text(content.title.isEmpty ? "Séance publiée sans titre" : content.title).font(.body.weight(.semibold))
                    if !content.description.isEmpty {
                        Text(content.description).font(.subheadline).foregroundStyle(Theme.text).textSelection(.enabled)
                    }
                    LinksAndAttachments(links: content.links, attachments: content.attachments, model: model, openLink: openLink)
                }
                .padding(.top, 4)
            }
        }
    }
}

private struct HomeworkCard: View {
    let item: NotebookHomework
    let model: NotebookModel
    let openLink: (String) -> Void

    var body: some View {
        NotebookCard {
            HStack(alignment: .firstTextBaseline) {
                Text(item.subject).font(.headline)
                Spacer(minLength: 8)
                Text("Pour le \(FrenchDate.format(item.dueDate, "EEEE d MMMM"))").font(.caption).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Text(item.description.isEmpty ? "Travail à faire" : item.description).font(.subheadline).textSelection(.enabled)
            Label(item.done ? "Statut Pronote : fait" : "Statut Pronote : à faire", systemImage: item.done ? "checkmark.circle.fill" : "circle")
                .font(.caption.weight(.semibold))
                .foregroundStyle(item.done ? Theme.success : Theme.textSecondary)
            LinksAndAttachments(links: item.links, attachments: item.attachments, model: model, openLink: openLink)
        }
    }
}

private struct LinksAndAttachments: View {
    let links: [NotebookLink]
    let attachments: [NotebookAttachment]
    let model: NotebookModel
    let openLink: (String) -> Void

    var body: some View {
        ForEach(Array(links.enumerated()), id: \.offset) { _, link in
            linkButton(link.label, url: link.url)
        }
        ForEach(attachments) { attachment in
            switch attachment.kind {
            case .link:
                if let url = attachment.url { linkButton(attachment.name, url: url) }
            case .file:
                Button { model.download(attachment) } label: {
                    HStack(spacing: 10) {
                        if model.downloadingId == attachment.id {
                            ProgressView()
                        } else {
                            Image(systemName: "doc").accessibilityHidden(true)
                        }
                        Text(attachment.name).lineLimit(2).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.violetDark)
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .background(Theme.violetSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(model.downloadingId != nil)
                .accessibilityLabel("Ouvrir la pièce jointe : \(attachment.name)")
            }
        }
    }

    private func linkButton(_ label: String, url: String) -> some View {
        Button { openLink(url) } label: {
            Label("Lien : \(label)", systemImage: "arrow.up.right.square")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.violetDark)
                .multilineTextAlignment(.leading)
                .frame(minHeight: 44, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Ouvrir le lien dans le navigateur : \(label)")
    }
}
