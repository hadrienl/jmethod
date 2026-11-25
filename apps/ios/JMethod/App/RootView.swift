import SwiftUI

struct RootView: View {
    let app: AppModel
    let pronote: PronoteSession
    let notebook: NotebookModel
    let importer: NotebookCourseImporter
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: Tab = .today
    @State private var lastAgendaScope: String?

    enum Tab: Hashable { case today, agenda, planning, courses, stats, settings }

    private var route: AppRoute { AppRoute.resolve(load: app.loadState, access: pronote.access) }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            // La page officielle reste dans la hiérarchie, avec une taille réelle, même masquée.
            OfficialPageLayer(pronote: pronote)
            content
            CelebrationLayer(celebration: app.celebration, confetti: app.confetti)
            ToastLayer(app: app)
        }
        .tint(Theme.violet)
        // Retour haptique léger quand une action aboutit (validation, cours créé, réglages enregistrés).
        .sensoryFeedback(trigger: app.toast) { _, toast in
            guard let toast else { return nil }
            return toast.kind == .warning ? .warning : .success
        }
        .task {
            await app.load()
            app.sceneVisible = scenePhase != .background
            app.openRevisionDay()
            #if DEBUG
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-JMethodPreviewSignIn") {
                pronote.enterSignInPreview()
                return
            }
            if arguments.contains("-JMethodOfflinePreview") || PreviewFixture.isEnabled {
                pronote.enterOfflinePreview()
                applyPreviewArguments()
                return
            }
            #endif
            if app.loadState == .ready { pronote.resumeAtLaunch() }
        }
        .task {
            // Le jour affiché suit l'horloge, même si l'app reste ouverte à minuit.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                app.refreshDay()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in app.refreshDay() }
        .onChange(of: scenePhase) { _, phase in
            app.sceneVisible = phase != .background
            switch phase {
            case .active:
                app.refreshDay()
                pronote.foregroundActivated()
            case .background:
                if pronote.hasSavedConnection { BackgroundRefresh.schedule() }
            default:
                break
            }
        }
        .onChange(of: notebook.agendaScope, initial: true) { _, scope in
            if let scope { lastAgendaScope = scope }
        }
        .onChange(of: pronote.access) { old, new in
            // Après une connexion réussie, l'app s'ouvre sur Aujourd'hui.
            if new == .connected && old != .connected { tab = .today }
            // Retour à l'écran de connexion : aucune feuille ne reste ouverte.
            if new == .signedOut {
                app.presented = nil
                if let scope = lastAgendaScope {
                    app.updateAgenda(scope: scope) { $0 = AgendaFollowUp(personal: $0.personal) }
                    lastAgendaScope = nil
                }
            }
        }
        .onChange(of: app.presented) { _, sheet in
            if case .notebook(let subject) = sheet {
                app.presented = nil
                notebook.open(subjectId: subject)
                tab = .agenda
            }
        }
        .onChange(of: pronote.notebookScope) { _, _ in notebook.scopeChanged() }
        .onChange(of: app.document.settings.lastPronoteSync) { _, synced in
            // Création automatique des cours : après une synchronisation réussie au premier plan seulement.
            guard synced != nil, scenePhase == .active else { return }
            Task { await importer.afterForegroundSync() }
        }
    }

    #if DEBUG
    /// Vérification visuelle (Debug) : `-JMethodPreviewTab planning|courses|stats|settings`,
    /// `-JMethodPreviewSheet addCourse|course:<id>|revision:<id>|score:<id>|review:<id>|notebook[:<id>]` ;
    /// `-JMethodPreviewFixture` charge un document fictif (`PreviewFixture`), `-JMethodPreviewSignIn` l'écran de connexion vide.
    private func applyPreviewArguments() {
        let defaults = UserDefaults.standard
        switch defaults.string(forKey: "JMethodPreviewTab") {
        case "planning": tab = .planning
        case "courses": tab = .courses
        case "settings": tab = .settings
        case "stats": tab = .stats
        default: break
        }
        guard let sheet = defaults.string(forKey: "JMethodPreviewSheet") else { return }
        let parts = sheet.split(separator: ":", maxSplits: 1).map(String.init)
        switch (parts.first, parts.count > 1 ? parts[1] : "") {
        case ("addCourse", _): app.openAddCourse()
        case ("course", let id): app.presented = .course(courseId: id)
        case ("revision", let id): app.presented = .revision(revisionId: id)
        case ("score", let id): app.presented = .score(revisionId: id)
        case ("review", let id): app.presented = .review(revisionId: id)
        case ("notebook", let id):
            notebook.loadPreview(today: app.today)
            app.presented = .notebook(subjectId: id.isEmpty ? nil : id)
        default: break
        }
    }
    #endif

    @ViewBuilder private var content: some View {
        switch route {
        case .loading:
            // Même logo, à la même place, que l'écran de lancement : aucune rupture visuelle.
            LaunchLayout { ProgressView().accessibilityLabel("Chargement") }
        case .resuming:
            ResumingView(pronote: pronote)
        case .loadFailure(let message):
            LoadFailureView(app: app, message: message)
        case .signIn:
            SignInView(pronote: pronote)
        case .app(let offline):
            // Bandeau au-dessus des onglets (et non en marge de sécurité) : les barres de navigation restent visibles.
            VStack(spacing: 0) {
                if pronote.silentRelogin {
                    ReconnectingBanner()
                } else if offline {
                    OfflineBanner(pronote: pronote)
                }
                TabView(selection: $tab) {
                    TodayView(app: app, offline: offline, refreshing: pronote.phase == .synchronizing || pronote.silentRelogin,
                              onShowCourses: { tab = .courses })
                        .tabItem { Label("Aujourd’hui", systemImage: "checklist") }
                        .badge(app.programme.due.count)
                        .tag(Tab.today)
                    NotebookView(app: app, model: notebook, importer: importer)
                        .tabItem { Label("Agenda", systemImage: "book.closed") }
                        .badge(notebook.urgentCount(app.document, today: app.today))
                        .tag(Tab.agenda)
                    PlanningView(app: app)
                        .tabItem { Label("Planning", systemImage: "calendar") }
                        .tag(Tab.planning)
                    CoursesView(app: app, onShowPlanning: { tab = .planning })
                        .tabItem { Label { Text("Cours").accessibilityLabel("Mes cours") } icon: { Image(systemName: "books.vertical") } }
                        .tag(Tab.courses)
                    StatsView(app: app)
                        .tabItem { Label { Text("Progrès").accessibilityLabel("Mes progrès") } icon: { Image(systemName: "chart.bar") } }
                        .tag(Tab.stats)
                    SettingsView(app: app, pronote: pronote, importer: importer)
                        .tabItem { Label("Réglages", systemImage: "gearshape") }
                        .tag(Tab.settings)
                }
                .sheet(item: Binding(get: {
                    if case .notebook = app.presented { return nil }
                    return app.presented
                }, set: { app.presented = $0 })) { sheet in
                    switch sheet {
                    case .score(let id): RevisionSessionSheet(app: app, notebook: notebook, importer: importer, revisionId: id, start: .score)
                    case .revision(let id): RevisionSessionSheet(app: app, notebook: notebook, importer: importer, revisionId: id)
                    case .review(let id): RevisionSessionSheet(app: app, notebook: notebook, importer: importer, revisionId: id, start: .review)
                    case .course(let id): CourseDetailSheet(app: app, notebook: notebook, importer: importer, courseId: id)
                    case .addCourse: AddCourseSheet(app: app)
                    case .notebook: EmptyView()
                    }
                }
            }
        }
    }
}

/// Logo centré comme l'écran de lancement (`UILaunchScreen`), contenu en dessous.
private struct LaunchLayout<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            BrandMark(size: 96, showsName: false)
            content
                .padding(.horizontal, Spacing.l)
                .frame(maxWidth: 360)
                .offset(y: 96)
        }
    }
}

/// Reprise de la connexion enregistrée : écran sobre ; après quelques secondes, l'app peut s'ouvrir sans attendre.
private struct ResumingView: View {
    let pronote: PronoteSession
    @State private var allowSkip = false

    var body: some View {
        LaunchLayout {
            VStack(spacing: Spacing.m) {
                ProgressView("Connexion à Pronote…")
                    .font(.subheadline)
                    .foregroundStyle(Theme.textSecondary)
                if allowSkip {
                    Button("Ouvrir sans attendre") { pronote.openWithoutWaiting() }
                        .buttonStyle(SecondaryButtonStyle())
                        .transition(.opacity)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .animation(.easeOut(duration: 0.2), value: allowSkip)
        .task {
            try? await Task.sleep(for: .seconds(10))
            allowSkip = true
        }
    }
}

/// Reconnexion silencieuse avec les identifiants enregistrés : bandeau discret, sans écran de connexion.
private struct ReconnectingBanner: View {
    var body: some View {
        HStack(spacing: Spacing.s) {
            ProgressView()
            Text("Reconnexion à Pronote…").font(.subheadline).foregroundStyle(Theme.text)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .background(Theme.violetSoft.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityElement(children: .combine)
    }
}

/// Bandeau du mode hors ligne : données locales, connexion conservée, nouvel essai possible.
/// Compact : une ligne de titre et une ligne d'explication, l'explication disparaît aux très grandes tailles de texte.
private struct OfflineBanner: View {
    let pronote: PronoteSession
    @Environment(\.dynamicTypeSize) private var typeSize

    private var detail: String {
        pronote.isWorking ? "Connexion à Pronote…"
            : pronote.isBlocked ? PronoteThrottle.blockedMessage : "Tes données enregistrées restent disponibles."
    }

    var body: some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "wifi.slash").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.warning).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 0) {
                Text("Hors ligne").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                if !typeSize.isAccessibilitySize || pronote.isBlocked {
                    Text(detail).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(2)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Hors ligne. \(detail)")
            Spacer(minLength: Spacing.xs)
            if pronote.isWorking {
                ProgressView()
            } else {
                Button("Réessayer") { pronote.resumeSavedConnection() }
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .fixedSize()
                    .frame(minWidth: 44, minHeight: 44)
            }
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.xxs)
        .background(Theme.warningSoft.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) { Divider() }
        // Bandeau d'état, comme une barre système : sa taille de texte est plafonnée pour laisser la place au contenu.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}

private struct LoadFailureView: View {
    let app: AppModel
    let message: String
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.m) {
            IconTile(systemName: "exclamationmark.triangle", tint: Theme.warning, background: Theme.warningSoft, size: 56)
            Text("Tes données ne s’ouvrent pas").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(message).foregroundStyle(Theme.textSecondary)
            Text("Si l’iPhone vient de redémarrer, déverrouille-le puis réessaie. Rien n’a été effacé.")
                .font(.callout).foregroundStyle(Theme.textSecondary)
            Button("Réessayer") { Task { await app.load() } }.buttonStyle(PrimaryButtonStyle())
            Button("Repartir de zéro") { confirming = true }.buttonStyle(SecondaryButtonStyle())
        }
        .padding(Spacing.l)
        .frame(maxWidth: 560, maxHeight: .infinity)
        .frame(maxWidth: .infinity)
        .background(Theme.background)
        .confirmationDialog("Repartir de zéro ?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Mettre le fichier de côté et repartir", role: .destructive) { Task { await app.startOverAfterUnreadableFile() } }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Le fichier illisible est conservé à part sur l’iPhone, et l’app repart d’un document vide.")
        }
    }
}

/// Page officielle Pronote présentée en feuille quand elle est nécessaire (ENT, vérification de sécurité).
/// Masquée, elle reste dans la hiérarchie avec une taille réelle pour que la connexion continue.
private struct OfficialPageLayer: View {
    let pronote: PronoteSession

    private var shown: Bool { pronote.webActive && pronote.webVisible }

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(shown ? 0.35 : 0).ignoresSafeArea()
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        Text("Connexion à Pronote").font(.headline).accessibilityAddTraits(.isHeader)
                        Spacer()
                        Button("Annuler") { pronote.cancel() }
                            .font(.body.weight(.semibold))
                            .frame(minHeight: 44)
                    }
                    if let message = pronote.webMessage {
                        Text(message).font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .background(Theme.surface)
                Divider()
                if let web = pronote.web { OfficialWebView(webView: web.webView) }
            }
            .background(Theme.surface)
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: 20, topTrailingRadius: 20, style: .continuous))
            .padding(.top, 44)
            .ignoresSafeArea(edges: .bottom)
        }
        .opacity(shown ? 1 : 0.001)
        .allowsHitTesting(shown)
        .accessibilityHidden(!shown)
        .accessibilityAddTraits(shown ? .isModal : [])
        .zIndex(shown ? 10 : -1)
        .animation(.easeOut(duration: 0.2), value: shown)
    }
}

private struct ToastLayer: View {
    @Bindable var app: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack {
            if let toast = app.toast {
                HStack(alignment: .top, spacing: Spacing.s) {
                    Image(systemName: toast.kind == .warning ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(toast.kind == .warning ? Theme.warning : Theme.success)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: Spacing.xxs) {
                        Text(toast.title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                        Text(toast.message).font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(Spacing.m)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Theme.border))
                .shadow(color: Theme.violetDeep.opacity(0.12), radius: 16, y: 6)
                .padding(.horizontal, Spacing.m)
                .frame(maxWidth: 520)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Touche pour fermer")
                .onTapGesture { app.toast = nil }
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                .task(id: toast.id) {
                    AccessibilityNotification.Announcement("\(toast.title). \(toast.message)").post()
                    try? await Task.sleep(for: .seconds(UIAccessibility.isVoiceOverRunning ? 9 : 4.5))
                    if app.toast?.id == toast.id { app.toast = nil }
                }
            }
            Spacer()
        }
        .padding(.top, Spacing.xs)
        .animation(.easeOut(duration: reduceMotion ? 0.15 : 0.25), value: app.toast)
        .zIndex(20)
    }
}
