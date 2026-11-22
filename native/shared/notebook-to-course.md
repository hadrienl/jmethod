# Du cahier de textes aux cours

Spécification commune iOS / Android. Le cahier de textes Pronote (voir `notebook-reference.md`) alimente la création des cours J-Method : manuellement par un bouton, ou automatiquement si l’utilisateur l’active. Rien n’est jamais écrit dans Pronote.

## 1. Unité : une feuille personnelle et ses ressources

Depuis le schéma 2 (ticket #6), un `Course` représente une feuille personnelle. Sa `reference` (par exemple C1) est modifiable et unique dans la matière pour les nouvelles créations. Le titre et le chapitre sont facultatifs à la saisie. La feuille est enregistrée **sans révision**.

- Une séance Pronote fournit des ressources : le formulaire permet de créer une nouvelle feuille ou de les associer à une feuille existante. « Autre feuille » permet de réutiliser explicitement une même séance. `sources[]` conserve ces origines ; `source` reste la première origine pour la compatibilité.
- Le suivi `notebookImports` protège toujours contre une réimportation automatique. Seule une action manuelle volontaire peut produire une autre feuille depuis une séance déjà traitée.
- La création automatique, lorsqu’elle est activée, enregistre uniquement des feuilles sans programme. Les devoirs ne créent ni feuille ni programme.
- « Démarrer un programme » choisit des feuilles existantes d’une même matière et une date J0 (demain par défaut). Un programme actif bloque le démarrage d’un autre programme pour les mêmes feuilles. Une feuille ajoutée ultérieurement ne reçoit aucun historique rétroactif.
- `RevisionProgramme` conserve `courseIds`, `startDate`, `rhythmSnapshot`, `adaptiveEnabled` et un éventuel `needsReview`. Chaque révision conserve également sa sélection immuable `courseIds` et son `programmeId` ; `courseId` est la première feuille pour la compatibilité des vues historiques.
- Le préréglage est J0/J1/J3 (encodage), J7/J14/J25 (consolidation), J45/J60/J100 (récupération). Chaque étape stocke sa phase explicitement. L’adaptation aux scores est activée par défaut ; le mode fixe ne déplace aucune date et ne crée pas de rattrapage.
- En mode adaptatif, les écarts utilisent les jours nominaux du snapshot du programme, même après un changement des réglages globaux. Les rattrapages gardent le groupe et la phase de la séance d’origine et portent `originRevisionId`. La validation reste idempotente et le calcul conserve le jour préparé le soir.
- Un rythme peut comporter un ou plusieurs jours, commencer à J0, puis suivre des jours uniques strictement croissants jusqu’à la borne technique de 3650. L’édition des jours/phases est enregistrée pour les futurs programmes ; l’application aux programmes actifs nécessite un aperçu et une confirmation. Les séances réalisées et rattrapages sont conservés ; une nouvelle date passée devient due.
- Une feuille utilisée dans un programme ne peut pas être supprimée : l’app conserve son historique et ses documents et l’explique à l’utilisateur.
- Le schéma 2 et les exports 2.0 conservent tous ces champs. Les exports 2.0 utilisent une enveloppe `{version, schemaVersion, exportDate, data}` : les anciennes apps, qui exigent `courses` à la racine, les refusent plutôt que perdre les programmes. La migration crée un programme historique par ancien cours, sans changer les identifiants, dates, phases, scores ou documents. Les rythmes ambigus portent `needsReview` et ne sont pas adaptés automatiquement. La migration est idempotente et ne regroupe pas les feuilles.
- Le nouveau document est écrit atomiquement dans `jmethod-data-v2.json` (ou le même suffixe pour un chemin personnalisé). Le fichier d’origine reste intact comme sauvegarde avant conversion. Une ancienne app conserve ainsi ses données v1 et ne peut pas écraser les programmes v2. Les données créées dans une ancienne app après retour en arrière ne sont pas fusionnées automatiquement.
- Chaque nouveau fichier importé conserve son `sourceSessionKey` propre, y compris après association ou copie vers une autre feuille. Deux séances peuvent fournir des fichiers de même nom : ils sont distingués par provenance, leur date est affichée et le retéléchargement ne cherche que dans la séance du fichier. Une recherche ambiguë est refusée plutôt que télécharger le premier homonyme. Les anciens documents sans provenance explicite gardent la compatibilité historique lorsqu’une seule origine est connue ; cette origine est matérialisée avant l’association d’une nouvelle séance, sans modifier les identifiants ni les fichiers. Plusieurs origines possibles sans provenance enregistrée interdisent le retéléchargement automatique, même si un seul homonyme reste dans la fenêtre lue.
- La fiche de séance Compose affiche les contenus, liens et pièces jointes non importées de **chaque** feuille du groupe, comme iOS.
- Vecteurs calendaires partagés : `domain/programme-vectors.json`, exécutés par les tests Swift et Kotlin.

Les règles de clé stable et de téléchargement ci-dessous restent applicables. Les mentions historiques « une séance = un cours » ou « génération initiale » sont remplacées par les règles ci-dessus.


- Source : une `SessionEntry` (contenus de séance, réponse 89). Les devoirs (88) ne créent jamais de cours.
- Clé de dédoublonnage **stable entre sessions Pronote** (corrigé le 18/11/2026 : les `N` du cahier et `cours.V.N` changent à chaque session et ne doivent jamais servir de clé) :
  `sessionKey = "pn1:" + date + "|" + matièreNormalisée + "|" + rang`
  - `date` : `SessionEntry.date` au format `YYYY-MM-DD` ;
  - `matièreNormalisée` : `SessionEntry.subject` en minuscules, accents retirés (NFD sans diacritiques), toute suite de caractères non alphanumériques remplacée par une espace, rognée ;
  - `rang` : rang (à partir de 1) de la séance parmi celles du même instantané ayant la même `date` et la même `matièreNormalisée`, dans l’ordre de la réponse Pronote (ordre conservé par le tri stable du modèle).
  - Le contenu (titres, textes) n’entre pas dans la clé : une séance modifiée par le professeur ne crée pas de doublon.
- Ceinture et bretelles : une séance est aussi considérée comme déjà importée si un cours existe avec `source.kind == "pronote-notebook"`, la même `source.date`, la même matière normalisée (`source.subjectLabel`) et le même `source.ordinal`.
- Précisions (normalisation Unicode, rang compté sur tout l’instantané, ordre de migration, doublons dus à l’ancien bug ramenés au même rang s’ils ont même titre et même chapitre, fusion des entrées) : règles 33 à 40 de `documents/README.md`, qui font foi.
- **Migration** (une fois, idempotente) des données créées avec l’ancienne clé : pour chaque cours ayant une `source`, recalculer sa clé à partir de `source.date`, `source.subjectLabel` et d’un rang = son ordre de création (`createdAt`) parmi les cours de même date et matière ; réécrire `notebookImports` avec la nouvelle clé (statut et `courseId` conservés) ; si deux cours obtiennent la même clé, la clé désigne le plus ancien et rien n’est supprimé ; supprimer les entrées dont la clé n’est pas au format `pn1:` et qui ne désignent aucun cours existant (anciens `dismissed`, impossibles à rattacher).
- La date de la feuille peut reprendre celle de la séance (`SessionEntry.date`). La date J0 du programme est choisie séparément ; aucune révision n’est générée à l’import.
- Matière : `matchSubject(SessionEntry.subject, subjects)`. Pas de correspondance → pas de création automatique ; la séance reste proposée avec le bouton (l’utilisateur choisit la matière dans le formulaire).

## 2. Données ajoutées au document

Champs facultatifs, ignorés par une ancienne sauvegarde web, conservés par l’export / la restauration JSON :

```jsonc
// Course
"source": { "kind": "pronote-notebook", "sessionKey": "pn1:2026-11-18|mathematiques|1", "subjectLabel": "MATHEMATIQUES", "date": "2026-11-18", "ordinal": 1 },
"documents": [
  { "id": "doc-<uuid>", "name": "Chapitre 3.pdf", "mime": "application/pdf", "size": 48213,
    "file": "documents/<courseId>/<docId>.pdf", "extractedTitle": "Les fonctions affines",
    "sourceSessionKey": "pn1:2026-11-18|mathematiques|1" }
]
// Racine du document
"notebookImports": {
  "<sessionKey>": { "status": "created" | "dismissed", "courseId": "…", "at": "2026-11-18T19:02:11Z" }
},
"settings": { …, "notebookAutoCreate": false, "notebookAutoCreateSince": "2026-11-18" }
```

- Les fichiers sont copiés dans le stockage privé **non cache** de l’app (`Application Support` / `filesDir`), exclus des sauvegardes système et d’iCloud. L’export JSON contient leurs métadonnées, pas leur contenu ; une restauration sans fichier affiche le document comme « à retélécharger » (bouton qui relit le cahier et retélécharge si la séance est encore dans la fenêtre).
- Supprimer une feuille sans programme supprime ses fichiers et passe son `notebookImports[sessionKey].status` à `dismissed` : il n’est plus jamais recréé automatiquement (le bouton manuel reste possible).
- La déconnexion Pronote ne supprime ni les cours ni leurs documents.
- Limites : 20 Mo par fichier (règle existante), 10 fichiers par cours ; au-delà, les suivants restent consultables dans le cahier de textes.

## 3. Création manuelle (bouton)

Sur chaque séance du cahier de textes :
- jamais importée → bouton **« Créer un cours »** : ouvre le formulaire de création prérempli (matière, date J0, chapitre, titre, description, liste des documents joints cochés par défaut) ; les fichiers sont téléchargés et le titre extrait **avant** l’ouverture du formulaire (indicateur de progression ; en cas d’échec de téléchargement, formulaire ouvert sans ce document, avec un message) ;
- déjà importée (`created`, cours existant) → **« Voir le cours »** ;
- `dismissed` → bouton « Créer un cours » de nouveau disponible (création manuelle volontaire).

## 4. Création automatique (réglage)

Réglages → section « Cahier de textes » → interrupteur **« Créer les cours automatiquement »**, désactivé par défaut.

- Activation : enregistre `notebookAutoCreateSince = aujourd’hui`, puis lit le cahier et propose dans une confirmation : « Importer aussi les N séances des 14 derniers jours ? » (Importer / Seulement à partir d’aujourd’hui).
- Déclenchement : après chaque lecture réussie du cahier au premier plan (ouverture de l’écran, actualisation) et après chaque synchronisation au premier plan réussie, au plus une lecture du cahier toutes les 2 h pour ce seul besoin. Pas de création en arrière-plan (téléchargements et extraction réservés au premier plan).
- Séances éligibles : `notebookAutoCreateSince ≤ date ≤ aujourd’hui` (les contenus publiés à l’avance pour une séance future attendent leur date), jamais importées (ni `created` ni `dismissed`), matière reconnue.
- Pour chaque séance éligible : télécharger les fichiers (≤ 20 Mo, ≤ 10), extraire titre et chapitre (section 5), créer le cours, enregistrer `notebookImports`. Un échec de téléchargement n’empêche pas la création (titre de repli).
- Retour utilisateur : message « 3 cours créés depuis le cahier de textes » (toast) et, dans le cahier, l’état « Cours créé » sur chaque séance concernée.

## 5. Titre, chapitre et description

### 5.1 Document principal
Premier fichier, dans l’ordre des contenus puis des pièces jointes, dont le type est extractible : `pdf`, `odt`, `odp`, `docx`, `pptx`, `txt`, `md`. Type déterminé par l’extension du nom puis par la signature (`%PDF-`, zip contenant `mimetype` ODF ou `[Content_Types].xml` OOXML).

### 5.2 Candidats, dans l’ordre
1. Titre des métadonnées : PDF (`/Title` du dictionnaire Info, sinon `dc:title` du XMP), ODF (`meta.xml` → `dc:title`), OOXML (`docProps/core.xml` → `dc:title`).
2. Premier titre du document : ODT premier `text:h` (niveau le plus haut présent en premier) ou paragraphe de style `Title`/`Titre` ; DOCX premier paragraphe de style `Title`, `Titre`, `Heading1`, `Titre1` ; ODP/PPTX titre de la première diapositive (`presentation:class="title"` / `p:ph type="title"|"ctrTitle"`) ; PDF première ligne significative de la première page (texte de plus grande taille si disponible, sinon première ligne) ; TXT/MD première ligne non vide (sans `#`).
3. Titre Pronote du contenu (`Content.title`) du premier contenu de la séance.
4. Nom du fichier principal sans extension (`_` et `-` multiples → espace).
5. « Cours du <jour mois> » (ex. « Cours du 18 novembre »).

Un candidat est **rejeté** s’il est vide après normalisation, fait moins de 3 lettres, est un nombre ou une date, ressemble à un en-tête administratif (`^(nom|prénom|classe|date)\s*:`), ou est générique (insensible à la casse et aux accents) : `sans titre`, `untitled`, `document`, `document\d*`, `présentation\d*`, `diapositive \d+`, `cours`, `séance`, `exercices`, `devoir(s)?`, le nom de la matière, ou égal au nom du fichier avec son extension. Préfixes retirés avant test : `Microsoft Word - `, `Microsoft PowerPoint - `, suffixes `.docx`, `.odt`, `.pdf`, `.pptx`.

Normalisation : blancs repliés, rognage, guillemets et ponctuation de fin retirés (`.:;-–—`), 120 caractères maximum (coupe au mot).

### 5.3 Chapitre
Sur le titre retenu, puis sur les autres candidats (métadonnées, premier titre, titre Pronote) :
`^(chapitre|chap\.?|ch\.?|séquence|sequence|séq\.?|thème|theme|partie|unité|unite)\s*([0-9]+|[IVXLC]+)\s*[:\-–—.)]?\s*(.*)$` (insensible à la casse et aux accents)
- Chapitre = mot-clé normalisé avec majuscule initiale (`Chapitre`, `Séquence`, `Thème`, `Partie`, `Unité`) + espace + numéro tel quel (`Chapitre 3`, `Séquence IV`).
- Titre = reste de la ligne s’il est valable (5.2) ; sinon le candidat suivant valable qui n’est pas lui-même un intitulé de chapitre.
- Sans motif : chapitre = `Sans chapitre`.

### 5.4 Description
Pour chaque contenu de la séance : `titre` puis `description` (texte sûr), séparés par une ligne vide ; 2 000 caractères maximum.

## 6. Afficher les documents sans quitter l’app

Fiche du cours → section « Documents » (nom, type, taille) :
- PDF : visionneuse native (PDFKit / `PdfRenderer`), défilement continu, zoom, pages.
- Images (`png`, `jpg`, `jpeg`, `gif`, `heic`, `webp`) : visionneuse avec zoom.
- Bureautique (`docx`, `pptx`, `xlsx`) : aperçu système quand la plateforme sait l’afficher (QuickLook sur iOS) ; sinon vue « Lecture » ci-dessous.
- ODF (`odt`, `odp`) et autres types extractibles : vue **« Lecture »** native à partir du texte structuré extrait (titres, paragraphes, listes ; images intégrées si simple), avec une mention « Aperçu simplifié ».
- Toujours disponible en action secondaire : « Ouvrir avec… » / « Partager » (aucune URL Pronote exposée : c’est le fichier local).
- Liens du cahier de textes : navigateur intégré (`SFSafariViewController` / Custom Tabs), jamais un WebView de l’app.

## 7. Vecteurs

`native/shared/documents/` : documents synthétiques (aucune donnée réelle) et `document-title-vectors.json` (fichier → type détecté, candidats, titre et chapitre attendus, texte de lecture attendu pour les formats ODF). `notebook-course-vectors.json` : séances synthétiques + réglages + `notebookImports` → cours attendus (éligibilité, dédoublonnage, `dismissed`, matière inconnue, dates futures, description).

Les choix qui précisent cette spécification (règles 1 à 32 : détection du type, document principal, rejets, chapitres, vue Lecture, proposition des 14 jours…) sont dans `documents/README.md` et font foi au même titre. Les attendus sont calculés par l’implémentation de référence `documents/reference.py` (Python, bibliothèque standard).

## 8. Réviser sans quitter Aujourd’hui

Objectif : pendant qu’on enchaîne les révisions du soir, tout ce qui concerne le cours est à un geste, sans changer d’onglet ni chercher dans le cahier de textes.

### 8.1 Fiche de séance (« Réviser »)
Ouverte depuis une séance d’Aujourd’hui, une tuile du Planning ou la fiche d’un cours. Même contenu partout, dans une feuille avec sa propre navigation interne (les documents et le cahier s’ouvrent **dans** la feuille, le retour ramène à la fiche) :
1. En-tête : matière, Jn, étape (Découvrir / S’entraîner / Se tester), titre du cours, chapitre · Cn, retard éventuel.
2. **Le cours** : description (repliée au-delà de 6 lignes, « Afficher plus »), puis la liste des **documents** (icône du type, nom, titre lu) ; toucher un document l’ouvre dans la visionneuse de la §6, poussée dans la feuille. Le document principal (premier de la liste) porte l’action « Revoir le cours ».
3. **Dans le cahier de textes** (si `source` existe et que la séance est dans la fenêtre lue) : contenus de la séance d’origine (titre, texte, liens dans le navigateur intégré, pièces jointes non importées téléchargeables) ; sinon une ligne « Séance du 18 novembre · Pronote » sans contenu.
4. **Séances précédentes** (repliable, existant).
5. Action principale en bas, toujours visible : **« Faire le point »** (autoévaluation existante, dans la même feuille).

### 8.2 Enchaîner
Après la validation d’une note depuis la fiche :
- s’il reste des séances dues dans le programme du soir : la feuille affiche le résultat (message de l’algorithme) et un bouton principal **« Séance suivante · <matière> — <titre> »** qui remplace le contenu de la feuille par la fiche de la séance suivante (ordre du programme du soir d’Aujourd’hui), plus « Revenir à Aujourd’hui » ;
- sinon : « Programme terminé » puis fermeture de la feuille et feu d’artifice (comportement existant).

### 8.3 Aujourd’hui
- Carte « Pour demain » : en plus de « Faire le point sur ce cours », un bouton secondaire **« Revoir le cours »** quand le cours a au moins un document (ouvre directement le document principal dans la feuille de la séance).
- Chaque ligne de séance du programme : toucher la ligne ouvre la fiche §8.1 ; une petite icône indique la présence de documents.
- Les créneaux de demain issus de Pronote : si un cours existe déjà pour cette matière et ce jour (séance du cahier importée), « Voir le cours » au lieu de « Ajouter ce cours ».

### 8.4 Mes cours
La fiche d’un cours donne le même accès aux documents et au contenu du cahier (§8.1, points 2 et 3) ; « Valider » ouvre la fiche de la prochaine séance due de ce cours.

### 8.5 Documents proches
Cas réel (18/11/2026) : la professeure joint le support de deux séances (« cours 3° 4 et 5 nov.odt ») à la seconde seulement ; le cours issu de la première n’a donc aucun document.
- Dans la fiche de séance (§8.1) et la fiche d’un cours, quand le cours n’a aucun document : la section « Le cours » affiche « Aucun document joint à cette séance dans Pronote ».
- Toujours, sous les documents du cours : **« Documents proches en <matière> »**, liste des fichiers (pas des liens) joints à d’autres séances de la **même matière normalisée** dans le cahier, à 10 jours au plus avant ou après la date du cours, hors documents déjà joints à ce cours. Sources : documents enregistrés dans les autres cours de la même matière, puis pièces jointes du cahier lu (instantané le plus récent, lu au besoin comme au §8.1). Tri : écart de date croissant, puis séance postérieure avant antérieure, puis ordre d’origine ; 5 au plus, « Afficher tout » au-delà. Chaque ligne : nom, titre lu si connu, « Séance du 5 novembre ».
- Toucher une ligne ouvre le document dans la feuille (téléchargé au besoin, mêmes règles de taille et de session que §2 et le cahier).
- Action **« Joindre à ce cours »** sur chaque ligne : copie le fichier dans les documents du cours (nouvel `id`, même nom, `extractedTitle` conservé), sans modifier l’autre cours ; le titre du cours n’est pas changé automatiquement.
