# Vecteurs de la logique métier

Les apps natives (`apps/ios`, `apps/android`) portent à l’identique la logique métier du web. Ce dossier contient des vecteurs produits **en exécutant le vrai code TypeScript** de `src/`. Les tests Swift et JVM les rejouent.

| Fichier | Rôle |
|---|---|
| `generate-vectors.mjs` | Générateur (Node, esbuild déjà présent via Vite, aucune dépendance ajoutée) |
| `domain-vectors.json` | Cas nommés : entrées complètes et sorties attendues |
| `constants.json` | Matières, intervalles et réglages par défaut, configuration des phases, libellés d’Aujourd’hui et du Planning, préréglages de score, bornes des curseurs de Réglages |

## Régénération

```bash
npm run vectors:domain
```

Le script est stable : deux exécutions produisent des fichiers identiques au bit près, quel que soit le fuseau de la machine. Il faut régénérer après chaque modification de `src/lib`, `src/types`, ou de l’un des composants dont la logique est recopiée : `DashboardView`, `App`, `Navigation`, `ScoreModal`, `PlanningView`, `RevisionDetailModal`, `CoursesView`, `AddCourseModal`, `CourseDetailModal`, `StatsView`, `SettingsView`. L’en-tête `sources` contient le SHA-256 de chacun de ces fichiers ; un test natif peut l’afficher pour repérer des vecteurs périmés.

Hors de `src/lib`, la logique des écrans (Aujourd’hui, validation, aperçu de score, Planning, Mes cours, Mes progrès, Réglages) est écrite en ligne dans des composants React. Le script la recopie, en garde la trace par des extraits de code exacts (`assertSource`) et **s’arrête en erreur** si l’un d’eux change dans `src/`. Il faut alors relire la copie dans le script, puis ce README.

## Environnement figé

- **Fuseau** : `process.env.TZ = 'Europe/Paris'` est imposé par le script (et par le script npm), puis vérifié.
- **Horloge** : `Date` est remplacée par une sous-classe dont `new Date()` et `Date.now()` renvoient un instant fixé avant chaque appel. Chaque cas qui dépend de l’horloge porte un champ `now` : `{ "epochMs": 1794942000000, "utc": "2026-11-17T19:00:00.000Z" }`. L’heure locale de Paris s’en déduit (ici 20:00).
- **Aléa** : `Math.random` renvoie toujours `0.5`. Seules les données de démonstration l’utilisent.
- **localStorage** : remplacé par une table en mémoire, vidée avant chaque cas de démonstration.

Les ports doivent donc pouvoir recevoir **une horloge** (instant courant) et **un fuseau** (tests en `Europe/Paris`) au lieu de lire ceux du système.

## Identifiants

Aucun identifiant n’est exclu des comparaisons. Ils sont tous déterministes :

| Origine | Format | Remarque |
|---|---|---|
| `generateInitialRevisions` | `<courseId>-rev-<index>` | |
| Rattrapage (`applyAdaptiveReview`) | `<courseId>-emerg-<now.epochMs>` | `Date.now()` en millisecondes ; le port doit utiliser l’horloge injectée |
| `parseICalFeed` sans `UID` | `pronote-<yyyy-MM-dd>-<HH:mm>-<SUMMARY>` | `SUMMARY` après `trim`, `[ANNULÉ]` compris |
| Démo (`storage.ts`) | `course-maths-1`, etc. | constants |
| Création de cours (`App.tsx`) | `course-<now.epochMs>` | horloge injectée |

Un port qui ne peut pas injecter l’horloge peut, en dernier recours, comparer l’id d’un rattrapage à `^<courseId>-emerg-\d+$`. Dans les enchaînements, il doit alors repartir des `expected.updatedRevisions` de l’étape précédente, car l’étape suivante désigne le rattrapage par son id attendu. Un nouveau cours reçoit l’id `course-<now.epochMs>` (suite `courseCreate`), selon la même règle d’horloge injectée.

## Règles de comparaison

- **Clé absente = valeur indéfinie.** `JSON.stringify` omet `undefined` : `nextDate`, `completedDate`, `score`, `isEmergencySession`, `matchedSubjectId`, `room`, `teacher`, `description`… Côté natif, une clé absente et `null` sont équivalentes. Pour rester compatible avec la sauvegarde web, un port qui sérialise doit omettre les valeurs nulles. Dans les suites `todayScreen` et `scoreSubmit`, qui viennent de l’interface, `null` est écrit explicitement.
- **Ordre des tableaux significatif** partout : il fait partie du contrat (tri stable, insertion du rattrapage, ordre des groupes).
- **Dates** : chaînes `yyyy-MM-dd` comparées telles quelles. Heures : `HH:mm` sur 24 h, heure locale de Paris.
- **Nombres** : entiers exacts. `explanation.factor` est un double calculé exactement comme `1 + ((score - 50) / 50) * sensitivity`. Avec cet ordre d’opérations, la comparaison est exacte ; une tolérance de `1e-12` reste acceptable. Les valeurs comme `1.1600000000000001` sont voulues.
- **Chaînes exactes**, avec la typographie d’origine : apostrophe droite `'` dans les messages de l’algorithme (`l'ancrage`, `d'origine`), apostrophe typographique `’` dans l’interface (`S’entraîner`, `c’est`), point médian ` · `, tiret demi-cadratin ` – `.
- **Arrondi** : `Math.round` de JavaScript arrondit au supérieur à mi-chemin (`2.5 → 3`). En Kotlin, il faut `Math.round(x)` ou `floor(x + 0.5)`, jamais `kotlin.math.round` ni `roundToInt` sans vérification, ni `rint`, qui arrondissent à l’entier pair. En Swift, `.rounded()` (`toNearestOrAwayFromZero`) convient, toutes les valeurs arrondies étant positives. Cas témoins : `rounding_tie_2.5_rounds_up`, `progress_rounding_half_up` (12,5 % → 13).
- **Calendrier** : toute l’arithmétique de dates porte sur des jours civils locaux (`LocalDate` / `DateComponents`), jamais sur des multiples de 86 400 s. Cela compte aux passages à l’heure d’été ou d’hiver (29 mars et 25 octobre 2026, 28 mars 2027).

## Structure de `domain-vectors.json`

```text
{
  schemaVersion, generator, regenerate, timeZone, containsPersonalData, sources,
  subjectSets: { default, defaultReversed, withCustom, empty },   // listes de Subject
  fixtures: { planning, revisionDetail, coursesList, importBefore }, // jeux de données partagés
  suites: { <suite>: [cas…] }
}
```

`subjectSets` évite de répéter les matières. Une entrée `"subjects": "default"` désigne `subjectSets.default`, c’est-à-dire `DEFAULT_SUBJECTS`. De même, un cas qui porte `"fixture": "planning"` lit ses `courses`, `revisions`, `subjects` et `pronoteEvents` dans `fixtures.planning`.

Les objets `Course`, `Revision`, `Subject`, `PronoteEvent` et `AppSettings` suivent exactement `src/types/index.ts`.

| Suite | Cas | Entrée | Attendu |
|---|---|---|---|
| `getPhaseForStep` | 13 | `input.stepIndex` | `expected` : `encodage`, `consolidation` ou `recuperation` |
| `generateInitialRevisions` | 11 | `input.course`, `input.intervals` | `expected` : `Revision[]` |
| `applyAdaptiveReview` | 65 | `now`, `input.revisions`, `revisionId`, `score`, `settings`, et facultativement `actualCompletionDate` et `schedulingReferenceDate` (absents = paramètre non fourni, valeur par défaut) | `expected: { updatedRevisions, explanation: { score, factor, message, actionType, nextDate? } }` ou `expectedError: { message }` |
| `applyAdaptiveReviewChains` | 4 chaînes, 44 étapes | `settings`, `course`, `initialRevisions`, puis `steps[]` : `{ now, revisionId, score, actualCompletionDate, schedulingReferenceDate, expected }` | L’entrée de l’étape *n* est `steps[n-1].expected.updatedRevisions`, ou `initialRevisions` pour la première |
| `getEveningProgramme` | 13 | `input.today`, `input.courses`, `input.revisions` | `expected: { preparationDate, due, completed, overdue, visible }`, ces quatre listes étant des **ids** dans l’ordre (la fonction renvoie les objets d’entrée tels quels) |
| `matchSubject` | 53 | `input.summary`, `input.subjects` (nom d’un `subjectSet`) | `expectedSubjectId` : id ou `null` |
| `parseICalFeed` | 6 | `input.ics` (texte brut, CRLF ou LF), `input.subjects` (nom d’un `subjectSet`) | `expected` : `PronoteEvent[]`. `parseWarnings` (nombre d’événements rejetés par exception) est informatif |
| `generateSamplePronoteFeed` | 4 | `now` | `expectedIcs` (texte exact), `expectedEvents` (le même flux passé à `parseICalFeed` avec `default`) |
| `initializeDefaultData` | 2 | `now`, `mathRandom` | `expected: { courses, revisions, subjects, pronoteEvents, settings }`, `expectedExport` (objet de `exportAllData()`) |
| `todayScreen` | 7 | `input: { today, courses, revisions, subjects, pronoteEvents }` | Modèle de l’écran Aujourd’hui (voir plus bas) |
| `scoreSubmit` | 7 | `now`, `input: { courses, revisions, settings, revisionId, score }` | `expected: { today, preparationDate?, ignored, revisions, celebrate, toast }` |
| `scorePreview` | 23 | `input: { score, sensitivity, emergencyThreshold }` | `expected: { tone, title, description, scoreBand, confettiOnSubmit }` |
| `planning` | 14 | `now`, `fixture: planning`, `input: { mode: day\|week\|month, date, subjectId?, phase? }` (filtres absents = `all`) | `expected: { period, days[] }` ; un jour vaut `{ date, heading, countLabel, isToday, outsideMonth? (mois seulement), timetableSummary?, events?, tiles? \| empty }` |
| `planningNavigate` | 10 | `input: { mode, date, direction: ±1 }` | `expected` : nouvelle date `yyyy-MM-dd` |
| `revisionDetail` | 7 | `fixture: revisionDetail`, `input.revisionId` | Détail de séance (`opens: false` si le cours ou la matière manque) |
| `coursesList` | 11 | `fixture: coursesList`, `input: { search?, subjectId? }` | `expected: { header, empty, groups[] }` |
| `courseDetail` | 5 | `fixture: coursesList`, `input.courseId` (révisions = celles du cours, dans l’ordre de la fixture) | Fiche du cours |
| `courseCreate` | 9 | `now`, `fixture: coursesList`, `input: { settings, form: { subjectId, chapter, title, date, description } }` | `expected: { courseNumber, submitLabel, chapterSuggestions, created, course?, newRevisions?, courseOrder?, revisionOrder?, toast? }` |
| `courseCreatePrefill` | 4 | `input: { event, subjects }` | `expected: { subjectId, date, title }` |
| `courseDelete` | 3 | `fixture: coursesList`, `input.courseId` | `expected: { courses, revisions (ids), toast }` |
| `stats` | 6 | `input: { courses, revisions, subjects }` | Indicateurs de Mes progrès |
| `settingsUpdate` | 30 | `input.action` parmi `addInterval`, `removeInterval`, `resetIntervals`, `save`, `sensitivityLabel`, `thresholdLabel`, `subjectColor` | Voir chaque cas |
| `importExport` | 13 | `fixture: importBefore` (état stocké avant l’import), `input.json` (texte brut) ; ou `export_format` : `now`, `input.state` | `expected: { accepted, toast, reloadsPage, stateAfterReload }` ; pour l’export, `{ json (texte exact), parsed }` |

Ce que couvrent les cas :

- **Adaptatif** : intervalles par défaut et personnalisés ; scores 0, 29, 30, 50, 70 et 100 sur J0 ; matrice de sensibilité 0,1 / 0,4 / 1,0 × scores sur J4 et J14 ; seuils 0 et 50 ; dernière étape ; rattrapage puis validation du rattrapage ; validations en retard et en avance ; dates par défaut tirées de l’horloge, y compris à 00:30 ; changements de mois et d’année ; heure d’été et d’hiver ; entrée non triée ; réglages plus courts que le planning ; étape déjà terminée hors ordre ; statut `overdue`.
- **Enchaînements** : séances faites le soir précédent (`today = scheduledDate - 1 + delayDays`, référence `today + 1`), en reprenant à chaque fois la séance en attente la plus ancienne.
- **Programme du soir** : retards, séances terminées le jour même ou en avance, cours supprimé, rattrapage, statut `overdue`, égalités, changements de mois et d’année, heure d’été et d’hiver.
- **iCal** : `Z` et `TZID`, lignes repliées (espace et tabulation), trois formes d’annulation, absence d’`UID` ou de `DTEND`, heure sans secondes, journée entière, date invalide, paramètres sur `SUMMARY` et `LOCATION`, passage de minuit UTC, heure inexistante ou ambiguë au changement d’heure, flux tronqué, propriétés hors `VEVENT`.

`generateSamplePronoteFeed` est **inclus** : il ne dépend que de l’horloge locale (il produit les cours du lendemain) et devient déterministe une fois `now` figé. `initializeDefaultDataIfEmpty` est inclus avec `Math.random = 0.5`, ce qui donne `score = 77`. Dans le code web, ce score est aléatoire : `Math.floor(65 + random * 25)`, soit un entier de 65 à 89. Un port peut le tirer au hasard, et ses tests acceptent alors toute valeur de cet intervalle. Le champ `exportDate` de l’export vaut `now` en ISO UTC.

## Écran Aujourd’hui (logique en ligne dans `DashboardView.tsx`, `App.tsx`, `Navigation.tsx`)

Le type `DayRevisionGroup` de `src/types` **n’est utilisé nulle part**. L’écran ne regroupe ni par chapitre ni par étape : il regroupe **par matière seulement**, et l’étape n’apparaît qu’en badge sur chaque ligne.

1. `today` est la date locale (`yyyy-MM-dd`) de l’instant courant. Le web la recalcule toutes les 30 s, si bien qu’après minuit le programme bascule sur le jour suivant.
2. `programme = getEveningProgramme(revisions, courses, today)`. `preparationDate` vaut `today + 1`.
3. `due` contient les séances non terminées (`status != 'completed'`, donc `pending` **et** `overdue`) dont le cours existe et dont `scheduledDate <= preparationDate`. Elles sont triées par `scheduledDate` croissant, par un tri **stable** : à date égale, l’ordre du tableau d’entrée est conservé.
4. `completed` contient les séances terminées dont le cours existe et dont `completedDate == today`, la date réelle et non celle de préparation, dans l’ordre d’entrée.
5. `overdue` est le sous-ensemble de `due` où `scheduledDate < preparationDate`. Une séance prévue `today` est donc en retard. `visible` vaut `due + completed`.
6. Compteurs : `total = due + completed` ; `progress = round(completed / total × 100)`, arrondi au supérieur à mi-chemin, ou 0 si `total = 0`. Le badge de l’onglet affiche `due.length`, calculé comme `pendingCountToday + overdueCount` avec `pendingCountToday = due - overdue`, et seulement s’il est supérieur à 0.
7. Séance mise en avant : `due[0]`. Libellé de la matière : `subject.name`, ou `ton cours` si la matière n’existe plus. Sans séance : « Tu peux souffler ! ».
8. Groupes : on parcourt `subjects` **dans l’ordre de la liste des matières**. Pour chacune, les lignes sont `visible.filter(course.subjectId == subject.id)`, dans l’ordre de `visible` (dues par date, puis terminées). Une matière sans ligne est ignorée. Libellé : `N séance(s) au programme`.
9. Les séances d’un cours dont la matière n’est pas dans `subjects` sont comptées dans `due`, `total`, `progress` et le badge, et peuvent être mises en avant, mais **n’apparaissent dans aucun groupe**.
10. Une ligne porte le badge `<label de phase> · <stepLabel>` (`Découvrir`, `S’entraîner` ou `Se tester`, voir `constants.json/TODAY_PHASES`), puis `<chapitre> · C<numéro>` et le titre. Une ligne terminée affiche `Fait · <score>/100` sans bouton ; une ligne à faire affiche « Faire le point ».
11. Bandeaux : « Toutes tes révisions sont terminées ! » si `due` est vide et `completed` non vide ; `N séance(s) à reprendre…` si `overdue` n’est pas vide ; « Tout est à jour » si `visible` est vide. Message de progression : `completed > 0` → « Chaque séance compte… » ; sinon `total > 0` → « La première séance… » ; sinon « Aucune séance à préparer pour demain. ».
12. « Ta journée de cours de demain » : `pronoteEvents.filter(date == preparationDate)`, dans l’ordre stocké (celui de `parseICalFeed`). Un cours annulé affiche `Annulé` et n’a pas de bouton d’ajout ; un cours maintenu affiche la salle, éventuellement absente.
13. Dates d’en-tête : `EEEE d MMMM` en français, en minuscules (`mardi 17 novembre`).

**Validation (`App.handleScoreSubmit`, suite `scoreSubmit`)** : la validation est ignorée si l’id est inconnu ou si la séance est déjà `completed`. Sinon, on appelle `applyAdaptiveReview(revisions du cours, id, score, settings, today, preparationDate)`. Le nouveau tableau global vaut `[révisions des autres cours dans l’ordre…, révisions du cours triées par stepIndex…]` : **l’ordre global change**, ce qui modifie l’ordre des égalités dans `due` et `completed`. Le feu d’artifice part si `due` était non vide avant et devient vide après. Le toast affiche `Rattrapage programmé` (type `warning`) pour un rattrapage, sinon `Révision validée (<score>/100)` (type `success`), avec `explanation.message`.

**Aperçu de score (`ScoreModal`, suite `scorePreview`)** : les règles s’appliquent dans cet ordre. Si `score < emergencyThreshold`, rattrapage. Sinon, si `score < 50`, séance rapprochée de `-round((1 - factor) × 100)%`. Sinon, si `score > 50`, séance espacée de `+round((factor - 1) × 100)%`. Sinon, même rythme. Couleur du score : `< 30` rose, `< 50` ambre, `== 50` ciel, `< 85` émeraude, sinon indigo. Confettis à la validation si `score >= 75`. Curseur de 0 à 100 par pas de 5, valeur initiale 50.

## Planning (`PlanningView.tsx`, `RevisionDetailModal.tsx`)

- **Révisions visibles** : celles dont le cours existe **et** dont la matière existe encore. Contrairement à Aujourd’hui, un cours de matière supprimée n’apparaît pas. S’y ajoutent les filtres matière (`course.subjectId`) et étape (`revision.phase`).
- **Jours affichés** : en mode jour, la date seule. En semaine, du lundi au dimanche de la semaine qui contient la date (un dimanche appartient à la semaine commencée le lundi précédent). En mois, du lundi de la semaine du 1er au dimanche de la semaine du dernier jour, soit 35 ou 42 jours ; les jours hors du mois portent `outsideMonth`.
- **Séances d’un jour** : `scheduledDate == jour`. D’abord les non terminées, puis les terminées, et dans chaque groupe par `stepIndex` croissant ; à égalité, l’ordre du tableau est conservé. Un rattrapage J0* (stepIndex 0) passe donc avant un J1 du même jour. Le statut `overdue` est traité comme non terminé.
- **Créneaux Pronote d’un jour** : `date == jour`, plus le filtre matière par `matchedSubjectId` (un créneau non apparié disparaît dès qu’une matière est choisie). Le filtre d’étape ne s’applique pas aux créneaux. Ordre du stockage. Libellé `HH:mm · <subjectName>`, suivi de ` · Annulé` si le cours est annulé ; résumé `Emploi du temps · N cours`.
- **Tuile** : nom de la matière, `stepLabel`, `<libellé Planning> · C<n>`, titre, chapitre. Une tuile terminée affiche `Terminée · <score ou —>/100`, une autre `Voir la séance →`. Libellé d’accessibilité : `<matière>, <stepLabel>, <phase>, C<n> <titre>` suivi de `, terminée` si la séance est faite.
- **Libellés de phase différents** : le Planning et le détail de séance disent `Encodage`, `Consolidation`, `Récupération` (`REVISION_PHASE_LABELS`), tandis qu’Aujourd’hui et la fiche de cours disent `Découvrir`, `S’entraîner`, `Se tester`.
- **En-têtes** : jour `EEEE d MMM` (mois : `EEE d`), compteur `N séance(s)` suivi de ` · Aujourd’hui` pour le jour courant. Période : mois `MMMM yyyy`, jour `EEEE d MMMM yyyy`, semaine `d MMM – d MMM yyyy`. Les chaînes sont en minuscules ; la classe CSS `capitalize` met **une majuscule à chaque mot** à l’affichage (« Lundi 30 Mars »). Un jour vide affiche « Aucune séance prévue. ».
- **Navigation** : ±1 jour, ±1 semaine, ±1 mois en arithmétique calendaire ; `addMonths` ramène au dernier jour du mois (31 janvier + 1 mois = 28 février). « Aujourd’hui » revient à l’instant courant.
- **Détail de séance** : l’historique contient les séances terminées du même cours, hors la séance ouverte. Si celle-ci est terminée, seules restent celles dont `completedDate ?? scheduledDate` est inférieure ou égale à la sienne (le même jour compris). Tri par cette date décroissante, puis `stepIndex` décroissant ; à égalité, ordre du tableau. Ligne : `<stepLabel> · <phase>` et `dd/MM/yyyy · <score>/100` ou `Sans note`. Séance terminée : `Séance terminée le dd/MM/yyyy · <score ou —>/100`, la date étant omise sans `completedDate`. Sinon, un curseur de 0 à 100 par pas de 5 (50 au départ), une confirmation `Terminer cette séance avec N/100 ?`, puis la même validation qu’Aujourd’hui (suite `scoreSubmit`, soir compris). Le détail ne s’ouvre pas si la matière a disparu.

## Mes cours (`CoursesView.tsx`, `CourseDetailModal.tsx`, `AddCourseModal.tsx`, `App.tsx`)

- **Filtre** : par matière, puis recherche. La recherche n’est active que si `search.trim()` n’est pas vide, mais la requête comparée est `search.toLowerCase()` **non nettoyée** (« test » suivi d’une espace ne trouve que les titres qui contiennent « test » suivi d’une espace). Elle porte sur le titre, le chapitre ou `c<numéro>` (« c1 » trouve C1 et C10 à C19), sans repli des accents.
- **Groupes** : matières dans l’ordre de la liste des matières ; un cours de matière supprimée n’est jamais listé, mais l’en-tête `Mes cours (N)` compte **tous** les cours, sans filtre. Dans une matière, les chapitres apparaissent dans l’ordre de première apparition dans `courses` (le web place les nouveaux cours en tête) et sont regroupés à **l’identique de la chaîne**, casse comprise (« grammaire » et « Grammaire » forment deux groupes). Dans un chapitre, les cours sont triés par `courseNumber`.
- **Carte de cours** : `progress = round(terminées / total × 100)`, ou 0 sans révision ; compteur `t / n J`. « Prochain » est la première révision **dans l’ordre du tableau** au statut `pending` (un rattrapage ajouté juste après sa séance peut donc passer avant ; le statut `overdue` est ignoré). « Dernier score » est le score de la dernière révision **dans l’ordre du tableau** qui en a un, pas la plus récente par date.
- **Fiche de cours** : révisions triées par `stepIndex` (stable) ; `t / n effectuées (p%)`, où `p` vaut **`NaN`** quand le cours n’a aucune révision (`progressPercent: "NaN"` dans les vecteurs). La moyenne est l’arrondi de la moyenne des scores définis, ou rien. « Créé le » affiche `course.date` (date du cours), pas `createdAt`. Une révision terminée sans score affiche **`50/100`** ici, mais `—` dans le Planning. Une révision non terminée, `overdue` compris, propose « Valider ». Le libellé de matière vaut `Matière` si elle a disparu.
- **Création** : le numéro vaut 1 si le chapitre est vide ou nouveau, sinon `max(courseNumber) + 1` parmi les cours de la même matière dont le chapitre est égal après `trim` et passage en minuscules, sans repli des accents (« Energie » ≠ « Énergie »). Les trous ne sont pas comblés. Le formulaire est refusé sans titre ou sans chapitre (après `trim`). Le cours stocke le titre et le chapitre nettoyés, une `description` nettoyée ou absente, l’id `course-<now.epochMs>` et `createdAt` égal à la date locale du jour. Ses révisions viennent de `generateInitialRevisions(course, settings.intervals)`. Le nouveau cours est placé **en tête** de `courses` et ses révisions **à la fin** de `revisions`. Le toast annonce toujours « 10 échéances », quel que soit le nombre d’intervalles. Suggestions de chapitre : chapitres distincts de la matière dans l’ordre des cours, au plus trois, visibles seulement quand le champ est vide.
- **État du formulaire** : il n’est pas remis à zéro entre deux ouvertures. Le chapitre est conservé ; la matière et la date ne changent que si un préremplissage les fournit ; la date initiale est celle du **lancement de l’app** ; le titre et la description sont vidés à chaque ouverture.
- **Préremplissage depuis un créneau Pronote** (bouton d’Aujourd’hui) : `subjectId = matchedSubjectId`, sinon la première matière ; `date = event.date` ; `title = event.subjectName`.
- **Suppression** : après confirmation (`Es-tu sûr de vouloir supprimer définitivement le cours C<n> : <titre> ?`), le cours et toutes ses révisions sont retirés. Les créneaux Pronote ne changent pas. Un id inconnu ne change rien, mais le toast s’affiche quand même.

## Mes progrès (`StatsView.tsx`)

Toutes les révisions stockées sont prises en compte, y compris celles d’un cours dont la matière a disparu.

| Indicateur | Formule |
|---|---|
| Taux de réalisation | `round(terminées / toutes × 100)`, 0 sans révision ; `t sur n faites` |
| Score moyen | `round(moyenne des score !== undefined)`, **50 s’il n’y a aucun score** ; `>= 50` → « Au-dessus de l’équilibre », sinon « Sous l’équilibre ». Un score posé sur une révision non terminée compte ; une révision terminée sans score ne compte pas |
| Régularité | **constante `5 j`** : la série n’est pas calculée |
| Cours suivis | `courses.length` ; `Sur N matières`, où N est le nombre de matières ayant au moins un cours (« Sur 1 matières », sans singulier) |
| Par matière | Matières dans l’ordre de la liste, gardées si elles ont au moins un cours : `(c cours • t/n J)`, taux, moyenne arrondie ou rien |
| À travailler | Matières dont la moyenne arrondie est `< 50` (une moyenne de 49,5 arrondie à 50 n’y figure pas) |

## Réglages (`SettingsView.tsx`)

- **Sauvegarde** : rien n’est enregistré avant « Enregistrer toutes les modifications ». On garde alors les autres champs des réglages, on nettoie l’URL iCal par `trim` et on trie les intervalles.
- **Ajout d’un palier** : `parseInt(valeur, 10)` au sens de JavaScript (espaces initiaux ignorés, signe accepté, préfixe numérique : `"12abc"` → 12, `"1.9"` → 1, `"1e3"` → 1, `"+5"` → 5, `"-0"` → 0). La valeur est acceptée si elle est un nombre `>= 0` absent de la liste ; la liste est alors triée.
- **Retrait d’un palier** : uniquement si la liste a **plus de deux** paliers. J0 n’a pas de bouton de retrait, mais la fonction le retirerait.
- **Rétablir** : remet `DEFAULT_INTERVALS`.
- **Effet des intervalles** : ils ne servent qu’aux **nouveaux** cours. Les plannings existants ne sont pas recalculés, mais `applyAdaptiveReview` lit les intervalles actuels par `stepIndex` (voir plus bas).
- **Curseurs** : sensibilité de 0,1 à 0,8 par pas de 0,05 (`Impact max : ±round(s × 100)%`) ; seuil de 10 à 40 par pas de 5. Un import peut contenir des valeurs hors de ces bornes (1,0 par exemple), et l’algorithme les accepte telles quelles.
- **Matières** : le web permet **seulement de changer la couleur** d’une matière (l’ordre et les autres champs sont conservés). Il n’y a ni ajout, ni suppression, ni renommage, ni distinction d’usage entre matières par défaut et personnalisées : `isCustom` n’est lu nulle part. Les matières personnalisées n’arrivent que par import. Retirer une matière par import produit les effets « matière supprimée » décrits plus haut.
- **Export** (`exportAllData`) : `JSON.stringify({ version: "1.0", exportDate: <now ISO UTC>, courses, revisions, subjects, settings }, null, 2)`. Les réglages exportés sont fusionnés avec les valeurs par défaut ; `pronoteEvents` n’est pas exporté. Le nom de fichier est `jmethod-sauvegarde-<yyyy-MM-dd>.json`.
- **Import** (`importAllData`, puis rechargement de la page) : il est refusé (toast `Erreur d’importation`) si le JSON est illisible, vaut `null`, ou si `courses` ou `revisions` est absent ou « faux ». Sinon, les cours et les révisions sont remplacés ; les matières et les réglages ne le sont que s’ils sont présents et non nuls. Les réglages sont stockés **tels quels**, sans fusion avec les réglages précédents, puis fusionnés avec `DEFAULT_SETTINGS` à la lecture : un import partiel perd donc les anciens réglages non fournis. Les créneaux Pronote de l’appareil sont conservés. `version` et les champs inconnus sont ignorés. **Aucune validation de forme** : `{"courses": {}, "revisions": {}}` ou des chaînes sont acceptés et stockés.
- « Recharger démo 3ème » vide tout le stockage puis recrée les données de démonstration (suite `initializeDefaultData`).

## Particularités du TypeScript à reproduire fidèlement

Il ne faut pas les corriger dans les ports : ce sont le comportement de référence tant que le web n’a pas tranché.

**`applyAdaptiveReview`**
- Les révisions sont triées par `stepIndex` avec un tri stable. Un rattrapage a le même `stepIndex` que la séance d’origine et reste placé juste après elle.
- Pour un rattrapage (`score < emergencyThreshold`, inégalité stricte), la nouvelle révision `<stepLabel>* (Rattrapage)` est créée avec la phase `consolidation`, `adjustedIntervalDays: 1` et la date `référence + 1`. Elle est insérée juste après la séance validée, **sans tri par date**. **Les étapes suivantes ne sont pas replanifiées.** Un rattrapage se déclenche aussi sur la dernière étape et sur un rattrapage déjà en cours.
- La « prochaine séance » est la première révision située après la séance validée qui a le statut `pending` et n’est pas un rattrapage. Une révision au statut `overdue` est sautée.
- Écart de base : `max(1, intervals[next.stepIndex] - intervals[current.stepIndex])`. Si l’index courant manque dans les réglages, il vaut 0 ; si l’index suivant manque, il vaut `courant + 1`. L’écart adapté vaut `max(1, round(écart × factor))`, compté à partir de `schedulingReferenceDate`.
- Toutes les révisions suivantes, **y compris celles déjà terminées et les rattrapages**, sont réécrites de proche en proche : `max(1, round(max(1, intervals[i] - intervals[i-1]) × factor))`, en prenant les intervalles **des réglages actuels**. Quand ces réglages sont plus courts que le planning, les écarts tombent à 1 jour (cas `settings_intervals_shorter_than_schedule`).
- À la dernière étape, `actionType` vaut `exact` quel que soit le score, `factor` est calculé quand même et `nextDate` est absent.
- Messages : `1 jours` (pas de singulier). `+0%` est possible (score 51, sensibilité 0,1). On peut lire « espacé de +24 % (1 jours au lieu de 1j) » : l’arrondi annule l’effet annoncé.
- Le message de rattrapage dit « pour demain » alors que la date cible est `référence + 1`, soit le surlendemain du soir de validation, à préparer le lendemain soir.
- Valeurs par défaut : `actualCompletionDate` est la date locale de `now`, et `schedulingReferenceDate` vaut `actualCompletionDate`. Passer `undefined` déclenche la valeur par défaut.
- Un id inconnu lève l’exception `Revision <id> non trouvée`.

**`generateInitialRevisions`** : `scheduledDate = course.date + intervals[i]`, `stepLabel = J<intervals[i]>`, phase tirée de l’index. `adjustedIntervalDays` vaut **0 pour l’index 0 même si le premier intervalle n’est pas nul**, puis `intervals[i] - intervals[i-1]`, qui peut être nul ou négatif. Il n’y a pas de dédoublonnage. `getPhaseForStep(-1)` renvoie `consolidation`.

**`matchSubject`**
- Normalisation : `toLowerCase()` (Kotlin : `Locale.ROOT`), puis NFD, puis suppression des seuls caractères U+0300 à U+036F. Ne pas utiliser `folding(.diacriticInsensitive)`, qui en retire davantage.
- L’éducation physique est testée d’abord, avec l’expression `/\beps\b|sport|ed\.?\s*physique|education physique/` (`\b` au sens ASCII de JavaScript). Si elle correspond, on renvoie la matière d’id `eps` **ou rien**, sans essayer les autres matières. Avec les matières par défaut, « ED.PHYSIQUE & SPORT. » et « EPS » ne correspondent donc à rien.
- Ensuite, on prend la **première matière de la liste** qui correspond : nom normalisé contenu dans le libellé, puis alias par sous-chaîne. L’ordre des matières compte : « HISTOIRE DES ARTS » donne `histoire-geo`, mais `arts-plastiques` si la liste est inversée.
- La sous-chaîne `geo` fait correspondre « GEOMETRIE » à `histoire-geo`. « EDUCATION MUSICALE » et « ENSEIGN. MORAL & CIVIQUE » ne correspondent à rien avec les matières par défaut. Un libellé vide donne `null`.

**`parseICalFeed`**
- Seul `\r\n` est remplacé par `\n`. Une ligne qui commence par une espace ou une tabulation est rattachée à la précédente, privée de ce premier caractère. Le déploiement des lignes se fait avant toute autre analyse.
- Seules `DTSTART` et `DTEND` acceptent des paramètres (`;TZID=…`, `;VALUE=DATE`). `SUMMARY;LANGUAGE=fr:` et `LOCATION;ALTREP=…:` sont ignorées. Sans `SUMMARY`, l’événement disparaît. Les valeurs sont nettoyées par `trim`, sans décodage des échappements (`\,` reste tel quel). Les propriétés hors `VEVENT` et un `VEVENT` jamais fermé sont ignorés.
- Dates : `yyyyMMddTHHmm[ss][Z]`. Avec `Z`, l’heure UTC est convertie en heure locale (Paris). Sans `Z`, **le `TZID` est ignoré** : l’heure est lue comme heure locale de l’appareil (`TZID=America/New_York:…T083000` donne 08:30). Une heure inexistante au passage à l’heure d’été (29/03/2026 02:30) devient **03:30**. Les autres formes passent par `parseISO` de date-fns : `20261120` (`VALUE=DATE`) donne 00:00. Une date illisible fait rejeter l’événement.
- `endTime` vaut `startTime` sans `DTEND`. La date de fin n’est pas conservée : seule l’heure l’est.
- Annulation : le libellé en minuscules contient `annulé` (é précomposé), ou `STATUS` vaut `CANCELLED` sans tenir compte de la casse. « ANNULE », sans accent, n’annule pas.
- `subjectName` vaut le nom de la matière trouvée ; à défaut, le libellé dont on retire **la première** occurrence de `[annulé]` (insensible à la casse), puis `trim`. `room` vient de `LOCATION`, `teacher` de `DESCRIPTION`, et `isCourseCreated` vaut `false`.
- Le tri se fait sur la chaîne `date + "T" + startTime`, de façon stable (deux cours à 10:00 restent dans l’ordre du flux).

**`getEveningProgramme`** : `completed` compare `completedDate` à `today`, pas à `preparationDate`. Le statut `overdue` compte comme dû. Les séances d’un cours supprimé disparaissent. Les rattrapages sont des séances dues comme les autres.

**Démo (`initializeDefaultDataIfEmpty`)** : neuf cours dont les dates sont relatives au jour courant (pas au soir). Les séances antérieures à aujourd’hui sont marquées terminées avec `completedDate = scheduledDate`, et non la veille. L’export ne contient pas `pronoteEvents`.

**Écrans des jalons 2 à 4** (détaillés plus haut) :
- Le Planning masque les cours de matière supprimée, alors qu’Aujourd’hui et Mes progrès les comptent.
- Il existe deux jeux de libellés de phase.
- Un score absent s’affiche `—` dans le Planning, mais `50/100` dans la fiche de cours.
- La fiche affiche `NaN%` pour un cours sans révision.
- « Prochain » et « Dernier score » suivent l’ordre du tableau, pas les dates.
- Dans Mes cours, la recherche n’est pas nettoyée, et le regroupement des chapitres respecte la casse alors que la numérotation l’ignore.
- La régularité est une constante `5 j`.
- Le score moyen vaut 50 en l’absence de données.
- Le toast de création annonce toujours 10 échéances.
- Le formulaire de création garde son état d’une ouverture à l’autre.
- L’import ne valide pas la forme des données et remplace les réglages sans fusion.
- Un `"score": null` venu d’un import est compté comme 0 dans Mes progrès (`null !== undefined`). Ce comportement est probablement accidentel et relève d’une décision à prendre. Le cas `score_null_from_import_counts_as_zero` le fige ; un port qui décode `null` en absence de valeur le fera échouer et devra le marquer comme écart connu.

**Validation** : une séance en rattrapage n’est jamais due le soir même, puisque sa date cible tombe après `preparationDate`. Valider la dernière séance du soir avec un score de rattrapage déclenche donc quand même le feu d’artifice (`last_due_emergency_also_celebrates`).
