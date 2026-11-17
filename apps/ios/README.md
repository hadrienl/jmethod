# J-Method iOS

Application SwiftUI (iOS 17+, Observation), sans dépendance tierce ni serveur. Plan : `docs/native-apps-plan.md`.

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild test -project apps/ios/JMethod.xcodeproj -scheme JMethod \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -derivedDataPath apps/ios/DerivedData
```

La signature se règle localement dans Xcode (équipe personnelle non versionnée).

## Organisation

- `JMethod/Domain` : modèle au schéma du web (`src/types`), algorithme adaptatif, programme du soir, appariement des matières, données de démonstration, modèles purs de l'écran Aujourd'hui, de l'aperçu de score et de la validation.
- `JMethod/Storage` : document JSON unique écrit atomiquement dans Application Support (`jmethod-data.json`), relisible depuis un export du web.
- `JMethod/Pronote` : connexion repris du POC `native/ios` (formulaire natif, page officielle masquée, QR automatique, protocole HTTPS natif en lecture seule, jeton dans le trousseau).
- `JMethod/UI` : Aujourd'hui, autoévaluation, détail d'une séance, feu d'artifice, Réglages (Pronote).
- `JMethodTests` : vecteurs partagés `native/shared/domain/*.json` et `native/shared/protocol-vectors.json` (référencés, jamais copiés), stockage, modèle.

Le script `native/shared/PronoteAutomation.js` est intégré tel quel au bundle.

En Debug, l'argument de lancement `--pronote-public-demo` connecte le compte public de démonstration Index Education par le protocole natif seul, sans rien enregistrer dans le trousseau.
