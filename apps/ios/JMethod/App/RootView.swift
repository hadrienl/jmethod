import SwiftUI

struct RootView: View {
    let app: AppModel
    let pronote: PronoteSession
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: Tab = .today

    enum Tab: Hashable { case today, settings }

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
        .task {
            await app.load()
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
        .onChange(of: pronote.access) { old, new in
            // Après une connexion réussie, l'app s'ouvre sur Aujourd'hui.
            if new == .connected && old != .connected { tab = .today }
        }
    }

    @ViewBuilder private var content: some View {
        switch route {
        case .loading:
            ProgressView("Chargement")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
        case .resuming:
            ResumingView(pronote: pronote)
        case .loadFailure(let message):
            LoadFailureView(app: app, message: message)
        case .signIn:
            SignInView(pronote: pronote)
        case .app(let offline):
            TabView(selection: $tab) {
                TodayView(app: app)
                    .tabItem { Label("Aujourd’hui", systemImage: "checklist") }
                    .badge(app.programme.due.count)
                    .tag(Tab.today)
                SettingsView(app: app, pronote: pronote)
                    .tabItem { Label("Réglages", systemImage: "gearshape") }
                    .tag(Tab.settings)
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if pronote.silentRelogin {
                    ReconnectingBanner()
                } else if offline {
                    OfflineBanner(pronote: pronote)
                }
            }
        }
    }
}

/// Reprise de la connexion enregistrée : écran sobre ; après quelques secondes, l'app peut s'ouvrir sans attendre.
private struct ResumingView: View {
    let pronote: PronoteSession
    @State private var allowSkip = false

    var body: some View {
        VStack(spacing: 20) {
            BrandMark()
            ProgressView("Connexion à Pronote…")
            if allowSkip {
                Button("Ouvrir sans attendre") { pronote.openWithoutWaiting() }
                    .buttonStyle(SecondaryButtonStyle())
                    .frame(maxWidth: 280)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
        .task {
            try? await Task.sleep(for: .seconds(10))
            allowSkip = true
        }
    }
}

/// Reconnexion silencieuse avec les identifiants enregistrés : bandeau discret, sans écran de connexion.
private struct ReconnectingBanner: View {
    var body: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text("Reconnexion à Pronote…").font(.subheadline)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Theme.violetSoft)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityElement(children: .combine)
    }
}

/// Bandeau du mode hors ligne : données locales, connexion conservée, nouvel essai possible.
private struct OfflineBanner: View {
    let pronote: PronoteSession

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "wifi.slash").foregroundStyle(Theme.warning).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Hors ligne").font(.subheadline.bold())
                Text(pronote.isWorking ? "Connexion à Pronote…" : "Tes données enregistrées restent disponibles.")
                    .font(.caption).foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            if pronote.isWorking {
                ProgressView()
            } else {
                Button("Réessayer") { pronote.resumeSavedConnection() }
                    .font(.subheadline.bold())
                    .frame(minWidth: 44, minHeight: 44)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Theme.warningSoft)
        .overlay(alignment: .bottom) { Divider() }
    }
}

private struct LoadFailureView: View {
    let app: AppModel
    let message: String
    @State private var confirming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "exclamationmark.triangle").font(.largeTitle).foregroundStyle(Theme.warning).accessibilityHidden(true)
            Text("Tes données ne s’ouvrent pas").font(.title2.bold())
            Text(message).foregroundStyle(Theme.textSecondary)
            Text("Si l’iPhone vient de redémarrer, déverrouille-le puis réessaie. Rien n’a été effacé.")
                .font(.callout).foregroundStyle(Theme.textSecondary)
            Button("Réessayer") { Task { await app.load() } }.buttonStyle(PrimaryButtonStyle())
            Button("Repartir de zéro") { confirming = true }.buttonStyle(SecondaryButtonStyle())
        }
        .padding(24)
        .frame(maxWidth: 560, maxHeight: .infinity)
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

    var body: some View {
        VStack {
            if let toast = app.toast {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: toast.kind == .warning ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                        .foregroundStyle(toast.kind == .warning ? Theme.warning : Theme.success)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(toast.title).font(.subheadline.bold())
                        Text(toast.message).font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.border))
                .shadow(color: .black.opacity(0.12), radius: 16, y: 6)
                .padding(.horizontal, 16)
                .frame(maxWidth: 520)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Touche pour fermer")
                .onTapGesture { app.toast = nil }
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: toast.id) {
                    AccessibilityNotification.Announcement("\(toast.title). \(toast.message)").post()
                    try? await Task.sleep(for: .seconds(UIAccessibility.isVoiceOverRunning ? 9 : 4.5))
                    if app.toast?.id == toast.id { app.toast = nil }
                }
            }
            Spacer()
        }
        .padding(.top, 8)
        .animation(.easeOut(duration: 0.2), value: app.toast)
        .zIndex(20)
    }
}
