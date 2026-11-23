# J-Method — apps Android et ordinateur

Application Kotlin / Compose (Material 3) pour Android et pour ordinateur (Windows, macOS), Kotlin Multiplatform et Compose Multiplatform. Android : `fr.jmethod.app`, Android 8+ (API 26), compile/target API 36. Aucun serveur : données de révision dans un document JSON privé, secrets dans le coffre du système, pas de synchronisation entre appareils (chaque appareil garde ses propres données).

## Modules

| Module | Rôle |
|---|---|
| `shared` | KMP, cibles Android et JVM ordinateur (`desktop`). `commonMain` : modèle et stockage JSON, logique métier, client du protocole Pronote et cryptographie, cahier de textes, analyse des documents (ODT/Office/PDF maison) et tous les écrans Compose. Les deux cibles étant des JVM, le code commun utilise directement le JDK (`java.time`, `javax.crypto`, `org.json`). `androidMain` / `desktopMain` : rendu PDF (PdfRenderer / PDFBox), images, retour système, barres de défilement, coffres de l’ordinateur. Tests JVM (`desktopTest`), vecteurs de `native/shared` compris. |
| `app` | Application Android, fine : activité, WorkManager, Keystore (`TokenVault`, `KeystoreLoginStore`, UUID), WebView officielle et QR, FileProvider, Custom Tabs, sélecteurs de documents. |
| `desktopApp` | Application ordinateur : fenêtre Compose Desktop, connexion directe à Pronote, Trousseau macOS ou DPAPI Windows, dossiers de données du système, `Desktop.open`, installeurs MSI et DMG. |

Les services du système passent par `PlatformServices` (ouvrir un document ou un lien, exporter, importer, page officielle) fourni par chaque app, et par quelques `expect`/`actual` d’interface dans `shared`.

## Compiler et tester

```sh
cd apps/android
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :shared:desktopTest :app:assembleDebug < /dev/null
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Java 21 est requis (le JBR Java 25 échoue avec le Kotlin DSL de Gradle 8.13). `local.properties` n’est pas versionné ; `ANDROID_HOME` suffit, même pour l’app ordinateur (le module `shared` a une cible Android). Les tests JVM (logique, protocole, connexion, cahier de textes, documents) sont dans `shared/src/desktopTest` et tournent avec `:shared:desktopTest`.

## App ordinateur

```sh
cd apps/android
./gradlew :desktopApp:run < /dev/null                 # lance l’app (macOS, Windows)
./gradlew :desktopApp:packageDmg < /dev/null          # macOS : DMG non signé (JDK Temurin, pas celui de Homebrew)
./gradlew :desktopApp:packageMsi                      # Windows : MSI non signé
```

Mêmes écrans et mêmes fonctions qu’Android. Différences :

- **Connexion directe** : pas de page officielle ni de QR. Le formulaire envoie l’identifiant et le mot de passe Pronote par le protocole natif à `<page>?login=true` (FonctionParametres, Identification, Authentification avec le défi habituel), puis les mêmes lectures que le mobile, en lecture seule. Validée sur la démonstration publique d’Index Education (identifiants publiés par l’éditeur). La page web 2026 répond `Start({"h":…,"d":true,"a":3})` : échanges en JSON clair (ni `CrA` ni `CoA`), `modeCompLog`/`modeCompMdp` à 0 ou 1. Une double authentification (code personnel, appareil de confiance) affiche « pas encore prise en charge ». Erreur 25 : blocage d’une heure enregistré dans le dossier de l’app ; erreur 8 ou autre perte de session : reconnexion par mot de passe et un rejeu ; inactivité de plus de 10 minutes : reconnexion avant la lecture.
- **Rester connecté** : établissement, type de compte, identifiant et mot de passe dans le Trousseau macOS (service « J-Method ») ou chiffrés par DPAPI dans `%APPDATA%\J-Method\secrets` sous Windows. Reconnexion silencieuse au lancement et quand la session expire ; le mot de passe enregistré n’est jamais révélable. Sans « Rester connecté », le mot de passe n’est gardé qu’en mémoire jusqu’à la fermeture.
- **Données** : `%APPDATA%\J-Method` (Windows) ou `~/Library/Application Support/J-Method` (macOS) : `jmethod-data.json`, `documents/` ; cache des pièces jointes dans `%LOCALAPPDATA%\J-Method\Cache` ou `~/Library/Caches/J-Method`.
- **Actualisation** pendant que l’app est ouverte (même règle que le retour au premier plan Android, vérifiée toutes les 15 minutes) ; aucun service en arrière-plan.
- **Documents** : visionneuse dans l’app (PDF rendu par PDFBox, images, vue « Lecture »), « Ouvrir avec… » ouvre l’app par défaut du système ; un document actif (HTML, SVG) est ouvert comme texte.
- **Clavier et souris** : Entrée envoie le formulaire, Échap ferme la visionneuse, la fiche ou le dialogue du dessus, barres de défilement, texte sélectionnable dans le cahier de textes et la vue « Lecture ». Fenêtre de 360 × 600 au minimum.

**Fenêtre adaptative** (aussi sur tablette et pliable Android) : largeur compacte (< 600 dp) comme un téléphone ; à partir de 600 dp, rail de navigation, colonne de lecture centrée, feuilles en panneau latéral (aide de connexion en dialogue) ; à partir de 840 dp, la fiche d’un cours (Mes cours) ou d’une séance (Aujourd’hui, Planning) s’ouvre à côté de la liste.

**Installeurs** : le workflow GitHub Actions `.github/workflows/desktop.yml` lance les tests JVM et construit le MSI (Windows) en artefact à chaque changement. Pour une étiquette `desktop-vX.Y.Z` (X ≥ 1, macOS refuse une version en 0), il construit aussi le DMG (macOS, Apple Silicon) et publie une Release avec les deux ; faute de machine macOS disponible chez GitHub, la Release part avec le MSI seul. Ils ne sont pas signés :

- Windows : SmartScreen affiche « Windows a protégé votre ordinateur » ; cliquer « Informations complémentaires » puis « Exécuter quand même ». L’installation se fait pour l’utilisateur courant.
- macOS : ouvrir le DMG et glisser J-Method dans Applications. Au premier lancement, macOS refuse l’app non signée : Réglages Système, Confidentialité et sécurité, « Ouvrir quand même ». Si macOS dit que l’app est endommagée (signature ad hoc d’un fichier téléchargé), lancer `xattr -dr com.apple.quarantine /Applications/J-Method.app` dans le Terminal puis rouvrir.

**Microsoft Store** : sous Windows 11, le Contrôle intelligent des applications bloque le MSI non signé au lancement, sans option pour passer outre. Le job Windows construit donc aussi `J-Method-X.Y.Z.msix` (artefact « J-Method-msix »), à téléverser dans l’Espace partenaires : le Store le signe. Le manifeste et les logos sont dans `desktopApp/msix`. L’identité réservée dans l’Espace partenaires (Gestion des produits › Identité du produit) se déclare en variables du dépôt `MSIX_IDENTITY_NAME` (`Package/Identity/Name`), `MSIX_PUBLISHER` (`Package/Identity/Publisher`, `CN=…`) et `MSIX_PUBLISHER_DISPLAY_NAME` ; sans elles, le paquet porte une identité de test que le Store refuse. Le job installe aussi le MSIX signé par un certificat de test et vérifie que l’app démarre (étape non bloquante).

Essais locaux sans toucher aux vraies données : `-Pjmethod.dataDir=<dossier> -Pjmethod.secretService="J-Method essai"` sur `:desktopApp:run`. Visite guidée avec captures (hors CI) : `./gradlew :desktopApp:test --tests '*DesktopTour*' -Pjmethod.tour=<dossier>` (hors ligne, données d’exemple) ; ajouter `-Pjmethod.demoLogin=true` pour se connecter à la démonstration publique (quelques connexions seulement). `:shared:desktopTest -Pjmethod.demoLogin=true --tests '*DemoLoginTest*'` vérifie le protocole seul.

## Organisation (`shared/src/commonMain`)

- `model/` : schéma du web (`src/types`) en kotlinx.serialization, constantes (`src/lib/constants.ts`).
- `domain/` : algorithme adaptatif, programme du soir, appariement des matières, modèles d’Aujourd’hui, du Planning (`PlanningModel`, `RevisionDetailModel`) et de Mes cours (`CoursesListModel`, `CourseDetailModel`, `CourseEditing` : création, préremplissage depuis un créneau, suppression), validation et aperçu de score. Portés à l’identique, particularités du TypeScript comprises, sauf les écarts volontaires de `native/shared/domain/DEVIATIONS.md` (D1 à D13), appliqués et testés nommément dans `PlanningCoursesVectorsTest`, `StatsSettingsVectorsTest` et `Deviations`. `NotebookVectorsTest` rejoue `native/shared/notebook-vectors.json`.
- `data/` : document JSON unique écrit atomiquement (`files/jmethod-data.json`), vide au premier lancement (réglages et matières par défaut), migrations (`DataMigrations` : la v1 retire la démo des installations du jalon 1), export compatible `exportAllData`.
- `pronote/` : protocole mobile, client HTTPS en lecture seule (liste blanche avec `PageCahierDeTexte`, onglets 88/89, six semaines au plus, refus local de toute fonction `Saisie…`), contrôle du bridge, coffre Keystore, contrôleur de connexion (formulaire natif, page officielle masquée, QR automatique, reprise par jeton, synchronisation des 14 prochains jours), cahier de textes (`Notebook.kt` : fenêtre de 28 jours, dates, HTML vers texte sûr, liens `http(s)`, modèle) et téléchargement protégé des pièces jointes (URL construite dans le client puis oubliée, même hôte et cookies de la session, 20 Mo au plus, cache privé `notebook/` effacé à la déconnexion et au changement d’enfant, ouverture par `FileProvider` en lecture seule, HTML et SVG présentés comme texte).
- `ui/` : écran de connexion (code d’établissement ou adresse, Élève/Parent), chargement pendant la reprise, bandeau hors ligne, Aujourd’hui, Planning (jour, semaine, mois en grille compacte, filtres, détail de séance), Mes cours (liste, recherche, fiche, création plein écran avec DatePicker, suppression confirmée), Réglages (compte Pronote), feu d’artifice Canvas, thème clair violet et citron. Mes progrès (indicateurs, série réelle de régularité, matières à travailler, barres par matière), Réglages complets (compte Pronote, algorithme, paliers, couleurs des matières par roue chromatique avec aperçu, export JSON compatible web et restauration validée par le Storage Access Framework, À propos), cahier de textes en lecture seule (depuis Mes cours ou la fiche d’un cours). Chaque onglet a son ViewModel (StateFlow) ; le retour système ramène d’abord à Aujourd’hui.

L’app exige une connexion Pronote : sans jeton (ou jeton refusé), seul l’écran de connexion est affiché ; avec un jeton et sans réseau, l’app s’ouvre sur les données locales avec un bouton Réessayer.

Ressources partagées lues sans copie dans `native/shared/` : `PronoteAutomation.js` est ajouté à l’APK au build (module `app`) ; les tests JVM de `shared` rejouent `protocol-vectors.json` et `domain/domain-vectors.json`.

« Rester connecté » (activé par défaut) enregistre l’établissement, le type de compte, l’identifiant et le mot de passe, chiffrés par une clé Android Keystore dédiée (AES-GCM), uniquement sur ce téléphone. Si Pronote refuse le jeton, une seule reconnexion silencieuse est tentée au premier plan (page officielle masquée) ; un mot de passe refusé est supprimé. WorkManager synchronise les 14 jours environ toutes les 6 h par jeton seulement (HTTPS natif, verrou partagé avec l’app) ; un jeton refusé en arrière-plan est seulement marqué. Le formulaire préremplit tout ce qui est enregistré ; le mot de passe enregistré reste masqué et n’est jamais révélable (l’icône œil n’apparaît que pour une saisie, et toute modification remplace entièrement le mot de passe enregistré). L’erreur Pronote 25 (trop de tentatives) suspend tout essai automatique (lancement, retour au premier plan, reconnexion silencieuse, WorkManager) pendant au moins une heure ; une seule reconnexion silencieuse automatique par heure. Une connexion par jeton déjà partie va jusqu’à l’enregistrement du jeton renouvelé, même si la reprise est interrompue. Expiration de session (`native/shared/protocol-reference.md`, « Session expiry ») : une seule règle (`PronoteSessionRules`) pour la synchronisation, le changement d’enfant, le cahier de textes, les pièces jointes et WorkManager. Après plus de 10 minutes d’inactivité, la session est reconnectée par jeton avant la lecture ; toute erreur `Erreur.G` sauf 25, un statut HTTP inattendu ou une réponse non JSON ou indéchiffrable déclenche une seule reconnexion par jeton (cookies, compteur, clé et IV remis à zéro, dernier jeton du coffre), puis un seul rejeu. Une pièce jointe dont la session a changé fait relire le cahier de textes avant le téléchargement. L’UUID d’application est généré une fois (Keystore) et réutilisé pour toutes les connexions par QR. La déconnexion supprime jeton, enfant suivi, données web, créneaux importés et la tâche périodique ; elle garde les identifiants pour le préremplissage (sans reconnexion automatique), sauf avec « Se déconnecter et oublier mes identifiants ».

## Du cahier de textes aux cours

Spécification commune : `native/shared/notebook-to-course.md`, règles 1 à 32 de `native/shared/documents/README.md`. Une séance du cahier de textes devient un cours (bouton « Créer un cours » / « Voir le cours », ou réglage « Créer les cours automatiquement », désactivé par défaut, avec proposition d’importer les 14 derniers jours). Les documents joints sont téléchargés, analysés (`documents/DocumentAnalyzer.kt` : ODT, ODP, DOCX, PPTX par `java.util.zip` et DOM sans DTD ; TXT/MD ; PDF par `documents/PdfText.kt`, lecteur maison sur le modèle de `reference.py`, sans bibliothèque) puis copiés dans `filesDir/documents/<courseId>/` (exclu des sauvegardes). Titre et chapitre : `domain/NotebookCourses.kt`. Fiche du cours : section « Documents », visionneuse dans l’app (PdfRenderer avec zoom, images, vue « Lecture » pour ODF/Office/TXT), « Ouvrir avec… » par FileProvider. Supprimer un cours supprime ses fichiers et marque la séance `dismissed`. Liens du cahier : Custom Tabs. `NotebookCourseVectorsTest` rejoue `document-title-vectors.json` (28 documents) et `notebook-course-vectors.json` (préremplissage, 5 scénarios).

