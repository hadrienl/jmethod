import SwiftUI
import PhotosUI

/// Onglet Réglages réduit, pour ce jalon, à la connexion Pronote.
struct SettingsView: View {
    let app: AppModel
    let pronote: PronoteSession

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Pronote").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                        Text("J-Method lit ton emploi du temps des 14 prochains jours, sans rien modifier dans Pronote.")
                            .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    }
                    PronoteStatusCard(pronote: pronote)
                    if pronote.isConnected {
                        PronoteAccountCard(app: app, pronote: pronote)
                    } else {
                        PronoteLoginForm(pronote: pronote)
                    }
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.background)
            .navigationTitle("Réglages")
        }
    }
}

private struct PronoteStatusCard: View {
    @Bindable var pronote: PronoteSession

    var body: some View {
        let showCard = pronote.isWorking || pronote.errorMessage != nil || pronote.phase == .login || pronote.webActive
        if showCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    if pronote.isWorking { ProgressView() }
                    Text(pronote.phase.label).font(.headline)
                }
                .accessibilityElement(children: .combine)
                if let message = pronote.errorMessage {
                    Label(message, systemImage: "exclamationmark.circle")
                        .font(.subheadline)
                        .foregroundStyle(Theme.danger)
                        .accessibilityLabel("Erreur : \(message)")
                }
                if pronote.webActive && !pronote.webVisible {
                    Button("Afficher la page Pronote") { pronote.webVisible = true }.buttonStyle(SecondaryButtonStyle())
                }
                if pronote.isWorking || pronote.webActive {
                    Button("Annuler") { pronote.cancel() }.buttonStyle(SecondaryButtonStyle())
                } else if pronote.errorMessage != nil {
                    Button("Fermer ce message") { pronote.dismissError() }.buttonStyle(SecondaryButtonStyle())
                }
            }
            .surface()
            .onChange(of: pronote.errorMessage) { _, message in
                if let message { AccessibilityNotification.Announcement(message).post() }
            }
        }
    }
}

private struct PronoteAccountCard: View {
    let app: AppModel
    @Bindable var pronote: PronoteSession
    @State private var confirmingDisconnect = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Label("Connecté", systemImage: "checkmark.circle.fill").font(.subheadline.bold()).foregroundStyle(Theme.success)
                Text(pronote.account?.name.isEmpty == false ? pronote.account?.name ?? "" : "Compte Pronote").font(.title3.bold())
            }
            if let children = pronote.account?.children, children.count > 1 {
                Picker("Élève suivi", selection: Binding(
                    get: { pronote.selectedChildId ?? children.first?.id ?? "" },
                    set: { pronote.selectChild($0) }
                )) {
                    ForEach(children) { Text($0.name).tag($0.id) }
                }
                .pickerStyle(.menu)
                .disabled(pronote.isWorking)
                .frame(minHeight: 44)
            }
            if let sync = app.lastPronoteSync {
                Text("Dernière synchronisation : \(sync.formatted(.dateTime.locale(Locale(identifier: "fr_FR")).weekday(.wide).day().month(.wide).hour().minute()))")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
                Text("\(app.document.pronoteEvents.count) créneau\(app.document.pronoteEvents.count > 1 ? "x" : "") sur les 14 prochains jours")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
            Button {
                pronote.synchronize()
            } label: {
                Label("Synchroniser maintenant", systemImage: "arrow.clockwise")
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(pronote.isWorking)
            Button("Se déconnecter", role: .destructive) { confirmingDisconnect = true }
                .frame(maxWidth: .infinity, minHeight: 44)
                .disabled(pronote.isWorking)
        }
        .surface()
        .confirmationDialog("Se déconnecter de Pronote ?", isPresented: $confirmingDisconnect, titleVisibility: .visible) {
            Button("Se déconnecter", role: .destructive) { Task { await pronote.disconnect() } }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Le jeton de connexion, la session de la page officielle et les créneaux importés sont effacés de cet iPhone.")
        }
    }
}

private struct PronoteLoginForm: View {
    @Bindable var pronote: PronoteSession
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var manualPIN = ""
    @State private var photo: PhotosPickerItem?
    @State private var importExpanded = false
    @FocusState private var focus: Field?

    private enum Field { case address, username, password, pin }

    private var canSignIn: Bool {
        !pronote.isWorking && !address.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if pronote.hasSavedConnection && !pronote.isWorking {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Une connexion est enregistrée sur cet iPhone.").font(.subheadline)
                    Button("Reprendre la connexion enregistrée") { pronote.resumeSavedConnection() }.buttonStyle(PrimaryButtonStyle())
                    Button("Oublier cette connexion", role: .destructive) { Task { await pronote.disconnect() } }
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .surface()
            }

            VStack(alignment: .leading, spacing: 14) {
                Text("Se connecter").font(.headline).accessibilityAddTraits(.isHeader)
                field("Adresse Pronote") {
                    TextField("xxxx.index-education.net", text: $address)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focus, equals: .address)
                        .submitLabel(.next)
                        .onSubmit { focus = .username }
                }
                field("Identifiant") {
                    TextField("Identifiant Pronote", text: $username)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($focus, equals: .username)
                        .submitLabel(.next)
                        .onSubmit { focus = .password }
                }
                field("Mot de passe") {
                    SecureField("Mot de passe", text: $password)
                        .textContentType(.password)
                        .focused($focus, equals: .password)
                        .submitLabel(.go)
                        .onSubmit(signIn)
                }
                Button(action: signIn) { Text("Se connecter") }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canSignIn)
                Text("Tes identifiants sont saisis une seule fois dans la page officielle Pronote, masquée, puis oubliés. Seul un jeton de connexion est conservé dans le trousseau de cet iPhone.")
                    .font(.footnote).foregroundStyle(Theme.textSecondary)
            }
            .surface()

            VStack(alignment: .leading, spacing: 12) {
                Text("Ton établissement passe par un ENT ou le formulaire ne fonctionne pas ?").font(.subheadline)
                Button("Se connecter dans la page Pronote officielle") {
                    focus = nil
                    pronote.openOfficialPage(address: address)
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(pronote.isWorking || address.trimmingCharacters(in: .whitespaces).isEmpty)

                DisclosureGroup(isExpanded: $importExpanded) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Dans Pronote, génère un QR code de connexion à l’application mobile avec un code à quatre chiffres, puis importe une capture de ce QR code.")
                            .font(.footnote).foregroundStyle(Theme.textSecondary)
                        SecureField("Code à quatre chiffres", text: $manualPIN)
                            .keyboardType(.numberPad)
                            .textContentType(.oneTimeCode)
                            .focused($focus, equals: .pin)
                            .padding(12)
                            .frame(minHeight: 44)
                            .background(Theme.background, in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.border))
                            .onChange(of: manualPIN) { _, value in
                                let digits = String(value.filter(\.isNumber).prefix(4))
                                if digits != value { manualPIN = digits }
                            }
                        PhotosPicker(selection: $photo, matching: .images) {
                            Label("Choisir l’image du QR code", systemImage: "qrcode")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(manualPIN.count != 4 || pronote.isWorking)
                    }
                    .padding(.top, 8)
                } label: {
                    Text("Importer un QR code").font(.subheadline.weight(.semibold)).frame(minHeight: 44)
                }
            }
            .surface()
        }
        .onAppear { if address.isEmpty { address = pronote.savedAddress } }
        .onChange(of: pronote.savedAddress) { _, value in if address.isEmpty { address = value } }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                let pin = manualPIN
                photo = nil
                manualPIN = ""
                if let data { pronote.importQR(image: data, pin: pin) }
                else { pronote.importQR(image: Data(), pin: pin) }
            }
        }
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.subheadline.weight(.semibold))
            content()
                .padding(12)
                .frame(minHeight: 44)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.border))
                .accessibilityLabel(label)
        }
    }

    private func signIn() {
        guard canSignIn else { return }
        focus = nil
        pronote.signIn(address: address, username: username, password: password)
        password = ""
    }
}
