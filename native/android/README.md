# J-Method — POC Android natif

Application Kotlin / Jetpack Compose, Android 8+ (API 26), compile/target API 36. Aucun serveur applicatif : les requêtes HTTPS et la cryptographie Pronote sont exécutées sur le téléphone. L’identifiant de paquet est `fr.jmethod.connectionpoc`.

## Compiler et installer

Avec Android SDK API 36 et Java 21 (le JBR Java 25 échoue avec le Kotlin DSL de Gradle 8.13) :

```sh
cd native/android
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
/bin/bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.VIEW -d jmethodpoc://demo fr.jmethod.connectionpoc
```

`local.properties` peut définir `sdk.dir` si nécessaire, il ne doit pas être commité. Aucun émulateur n’est requis pour un téléphone USB autorisé.

## Tester

- « Tester uniquement le protocole natif public » ou `jmethodpoc://native-demo` teste directement le client Kotlin sans QR ni stockage de jeton.
- « Tester la démonstration publique » démarre le compte public Index Éducation. Ce résultat ne prouve pas la connexion d’un établissement réel.
- Pour un compte réel, saisir l’adresse HTTPS de l’espace Élèves ou Parents hébergé sur `*.index-education.net`, puis se connecter dans la WebView officielle visible. Les mots de passe ne sont jamais lus par le bridge.
- Après connexion, le script partagé ouvre le QR d’association et renseigne uniquement un code éphémère aléatoire. Le QR est décodé par ZXing, puis le client Kotlin effectue la connexion et lit les sept prochains jours de cours. La WebView reste vivante et dimensionnée pendant cette étape.
- « Afficher le navigateur » permet de reprendre une validation ou de comprendre un blocage ; « Annuler » invalide les traitements en cours ; « Oublier le compte » retire le jeton chiffré et les cookies locaux.
- Le jeton renouvelé est chiffré AES-GCM par une clé Android Keystore ; la sauvegarde Android est désactivée. Ni QR, ni code, ni jeton, ni données personnelles ne sont journalisés.

Les tests JVM utilisent exclusivement les vecteurs synthétiques partagés générés avec pronotepy (AES/QR), et vérifient la politique d’origine, la lecture seule et l’analyse des paramètres de session. Le protocole non officiel peut changer ; les établissements à domaine personnalisé et les validations de sécurité supplémentaires demandent une adaptation. Le compte de démonstration peut invalider ses jetons entre deux lancements. Cette première version conserve son état d’interface dans l’activité : une rotation/recréation peut nécessiter une reprise de session. Les traitements réseau bloquants sont exécutés hors du thread UI ; leur résultat est invalidé après annulation.
