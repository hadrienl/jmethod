import SwiftUI
import WebKit
import CoreImage

@MainActor
final class PronoteWebFlow: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published var visible = true
    let webView: WKWebView
    var onPhase: ((ConnectionPhase) -> Void)?
    var onQR: ((PronoteQRData, String) -> Void)?
    var onError: ((String) -> Void)?
    /// Identifiants refusés par Pronote : le formulaire natif reprend la main, la page reste masquée.
    var onRejected: ((String) -> Void)?
    private var origin = ""
    private var nonce = ""
    private var pin = ""
    private var demo = false
    private var claimed = false
    private var timeout: Task<Void, Never>?
    /// Identifiant et mot de passe saisis dans le formulaire natif : transmis à la première injection uniquement, puis effacés.
    private var pendingLogin: (username: String, password: String)?
    private(set) var nativeLogin = false
    override init() {
        let config = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: config)
        super.init()
        webView.navigationDelegate = self
        webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        config.userContentController.add(self, name: "jmethodPronote")
    }
    func start(url: URL, demo: Bool = false, username: String? = nil, password: String? = nil) {
        cancel()
        self.demo = demo
        if let username, let password, !username.isEmpty, !password.isEmpty { pendingLogin = (username, password); nativeLogin = true } else { nativeLogin = false }
        origin = PronoteURL.origin(url)
        nonce = UUID().uuidString
        pin = String(format: "%04d", Int.random(in: 0...9999))
        claimed = false
        visible = !nativeLogin
        onPhase?(nativeLogin ? .signingIn : .login)
        webView.load(URLRequest(url: url))
        let limit = nativeLogin ? 90 : 600
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(limit))
            guard !Task.isCancelled else { return }
            self?.recover("La connexion a expiré. Relance la connexion lorsque tu es prêt.")
        }
    }
    func cancel() {
        timeout?.cancel(); timeout = nil
        pendingLogin = nil
        claimed = true
        nonce = UUID().uuidString
        webView.evaluateJavaScript("window.__JM_PRONOTE_POC?.stop()", completionHandler: nil)
        webView.stopLoading()
    }
    private func trusted(_ url: URL?) -> Bool {
        guard let url, PronoteURL.origin(url) == origin else { return false }
        return ["eleve.html", "parent.html"].contains(url.lastPathComponent.lowercased())
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if !claimed, nativeLogin, !visible, !trusted(webView.url) {
            // Redirection vers un ENT / CAS : on ne remplit rien hors de Pronote, l'utilisateur termine dans la page.
            pendingLogin = nil
            visible = true
            onError?("Ton établissement passe par un ENT : termine la connexion dans la page affichée.")
            return
        }
        var settings: [String: Any] = ["nonce": nonce, "pin": pin, "allowedOrigin": origin, "demo": demo]
        guard !claimed, trusted(webView.url) else { return }
        if let login = pendingLogin { settings["credentials"] = ["username": login.username, "password": login.password]; pendingLogin = nil }
        guard let scriptURL = Bundle.main.url(forResource: "PronoteAutomation", withExtension: "js"), let script = try? String(contentsOf: scriptURL, encoding: .utf8), let config = try? JSONSerialization.data(withJSONObject: settings), let json = String(data: config, encoding: .utf8) else { return }
        webView.evaluateJavaScript("window.__JM_CONFIG = \(json);\n" + script) { [weak self] _, error in
            if error != nil { self?.recover("L’automatisation n’a pas démarré. Tu peux reprendre dans Pronote.") }
        }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard !claimed, message.frameInfo.isMainFrame, trusted(webView.url), let event = message.body as? [String: Any], event["version"] as? Int == 1, event["nonce"] as? String == nonce, event["origin"] as? String == origin, let eventURL = event["url"] as? String, trusted(URL(string: eventURL)), message.frameInfo.securityOrigin.host == webView.url?.host else { return }
        switch event["type"] as? String {
        case "authenticated":
            visible = false
            onPhase?(.authenticated)
            timeout?.cancel()
            timeout = Task { [weak self] in
                try? await Task.sleep(for: .seconds(45))
                guard !Task.isCancelled else { return }
                self?.recover("Le QR code attend une intervention. Reprends la page Pronote.")
            }
        case "progress":
            switch event["stage"] as? String {
            case "filling-login", "submitting-login": onPhase?(.signingIn)
            default: onPhase?(.qr)
            }
        case "error":
            switch event["code"] as? String {
            case "LOGIN_REJECTED":
                cancel(); visible = false
                onRejected?("Pronote a refusé l’identifiant ou le mot de passe. Vérifie-les puis réessaie.")
            case "MFA_REQUIRED": recover("Pronote demande une vérification supplémentaire : termine-la dans la page affichée.")
            case "LOGIN_FORM_NOT_FOUND": recover("Le formulaire Pronote n’a pas été trouvé (ENT ?) : connecte-toi dans la page affichée.")
            case "LOGIN_STALLED": recover("Pronote ne répond pas après la connexion : vérifie la page affichée.")
            default: recover("La génération automatique attend une intervention dans Pronote.")
            }
        case "qr":
            guard event["pin"] as? String == pin, let image = event["image"] as? String, image.utf8.count <= 2_000_000 else { return }
            claimed = true
            timeout?.cancel()
            webView.evaluateJavaScript("window.__JM_PRONOTE_POC?.stop()", completionHandler: nil)
            do { onQR?(try Self.decode(dataURL: image), pin) }
            catch { claimed = false; recover("Le QR code n’a pas pu être lu. Réessaie ou importe une image.") }
        default: break
        }
    }
    private func recover(_ message: String) { cancel(); visible = true; onError?(message) }
    static func decode(dataURL: String) throws -> PronoteQRData {
        guard dataURL.hasPrefix("data:image/png;base64,"), let data = Data(base64Encoded: String(dataURL.dropFirst("data:image/png;base64,".count))) else { throw PronoteFailure.invalid("qr-image", "Image QR invalide.") }
        return try decode(image: data)
    }
    static func decode(image: Data) throws -> PronoteQRData {
        guard image.count <= 2_000_000, let ci = CIImage(data: image), ci.extent.width <= 4096, ci.extent.height <= 4096, let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]), let text = (detector.features(in: ci).first as? CIQRCodeFeature)?.messageString, let data = text.data(using: .utf8) else { throw PronoteFailure.invalid("qr-decode", "Aucun QR code Pronote lisible.") }
        let value = try JSONDecoder().decode(PronoteQRData.self, from: data)
        _ = try PronoteURL.validated(value.url)
        return value
    }
}

struct OfficialWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
