# J-Method iOS

Application SwiftUI (iOS 17+, Observation), sans dépendance tierce ni serveur. Plan : `docs/native-apps-plan.md`.

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild test -project apps/ios/JMethod.xcodeproj -scheme JMethod \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath apps/ios/DerivedData
```

La signature se règle localement dans Xcode (équipe personnelle non versionnée).

## Organisation

- `JMethod/Domain` : modèle au schéma du web (`src/types`), algorithme adaptatif, programme du soir, appariement des matières, modèles purs de l'écran Aujourd'hui, de l'aperçu de score et de la validation, migration qui retire l'ancien jeu de démonstration.
- `JMethod/Storage` : document JSON unique écrit atomiquement dans Application Support (`jmethod-data.json`), relisible depuis un export du web.
- `JMethod/Pronote` : connexion reprise du POC `native/ios` (formulaire natif, page officielle masquée et affichée seulement pour un ENT ou une vérification, QR automatique, protocole HTTPS natif en lecture seule, jeton dans le trousseau).
- `JMethod/UI` : écran de connexion, Aujourd'hui, autoévaluation, détail d'une séance, feu d'artifice, Réglages (Pronote).

Accès : sans connexion active, seul l'écran de connexion s'affiche. Avec un jeton enregistré, l'app reprend la connexion au lancement ; si Pronote est injoignable, elle s'ouvre hors ligne sur les données locales.
- `JMethodTests` : vecteurs partagés `native/shared/domain/*.json` et `native/shared/protocol-vectors.json` (référencés, jamais copiés), stockage, modèle.

Le script `native/shared/PronoteAutomation.js` est intégré tel quel au bundle.
