import SwiftUI

@MainActor
final class ConnectionModel: ObservableObject {
    @Published var schoolURL = ""
    @Published var phase: ConnectionPhase = .idle
    @Published var error: String?
    @Published var account: PronoteAccount?
    @Published var lessons: [PronoteLesson] = []
    @Published var webActive = false
    @Published var busy = false
    @Published var selectedChild = ""
    @Published var hasSaved = false
    @Published var logs: [ConnectionLogEntry] = []
    let web = PronoteWebFlow()
    private var client: PronoteClient?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var publicDemo = false
    init() {
        hasSaved = (try? SecureStore.load()) != nil
        web.onPhase = { [weak self] in self?.setPhase($0) }
        web.onError = { [weak self] in self?.busy = false; self?.error = $0; self?.setPhase(.failed) }
        web.onRejected = { [weak self] in self?.busy = false; self?.webActive = false; self?.error = $0; self?.setPhase(.failed) }
        web.onQR = { [weak self] qr, pin in
            guard let self else { return }
            do { self.connect(try PronoteClient.credentials(qr: qr, pin: pin), mode: .qr) }
            catch { self.fail(error) }
        }
    }
    func setPhase(_ phase: ConnectionPhase) { self.phase = phase; logs.append(.init(phase: phase)); if logs.count > 12 { logs.removeFirst() } }
    /// Connexion depuis le formulaire natif : la page officielle reste masquée et reçoit les identifiants une seule fois.
    func signIn(username: String, password: String) {
        open(demo: false, username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
    }
    func open(demo: Bool = false, username: String? = nil, password: String? = nil) {
        cancel()
        error = nil; setPhase(.opening)
        do {
            if !demo {
                guard !schoolURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw PronoteFailure.invalid("url", "Indique l’adresse de ton espace Pronote.") }
                schoolURL = PronoteURL.normalized(schoolURL)
            }
            let url = try PronoteURL.validated(demo ? "https://demo.index-education.net/pronote/eleve.html" : schoolURL)
            guard ["eleve.html", "parent.html"].contains(url.lastPathComponent.lowercased()) else { throw PronoteFailure.invalid("url", "Utilise l’adresse web élève ou parent, sans mobile.") }
            publicDemo = demo; webActive = true; error = nil
            busy = username != nil
            web.start(url: url, demo: demo, username: username, password: password)
        } catch { fail(error) }
    }
    func nativeDemo() {
        cancel(); publicDemo = true
        connect(.init(url: "https://demo.index-education.net/pronote/mobile.eleve.html?fd=1", username: "demonstration", password: "pronotevs", uuid: UUID().uuidString, clientIdentifier: nil), mode: .demo)
    }
    func restore() {
        do { guard let credentials = try SecureStore.load() else { return }; publicDemo = false; connect(credentials, mode: .token) } catch { fail(error) }
    }
    func importQR(_ data: Data, pin: String) {
        do { publicDemo = false; connect(try PronoteClient.credentials(qr: PronoteWebFlow.decode(image: data), pin: pin), mode: .qr) } catch { fail(error) }
    }
    func connect(_ credentials: PronoteCredentials, mode: PronoteClient.LoginMode) {
        task?.cancel()
        let attempt = UUID(); generation = attempt
        busy = true; error = nil; setPhase(.nativeAuthentication)
        task = Task {
            do {
                let client = try PronoteClient(credentials: credentials)
                let save = !publicDemo
                let account = try await client.login(mode: mode, persist: { credentials in
                    if save { try await MainActor.run {
                        guard self.generation == attempt, !Task.isCancelled else { throw CancellationError() }
                        try SecureStore.save(credentials); self.hasSaved = true
                    } }
                })
                try Task.checkCancellation()
                guard generation == attempt else { return }
                if !publicDemo { try SecureStore.save(await client.credentialSnapshot()); hasSaved = true }
                self.client = client; self.account = account; selectedChild = account.selectedChildId ?? ""
                setPhase(.synchronizing)
                let lessons = try await client.timetable(days: 14)
                try Task.checkCancellation()
                guard generation == attempt else { return }
                self.lessons = lessons; busy = false; webActive = false; web.cancel(); setPhase(.ready)
            } catch {
                guard generation == attempt, !Task.isCancelled else { return }
                busy = false; web.visible = true; fail(error)
            }
        }
    }
    func refresh() {
        guard let client else { return }
        task?.cancel(); busy = true; error = nil; setPhase(.synchronizing)
        let attempt = generation
        task = Task {
            do {
                let lessons = try await client.timetable(days: 14, childId: selectedChild.isEmpty ? nil : selectedChild)
                try Task.checkCancellation(); guard generation == attempt else { return }
                self.lessons = lessons; busy = false; setPhase(.ready)
            } catch { guard generation == attempt, !Task.isCancelled else { return }; busy = false; fail(error) }
        }
    }
    func cancel() { generation = UUID(); task?.cancel(); task = nil; web.cancel(); webActive = false; busy = false }
    func forget() { cancel(); SecureStore.remove(); hasSaved = false; account = nil; client = nil; lessons = []; selectedChild = ""; error = nil; logs = []; setPhase(.idle)
        let store = web.webView.configuration.websiteDataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }
    private func fail(_ failure: Error) { error = (failure as? PronoteFailure)?.message ?? "La connexion n’a pas abouti. Vérifie le réseau ou reconnecte-toi dans Pronote."; setPhase(.failed) }
}
import WebKit
