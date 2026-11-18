import SwiftUI
import PhotosUI

@main
struct JMethodApp: App {
    @StateObject private var model = ConnectionModel()
    var body: some Scene {
        WindowGroup {
            ConnectionView(model: model, web: model.web)
                .onOpenURL { url in if url.scheme == "jmethodpoc", url.host == "demo" { model.open(demo: true) } }
                .task { if ProcessInfo.processInfo.arguments.contains("--public-demo") { model.open(demo: true) } }
        }
    }
}

struct ConnectionView: View {
    @ObservedObject var model: ConnectionModel
    @ObservedObject var web: PronoteWebFlow
    @State private var photo: PhotosPickerItem?
    @State private var manualPIN = ""
    @State private var username = ""
    @State private var password = ""
    @FocusState private var focus: Field?
    private enum Field { case username, password }
    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground).ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Connexion Pronote").font(.largeTitle.bold())
                        Text("POC iOS natif · lecture seule").foregroundStyle(.secondary)
                        GroupBox {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(model.phase.label).font(.headline)
                                if model.busy { ProgressView() }
                                if let error = model.error { Text(error).foregroundStyle(.red) }
                                Text("La connexion s’effectue sur la page officielle. Le jeton mobile reste dans le trousseau de cet iPhone.").font(.footnote).foregroundStyle(.secondary)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        if model.account == nil {
                            TextField("https://…index-education.net/pronote/eleve.html", text: $model.schoolURL).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL).textFieldStyle(.roundedBorder).accessibilityLabel("Adresse officielle Pronote")
                            TextField("Identifiant Pronote", text: $username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder).focused($focus, equals: .username).submitLabel(.next).onSubmit { focus = .password }
                            SecureField("Mot de passe", text: $password).textContentType(.password).textFieldStyle(.roundedBorder).focused($focus, equals: .password).submitLabel(.go).onSubmit(signIn)
                            Button(action: signIn) { Text("Se connecter").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).controlSize(.large).disabled(model.busy || username.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty)
                            if let error = model.error { Text(error).font(.footnote).foregroundStyle(.red) }
                            Text("Tes identifiants sont saisis dans la page officielle Pronote, masquée, puis oubliés : seul le jeton mobile est conservé dans le trousseau.").font(.footnote).foregroundStyle(.secondary)
                            Button("Se connecter dans la page Pronote officielle") { model.open() }.disabled(model.busy)
                            if model.hasSaved { Button("Reprendre la connexion enregistrée") { model.restore() }.disabled(model.busy) }
                            Button("Tester la démonstration publique") { model.open(demo: true) }.disabled(model.busy)
                            Button("Tester uniquement le protocole natif public") { model.nativeDemo() }.font(.footnote).disabled(model.busy)
                            DisclosureGroup("Importer un QR code manuellement") {
                                VStack(alignment: .leading, spacing: 12) {
                                    Text("Génère un QR code de connexion à l’application mobile dans Pronote avec un code à quatre chiffres, puis importe son image.").font(.footnote)
                                    SecureField("Code temporaire à quatre chiffres", text: $manualPIN).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
                                    PhotosPicker("Choisir l’image du QR code", selection: $photo, matching: .images).disabled(manualPIN.count != 4 || model.busy)
                                }.padding(.top, 8)
                            }
                        }
                        if let account = model.account {
                            Text(account.name.isEmpty ? "Compte connecté" : account.name).font(.title2.bold())
                            if account.children.count > 1 {
                                Picker("Élève", selection: $model.selectedChild) { ForEach(account.children) { Text($0.name).tag($0.id) } }.onChange(of: model.selectedChild) { _, _ in model.refresh() }
                            }
                            HStack { Text("Les 14 prochains jours").font(.headline); Spacer(); Button("Actualiser") { model.refresh() }.disabled(model.busy) }
                            if model.lessons.isEmpty && !model.busy { Text("Aucun cours sur cette période.").foregroundStyle(.secondary) }
                            ForEach(model.lessons) { lesson in
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack { Text(lesson.subject.isEmpty ? "Cours" : lesson.subject).font(.headline); Spacer(); if lesson.cancelled { Text("Annulé").foregroundStyle(.red) } }
                                    Text(displayDate(lesson.start) + (lesson.end.isEmpty ? "" : " – " + String(lesson.end.suffix(8).prefix(5)))).font(.subheadline)
                                    Text([lesson.teacher, lesson.room].filter { !$0.isEmpty }.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary)
                                }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.background, in: RoundedRectangle(cornerRadius: 14))
                            }
                        }
                        if model.webActive && !web.visible { Button("Afficher la page Pronote") { web.visible = true } }
                        if model.busy || model.webActive { Button("Annuler") { model.cancel() } }
                        if model.hasSaved || model.account != nil { Button("Oublier ce compte", role: .destructive) { model.forget() } }
                        DisclosureGroup("Étapes de connexion") { ForEach(model.logs) { Text($0.phase.label).font(.caption).frame(maxWidth: .infinity, alignment: .leading) } }
                    }.padding(20).frame(maxWidth: 700)
                }
                // Preserve a real, nonzero WKWebView hierarchy while automation runs.
                VStack(spacing: 0) {
                    HStack { Text("Page officielle Pronote").font(.headline); Spacer(); Button("Masquer") { web.visible = false }; Button("Fermer") { model.cancel() } }.padding().background(.regularMaterial)
                    OfficialWebView(webView: web.webView)
                }.opacity(model.webActive && web.visible ? 1 : 0.001).allowsHitTesting(model.webActive && web.visible).accessibilityHidden(!(model.webActive && web.visible)).zIndex(model.webActive && web.visible ? 1 : -1)
            }.tint(.indigo).navigationBarTitleDisplayMode(.inline)
        }
        .onChange(of: photo) { _, item in
            Task { guard let data = try? await item?.loadTransferable(type: Data.self) else { return }; model.importQR(data, pin: manualPIN); photo = nil; manualPIN = "" }
        }
    }
    private func signIn() {
        guard !model.busy, !username.trimmingCharacters(in: .whitespaces).isEmpty, !password.isEmpty else { return }
        focus = nil
        model.signIn(username: username, password: password)
        password = ""
    }
    private func displayDate(_ value: String) -> String { value.replacingOccurrences(of: "T", with: " · ") }
}
