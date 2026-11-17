import SwiftUI
import WebKit
import CoreImage

/// Parcours de connexion par la page officielle (remplaçable dans les tests).
@MainActor
protocol LoginPageDriver: AnyObject {
    var onEvent: ((PronoteWebFlow.Event) -> Void)? { get set }
    func start(url: URL, username: String, password: String)
    func close()
    func setPageVisible(_ visible: Bool)
}

/// Page officielle Pronote dans un WKWebView conservé (masqué ou visible) et pont `jmethodPronote`.
/// Le mot de passe saisi dans le formulaire natif est transmis une seule fois au script partagé, puis effacé.
@MainActor
final class PronoteWebFlow: NSObject, WKNavigationDelegate, LoginPageDriver {
    enum Event {
        case phase(PronoteSession.Phase)
        /// La page doit être affichée (ENT, vérification de sécurité, formulaire inhabituel) ; la tentative continue.
        case reveal(String)
        /// La page n'est plus nécessaire (vérification terminée, session reconnue).
        case conceal
        /// Tentative terminée sans succès : la page est refermée, le formulaire reprend la main.
        case failed(String)
        /// Identifiants refusés : le formulaire natif reprend la main, la page reste masquée.
        case rejected(String)
        case qr(PronoteQRData, pin: String)
    }

    static let handlerName = "jmethodPronote"
    nonisolated static let maximumImageBytes = 2_000_000
    nonisolated static let maximumImageSide: CGFloat = 4096
    private static let maximumMessageBytes = 2_900_000
    private static let desktopUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    let webView: WKWebView
    var onEvent: ((Event) -> Void)?
    private(set) var isActive = false

    private var origin = ""
    private var nonce = ""
    private var pin = ""
    private var claimed = true
    private var nativeLogin = false
    private var pageVisible = false
    private var timeout: Task<Void, Never>?
    /// Identifiants du formulaire natif : transmis à la première injection sur une page de confiance, puis effacés.
    private var pendingLogin: (username: String, password: String)?

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.customUserAgent = Self.desktopUserAgent
        configuration.userContentController.add(WeakScriptHandler(self), name: Self.handlerName)
    }

    /// Démarre une tentative : nonce et code temporaire neufs, page masquée ; les identifiants du formulaire
    /// sont transmis une seule fois au script sur une page Pronote de confiance.
    func start(url: URL, username: String, password: String) {
        stop()
        pendingLogin = (username, password)
        nativeLogin = true
        origin = PronoteURL.origin(url)
        beginAttempt()
        isActive = true
        pageVisible = false
        onEvent?(.phase(.signingIn))
        webView.load(URLRequest(url: url))
        armTimeout(seconds: 90, message: "Pronote met trop de temps à répondre. Réessaie dans un instant.")
    }

    /// Formulaire Pronote introuvable ou sans réponse : la page est affichée et le script, relancé sans identifiants,
    /// attend que l'utilisateur se connecte lui-même pour terminer la connexion.
    private func watchVisiblePage(_ message: String) {
        guard isActive, trusted(webView.url) else { fail(message); return }
        nativeLogin = false
        pendingLogin = nil
        beginAttempt()
        pageVisible = true
        onEvent?(.reveal(message))
        armTimeout(seconds: 600, message: "La connexion a pris trop de temps. Réessaie.")
        inject()
    }

    func setPageVisible(_ visible: Bool) { pageVisible = visible }

    /// Arrête le script et invalide la tentative courante ; la page reste chargée.
    func stop() {
        timeout?.cancel()
        timeout = nil
        pendingLogin = nil
        claimed = true
        nonce = Self.randomNonce()
        pin = ""
        webView.evaluateJavaScript("window.__JM_PRONOTE_POC && window.__JM_PRONOTE_POC.stop && window.__JM_PRONOTE_POC.stop()", completionHandler: nil)
    }

    /// Fin de flux : arrêt, page vidée, WebView conservé pour une prochaine tentative.
    func close() {
        stop()
        webView.stopLoading()
        if webView.url != nil { webView.loadHTMLString("", baseURL: nil) }
        isActive = false
    }

    static func clearWebsiteData() async {
        let store = WKWebsiteDataStore.default()
        await store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)
    }

    private func beginAttempt() {
        timeout?.cancel()
        nonce = Self.randomNonce()
        pin = String(format: "%04d", Int.random(in: 0...9999))
        claimed = false
    }

    private func armTimeout(seconds: Int, message: String) {
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.fail(message)
        }
    }

    /// Fin de tentative sans succès : script arrêté, page refermée.
    private func fail(_ message: String) {
        close()
        pageVisible = false
        onEvent?(.failed(message))
    }

    /// Page nécessaire à l'utilisateur, tentative conservée (même nonce, même code), délai étendu.
    private func reveal(_ message: String) {
        pageVisible = true
        onEvent?(.reveal(message))
        armTimeout(seconds: 600, message: "La connexion a pris trop de temps. Réessaie.")
    }

    private func trusted(_ url: URL?) -> Bool {
        guard let url, url.scheme == "https", PronoteURL.origin(url) == origin else { return false }
        return PronoteURL.trustedPages.contains(url.lastPathComponent.lowercased())
    }

    private static func randomNonce() -> String {
        ((try? ProtocolCrypto.randomBytes(16)).map(ProtocolCrypto.hex)) ?? UUID().uuidString
    }

    // MARK: Navigation

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pageLoaded()
    }

    private func pageLoaded() {
        guard isActive, !claimed else { return }
        if !trusted(webView.url) {
            // ENT / CAS : rien n'est saisi hors de Pronote, l'utilisateur termine dans la page.
            if !pageVisible {
                pendingLogin = nil
                reveal("Ton établissement passe par un ENT : termine la connexion ci-dessous.")
            }
            return
        }
        inject()
    }

    private func inject() {
        var settings: [String: Any] = ["version": 1, "nonce": nonce, "pin": pin, "allowedOrigin": origin, "demo": false]
        if let login = pendingLogin {
            settings["credentials"] = ["username": login.username, "password": login.password]
            pendingLogin = nil
        }
        guard let scriptURL = Bundle.main.url(forResource: "PronoteAutomation", withExtension: "js"),
              let script = try? String(contentsOf: scriptURL, encoding: .utf8),
              let config = try? JSONSerialization.data(withJSONObject: settings),
              let json = String(data: config, encoding: .utf8) else {
            fail("La connexion n’a pas pu démarrer. Réessaie.")
            return
        }
        let attempt = nonce
        webView.evaluateJavaScript("window.__JM_CONFIG = \(json);\n" + script) { [weak self] _, error in
            guard let self, error != nil, self.nonce == attempt, !self.claimed else { return }
            self.fail("La connexion n’a pas pu démarrer. Réessaie.")
        }
    }

    // MARK: Bridge

    fileprivate func receive(_ message: WKScriptMessage) {
        guard !claimed, isActive, message.frameInfo.isMainFrame, trusted(webView.url) else { return }
        let security = message.frameInfo.securityOrigin
        guard security.protocol == "https", "https://" + security.host.lowercased() + (security.port == 0 || security.port == 443 ? "" : ":\(security.port)") == origin else { return }
        guard let event = message.body as? [String: Any],
              let encoded = try? JSONSerialization.data(withJSONObject: event), encoded.count <= Self.maximumMessageBytes,
              ProtocolJSON.integer(event["version"]) == 1, event["nonce"] as? String == nonce,
              event["origin"] as? String == origin,
              let eventURL = event["url"] as? String, trusted(URL(string: eventURL)) else { return }

        switch event["type"] as? String {
        case "authenticated":
            pageVisible = false
            onEvent?(.conceal)
            onEvent?(.phase(.authenticated))
            armTimeout(seconds: 45, message: "La connexion n’a pas abouti. Réessaie.")
        case "progress":
            switch event["stage"] as? String {
            case "filling-login", "submitting-login": onEvent?(.phase(.signingIn))
            case "mfa-done":
                // Vérification terminée par l'utilisateur : la page est masquée, le script reprend seul.
                pageVisible = false
                onEvent?(.conceal)
                onEvent?(.phase(.signingIn))
                armTimeout(seconds: 90, message: "Pronote met trop de temps à répondre. Réessaie dans un instant.")
            case "opening-qr", "filling-code", "generating-qr": onEvent?(.phase(.qr))
            default: break
            }
        case "error":
            handleError(code: event["code"] as? String ?? "")
        case "qr":
            guard event["pin"] as? String == pin, let image = event["image"] as? String, image.utf8.count <= Self.maximumMessageBytes else { return }
            // Revendication unique du QR avant tout traitement.
            claimed = true
            let qrPin = pin
            timeout?.cancel()
            webView.evaluateJavaScript("window.__JM_PRONOTE_POC && window.__JM_PRONOTE_POC.stop && window.__JM_PRONOTE_POC.stop()", completionHandler: nil)
            do {
                let qr = try Self.decode(dataURL: image)
                onEvent?(.qr(qr, pin: qrPin))
            } catch {
                fail("La connexion n’a pas pu être finalisée. Réessaie.")
            }
        default:
            break
        }
    }

    private func handleError(code: String) {
        switch code {
        case "LOGIN_REJECTED":
            stop()
            pageVisible = false
            onEvent?(.rejected("Identifiant ou mot de passe incorrect."))
        case "MFA_REQUIRED":
            // Non terminal : la tentative continue, le script signale lui-même la fin de la vérification.
            reveal("Pronote demande une vérification de sécurité : termine-la ci-dessous.")
        case "LOGIN_FORM_NOT_FOUND":
            watchVisiblePage("Connecte-toi ci-dessous pour terminer.")
        case "LOGIN_STALLED":
            watchVisiblePage("Pronote ne répond pas : vérifie la page ci-dessous.")
        case "LOGIN_TIMEOUT":
            fail("La connexion a pris trop de temps. Réessaie.")
        default:
            // QR_TIMEOUT, QR_TOO_LARGE, AUTOMATION_FAILED et codes inconnus.
            fail("La connexion n’a pas abouti. Réessaie.")
        }
    }

    // MARK: QR

    nonisolated static func decode(dataURL: String) throws -> PronoteQRData {
        let prefix = "data:image/png;base64,"
        guard dataURL.hasPrefix(prefix), let data = Data(base64Encoded: String(dataURL.dropFirst(prefix.count))) else {
            throw PronoteFailure.invalid("qr-image", "Image invalide.")
        }
        return try decode(image: data)
    }

    /// Décodage natif borné en octets et en dimensions avant toute analyse.
    nonisolated static func decode(image: Data) throws -> PronoteQRData {
        guard image.count <= maximumImageBytes, let picture = CIImage(data: image),
              picture.extent.width > 0, picture.extent.width <= maximumImageSide,
              picture.extent.height > 0, picture.extent.height <= maximumImageSide,
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]),
              let text = (detector.features(in: picture).first as? CIQRCodeFeature)?.messageString,
              let data = text.data(using: .utf8) else {
            throw PronoteFailure.invalid("qr-decode", "La connexion n’a pas pu être finalisée. Réessaie.")
        }
        guard let value = try? JSONDecoder().decode(PronoteQRData.self, from: data) else {
            throw PronoteFailure.invalid("qr-decode", "La connexion n’a pas pu être finalisée. Réessaie.")
        }
        _ = try PronoteURL.validated(value.url)
        return value
    }
}

/// Évite le cycle de rétention entre WKUserContentController et le flux.
@MainActor
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var flow: PronoteWebFlow?
    init(_ flow: PronoteWebFlow) { self.flow = flow }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        flow?.receive(message)
    }
}

struct OfficialWebView: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
