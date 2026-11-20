# Documents et vecteurs « cahier de textes → cours »

Données de test partagées par iOS et Android pour `../notebook-to-course.md` (section 7). Tout est synthétique : sujets scolaires, noms fictifs, aucune donnée réelle. Les deux scripts n’utilisent que la bibliothèque standard de Python.

| Fichier | Rôle |
| --- | --- |
| `files/` | 28 documents (< 6 Ko chacun) |
| `make_documents.py` | génère `files/` octet par octet (PDF écrits à la main, zip à horodatage fixe) |
| `reference.py` | implémentation de référence de §4 et §5.1–5.4, plus `matchSubject` ; calcule **tous** les `expected` |
| `document-title-vectors.json` | 28 cas : fichier → type, candidats, titre, chapitre, vue Lecture |
| `../notebook-course-vectors.json` | 14 séances, 8 scénarios et une migration : clés stables, éligibilité, cours créés, numérotation, boutons, proposition 14 jours |

## Régénérer

```sh
python3 native/shared/documents/make_documents.py
python3 native/shared/documents/reference.py
# vérification (CI) : deux constructions identiques et identiques au disque
python3 native/shared/documents/make_documents.py --check
python3 native/shared/documents/reference.py --check
```

Chaque script construit deux fois en mémoire et échoue si les résultats diffèrent. `reference.py` vérifie aussi son port de `matchSubject` sur les 53 cas de `domain/domain-vectors.json`. `size` et `sha256` décrivent les fichiers présents dans `files/`. Une autre version de zlib peut produire d’autres octets compressés : régénérer alors les deux fichiers ensemble.

## `document-title-vectors.json`

`rules` reprend les constantes et expressions de la référence (à copier dans les ports). Chaque élément de `cases` :

```jsonc
{
  "id": "pdf-word-title",
  "file": "files/pdf-word-title.pdf", "size": 987, "sha256": "…",
  "input": { "fileName": "pdf-word-title.pdf", "sessionDate": "2026-11-18",
             "contentTitle": null,               // Content.title Pronote (candidat 3), ou null
             "subjectLabel": "HISTOIRE-GEOGRAPHIE", // libellé Pronote de la séance
             "subjectName": "Histoire-géographie" }, // matière J-Method reconnue
  "detectedType": "pdf" | "odt" | … | null,  "detectedBy": "extension" | "signature" | null,
  "mime": "application/pdf",
  "extraction": { "error": null | "unreadable", "metadata": "…" | null,
                  "metadataDetail": "pdfInfoTitle" | "xmpDcTitle" | "metaXmlDcTitle" | "coreXmlDcTitle" | null,
                  "pdfLines": [{ "text": "…", "size": 20.0 }] },   // PDF seulement, page 1, ordre du flux
  "candidates": [ { "source": "metadata" | "firstHeading" | "pronoteTitle" | "fileName" | "dateFallback",
                    "detail": "pdfInfoTitle",        // facultatif
                    "raw": "Microsoft Word - Chapitre 2 - La Révolution.docx",
                    "value": "Chapitre 2 - La Révolution",   // après normalisation
                    "accepted": true, "reason": null | "empty" | "numberOrDate" | "tooFewLetters"
                              | "adminHeader" | "generic" | "subjectName" | "fileName" } ],
  "expected": { "title": "La Révolution", "chapter": "Chapitre 2",
                "titleSource": "metadata", "chapterSource": "metadata",
                "extractedTitle": "La Révolution" },  // Course.documents[].extractedTitle
  "readingView": [ { "kind": "heading" | "paragraph" | "listItem", "level": 1, "text": "…" } ] | null
}
```

Tous les candidats présents sont listés, y compris ceux qui suivent le candidat retenu : un port peut tester chaque extracteur et chaque rejet séparément. Un candidat absent (pas de métadonnées, pas de document principal, pas de titre Pronote) n’apparaît pas. `readingView` est donné pour ODT, ODP, DOCX et PPTX ; `null` ailleurs.

Cas couverts : PDF `/Title` (PDFDocEncoding), `/Title` UTF-16 « Microsoft Word - … .docx », titre XMP seul, flux non compressé (grande ligne puis « Nom : »), FlateDecode (« Nom : » d’abord, titre en grand en `TJ`), `/Title` « Document1 » rejeté, `/Title` égal au nom du fichier, PDF chiffré (RC4, mot de passe utilisateur `motdepasse`), PDF illisible ; ODT `dc:title`, premier `text:h`, style automatique hérité de `Title`, niveau 2 avant niveau 1, paquet ODT nommé `.bin` ; ODP (`dc:title` « Présentation1 » rejeté) ; DOCX `dc:title` entre guillemets, `dc:title` = matière, `Heading2` ignoré, runs coupés, `w:del` ; style `Title` ; PPTX `ctrTitle`, ordre `sldIdLst` ≠ noms des fichiers ; TXT avec BOM, Windows-1252/CRLF, en-tête « Nom : », titre > 120 caractères ; MD `# Titre :` ; fichier vide ; PNG non extractible. Chapitres : `Chapitre 2 -`, `Chapitre 5 –`, `CHAP. 2)`, `Séquence IV –`, `Partie 3 :`, `Unité 3 :`, `Thème 2 –`, `Thème 1 :` sans reste (→ titre Pronote), chapitre trouvé sur un autre candidat que le titre retenu, absence → `Sans chapitre`.

## `notebook-course-vectors.json` (schemaVersion 2)

```jsonc
{
  "today": "2026-11-18",
  "subjects": [ … ],              // DEFAULT_SUBJECTS de domain/constants.json
  "subjectNormalization": [ { "input": "Histoire - Géographie", "expected": "histoire geographie" } ],
  "sessions": [ SessionEntry ],   // instantané de base, modèle de notebook-reference §5, ordre du modèle (date décroissante)
  "sessionKeys": [ { "id", "date", "subject", "normalizedSubject", "ordinal", "sessionKey" } ],  // une par séance
  "prefill": [ … ],               // §3 : formulaire prérempli de chaque séance, sur les cours de base
  "scenarios": [ { "id", "note", "today", "settings", "sessions", "courses", "notebookImports", "expected" } ],
  "migration": { "input": { "courses", "notebookImports" }, "expected": { "courses", "notebookImports" },
                 "idempotent": true, "afterMigration": { …scénario… } }
}
```

Chaque scénario porte son propre instantané `sessions` (`SessionEntry.id` = `N` Pronote, qui n’est **jamais** une clé). Champs de test ajoutés aux pièces jointes `kind: "file"` (absents du modèle réel) : `fixture` (chemin relatif à ce dossier : contenu « téléchargé ») ou `downloadError` (`unavailable` = échec de téléchargement, `tooLarge` = plus de 20 Mo). Le `name` de la pièce jointe sert à la détection du type et au candidat 4 ; il diffère volontairement du nom du fichier de `files/`.

`prefill[]` : `sessionKey` (`pn1:…`), `ordinal`, `notebookId` (le `N`, pour information), `subjectId` (ou `null`), `date`, `chapter`, `title`, `titleSource`, `candidates`, `description`, `principalDocument`, `documents[] {name, mime, size, extractedTitle}`, `skippedAttachments[] {id, name, reason: unavailable | tooLarge | limit}`, `courseNumber` (absent si matière inconnue).

`scenarios[].expected` :

| Champ | Contenu |
| --- | --- |
| `sessionKeys` | clé de chaque séance de l’instantané du scénario, dans l’ordre |
| `eligibleSessionKeys` | séances créées, dans l’ordre de traitement |
| `skipped[] {sessionKey, reason}` | `autoCreateDisabled`, `created`, `dismissed`, `courseExists`, `future`, `beforeSince`, `unknownSubject` (une seule raison, dans cet ordre de priorité) |
| `createdCourses[]` | cours créés : `subjectId`, `chapter`, `courseNumber`, `title`, `date`, `createdAt`, `description`, `source {kind, sessionKey, subjectLabel, date, ordinal}`, `documents`, `titleSource`. `id` (`course-nb-<date>-<matière>-<rang>`) est fictif : ne pas le comparer, il sert à enchaîner les scénarios |
| `notebookImportsAfter` | `{status, courseId}` par `sessionKey` (sans `at`) |
| `toast` | « N cours créés depuis le cahier de textes », ou `null` |
| `buttons` | `createCourse` ou `viewCourse` par `sessionKey`, après la création automatique |
| `backfillProposal` | scénario `activation-today` seulement : `from`, `to`, `count`, `sessionKeys`, `message`, `acceptSetsSince` |

Scénarios :

| id | Situation | Résultat |
| --- | --- | --- |
| `auto-since-nov-12` | réglage actif depuis le 12/11 ; deux séances de maths le 17/11 (rangs 1 et 2) | 6 créations |
| `activation-today` | `since = today` | 2 créations, proposition de 7 séances |
| `backfill-accepted` | `since = today − 14` | 9 créations |
| `auto-disabled` | réglage désactivé | aucune |
| `rerun-after-auto` | relecture après le premier | aucune |
| `new-pronote-session` | même instantané, autres `N`, `lessonId` et identifiants de pièces jointes, libellés « Histoire-Géographie », « Sciences Vie & Terre. » | aucune, mêmes boutons « Voir le cours » |
| `modified-sessions` | titre, contenus et pièces jointes modifiés par le professeur | aucune (même clé) |
| `belt-and-braces` | `notebookImports` perdus pour les cours créés | aucune (`courseExists`) |
| `migration.afterMigration` | instantané de base sur le document migré | 4 créations (voir règle 40) |

Les révisions initiales ne sont pas répétées : elles suivent `generateInitialRevisions` (vecteurs `courseCreate` du domaine).

`migration` : document créé avec les anciennes clés (`18#A1B`, `42#K9Z`, `cours:cours-svt@2026-11-18`), cours `source` sans `ordinal`, deux cours en double après un redémarrage (même date, matière, titre et chapitre), un cours déjà au format `pn1:`, un `dismissed` ancien orphelin et un `dismissed` `pn1:` orphelin. `expected` = document après migration ; migrer `expected` redonne `expected` (vérifié par le générateur).

## Règles appliquées par la référence

Les points suivants précisent la spécification. Ils sont classés par section ; chacun est un choix à reporter dans `notebook-to-course.md`.

### 5.1 Document principal et type

1. **Type** : si l’extension (insensible à la casse, 1 à 5 caractères alphanumériques après le dernier `.`) est `pdf odt odp docx pptx txt md`, elle décide seule, sans contrôle de signature. Sinon, signature : `%PDF-` à l’octet 0 → `pdf` ; zip (`PK\3\4`) contenant `mimetype` dont le contenu rogné vaut exactement le type MIME ODT ou ODP → `odt` / `odp` ; zip contenant `[Content_Types].xml` et `word/document.xml` → `docx`, ou `ppt/presentation.xml` → `pptx`. TXT et MD ne sont jamais détectés par signature. Un fichier `.pdf` illisible reste de type `pdf`.
2. **Document principal** : premier fichier **effectivement téléchargé** (contenus dans l’ordre, puis pièces jointes dans l’ordre) dont le type est extractible. Un fichier dont l’extraction échoue (chiffré, corrompu, vide) reste principal : il ne donne pas de candidats 1 et 2, mais son nom donne le candidat 4.
3. Sans document principal (aucun fichier, aucun téléchargé, ou aucun extractible), **pas de candidat 4** : on ne prend pas le nom d’une image (« IMG 2034 » serait accepté).
4. **Limites** : les fichiers sont tentés dans l’ordre ; on s’arrête après 10 fichiers **enregistrés** ; les échecs (`unavailable`, `tooLarge`) ne comptent pas ; les suivants sont signalés `limit`. Les liens ne sont jamais téléchargés.

### 5.2 Candidats

5. **PDF, candidat 1** : `/Title` de `/Info` s’il est non vide après nettoyage des blancs ; sinon `dc:title` du XMP (`rdf:li xml:lang="x-default"`, sinon le premier `rdf:li`). Un `/Title` présent mais rejeté (« Document1 ») ne fait **pas** consulter le XMP. Chaînes : UTF-16BE avec BOM `FE FF`, UTF-8 avec BOM, sinon PDFDocEncoding.
6. **PDF chiffré** (`/Encrypt` dans le trailer ; `PDFDocument.isEncrypted` sur iOS) : candidats 1 et 2 absents, **même** s’il s’ouvre avec un mot de passe vide (PDFKit l’ouvrirait, un lecteur maison non : on aligne les deux).
7. **PDF, candidat 2** : page 1 seulement (premier `Kids` en profondeur). Lignes = textes consécutifs de même ligne de base (`y` du `Tm` courant, à 1 point près) ; taille = taille de `Tf` × échelle verticale de `Tm`. Le candidat est la **première ligne non rejetée** dans l’ordre (taille décroissante, puis ordre du flux). Si toutes sont rejetées, la première de cet ordre, rejetée. Sans information de taille, l’ordre du flux seul : les vecteurs sont construits pour que les deux méthodes donnent le même résultat (`pdf-flate-content` : « Nom : », « Classe : » sont avant le titre, en petit). Dans un `TJ`, les nombres n’insèrent jamais d’espace. Filtres acceptés : aucun ou `FlateDecode`. Texte : polices standard en `WinAnsiEncoding` (seul cas des vecteurs).
8. **ODT, candidat 2** : dans l’ordre du document, premier élément non vide qui est soit un `text:h` du **plus petit** `text:outline-level` présent (absent = 1), soit un `text:p` de style `Title`/`Titre` (insensible à la casse), directement ou par `style:parent-style-name` d’un style automatique de `content.xml` (un niveau). LibreOffice écrit `text:style-name="P1"` avec `P1` héritant de `Title` (cas `odt-title-style`).
9. **DOCX, candidat 2** : premier `w:p` du document (tableaux compris) dont le `w:pStyle/@w:val` (identifiant de style, insensible à la casse ; `styles.xml` n’est pas lu) vaut `Title`, `Titre`, `Heading1` ou `Titre1`, avec un texte non vide. Texte : `w:t` ; `w:tab`, `w:br`, `w:cr` → espace ; contenu de `w:del`, `w:moveFrom`, `w:instrText` ignoré. Métadonnées : `docProps/core.xml`, document : `word/document.xml` (chemins fixes).
10. **ODP / PPTX, candidat 2** : première diapositive = premier `draw:page` / premier `p:sldId` de `ppt/presentation.xml` (via `ppt/_rels/presentation.xml.rels`), **pas** `slide1.xml` (cas `pptx-theme-empty`). Titre = première forme `presentation:class="title"` / `p:ph type="title"|"ctrTitle"`, paragraphes joints par une espace. Si la première diapositive n’a pas de titre, pas de candidat 2. ODP : seuls les `draw:frame` enfants directs de `draw:page` (les notes sont exclues).
11. **TXT / MD** : UTF-8 (BOM retiré), sinon Windows-1252. Lignes séparées par CRLF, CR ou LF. Candidat 2 = première ligne non vide, **sans** sauter une ligne rejetée (contrairement au PDF : cas `txt-admin-header`). MD : `#{1,6}` en tête (jusqu’à 3 espaces avant) suivi d’un blanc ou de la fin de ligne est retiré.
12. **Candidat 3** : titre du premier contenu dont le titre est **non vide** (et non strictement le premier contenu).
13. **Candidat 4** : nom de la pièce jointe privé de sa dernière extension (`.` + 1 à 5 alphanumériques) ; `_+` → espace, `-{2,}` → espace ; un `-` seul est gardé (« Physique-chimie »).
14. **Candidat 5** : « Cours du 18 novembre », « Cours du 1er novembre » le 1er du mois, mois en minuscules, sans année. Jamais soumis aux rejets.

### Normalisation et rejets

15. **Normalisation**, dans l’ordre : NFC ; suppression de U+0000–U+0008, U+000E–U+001F, U+007F–U+009F, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069, U+FEFF ; blancs (`\t \n \v \f \r`, espace, U+00A0, U+2009, U+202F) → une espace, rognage ; préfixe `Microsoft Word - ` / `Microsoft PowerPoint - ` puis suffixe `.docx .odt .pdf .pptx` retirés une fois chacun (insensible à la casse) ; puis, jusqu’à stabilité : espaces, guillemets ``" ' « » “ ” „ ‘ ’ ‹ ›`` aux deux bouts, ponctuation `. : ; - – —` à la fin. Au-delà de 120 caractères : si le 121e est une espace, coupe à 120 ; sinon coupe à la dernière espace des 120 premiers (coupe sèche s’il n’y en a pas), puis nouveau retrait de la ponctuation finale. Longueurs en points de code (les vecteurs n’utilisent que le BMP précomposé, où Swift, Kotlin et Python comptent pareil).
16. **Comparaisons** sur la forme repliée de `matchSubject` (minuscules, NFD, retrait de U+0300–U+036F), en correspondance **entière**.
17. **Ordre des rejets** (la première raison est donnée) : `empty`, `numberOrDate`, `tooFewLetters` (moins de 3 lettres Unicode), `adminHeader`, `generic`, `subjectName`, `fileName`.
18. **Nombre ou date** : `^[+-]?\d+([.,]\d+)?$`, `^\d{1,4}[/.-]\d{1,2}([/.-]\d{1,4})?$`, ou date en toutes lettres `[jour de semaine] (\d{1,2}|1er) <mois> [année]` (« Mercredi 18 novembre », cas `not-extractible-png`).
19. **Génériques** : `sans titre`, `untitled`, `document\s*\d*`, `presentation\s*\d*`, `diapositive\s*\d+`, `cours`, `seance`, `exercices?` (singulier ajouté), `devoirs?`. Espaces facultatives ajoutées (« Document 1 », « Présentation 2 »).
20. **Nom de la matière** : le libellé Pronote de la séance **et** le nom de la matière J-Method reconnue, normalisés puis repliés (« S.V.T. » → « s.v.t »). Matière inconnue : libellé Pronote seul.
21. **Nom du fichier** : rejet si la valeur repliée, ou la valeur brute nettoyée des blancs (avant retrait du suffixe), égale le nom complet du fichier. Sans ce second test, « fiche_exo_3.pdf » deviendrait « fiche_exo_3 » et passerait (cas `pdf-title-is-file-name`).

### 5.3 Chapitre

22. **Expression**, écrite pour Java/ICU sans `UNICODE_CASE` : `^(?i:(chapitre|chap\.?|ch\.?|s[eéEÉ]quence|s[eéEÉ]q\.?|th[eèéEÈÉ]me|partie|unit[eéEÉ]))\s*([0-9]+|[IVXLC]+)(?![0-9A-Za-zÀ-ÖØ-öø-ÿ])\s*[:\-–—.)]?\s*(.*)$`, appliquée à la valeur normalisée. Chiffres romains en **majuscules seulement**, numéro non suivi d’une lettre ou d’un chiffre : sinon « CHIMIE » (`ch` + `I`), « Partie civile » ou « Children » seraient des chapitres. Numéro gardé tel quel (`02` reste `02`).
23. **Ordre de recherche** : titre retenu ; s’il ne correspond pas, candidats 1, 2, 3 dans l’ordre, **rejetés compris** (valeur non vide) ; le premier qui correspond donne le chapitre et le titre retenu ne change pas (cas `odt-meta-title`, `docx-core-title`).
24. **Reste vide ou rejeté** : on parcourt les candidats acceptés suivants ; un candidat qui correspond à l’expression donne son reste s’il est valable, sinon il est sauté ; un autre donne sa valeur. Le repli 5 garantit un titre.
25. `extractedTitle` d’un document = 5.2–5.3 limités aux candidats 1–2 de ce document ; `null` s’il n’y en a pas d’accepté ou si le seul est un intitulé de chapitre sans reste.

### 5.4 Description

26. Titre et description non vides de chaque contenu, **tous** séparés par une ligne vide (`\n\n`), y compris entre deux contenus. Les titres génériques (« Cours », « Exercices ») sont gardés. Au-delà de 2 000 caractères : coupe au dernier blanc (même règle que les titres), sans points de suspension.

### §3–§4 Création

27. **Raison d’exclusion**, une seule, par priorité : `autoCreateDisabled`, `created` (même si le cours n’existe plus), `dismissed`, `courseExists` (règle 36), `future` (`date > today`), `beforeSince` (`date < since`), `unknownSubject`.
28. **Ordre de création** : date croissante, ordre de la réponse pour une même date. Les numéros sont attribués à la suite : les cours créés dans le même lot se voient (règle `courseCreate` existante : même matière, chapitre égal après `trim` et minuscules, sans repli des accents, `max + 1`). `Sans chapitre` est un chapitre ordinaire, numéroté.
29. **Proposition « 14 derniers jours »** : séances avec `today − 14 ≤ date ≤ today − 1`, ni `created` ni `dismissed`, sans cours correspondant (règle 36), **matière reconnue** (les autres ne peuvent pas être créées automatiquement). `count == 0` : pas de dialogue. « Importer » enregistre `notebookAutoCreateSince = today − 14`, puis la règle d’éligibilité ordinaire s’applique (scénario `backfill-accepted`). Les séances du jour sont déjà couvertes par `since = today`.
30. **Toast** : « 1 cours créé … », « N cours créés … », rien pour 0.
31. **Bouton** : `viewCourse` si `notebookImports[key].status == created` **et** `courseId` existe dans `courses`, ou si un cours correspond par sa `source` (règle 36) ; sinon `createCourse` (y compris `dismissed`).

### §6 Vue « Lecture »

32. Blocs `heading` (avec `level`), `paragraph`, `listItem` (avec `level` = profondeur, 1 pour le premier niveau). Texte nettoyé (blancs repliés, rognage), sans retrait de ponctuation ; blocs vides supprimés ; images non représentées.
    - ODT : `text:h` → `heading` de son niveau ; `text:p` de style Titre (règle 8) → `heading` niveau 1 ; `text:p` → `paragraph` ; `text:p` dans `text:list-item` → `listItem` ; `text:section` et tableaux parcourus ; `text:s` → espaces (puis repliées), `text:tab`, `text:line-break` → espace ; `text:note` et `office:annotation` ignorés.
    - ODP : par diapositive, le titre → `heading` 1, les autres cadres → comme en ODT ; notes exclues.
    - DOCX : `Title`/`Titre` → `heading` 1 ; `HeadingN`/`TitreN` → `heading` N ; paragraphe avec `w:numPr` → `listItem` niveau `ilvl + 1` ; sinon `paragraph` ; tableaux et `w:sdt` parcourus.
    - PPTX : par diapositive (ordre `sldIdLst`), titre → `heading` 1 ; paragraphes d’un espace réservé `body` ou `obj` (un `p:ph` sans `type` vaut `obj`) → `listItem` niveau `lvl + 1`, sauf `a:buNone` ; sous-titre et autres formes → `paragraph`.
    - TXT, MD et PDF n’ont pas de vue Lecture dans les vecteurs.

### §1 Clé stable et migration (spec du 18/11, commit 484aa4d)

33. **Matière normalisée** : forme repliée de la règle 16 (minuscules, NFD, retrait de U+0300–U+036F), puis toute suite de caractères qui ne sont ni lettre ni chiffre (Unicode : `Character.isLetterOrDigit`, Swift `isLetter || isNumber`) → une espace, rognage. Pas de recomposition NFC après le retrait des accents. Les vecteurs n’utilisent que des libellés qui donnent de l’ASCII (« S.V.T. » → `s v t`, « Anglais (LV1) » → `anglais lv1`, `---` → chaîne vide).
34. **Rang** : compté dans l’instantané **entier** (pas seulement dans la fenêtre d’éligibilité), dans l’ordre du modèle (tri stable par date décroissante, donc ordre de la réponse pour une même date). Les séances ignorées par le modèle (sans date, sans contenu) ne comptent pas. Limite connue : si le professeur supprime la première de deux séances du même jour, la seconde prend le rang 1 et se trouve rattachée au cours de la première.
35. **`source.ordinal`** est enregistré à la création ; `source.subjectLabel` garde le libellé Pronote brut, la comparaison se fait sur sa forme normalisée.
36. **Ceinture et bretelles** : correspondance si un cours a `source.kind == "pronote-notebook"`, `source.date` égale, `normalize(source.subjectLabel)` égale et `source.ordinal` égal (entier ; un cours sans `ordinal` ne correspond jamais). Raison d’exclusion `courseExists`, après `created`/`dismissed` (un `dismissed` reste `dismissed` même si un cours correspond). La correspondance n’écrit **pas** d’entrée dans `notebookImports` (scénario `belt-and-braces` : `notebookImportsAfter` inchangé).
37. **Migration, rang des cours anciens** : sont migrés les cours `pronote-notebook` dont `source.ordinal` n’est pas un entier ; un cours qui a déjà un `ordinal` le garde. Les cours anciens d’un même groupe (date, matière normalisée) sont triés par ordre de création : `createdAt` (jour seulement, donc souvent égal), puis le plus petit `at` des entrées de `notebookImports` qui désignent le cours (absent = en dernier), puis position dans `courses` décroissante (les nouveaux cours sont insérés en tête). Rang = 1, 2, 3… dans cet ordre, **sauf** qu’un cours de même titre et même chapitre (repliés) qu’un cours précédent du groupe reçoit le même rang : ce sont les doublons du bug, qui tombent ainsi sur la même clé. Sans cette règle, un doublon prendrait le rang 2 et empêcherait plus tard la création d’une vraie deuxième séance de ce jour (règle 36).
38. **Migration, clés** : `source.sessionKey` de chaque cours `pronote-notebook` est réécrite (`pn1:…`). Pour chaque clé désignée par au moins une entrée existante, une seule entrée : `courseId` = le cours le plus ancien de la clé (ordre de la règle 37) ; statut et `at` de l’entrée qui désignait ce cours, sinon `created` et le plus petit `at` des entrées de la clé. Les autres entrées qui désignaient un cours de la clé disparaissent ; aucun cours n’est supprimé (le doublon garde sa `source`, sans entrée).
39. **Migration, entrées restantes** : une entrée `pn1:` qui ne désigne aucun cours est gardée (`dismissed` récent) ; une entrée d’un autre format est gardée si son `courseId` existe (cours sans `source`), supprimée sinon (ancien `dismissed` orphelin). Les clés de `notebookImports` sont triées. Une migration sur un document déjà migré ne change rien.
40. **Conséquence assumée** : un ancien `dismissed` orphelin est supprimé, donc la séance correspondante peut être recréée automatiquement si elle est encore dans la fenêtre et après `since` (`migration.afterMigration` : physique-chimie du 13/11 recréée). Un ancien `created` dont le cours a été supprimé hors application disparaît de même.

## Notes pour les ports

- **iOS** : `PDFDocument.documentAttributes[.titleAttribute]` lit `/Info` mais **pas** le XMP (vérifié sur `pdf-xmp-title.pdf`) : lire le flux `/Metadata` du catalogue avec `CGPDFDocument`. Les tailles de police viennent de `page.attributedString` (attribut `.font`) ; PDFKit donne les mêmes lignes et tailles que `pdfLines` sur ces fichiers. `pdf-encrypted.pdf` : `isLocked == true`. `controle_SVT__version_finale.pdf` : `PDFDocument(url:)` renvoie `nil`.
- **Android** : `PdfRenderer` n’extrait pas de texte ; un lecteur minimal (objets, trailer, `FlateDecode` via `java.util.zip.Inflater`, opérateurs `BT Tf Td TD Tm T* Tj TJ ' "`) suffit pour ces vecteurs, sur le modèle de `Pdf` et `content_lines` dans `reference.py`.
- QuickLook (iOS) affiche les DOCX et PPTX générés (vérifié avec `qlmanage`) ; Spotlight lit les PPTX. Ces fichiers sont minimaux (un masque, une disposition, un thème) : ils ne prétendent pas reproduire tout Office.
