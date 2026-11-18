import SwiftUI

/// Aide du formulaire de connexion (texte de référence : `native/shared/sign-in-help.md`).
struct SignInHelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.l) {
                    section("Le code de l’établissement") {
                        paragraph("Si tu passes d’habitude par l’ENT pour ouvrir Pronote, voici comment trouver le code :")
                        step(1, "Connecte-toi à l’ENT comme d’habitude, depuis un navigateur.")
                        step(2, "Ouvre Pronote depuis l’ENT.")
                        step(3, "Regarde l’adresse de la page Pronote. Sur téléphone, touche la barre d’adresse pour l’afficher en entier. Elle ressemble à :")
                        Text(verbatim: "https://abcd1234.index-education.net/pronote/eleve.html")
                            .font(.callout.monospaced())
                            .foregroundStyle(Theme.text)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(Spacing.s)
                            .background(Theme.background, in: RoundedRectangle(cornerRadius: Radius.control))
                        step(4, "Le code est la partie avant « .index-education.net », ici abcd1234. Tu peux aussi coller l’adresse entière.")
                    }

                    section("Quel identifiant et quel mot de passe ?") {
                        paragraph("Il faut l’identifiant et le mot de passe Pronote, pas ceux d’ÉduConnect ni de l’ENT. L’app se connecte directement à Pronote, sans passer par l’ENT.")
                        paragraph("L’établissement les donne en général à la rentrée. Si tu ne les as pas ou plus, demande-les à la vie scolaire.")
                        paragraph("Pour les essayer dans un navigateur, ouvre l’adresse de Pronote en remplaçant la fin par « eleve.html?login=true » (ou « parent.html?login=true ») : la page de connexion de Pronote s’affiche sans passer par l’ENT.")
                    }

                    section("Élève ou Parent") {
                        paragraph("Choisis Élève pour le compte de l’élève, Parent pour celui d’un responsable. Chaque compte a ses propres identifiants.")
                    }

                    Button("J’ai compris") { dismiss() }
                        .buttonStyle(PrimaryButtonStyle())
                }
                .padding(Spacing.l)
                .frame(maxWidth: Spacing.readable)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.surface)
            .navigationTitle("Se connecter à Pronote")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fermer") { dismiss() } }
            }
        }
        .presentationDetents([.large])
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text(title).font(.headline).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
            content()
        }
    }

    private func paragraph(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
    }

    private func step(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
            Text("\(number)")
                .font(.caption.weight(.bold))
                .foregroundStyle(Theme.violet)
                .frame(width: 24, height: 24)
                .background(Theme.violetTrack, in: Circle())
                .accessibilityHidden(true)
            Text(text).font(.subheadline).foregroundStyle(Theme.textSecondary).fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Étape \(number) : \(text)")
    }
}
