import SwiftUI

/// Onglet Réglages réduit, pour ce jalon, au compte Pronote.
struct SettingsView: View {
    let app: AppModel
    let pronote: PronoteSession
    @State private var confirmingDisconnect = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Pronote").font(.title2.weight(.heavy)).accessibilityAddTraits(.isHeader)
                    VStack(alignment: .leading, spacing: 16) {
                        status
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
                            Text("Dernière mise à jour : \(sync.formatted(.dateTime.locale(Locale(identifier: "fr_FR")).weekday(.wide).day().month(.wide).hour().minute()))")
                                .font(.subheadline).foregroundStyle(Theme.textSecondary)
                        }
                        if let message = pronote.errorMessage, !pronote.isWorking {
                            Label(message, systemImage: "exclamationmark.circle")
                                .font(.subheadline).foregroundStyle(Theme.danger)
                                .accessibilityLabel("Erreur : \(message)")
                        }
                        Button {
                            if pronote.access == .connected { pronote.synchronize() } else { pronote.resumeSavedConnection() }
                        } label: {
                            HStack(spacing: 10) {
                                if pronote.isWorking { ProgressView().tint(.white) }
                                Text(pronote.isWorking ? pronote.phase.progressLabel : (pronote.access == .connected ? "Mettre à jour l’emploi du temps" : "Réessayer la connexion"))
                            }
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(pronote.isWorking)
                        Button("Se déconnecter", role: .destructive) { confirmingDisconnect = true }
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .surface()
                }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.background)
            .navigationTitle("Réglages")
        }
        .confirmationDialog("Se déconnecter de Pronote ?", isPresented: $confirmingDisconnect, titleVisibility: .visible) {
            Button("Se déconnecter", role: .destructive) { Task { await pronote.disconnect(forgetLogin: false) } }
            Button("Se déconnecter et oublier mes identifiants", role: .destructive) { Task { await pronote.disconnect(forgetLogin: true) } }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("« Se déconnecter » garde ton établissement, ton identifiant et ton mot de passe enregistrés sur cet iPhone pour te reconnecter plus vite. L’autre choix efface aussi ton identifiant et ton mot de passe. Dans les deux cas, ton emploi du temps importé est effacé et tes cours et tes révisions restent enregistrés.")
        }
    }

    @ViewBuilder private var status: some View {
        if pronote.access == .connected {
            VStack(alignment: .leading, spacing: 4) {
                Label("Connecté", systemImage: "checkmark.circle.fill").font(.subheadline.bold()).foregroundStyle(Theme.success)
                if let name = pronote.account?.name, !name.isEmpty { Text(name).font(.title3.bold()) }
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Label("Hors ligne", systemImage: "wifi.slash").font(.subheadline.bold()).foregroundStyle(Theme.warning)
                Text("Pronote n’a pas pu être joint. Tes données enregistrées restent disponibles.")
                    .font(.subheadline).foregroundStyle(Theme.textSecondary)
            }
        }
    }
}
