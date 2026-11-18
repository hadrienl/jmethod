# Apps natives J-Method — plan de développement

Objectif : porter le POC web (`src/`, retiré du dépôt depuis, disponible au tag `web-poc-final`) en deux applications natives indépendantes, **SwiftUI** (`apps/ios`) et **Kotlin / Jetpack Compose** (`apps/android`), avec la connexion Pronote validée par le POC (`native/`). Pas de serveur J-Method : les données restent sur le téléphone.

## Principes

- **Parité fonctionnelle avec le web**, livrée par jalons testables sur téléphone. Les cinq jalons sont livrés ; le POC web a ensuite été retiré, et les vecteurs figés de `native/shared/domain` font foi.
- **Une seule logique métier, deux implémentations.** L’algorithme adaptatif, le programme du soir, l’appariement des matières et le parseur iCal sont portés à l’identique. Leur parité est vérifiée par des vecteurs générés depuis le code TypeScript (`native/shared/domain/`), exécutés par les tests Swift et JVM.
- **Données** : un document JSON local au schéma du web (`src/types`, export de `src/lib/storage.ts`), écrit de façon atomique dans le stockage privé de l’app. La sauvegarde JSON est donc échangeable entre web, iOS et Android. Le jeton Pronote reste dans le Keychain / Keystore, jamais dans ce document.
- **Dates** : jours calendaires au fuseau Europe/Paris, chaînes `YYYY-MM-DD` comme le web. Le programme du soir prépare le lendemain (voir `docs/evening-workflow.md`).
- **Pronote** : reprise du POC (formulaire natif, page officielle masquée, QR automatique, client HTTPS natif, reconnexion par jeton, lecture seule). Contrat : `native/shared/CONTRACT.md` et `native/shared/protocol-reference.md`. Le script `native/shared/PronoteAutomation.js` reste partagé.
- **Design** : `docs/design-direction.md`. Clair violet et citron, Découvrir / S’entraîner / Se tester, aucun emoji, accessibilité (VoiceOver/TalkBack, tailles dynamiques, cibles 44 pt / 48 dp).
- **Dépôt public** : aucun host d’établissement réel, identifiant d’équipe, UDID, jeton, QR ou donnée de compte dans le code, les tests ou les captures.
- Seuls les onglets d’un jalon livré apparaissent ; pas d’écran factice.

## Socle technique

| | iOS | Android |
|---|---|---|
| Projet | XcodeGen `apps/ios/project.yml`, app `JMethod`, bundle `fr.jmethod.app` | Gradle `apps/android`, package `fr.jmethod.app` |
| Cible | iOS 17+ | minSdk 26, targetSdk à jour |
| UI | SwiftUI, Observation (`@Observable`) | Compose Material 3, ViewModel + StateFlow (UDF) |
| Persistance | JSON Codable + écriture atomique | kotlinx.serialization + fichier atomique |
| Secrets | Keychain `WhenUnlockedThisDeviceOnly` | Android Keystore |
| Tests | XCTest / Swift Testing (vecteurs partagés) | JUnit JVM (vecteurs partagés) |

## App ordinateur (Windows, macOS)

Décision du 18 novembre 2026 : la même app que sur téléphone, en Kotlin, pour ordinateur. Pas de synchronisation pour l’instant : l’ordinateur garde ses propres données, comme chaque téléphone.

- **Kotlin Multiplatform** dans `apps/android` : `shared` (modèle, logique, protocole Pronote, cahier de textes, documents, écrans Compose ; cibles Android et JVM), `app` (Android, fin), `desktopApp` (Compose Desktop). L’app Android garde son identifiant, son fichier de données et ses alias Keystore.
- **Connexion directe** sur ordinateur (pas de WebView) : identifiant et mot de passe Pronote envoyés par le protocole natif à `<page>?login=true`, mêmes lectures et mêmes règles (lecture seule, erreur 25, expiration de session) ; double authentification non prise en charge (message clair).
- **Secrets** : Trousseau macOS, DPAPI Windows. **Données** : dossier de l’app du système. **Actualisation** seulement pendant que l’app est ouverte.
- **Fenêtre adaptative**, partagée avec Android (tablettes, pliables) : classes de largeur Material 3 (barre en bas, rail, deux volets), feuilles en panneau latéral ou en dialogue sur grande fenêtre.
- **Distribution** : GitHub Actions (`.github/workflows/desktop.yml`) construit un MSI et un DMG non signés en artefacts, joints à une Release pour l’étiquette `desktop-vX.Y.Z`.

## Jalons

1. **Socle, Pronote, Aujourd’hui.** Projets, modèle de données et persistance, données de démonstration au premier lancement, logique métier portée et vérifiée par les vecteurs, connexion Pronote (formulaire natif, reprise par jeton, synchronisation des 14 prochains jours vers les créneaux Pronote, choix de l’enfant), écran Aujourd’hui complet : séances à préparer ce soir groupées par matière et par étape, retards, progression, « Ta journée de cours de demain », validation d’une séance avec autoévaluation 0–100 et replanification adaptative, feu d’artifice quand tout est fait.
2. **Planning.** Jour, semaine, mois, filtres, détail d’une séance (Jn, séances précédentes, étape, validation).
3. **Mes cours.** Création (y compris depuis un créneau Pronote), détail, historique, suppression.
4. **Mes progrès et Réglages.** Indicateurs, intervalles, sensibilité, seuil de rattrapage, matières, export / import JSON compatible web, oubli du compte Pronote.
5. **Cahier de textes.** Aperçu lecture seule des contenus, devoirs et pièces jointes, téléchargement protégé.

Chaque jalon se termine par : tests verts sur les deux plateformes, installation sur iPhone et Android, vérification par l’utilisateur, commit.

## Organisation

Deux experts (`jmethod-ios-swiftui-expert`, `jmethod-android-compose-expert`, qui porte aussi l’app ordinateur) travaillent en parallèle, chacun dans son dossier, pilotés par la session principale. Chacun compile et teste sa propre plateforme ; l’orchestrateur relit, installe sur les appareils et committe. Les changements de contrat partagé (`native/shared/`) passent par l’orchestrateur.
