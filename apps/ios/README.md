# J-Method iOS

Application SwiftUI (iOS 17+, Observation), sans dépendance tierce ni serveur. Plan : `docs/native-apps-plan.md`.

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild test -project apps/ios/JMethod.xcodeproj -scheme JMethod \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath apps/ios/DerivedData
```

La signature se règle localement dans Xcode (équipe personnelle non versionnée).

## Organisation

- `JMethod/Domain` : modèle au schéma du web (`src/types`), algorithme adaptatif, programme du soir, appariement des matières, modèles purs des écrans Aujourd'hui, Planning (jour, semaine, mois, navigation), détail de séance, Mes cours (liste, fiche, création, suppression), Mes progrès (série réelle de régularité), Réglages (paliers, curseurs, couleurs), sauvegarde JSON au texte exact d'`exportAllData` et restauration validée, de l'aperçu de score et de la validation, migration qui retire l'ancien jeu de démonstration. Les écarts volontaires avec le web sont ceux de `native/shared/domain/DEVIATIONS.md`.
- `JMethod/Storage` : document JSON unique écrit atomiquement dans Application Support (`jmethod-data.json`), relisible depuis un export du web.
- `JMethod/Pronote` : connexion reprise du POC `native/ios` (formulaire natif, page officielle masquée et affichée seulement pour un ENT ou une vérification, QR automatique, protocole HTTPS natif en lecture seule, jeton dans le trousseau). Liste blanche avec `PageCahierDeTexte` (onglets 88/89, `domaine` de type 8 sur six semaines au plus, `listeOnglets` respecté), refus local de toute fonction `Saisie…`, appels sérialisés. Cahier de textes (`Notebook.swift`, `native/shared/notebook-reference.md`) : fenêtre de 28 jours, dates, HTML vers texte sûr, liens `http(s)` ouverts dans Safari, pièces jointes téléchargées (URL construite dans le client puis oubliée, même session et mêmes cookies, 20 Mo au plus, refus si la session a changé depuis la lecture) dans le cache privé `Caches/notebook`, effacé au lancement, à la déconnexion et au changement d'enfant, puis ouvertes par QuickLook.
- `JMethod/UI` : cinq onglets (Aujourd'hui, Planning, Cours, Progrès, Réglages ; VoiceOver lit « Mes cours », « Mes progrès »), écran de connexion, Aujourd'hui, Planning (semaine en jours empilés, mois en calendrier puis séances du jour choisi), Mes cours (fiche, création, y compris depuis un créneau Pronote), autoévaluation, détail d'une séance, feu d'artifice, Mes progrès, Réglages (compte Pronote, algorithme avec barre « Modifications non enregistrées », paliers, couleurs, export et restauration, À propos), cahier de textes en lecture seule (depuis Mes cours ou la fiche d'un cours). Une seule feuille à la fois, pilotée par `AppModel.presented`.

Accès : sans connexion active, seul l'écran de connexion s'affiche. Avec un jeton enregistré, l'app reprend la connexion au lancement ; si Pronote est injoignable, elle s'ouvre hors ligne sur les données locales.

« Rester connecté » (activé par défaut) enregistre établissement, type de compte, identifiant et mot de passe de façon chiffrée, uniquement sur cet iPhone (trousseau, `AfterFirstUnlockThisDeviceOnly`, sans iCloud). Si Pronote refuse le jeton, l'app tente une seule reconnexion silencieuse au premier plan par la page officielle masquée. Une actualisation en arrière-plan (`BGAppRefreshTask` `fr.jmethod.app.refresh`) se reconnecte par jeton en HTTPS natif et synchronise les 14 jours ; un jeton refusé en arrière-plan est seulement marqué pour une reconnexion au prochain premier plan. Au retour au premier plan, l'emploi du temps est relu si la dernière synchronisation date de plus de 6 h. Erreur Pronote 25 (trop de tentatives) : aucun essai automatique ni « Réessayer » pendant une heure (horodatage persistant). Reconnexion silencieuse automatique : une seule tant qu'aucune synchronisation n'a réussi, et au plus une par heure. Une fois l'Authentification envoyée, la connexion par jeton va jusqu'à l'enregistrement du jeton renouvelé, même annulée. UUID d'application stable (trousseau, conservé à la déconnexion). Un mot de passe enregistré est prérempli masqué et ne peut jamais être affiché.
- `JMethodTests` : vecteurs partagés `native/shared/domain/*.json`, `native/shared/protocol-vectors.json` et `native/shared/notebook-vectors.json` (référencés, jamais copiés), écarts D1 à D13 nommés dans les tests, stockage, modèle. Lancer les tests avec `-test-timeouts-enabled YES -default-test-execution-time-allowance 60` : sur simulateur, un échec d'assertion met plusieurs minutes à être signalé.

Le script `native/shared/PronoteAutomation.js` est intégré tel quel au bundle.

Tests de fumée et captures d'écran (`JMethodUITests`, schéma séparé `JMethodUITests` pour garder les tests unitaires rapides) : chaque test lance l'app en Debug avec `-JMethodPreviewFixture`, un document fictif calculé autour du jour réel et écrit dans un fichier temporaire distinct (ni le document de l'utilisateur, ni ses préférences, ni Pronote ne sont lus ou modifiés). Ils parcourent les cinq onglets et les trois modes du Planning, font défiler jusqu'en bas, ouvrent chaque feuille, saisissent dans le formulaire de création (clavier), valident une séance, affichent l'écran de connexion (`-JMethodPreviewSignIn`, aucun identifiant prérempli) et reprennent l'essentiel en Dynamic Type « Accessibilité XL ». Les captures sont jointes au résultat ; pour les écrire aussi en PNG :

```sh
TEST_RUNNER_JMETHOD_SCREENSHOT_DIR=/chemin/captures TEST_RUNNER_JMETHOD_SCREENSHOT_PREFIX=clair \
xcodebuild test -project apps/ios/JMethod.xcodeproj -scheme JMethodUITests \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath apps/ios/DerivedData
```

`TEST_RUNNER_JMETHOD_UI_TEXT_SIZE=xl` passe toute la série en très grande taille de texte. L'app assume un thème clair (`UIUserInterfaceStyle = Light`, comme le web et Android) : en apparence sombre du système, elle reste identique.

Design : jetons dans `JMethod/UI/Theme.swift` (palette, espacements 4/8/12/16/24/32, rayons, styles de texte système) et composants partagés (`surface`, `panel`, `inputField`, boutons principal/secondaire/teinté, `Tag`, `IconTile`, `SectionHeader`, `EmptyState`). L'icône (claire, sombre et teintée) et le logo de l'écran de lancement sont dessinés par `Tools/make-icons.swift` (CoreGraphics) : `swift apps/ios/Tools/make-icons.swift apps/ios/JMethod/Assets.xcassets`.

Vérification visuelle sur simulateur : en build Debug seulement, l'argument de lancement `-JMethodOfflinePreview` ouvre l'app hors ligne sur les données locales (aucune connexion ni requête Pronote). Les données d'essai se déposent dans le conteneur de l'app (`xcrun simctl get_app_container booted fr.jmethod.app data`, fichier `Library/Application Support/jmethod-data.json`) ; elles restent fictives et hors du dépôt.
