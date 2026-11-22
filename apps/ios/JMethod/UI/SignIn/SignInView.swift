import SwiftUI

/// Écran de connexion plein écran, affiché tant qu'aucune connexion Pronote n'est active.
struct SignInView: View {
    let pronote: PronoteSession
    @State private var schoolInput = ""
    @State private var accountType: PronoteAccountType = .student
    @State private var username = ""
    @State private var password = PasswordFieldState()
    @State private var remember = true
    @State private var showPassword = false
    @State private var showHelp = false
    @FocusState private var focus: Field?

    private enum Field { case school, username, password }

    private var canSubmit: Bool {
        !pronote.isWorking && !schoolInput.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.l) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    BrandMark(size: 56)
                        .padding(.bottom, Spacing.xs)
                    Text("Connexion").font(.largeTitle.weight(.bold)).foregroundStyle(Theme.text).accessibilityAddTraits(.isHeader)
                    Text("Utilise ton identifiant et ton mot de passe Pronote, pas ceux d’ÉduConnect.").foregroundStyle(Theme.textSecondary)
                    Button("En savoir plus") { showHelp = true }
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Theme.violet)
                        .frame(minHeight: 44)
                        .accessibilityHint("Où trouver le code de l’établissement et quels identifiants utiliser")
                }

                VStack(alignment: .leading, spacing: Spacing.m) {
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
                                // Un mot de passe enregistré n'est jamais affiché en clair.
                                if showPassword && password.canReveal {
                                    TextField("Mot de passe", text: passwordBinding)
                                        .textInputAutocapitalization(.never)
                                        .autocorrectionDisabled()
                                } else {
                                    SecureField("Mot de passe", text: passwordBinding)
                                }
                            }
                            .textContentType(.password)
                            .focused($focus, equals: .password)
                            .submitLabel(.go)
                            .onSubmit(submit)
                            .accessibilityLabel("Mot de passe")
                            if password.canReveal {
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
                    }

                    VStack(alignment: .leading, spacing: Spacing.xxs) {
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
                        HStack(spacing: Spacing.s) {
                            if pronote.isWorking { ProgressView().tint(.white) }
                            Text(pronote.isWorking ? pronote.phase.progressLabel : "Se connecter")
                        }
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!canSubmit)

                    Button("Continuer sans Pronote") { pronote.useLocally() }
                        .buttonStyle(SecondaryButtonStyle()).disabled(pronote.isWorking)

                    if let message = pronote.errorMessage, !pronote.isWorking {
                        Label(message, systemImage: "exclamationmark.circle")
                            .font(.subheadline)
                            .foregroundStyle(Theme.danger)
                            .panel(fill: Theme.dangerSoft)
                            .accessibilityLabel("Erreur : \(message)")
                    }
                }
            }
            .padding(.horizontal, Spacing.l)
            .padding(.vertical, Spacing.l)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.background)
        .sheet(isPresented: $showHelp) { SignInHelpSheet() }
        .onAppear(perform: prefill)
        .onChange(of: pronote.savedLogin) { _, _ in prefill() }
        .onChange(of: pronote.errorMessage) { _, message in
            if let message { AccessibilityNotification.Announcement(message).post() }
        }
    }

    private var passwordBinding: Binding<String> {
        Binding(get: { password.text }, set: { password.edit($0) })
    }

    private func prefill() {
        if schoolInput.isEmpty, let school = pronote.school {
            schoolInput = school.input
            accountType = school.account
        }
        if username.isEmpty, let login = pronote.savedLogin {
            username = login.username
            if password.isEmpty, let saved = login.password {
                password.prefill(stored: saved)
                showPassword = false
            }
        }
    }

    private func submit() {
        guard canSubmit else { return }
        focus = nil
        // Une adresse complète impose son espace : le choix Élève/Parent est mis à jour.
        accountType = PronoteURL.resolve(schoolInput, account: accountType).account
        pronote.signIn(school: schoolInput, account: accountType, username: username, password: password.text, remember: remember)
        password.clear()
        showPassword = false
    }
}

struct LabeledField<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
            content.inputField(fill: Theme.surface)
        }
    }
}
