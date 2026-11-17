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
- `domain/` : algorithme adaptatif, programme du soir, appariement des matières, modèles d’Aujourd’hui, du Planning (`PlanningModel`, `RevisionDetailModel`) et de Mes cours (`CoursesListModel`, `CourseDetailModel`, `CourseEditing` : création, préremplissage depuis un créneau, suppression), validation et aperçu de score. Portés à l’identique, particularités du TypeScript comprises, sauf les écarts volontaires de `native/shared/domain/DEVIATIONS.md` (D1, D2, D7 à D12 ici), appliqués et testés nommément dans `PlanningCoursesVectorsTest` / `Deviations`.
- `data/` : document JSON unique écrit atomiquement (`files/jmethod-data.json`), vide au premier lancement (réglages et matières par défaut), migrations (`DataMigrations` : la v1 retire la démo des installations du jalon 1), export compatible `exportAllData`.
- `pronote/` : protocole mobile, client HTTPS en lecture seule, contrôle du bridge, coffre Keystore, contrôleur de connexion (formulaire natif, page officielle masquée, QR automatique, reprise par jeton, synchronisation des 14 prochains jours).
- `ui/` : écran de connexion (code d’établissement ou adresse, Élève/Parent), chargement pendant la reprise, bandeau hors ligne, Aujourd’hui, Planning (jour, semaine, mois en grille compacte, filtres, détail de séance), Mes cours (liste, recherche, fiche, création plein écran avec DatePicker, suppression confirmée), Réglages (compte Pronote), feu d’artifice Canvas, thème clair violet et citron. Chaque onglet a son ViewModel (StateFlow) ; le retour système ramène d’abord à Aujourd’hui.

L’app exige une connexion Pronote : sans jeton (ou jeton refusé), seul l’écran de connexion est affiché ; avec un jeton et sans réseau, l’app s’ouvre sur les données locales avec un bouton Réessayer.

Ressources partagées lues sans copie dans `native/shared/` : `PronoteAutomation.js` est ajouté à l’APK au build ; les tests JVM rejouent `protocol-vectors.json` et `domain/domain-vectors.json`.

« Rester connecté » (activé par défaut) enregistre l’établissement, le type de compte, l’identifiant et le mot de passe, chiffrés par une clé Android Keystore dédiée (AES-GCM), uniquement sur ce téléphone. Si Pronote refuse le jeton, une seule reconnexion silencieuse est tentée au premier plan (page officielle masquée) ; un mot de passe refusé est supprimé. WorkManager synchronise les 14 jours environ toutes les 6 h par jeton seulement (HTTPS natif, verrou partagé avec l’app) ; un jeton refusé en arrière-plan est seulement marqué. Le formulaire préremplit tout ce qui est enregistré (mot de passe masqué, icône œil). La déconnexion supprime jeton, enfant suivi, données web, créneaux importés et la tâche périodique ; elle garde les identifiants pour le préremplissage (sans reconnexion automatique), sauf avec « Se déconnecter et oublier mes identifiants ».
