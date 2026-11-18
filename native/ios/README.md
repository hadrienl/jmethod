# POC connexion iOS

Application SwiftUI iOS 17+, sans serveur et sans dépendance tierce. Connexion officielle dans WKWebView, automatisation locale de la génération du QR mobile, décodage Core Image, protocole mobile en URLSession/CommonCrypto/zlib, jeton rotatif dans le trousseau `WhenUnlockedThisDeviceOnly`, lecture de quatorze jours de cours.

Ouvrir `JMethodConnectionPOC.xcodeproj`, choisir son équipe de développement dans Xcode, puis lancer la cible sur l’iPhone. Le projet se régénère avec `xcodegen generate --spec native/ios/project.yml` depuis la racine. L’orchestrateur doit être seul à lancer les builds.

Exemple de compilation :

```sh
xcodebuild -project native/ios/JMethodConnectionPOC.xcodeproj -scheme JMethodConnectionPOC -destination 'generic/platform=iOS' -derivedDataPath /tmp/jmethod-ios CODE_SIGNING_ALLOWED=NO build
```

Le bouton de démonstration ou `jmethodpoc://demo` lance uniquement le compte public Index Education. `--public-demo` permet le même scénario au lancement. L’utilisateur doit se connecter lui-même pour un établissement réel. Les redirections ENT et vérifications restent visibles. En cas d’échec automatique, relancer ou importer le QR et son code temporaire dans l’interface. Aucun mot de passe réel n’est lu par l’automatisation.

Les tests utilisent les vecteurs synthétiques partagés (AES/UTF-8, QR, compression et validation des adresses). La démonstration publique peut refuser la reprise des jetons après redémarrage. Ce POC ne garantit pas la stabilité d’un protocole non officiel ; une évolution Pronote peut demander une adaptation. Les builds et essais sur appareil sont effectués par l’orchestrateur ; leur résultat doit être rapporté séparément.
