import Foundation

/// Champ mot de passe du formulaire de connexion. Un mot de passe enregistré est prérempli masqué et ne peut
/// jamais être affiché : dès la première modification, il est remplacé par la seule saisie de l'utilisateur
/// (effacer un caractère vide le champ). L'œil ne s'applique qu'à une saisie de l'utilisateur.
/// Aucune description (journal, débogueur, `dump`) n'expose la valeur ; rien n'est conservé dans un état restauré.
struct PasswordFieldState: Equatable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    private(set) var text = ""
    /// Le champ contient le mot de passe enregistré.
    private(set) var fromStore = false

    var canReveal: Bool { !fromStore }
    var isEmpty: Bool { text.isEmpty }

    mutating func prefill(stored: String) {
        text = stored
        fromStore = true
    }

    /// Nouvelle valeur du champ ; un mot de passe enregistré n'est jamais conservé, même partiellement.
    mutating func edit(_ value: String) {
        guard fromStore else {
            text = value
            return
        }
        fromStore = false
        text = value.count > text.count && value.hasPrefix(text) ? String(value.dropFirst(text.count)) : ""
    }

    mutating func clear() {
        text = ""
        fromStore = false
    }

    var description: String { "PasswordFieldState(<masqué>)" }
    var debugDescription: String { description }
    var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}
