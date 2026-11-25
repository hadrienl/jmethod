import QuickLook
import SwiftUI

/// Cahier de textes en lecture seule (contenus de séance, devoirs, pièces jointes et liens), ouvert depuis Mes cours
/// ou la fiche d'un cours. Rien n'est modifié dans Pronote. Chaque séance peut devenir un cours (« Créer un cours »,
/// « Voir le cours »). Les liens s'ouvrent dans le navigateur intégré, les pièces jointes sont téléchargées dans le
/// cache privé puis présentées par QuickLook.
struct NotebookView: View {
    let app: AppModel
    @Bindable var model: NotebookModel
    let importer: NotebookCourseImporter
    @State private var webLink: WebLink?
    @State private var search = ""
    @State private var filter: HomeworkFilter = .todo
    @State private var upcoming = false
    @State private var editor: PersonalHomework?
    @State private var pendingDelete: PersonalHomework?

    private var agendaScope: String? { model.agendaScope }
    private var followUp: AgendaFollowUp {
        agendaScope.flatMap { app.document.agendaFollowUp[$0] } ?? AgendaFollowUp()
    }
    private var visibleSnapshot: NotebookSnapshot? {
        model.snapshotScope == agendaScope ? model.snapshot : nil
    }

    private var keys: [String: Int] {
        Dictionary(grouping: visibleSnapshot?.homework ?? [], by: \.agendaKey).mapValues(\.count)
    }
    private func canTrack(_ item: NotebookHomework) -> Bool {
        let tag = item.agendaSourceTag
        return agendaScope != nil && model.snapshotScope == agendaScope && keys[item.agendaKey] == 1 &&
            tag != nil && followUp.cached[item.agendaKey]?.sourceTag == tag
    }
    private func done(_ item: NotebookHomework) -> Bool {
        (canTrack(item) ? followUp.localChecks[item.agendaKey] : nil) ?? (item.remoteDone == true)
    }
    private func statusMatches(_ done: Bool) -> Bool { filter == .all || (filter == .done) == done }

    private var personal: [PersonalHomework] {
        followUp.personal.filter { task in
            if let id = model.subjectId, id != task.subjectId { return false }
            if upcoming, let end = SchoolDay.adding(7, to: app.today), task.due > end { return false }
            let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = subjects.first { $0.id == task.subjectId }?.name ?? ""
            return statusMatches(task.done) && (query.isEmpty || task.description.localizedCaseInsensitiveContains(query) ||
                                                name.localizedCaseInsensitiveContains(query))
        }.sorted { $0.due < $1.due }
    }

    private struct HomeworkGroup: Identifiable {
        var date: String
        var items: [NotebookHomework]
        var id: String { date }
    }

    private enum HomeworkFilter: String, CaseIterable {
        case todo = "À faire", done = "Faits", all = "Tous"
    }

    private var subjects: [Subject] { app.document.subjects }
    private var subjectName: String? { model.subjectId.flatMap { id in subjects.first { $0.id == id }?.name } }

    private func matches(subject: String, date: String, text: String) -> Bool {
        if let id = model.subjectId, !NotebookModel.matches(subject, subjectId: id, subjects: subjects) { return false }
        if upcoming, let end = SchoolDay.adding(7, to: app.today), date > end { return false }
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty || subject.localizedCaseInsensitiveContains(query) || text.localizedCaseInsensitiveContains(query)
    }

    private var sessions: [NotebookSession] {
        (visibleSnapshot?.sessions ?? []).filter {
            matches(subject: $0.subject, date: $0.date, text: $0.contents.map(\.description).joined(separator: "\n"))
        }
    }

    private var homework: [NotebookHomework] {
        (visibleSnapshot?.homework ?? []).filter {
            matches(subject: $0.subject, date: $0.dueDate, text: $0.description) &&
                statusMatches(done($0))
        }
    }

    private var cached: [CachedHomework] {
        followUp.cached.values.filter {
            keys[$0.key] == nil && matches(subject: $0.subject, date: $0.due, text: $0.description) &&
                statusMatches(followUp.localChecks[$0.key] ?? ($0.remoteDone == true))
        }.sorted { $0.due < $1.due }
    }

    private var homeworkGroups: [HomeworkGroup] {
        let byDate = Dictionary(grouping: homework) { item in
            item.dueDate < app.today && !done(item) ? "" : item.dueDate
        }
        return byDate.keys.sorted().map { HomeworkGroup(date: $0, items: byDate[$0] ?? []) }
    }

    private var attachmentCount: Int {
        guard model.subjectId != nil else { return model.snapshot?.attachmentCount ?? 0 }
        return sessions.reduce(0) { $0 + $1.contents.reduce(0) { $0 + $1.attachments.count } } + homework.reduce(0) { $0 + $1.attachments.count }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Label(app.document.settings.autoCreatesCourses
                          ? "Lecture seule : rien n’est modifié dans Pronote. Tes séances deviennent des cours automatiquement."
                          : "Lecture seule : rien n’est modifié dans Pronote. Chaque séance peut devenir un cours.", systemImage: "lock")
                        .font(.footnote).foregroundStyle(Theme.textSecondary)
                    if let subjectName {
                        Button { model.subjectId = nil } label: {
                            HStack(spacing: Spacing.xs) {
                                Text(subjectName).font(.subheadline.weight(.semibold))
                                Image(systemName: "xmark").font(.caption.weight(.bold))
                            }
                            .foregroundStyle(Theme.violetDeep)
                            .padding(.horizontal, Spacing.m)
                            .frame(minHeight: 44)
                            .background(Theme.violetTrack, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Matière : \(subjectName)")
                        .accessibilityHint("Affiche toutes les matières")
                    }
                    if let error = model.error {
                        VStack(alignment: .leading, spacing: Spacing.s) {
                            Label(error, systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(Theme.danger)
                            Button("Réessayer") { model.refresh() }.buttonStyle(SecondaryButtonStyle())
                        }
                        .panel(padding: Spacing.m, fill: Theme.dangerSoft)
                    }
                    if model.loading {
                        HStack(spacing: Spacing.s) {
                            ProgressView()
                            Text("Lecture du cahier de textes…").font(.subheadline).foregroundStyle(Theme.textSecondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.l)
                        .accessibilityElement(children: .combine)
                    }
                    Picker("Affichage", selection: $model.tab) {
                        Text("Devoirs").tag(NotebookModel.Tab.homework)
                        Text("Contenus et documents").tag(NotebookModel.Tab.contents)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: model.tab) { _, _ in search = "" }
                    TextField("Rechercher", text: $search).textFieldStyle(.roundedBorder)
                    HStack {
                        Menu(subjectName ?? "Toutes les matières") {
                            Button("Toutes les matières") { model.subjectId = nil }
                            ForEach(subjects) { subject in
                                Button(subject.name) { model.subjectId = subject.id }
                            }
                        }
                        Spacer()
                        Menu(upcoming ? "Jusqu’à 7 jours" : "Toute la période lue") {
                            Button("Toute la période lue") { upcoming = false }
                            Button("Jusqu’à 7 jours") { upcoming = true }
                        }
                    }
                    if model.tab == .homework {
                        Picker("État", selection: $filter) {
                            ForEach(HomeworkFilter.allCases, id: \.self) { item in Text(item.rawValue).tag(item) }
                        }
                        .pickerStyle(.segmented)
                    }
                    if model.tab == .homework {
                        DisclosureGroup("Périodes Pronote chargées") {
                            let coverage = (followUp.coverage ?? [:]).values.sorted { $0.from < $1.from }
                            if coverage.isEmpty { Text("Aucune période complète enregistrée.") }
                            ForEach(coverage, id: \.from) { range in
                                Text("Du \(FrenchDate.format(range.from, "dd/MM/yyyy")) au \(FrenchDate.format(range.to, "dd/MM/yyyy")) · lu le \(FrenchDate.format(String(range.observedAt.prefix(10)), "dd/MM/yyyy"))")
                            }
                            if coverage.contains(where: { $0.from <= app.today && app.today <= $0.to }) {
                                Button("Charger la période précédente") {
                                    model.loadAgendaPeriod(previous: true, today: app.today, coverage: coverage)
                                }
                                .disabled(model.loading || agendaScope == nil)
                                Button("Charger la période suivante") {
                                    model.loadAgendaPeriod(previous: false, today: app.today, coverage: coverage)
                                }
                                .disabled(model.loading || agendaScope == nil)
                            } else {
                                Button("Charger la période courante") { model.refresh() }
                                    .disabled(model.loading || agendaScope == nil)
                            }
                        }
                    }
                    if model.tab == .homework {
                        Button("Ajouter un devoir personnel") {
                            editor = PersonalHomework(id: UUID().uuidString, subjectId: subjects.first?.id ?? "",
                                                      description: "", due: app.today)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(agendaScope == nil || subjects.isEmpty)
                        if !personal.isEmpty { Text("Devoirs personnels").font(.headline).accessibilityAddTraits(.isHeader) }
                        ForEach(personal) { task in
                            NotebookCard {
                                Text((subjects.first { $0.id == task.subjectId }?.name ?? "Matière supprimée") +
                                     " · " + (task.due < app.today && !task.done ? "En retard · " : "") + dayLabel(task.due))
                                    .font(.headline)
                                Text(task.description).textSelection(.enabled)
                                Toggle("Fait sur cet appareil", isOn: Binding(get: { task.done }, set: { checked in
                                    updateFollowUp { data in
                                        if let index = data.personal.firstIndex(where: { $0.id == task.id }) { data.personal[index].done = checked }
                                    }
                                }))
                                HStack {
                                    Button("Modifier") { editor = task }
                                    Spacer()
                                    Button("Supprimer", role: .destructive) { pendingDelete = task }
                                }
                            }
                        }
                    }
                    if model.tab == .homework && !cached.isEmpty {
                        Text("Devoirs connus · cache local").font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(cached) { item in
                            let checked = followUp.localChecks[item.key] ?? (item.remoteDone == true)
                            NotebookCard {
                                Text(item.subject + " · " + (!checked && item.due < app.today ? "En retard · " : "") + dayLabel(item.due))
                                    .font(.headline)
                                Text(item.description)
                                Text(item.unavailable ? "Source indisponible sur la dernière lecture"
                                     : "Dernière lecture : " + String(item.observedAt.prefix(10)))
                                    .font(.caption).foregroundStyle(Theme.textSecondary)
                                Text("Statut Pronote connu : " + (item.remoteDone.map { $0 ? "fait" : "à faire" } ?? "inconnu"))
                                    .font(.caption)
                                if !item.documentNames.isEmpty {
                                    Text("Documents à actualiser : " + item.documentNames.joined(separator: ", ")).font(.caption)
                                }
                                if !item.unavailable && item.sourceTag != nil {
                                    Toggle("Fait sur cet appareil", isOn: Binding(get: { checked }, set: { value in
                                        guard value != checked else { return }
                                        updateFollowUp { $0.localChecks[item.key] = value }
                                    }))
                                }
                            }
                        }
                    }
                    if let snapshot = visibleSnapshot {
                        summary(snapshot)
                        if model.tab == .contents {
                            if sessions.isEmpty { empty("Aucun contenu de cours publié sur cette période.") }
                            ForEach(sessions) { session in
                                SessionCard(session: session, model: model, openLink: open,
                                            state: NotebookCourses.state(of: session, imports: app.document.notebookImports, courses: app.document.courses),
                                            preparing: importer.preparingSessionId == session.id,
                                            busy: importer.preparingSessionId != nil || model.readSession == nil,
                                            onCreate: { if let read = model.readSession { importer.prepare(session, readSession: read) } },
                                            onShow: { app.presented = .course(courseId: $0) })
                            }
                        } else {
                            if homework.isEmpty && personal.isEmpty && cached.isEmpty && snapshot.homeworkComplete && !model.loading && model.error == nil {
                                empty("Aucun devoir ne correspond aux filtres de la période chargée.")
                            }
                            ForEach(homeworkGroups, id: \.date) { group in
                                Text(group.date.isEmpty ? "En retard" : dayLabel(group.date))
                                    .font(.headline).accessibilityAddTraits(.isHeader)
                                ForEach(Array(group.items.enumerated()), id: \.offset) {
                                    let item = $0.element
                                    HomeworkCard(item: item, model: model, openLink: open,
                                                 localCheck: canTrack(item) ? Binding(get: { done(item) }, set: { checked in
                                                     guard canTrack(item), done(item) != checked else { return }
                                                     updateFollowUp { $0.localChecks[item.agendaKey] = checked }
                                                 }) : nil)
                                }
                            }
                        }
                    }
                }
                .screenContent()
            }
            .background(Theme.background)
            .navigationTitle("Agenda")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
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
        .sheet(item: $webLink) { link in
            SafariView(url: link.url).ignoresSafeArea()
        }
        .sheet(item: $editor) { item in
            PersonalHomeworkEditor(item: item, subjects: subjects) { edited in
                updateFollowUp { data in
                    if let index = data.personal.firstIndex(where: { $0.id == edited.id }) {
                        var updated = edited
                        updated.done = data.personal[index].done
                        data.personal[index] = updated
                    } else {
                        data.personal.append(edited)
                    }
                }
            }
        }
        .confirmationDialog("Supprimer ce devoir personnel ?", isPresented: Binding(
            get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }
        ), titleVisibility: .visible) {
            Button("Supprimer", role: .destructive) {
                if let task = pendingDelete { updateFollowUp { $0.personal.removeAll { $0.id == task.id } } }
                pendingDelete = nil
            }
            Button("Annuler", role: .cancel) { pendingDelete = nil }
        }
        .onChange(of: agendaScope) { _, _ in
            editor = nil
            pendingDelete = nil
            model.scopeChanged()
            if visibleSnapshot == nil { model.refresh() }
        }
        .onAppear { if model.snapshot == nil { model.ensureRead() } }
    }

    private func updateFollowUp(_ change: (inout AgendaFollowUp) -> Void) {
        if let scope = agendaScope { app.updateAgenda(scope: scope, change) }
    }

    private func open(_ url: String) {
        // Navigateur intégré, sans les cookies de la session Pronote.
        if let target = URL(string: url), NotebookRules.validLinkURL(url) != nil { webLink = WebLink(url: target) }
    }

    private func summary(_ snapshot: NotebookSnapshot) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text("\(sessions.count) \(sessions.count > 1 ? "séances publiées" : "séance publiée") · \(homework.count) \(homework.count > 1 ? "devoirs" : "devoir") · \(attachmentCount) \(attachmentCount > 1 ? "pièces jointes et liens" : "pièce jointe ou lien")")
                .font(.subheadline.weight(.semibold))
            Text("Du \(FrenchDate.format(snapshot.from, "dd/MM/yyyy")) au \(FrenchDate.format(snapshot.to, "dd/MM/yyyy"))")
                .font(.footnote).foregroundStyle(Theme.textSecondary)
            if snapshot.skippedEntries > 0 {
                Text(snapshot.skippedEntries > 1
                     ? "\(snapshot.skippedEntries) entrées sans date exploitable n’ont pas été affichées."
                     : "1 entrée sans date exploitable n’a pas été affichée.")
                    .font(.footnote).foregroundStyle(Theme.warning)
            }
            if let refreshedAt = model.refreshedAt {
                Text("Lu le \(refreshedAt.formatted(.dateTime.day().month().hour().minute()))")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            if !snapshot.homeworkAvailable {
                Text("Le travail à faire n’est pas disponible pour ce compte.").font(.footnote).foregroundStyle(Theme.textSecondary)
            } else if !snapshot.homeworkComplete {
                Text("La liste des devoirs est incomplète. Les devoirs déjà connus sont conservés.")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
        }
        .surface()
        .accessibilityElement(children: .combine)
    }

    private func empty(_ text: String) -> some View {
        EmptyState(systemImage: "tray", title: "Rien pour l’instant", message: text)
    }
}

/// « Lundi 16 novembre »
private func dayLabel(_ day: String) -> String { FrenchDate.format(day, "EEEE d MMMM").capitalizedFirst }

private struct NotebookCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) { content }
            .surface()
    }
}

private struct SessionCard: View {
    let session: NotebookSession
    let model: NotebookModel
    let openLink: (String) -> Void
    let state: NotebookCourses.ImportState
    let preparing: Bool
    let busy: Bool
    let onCreate: () -> Void
    let onShow: (String) -> Void

    var body: some View {
        NotebookCard {
            HStack(alignment: .firstTextBaseline) {
                Text(session.subject).font(.cardTitle)
                Spacer(minLength: Spacing.xs)
                Text(dayLabel(session.date)).font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            ForEach(Array(session.contents.enumerated()), id: \.offset) { _, content in
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    if !content.category.isEmpty {
                        Tag(text: content.category)
                    }
                    Text(content.title.isEmpty ? "Séance publiée sans titre" : content.title).font(.body.weight(.semibold))
                    if !content.description.isEmpty {
                        Text(content.description).font(.subheadline).foregroundStyle(Theme.text).textSelection(.enabled)
                    }
                    LinksAndAttachments(links: content.links, attachments: content.attachments, model: model, openLink: openLink)
                }
                .padding(.top, Spacing.xxs)
            }
            Divider().padding(.vertical, Spacing.xxs)
            courseAction
        }
    }

    @ViewBuilder private var courseAction: some View {
        switch state {
        case .created(let courseId):
            HStack(spacing: Spacing.s) {
                Label("Cours créé", systemImage: "checkmark.circle.fill")
                    .font(.footnote.weight(.semibold)).foregroundStyle(Theme.success)
                Spacer(minLength: Spacing.xs)
                Button("Autre feuille", action: onCreate).disabled(busy)
                Button("Voir le cours") { onShow(courseId) }
                    .buttonStyle(TintedButtonStyle(fullWidth: false))
                    .accessibilityLabel("Voir le cours créé depuis cette séance")
            }
        case .none, .dismissed:
            Button(action: onCreate) {
                HStack(spacing: Spacing.s) {
                    if preparing { ProgressView() } else { Image(systemName: "plus.circle").accessibilityHidden(true) }
                    Text(preparing ? "Préparation du cours…" : "Créer un cours")
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(TintedButtonStyle())
            .disabled(busy)
            .accessibilityLabel(preparing ? "Préparation du cours en cours" : "Créer un cours à partir de cette séance")
            .accessibilityHint("Télécharge les documents, puis ouvre le formulaire prérempli")
        }
    }
}

private struct HomeworkCard: View {
    let item: NotebookHomework
    let model: NotebookModel
    let openLink: (String) -> Void
    let localCheck: Binding<Bool>?

    var body: some View {
        NotebookCard {
            HStack(alignment: .firstTextBaseline) {
                Text(item.subject).font(.cardTitle)
                Spacer(minLength: Spacing.xs)
                Text("Pour le \(FrenchDate.format(item.dueDate, "EEEE d MMMM"))").font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Text(item.description.isEmpty ? "Travail à faire" : item.description).font(.subheadline).textSelection(.enabled)
            Label(item.remoteDone.map { $0 ? "Statut Pronote : fait" : "Statut Pronote : à faire" } ?? "Statut Pronote : inconnu",
                  systemImage: item.remoteDone == true ? "checkmark.circle.fill" : "circle")
                .font(.caption.weight(.semibold))
                .foregroundStyle(item.remoteDone == true ? Theme.success : Theme.textSecondary)
            LinksAndAttachments(links: item.links, attachments: item.attachments, model: model, openLink: openLink)
            if let localCheck {
                Toggle("Fait sur cet appareil", isOn: localCheck)
                    .accessibilityHint("Ce suivi n’est pas transmis à Pronote.")
            }
        }
    }
}

private struct PersonalHomeworkEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State var item: PersonalHomework
    let subjects: [Subject]
    let onSave: (PersonalHomework) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Picker("Matière", selection: $item.subjectId) {
                    ForEach(subjects) { Text($0.name).tag($0.id) }
                }
                TextField("Consigne", text: $item.description, axis: .vertical)
                TextField("Échéance (AAAA-MM-JJ)", text: $item.due)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            .navigationTitle("Devoir personnel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Enregistrer") {
                        item.description = item.description.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(item)
                        dismiss()
                    }
                    .disabled(item.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              item.description.count > 4000 || SchoolDay.adding(0, to: item.due) != item.due ||
                              !subjects.contains { $0.id == item.subjectId })
                }
            }
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
                    HStack(spacing: Spacing.s) {
                        if model.downloadingId == attachment.id {
                            ProgressView()
                        } else {
                            Image(systemName: "doc").accessibilityHidden(true)
                        }
                        Text(attachment.name).lineLimit(2).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                }
                .buttonStyle(TintedButtonStyle())
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
