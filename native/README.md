# POC de connexion mobile J-Method

Deux applications natives indépendantes : SwiftUI dans `ios/`, Kotlin/Jetpack Compose dans `android/`. Elles testent la connexion et la lecture de l’emploi du temps ; elles ne reprennent pas encore toutes les fonctions du POC web.

L’utilisateur saisit son identifiant et son mot de passe dans un formulaire natif. L’app charge la page officielle Pronote dans une WebView masquée, lui transmet les identifiants une seule fois, puis génère le QR d’association avec un code temporaire aléatoire. Un client HTTPS natif s’authentifie ensuite avec ce QR. Le téléphone effectue le protocole et ne conserve que le jeton mobile, dans son stockage sécurisé. Aucun serveur J-Method n’est nécessaire.

La WebView est réaffichée en cas d’ENT, de vérification supplémentaire ou de blocage ; un bouton permet aussi de se connecter directement dans la page officielle. Le mot de passe n’est jamais enregistré, journalisé ni renvoyé par le script. Les requêtes métier sont limitées à une liste de fonctions de lecture ; l’association renouvelle toutefois le jeton de connexion mobile dans Pronote.

- `ios/README.md` : compilation Xcode, démonstration et import manuel du QR.
- `android/README.md` : compilation Java 21/Gradle, installation ADB et démonstration.
- `shared/CONTRACT.md` : contrat des événements WebView et contrôles d’origine.
- `shared/protocol-reference.md` : protocole et différences compression/chiffrement.
- `shared/protocol-vectors.json` : vecteurs exclusivement synthétiques.
- `tests/` : huit scénarios navigateur synthétiques (`npm ci && npm test`). Chrome local requis ; définir `CHROME_PATH` sur les autres machines.

Le protocole n’est pas officiel. La démonstration publique, les tests synthétiques et une connexion réelle sont trois validations différentes. Les domaines personnalisés et certains parcours ENT/MFA peuvent nécessiter une adaptation. Ne jamais ajouter de QR, jeton, code, compte, données de cours ou identifiants d’appareils aux fichiers versionnés.

## Vérification du POC

Validé le 17 novembre 2026 sur un compte élève réel, sur iPhone (SwiftUI) et sur Samsung (Kotlin) : formulaire natif, connexion dans la page masquée, génération du QR, connexion native, lecture de l’emploi du temps. Un host seul (`xxxx.index-education.net`) suffit comme adresse.

- Huit scénarios navigateur synthétiques (dont remplissage unique des identifiants, refus sans nouvel essai, aucune saisie hors origine Pronote) et dix tests JVM passent ; les deux apps compilent.
- Compte sécurisé : après la connexion QR, Pronote peut exiger une seconde connexion par jeton (`PageInfosPerso.securisation.mode != 0`). Les deux clients la font automatiquement ; ce n’est pas une double authentification à afficher.
- Restent à éprouver : refus d’identifiants sur la vraie page (détection du message), ENT, double authentification réelle, compte parent avec plusieurs enfants.
