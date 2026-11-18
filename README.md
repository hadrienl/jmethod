# J-Method

Assistant de révision pour collégiens et lycéens, basé sur la méthode des J : chaque soir, l’app prépare les révisions du lendemain (Découvrir, S’entraîner, Se tester) et adapte les intervalles à l’autoévaluation.

Applications natives sans serveur : les données restent sur l’appareil, sans synchronisation entre appareils.

| Plateforme | Dossier | Pile |
|---|---|---|
| iOS 17+ | [`apps/ios`](apps/ios/README.md) | SwiftUI, Observation, XcodeGen |
| Android 8+ (API 26) | [`apps/android`](apps/android/README.md) (`app`, `shared`) | Kotlin, Compose Multiplatform, Material 3 |
| Windows 10+, macOS (Apple Silicon) | [`apps/android`](apps/android/README.md#app-ordinateur) (`desktopApp`, `shared`) | Kotlin, Compose Desktop, Material 3 |

## Fonctionnalités

- **Connexion Pronote** en lecture seule : code d’établissement, identifiant et mot de passe dans un formulaire natif ; la page officielle est utilisée masquée pour associer l’app, puis un client HTTPS natif lit les données. « Rester connecté » garde les identifiants chiffrés sur l’appareil (Keychain, Android Keystore). Synchronisation en arrière-plan. Sur ordinateur, connexion directe par le protocole natif (sans page officielle), identifiants dans le Trousseau macOS ou chiffrés par DPAPI sous Windows, actualisation pendant que l’app est ouverte.
- **Aujourd’hui** : le programme du soir pour le lendemain, l’emploi du temps de demain, l’autoévaluation de 0 à 100 et la replanification adaptative.
- **Planning** : jour, semaine, mois, filtres, détail d’une séance.
- **Mes cours** : création (y compris depuis un créneau Pronote), fiche, historique, suppression.
- **Mes progrès** et **Réglages** : indicateurs, algorithme, paliers, couleurs des matières, sauvegarde et restauration JSON.
- **Cahier de textes** : contenus de séance, devoirs et pièces jointes, en lecture seule.

## Compiler

```sh
# iOS
xcodegen generate --spec apps/ios/project.yml
open apps/ios/JMethod.xcodeproj

# Android (Java 21) : tests JVM communs et APK
cd apps/android && ./gradlew :shared:desktopTest :app:assembleDebug

# Ordinateur : lancer l’app (installeurs MSI et DMG construits par GitHub Actions)
cd apps/android && ./gradlew :desktopApp:run
```

Les détails (signature, appareils, tests d’interface) sont dans le README de chaque app.

## Organisation du dépôt

- `apps/` : l’app iOS ; l’app Android et l’app ordinateur (module Kotlin Multiplatform commun `shared`).
- `native/shared/` : ce que les deux apps partagent — script d’automatisation de la page Pronote, contrat du pont WebView, références du protocole (emploi du temps, cahier de textes) et vecteurs de test synthétiques.
- `native/shared/domain/` : vecteurs de parité de la logique métier, rejoués par les tests Swift et JVM, et [écarts volontaires](native/shared/domain/DEVIATIONS.md).
- `native/ios`, `native/android`, `native/tests` : POC de connexion qui a validé l’approche.
- `docs/` : plan de développement, direction de design, programme du soir.

## Historique

L’app a d’abord été prototypée en web (React, connecteur Python local). Ce POC a été retiré du dépôt une fois les apps natives livrées ; il reste disponible au tag [`web-poc-final`](https://github.com/hadrienl/jmethod/tree/web-poc-final), qui est aussi la source des vecteurs de parité.

Le protocole Pronote utilisé n’est pas officiel. Les apps n’envoient aucune requête d’écriture et ne stockent ni QR code ni donnée de compte dans le dépôt.
