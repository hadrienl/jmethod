import SwiftUI

/// Écran de connexion plein écran, affiché tant qu'aucune connexion Pronote n'est active.
struct SignInView: View {
    let pronote: PronoteSession
    @State private var schoolInput = ""
    @State private var accountType: PronoteAccountType = .student
    @State private var username = ""
    @State private var password = ""
    @State private var remember = true
    @State private var showPassword = false
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
                            .accessibilityLabel("Code ou adresse de l’établissement")
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
                            .accessibilityLabel("Identifiant")
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focus, equals: .username)
                            .submitLabel(.next)
                            .onSubmit { focus = .password }
                    }

                    LabeledField(title: "Mot de passe") {
                        HStack(spacing: 8) {
                            Group {
                                if showPassword {
                                    TextField("Mot de passe", text: $password)
                                        .textInputAutocapitalization(.never)
                                        .autocorrectionDisabled()
                                } else {
                                    SecureField("Mot de passe", text: $password)
                                }
                            }
                            .textContentType(.password)
                            .focused($focus, equals: .password)
                            .submitLabel(.go)
                            .onSubmit(submit)
                            .accessibilityLabel("Mot de passe")
                            Button { showPassword.toggle() } label: {
                                Image(systemName: showPassword ? "eye.slash" : "eye")
                                    .foregroundStyle(Theme.textSecondary)
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(showPassword ? "Masquer le mot de passe" : "Afficher le mot de passe")
                        }
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Toggle("Rester connecté", isOn: $remember)
                            .font(.body.weight(.semibold))
                            .tint(Theme.violet)
                            .frame(minHeight: 44)
                        Text(remember
                             ? "Ton identifiant et ton mot de passe sont enregistrés de façon chiffrée, uniquement sur cet iPhone, pour te reconnecter automatiquement."
                             : "Ton mot de passe ne sera pas enregistré, et celui déjà enregistré sera effacé : il faudra le saisir de nouveau si Pronote te déconnecte.")
                            .font(.footnote)
                            .foregroundStyle(Theme.textSecondary)
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
        .onChange(of: pronote.savedLogin) { _, _ in prefill() }
        .onChange(of: pronote.errorMessage) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
    }

    private func prefill() {
        if schoolInput.isEmpty, let school = pronote.school {
            schoolInput = school.input
            accountType = school.account
        }
        if username.isEmpty, let login = pronote.savedLogin {
            username = login.username
            if password.isEmpty, let saved = login.password { password = saved }
        }
    }

    private func submit() {
        guard canSubmit else { return }
        focus = nil
        // Une adresse complète impose son espace : le choix Élève/Parent est mis à jour.
        accountType = PronoteURL.resolve(schoolInput, account: accountType).account
        pronote.signIn(school: schoolInput, account: accountType, username: username, password: password, remember: remember)
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
        }
    }
}
