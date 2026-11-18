# Du cahier de textes aux cours

Spécification commune iOS / Android. Le cahier de textes Pronote (voir `notebook-reference.md`) alimente la création des cours J-Method : manuellement par un bouton, ou automatiquement si l’utilisateur l’active. Rien n’est jamais écrit dans Pronote.

## 1. Unité : une séance = un cours

- Source : une `SessionEntry` (contenus de séance, réponse 89). Les devoirs (88) ne créent jamais de cours.
- Clé de dédoublonnage : `sessionKey = "<SessionEntry.id>"` (déjà stable : `N`, sinon `cours:<lessonId>@<date>`).
- J0 du cours = date de la séance (`SessionEntry.date`). Les révisions initiales sont générées comme pour une création manuelle (`generateInitialRevisions`).
- Matière : `matchSubject(SessionEntry.subject, subjects)`. Pas de correspondance → pas de création automatique ; la séance reste proposée avec le bouton (l’utilisateur choisit la matière dans le formulaire).

## 2. Données ajoutées au document

Champs facultatifs, ignorés par une ancienne sauvegarde web, conservés par l’export / la restauration JSON :

```jsonc
// Course
"source": { "kind": "pronote-notebook", "sessionKey": "…", "subjectLabel": "MATHEMATIQUES", "date": "2026-11-18" },
"documents": [
  { "id": "doc-<uuid>", "name": "Chapitre 3.pdf", "mime": "application/pdf", "size": 48213,
    "file": "documents/<courseId>/<docId>.pdf", "extractedTitle": "Les fonctions affines" }
]
// Racine du document
"notebookImports": {
  "<sessionKey>": { "status": "created" | "dismissed", "courseId": "…", "at": "2026-11-18T19:02:11Z" }
},
"settings": { …, "notebookAutoCreate": false, "notebookAutoCreateSince": "2026-11-18" }
```

- Les fichiers sont copiés dans le stockage privé **non cache** de l’app (`Application Support` / `filesDir`), exclus des sauvegardes système et d’iCloud. L’export JSON contient leurs métadonnées, pas leur contenu ; une restauration sans fichier affiche le document comme « à retélécharger » (bouton qui relit le cahier et retélécharge si la séance est encore dans la fenêtre).
- Supprimer un cours supprime ses fichiers et passe son `notebookImports[sessionKey].status` à `dismissed` : il n’est plus jamais recréé automatiquement (le bouton manuel reste possible).
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
