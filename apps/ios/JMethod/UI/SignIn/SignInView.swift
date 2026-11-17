import SwiftUI

/// Écran de connexion plein écran, affiché tant qu'aucune connexion Pronote n'est active.
struct SignInView: View {
    let pronote: PronoteSession
    @State private var schoolInput = ""
    @State private var accountType: PronoteAccountType = .student
    @State private var username = ""
    @State private var password = ""
    @FocusState private var focus: Field?

    private enum Field { case school, username, password }

    private var canSubmit: Bool {
        !pronote.isWorking && !schoolInput.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(alignment: .leading, spacing: 10) {
                    BrandMark()
                    Text("Connexion").font(.largeTitle.weight(.heavy)).accessibilityAddTraits(.isHeader)
                    Text("Connecte-toi avec ton compte Pronote.").foregroundStyle(Theme.textSecondary)
                }

                VStack(alignment: .leading, spacing: 18) {
                    LabeledField(title: "Code ou adresse de l’établissement") {
                        TextField("Exemple : abcd1234", text: $schoolInput)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focus, equals: .school)
                            .submitLabel(.next)
                            .onSubmit { focus = .username }
                    }

                    Picker("Type de compte", selection: $accountType) {
                        ForEach(PronoteAccountType.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(minHeight: 44)

                    LabeledField(title: "Identifiant") {
                        TextField("Identifiant", text: $username)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focus, equals: .username)
                            .submitLabel(.next)
                            .onSubmit { focus = .password }
                    }

                    LabeledField(title: "Mot de passe") {
                        SecureField("Mot de passe", text: $password)
                            .textContentType(.password)
                            .focused($focus, equals: .password)
                            .submitLabel(.go)
                            .onSubmit(submit)
                    }

                    Button(action: submit) {
                        HStack(spacing: 10) {
                            if pronote.isWorking { ProgressView().tint(.white) }
                            Text(pronote.isWorking ? pronote.phase.progressLabel : "Se connecter")
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canSubmit)

                    if let message = pronote.errorMessage, !pronote.isWorking {
                        Label(message, systemImage: "exclamationmark.circle")
                            .font(.subheadline)
                            .foregroundStyle(Theme.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityLabel("Erreur : \(message)")
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background)
        .onAppear(perform: prefill)
        .onChange(of: pronote.errorMessage) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
    }

    private func prefill() {
        guard schoolInput.isEmpty, let school = pronote.school else { return }
        schoolInput = school.input
        accountType = school.account
    }

    private func submit() {
        guard canSubmit else { return }
        focus = nil
        // Une adresse complète impose son espace : le choix Élève/Parent est mis à jour.
        accountType = PronoteURL.resolve(schoolInput, account: accountType).account
        pronote.signIn(school: schoolInput, account: accountType, username: username, password: password)
        password = ""
    }
}

/// Marque J-Method (lettre et point citron), comme le web.
struct BrandMark: View {
    var body: some View {
        HStack(spacing: 12) {
            (Text("j") + Text("•").foregroundColor(Theme.lime))
                .font(.system(.title, design: .rounded).weight(.heavy))
                .foregroundStyle(.white)
                .frame(width: 48, height: 48)
                .background(Theme.violet, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .accessibilityHidden(true)
            Text("J-Method").font(.title2.weight(.heavy))
        }
        .accessibilityElement(children: .combine)
    }
}

struct LabeledField<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold))
            content
                .padding(12)
                .frame(minHeight: 48)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.border))
                .accessibilityLabel(title)
        }
    }
}
