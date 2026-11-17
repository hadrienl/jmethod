# J-Method — app Android

Application Kotlin / Jetpack Compose (Material 3), `fr.jmethod.app`, Android 8+ (API 26), compile/target API 36. Aucun serveur : données de révision dans un document JSON privé, jeton Pronote chiffré par Android Keystore, sauvegardes Android désactivées.

## Compiler et tester

```sh
cd apps/android
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Java 21 est requis (le JBR Java 25 échoue avec le Kotlin DSL de Gradle 8.13). `local.properties` n’est pas versionné.

## Organisation

- `model/` : schéma du web (`src/types`) en kotlinx.serialization, constantes (`src/lib/constants.ts`).
- `domain/` : algorithme adaptatif, programme du soir, appariement des matières, modèle d’Aujourd’hui, validation et aperçu de score. Portés à l’identique, particularités du TypeScript comprises.
- `data/` : document JSON unique écrit atomiquement (`files/jmethod-data.json`), données de démonstration au premier lancement, export compatible `exportAllData`.
- `pronote/` : protocole mobile, client HTTPS en lecture seule, contrôle du bridge, coffre Keystore, contrôleur de connexion (formulaire natif, page officielle masquée, QR automatique, reprise par jeton, synchronisation des 14 prochains jours).
- `ui/` : Aujourd’hui, Réglages (compte Pronote), feu d’artifice Canvas, thème clair violet et citron.

Ressources partagées lues sans copie dans `native/shared/` : `PronoteAutomation.js` est ajouté à l’APK au build ; les tests JVM rejouent `protocol-vectors.json` et `domain/domain-vectors.json`.
