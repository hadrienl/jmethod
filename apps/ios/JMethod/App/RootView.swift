import SwiftUI

struct RootView: View {
    let app: AppModel
    let pronote: PronoteSession
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab: Tab = .today

    enum Tab: Hashable { case today, settings }

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
        .onChange(of: scenePhase) { _, phase in if phase == .active { app.refreshDay() } }
    }

    @ViewBuilder private var content: some View {
        switch app.loadState {
        case .loading:
            ProgressView("Chargement de tes révisions")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
        case .failed(let message):
            LoadFailureView(app: app, message: message)
        case .ready:
            TabView(selection: $tab) {
                TodayView(app: app)
                    .tabItem { Label("Aujourd’hui", systemImage: "checklist") }
                    .badge(app.programme.due.count)
                    .tag(Tab.today)
                SettingsView(app: app, pronote: pronote)
                    .tabItem { Label("Réglages", systemImage: "gearshape") }
                    .badge(pronote.errorMessage == nil ? 0 : 1)
                    .tag(Tab.settings)
            }
        }
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
            Text("Le fichier illisible est conservé à part sur l’iPhone, et l’app recharge les données de démonstration.")
        }
    }
}

/// Page officielle Pronote : affichée en plein écran à la demande, sinon conservée derrière le contenu.
private struct OfficialPageLayer: View {
    @Bindable var pronote: PronoteSession

    private var shown: Bool { pronote.webActive && pronote.webVisible }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Text("Page officielle Pronote").font(.headline)
                    Spacer()
                    Button("Masquer") { pronote.webVisible = false }
                        .frame(minHeight: 44)
                    Button("Fermer") { pronote.cancel() }
                        .frame(minHeight: 44)
                }
                if let message = pronote.errorMessage {
                    Label(message, systemImage: "info.circle").font(.footnote).foregroundStyle(Theme.text)
                }
                Button("Continuer la connexion automatique") { pronote.continueOnVisiblePage() }
                    .buttonStyle(SecondaryButtonStyle())
                    .accessibilityHint("Après une vérification terminée dans la page, prépare la connexion de l’iPhone.")
            }
            .padding()
            .background(.regularMaterial)
            OfficialWebView(webView: pronote.web.webView)
        }
        .background(Theme.surface)
        .opacity(shown ? 1 : 0.001)
        .allowsHitTesting(shown)
        .accessibilityHidden(!shown)
        .zIndex(shown ? 10 : -1)
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
