# Cahier de textes : référence native (jalon 5, lecture seule)

Aperçu en lecture seule du cahier de textes PRONOTE dans les apps natives : contenus de séance, devoirs avec échéance, pièces jointes et liens, téléchargement protégé. Ce document complète `protocol-reference.md` (transport, chiffrement, signature parent) et `CONTRACT.md`. Il décrit le comportement du connecteur web (`scripts/pronote_notebook.py`, `scripts/pronote_server.py`, `src/components/PronoteNotebookPreview.tsx`) et de pronotepy 2.15.7 (`clients.py`, `dataClasses.py`), puis les règles natives quand elles s’en écartent. Le protocole n’est pas officiel et dépend des versions de PRONOTE.

Vecteurs : `notebook-vectors.json`, générés par `notebook-vectors.py` (voir la fin du document).

## 1. Liste blanche

| Appel | Statut | Conditions |
| --- | --- | --- |
| `FonctionParametres`, `Identification`, `Authentification`, `ParametresUtilisateur`, `PageInfosPerso`, `Navigation`, `PageEmploiDuTemps` | LECTURE (inchangé) | voir `protocol-reference.md` |
| `PageCahierDeTexte` | **LECTURE (ajout jalon 5)** | onglet `89` (contenus) ou `88` (travail à faire) uniquement ; `data` ne contient que `domaine` ; `domaine._T == 8` ; `V == "[a..b]"` avec `1 ≤ a ≤ b` et `b − a ≤ 5` |
| `GET <racine>/FichiersExternes/<id chiffré>/<nom>?Session=<h>` | LECTURE (téléchargement) | HTTPS, même hôte que la session, cookies de la session, 20 Mo maximum (section 8) |

Refuser localement, avant tout envoi :

- toute fonction absente de la liste, et en particulier tout identifiant commençant par `Saisie` ;
- `SaisieTAFFaitEleve` (onglet 88, `{"listeTAF":[{"N":…,"TAFFait":bool}]}`) : c’est `Homework.set_done` de pronotepy, qui **marque un devoir fait**. L’aperçu affiche l’état `TAFFait` sans jamais proposer de le modifier ;
- les autres écritures connues de pronotepy : `SaisieActualites` (réponses aux sondages), `SaisieMessage`, `SaisiePublicMessage` (messagerie) ; la sécurité `SecurisationCompteDoubleAuth` et `JetonAppliMobile` (déjà refusées) ;
- tout dépôt de fichier (rendu de copie, envoi multipart) et toute requête hors `appelfonction` autre que le `GET FichiersExternes` décrit ici ;
- `PageCahierDeTexte` avec un autre onglet, d’autres clés dans `data` ou plus de six semaines.

Comme pour l’emploi du temps, l’onglet doit figurer dans les onglets autorisés de `ParametresUtilisateur` (`listeOnglets`, aplati comme `_prepare_onglets`). pronotepy lève une erreur locale sinon. Sans 89, l’aperçu indique que le cahier de textes n’est pas disponible pour ce compte. Sans 88, il affiche les contenus seuls.

## 2. Fenêtre de 28 jours

Identique au web : `aujourd’hui` est le jour calendaire local (fuseau de l’établissement, `Europe/Paris` en métropole ; les tests injectent l’horloge). `from = aujourd’hui − 14 j`, `to = aujourd’hui + 13 j`, bornes incluses : 14 jours précédents, le jour courant, 13 jours suivants. Toutes les comparaisons portent sur des dates calendaires, jamais sur des instants UTC.

Semaine PRONOTE : `semaine(d) = 1 + trunc((d − PremierLundi).jours / 7)` (troncature vers zéro, `ClientBase.get_week`), avec `PremierLundi = FonctionParametres…General.PremierLundi.V`. `domaine = {"_T": 8, "V": "[semaine(from)..semaine(to)]"}`. 28 jours couvrent 4 ou 5 semaines.

Écart natif : la première semaine est ramenée à au moins 1 (`max(1, …)`). En début d’année, pronotepy enverrait `[0..3]` (vecteur `weeks`, 2 septembre 2026). Aucun cours n’existe avant `PremierLundi`, donc le résultat attendu ne change pas.

## 3. Requêtes

Deux appels `appelfonction`, sérialisés comme tous les autres (ordre + 2 à chaque requête), après `ParametresUtilisateur` :

```json
{"Signature":{"onglet":89},"data":{"domaine":{"_T":8,"V":"[10..14]"}}}
{"Signature":{"onglet":88},"data":{"domaine":{"_T":8,"V":"[10..14]"}}}
```

- 89 = contenus et ressources (`Lesson.content`, `fetch_notebook`) ; 88 = travail à faire (`Client.homework`). Le web appelle 89 puis 88. Les vecteurs `requests` reproduisent les charges utiles exactes, avant compression et chiffrement.
- Parent : la signature porte l’enfant sélectionné, sans `ressource` dans `data` (`ParentClient.post`) : `{"Signature":{"onglet":89,"membre":{"N":"<id enfant>","G":4}},"data":{"domaine":…}}`, de même pour 88. Changer d’enfant invalide l’aperçu et les pièces jointes.
- Le web lit aussi l’emploi du temps (`client.lessons`) sur la fenêtre, uniquement pour afficher un nombre de créneaux et retrouver une matière absente. En natif, cet appel est facultatif : on peut prendre la matière dans l’emploi du temps déjà chargé (`timetableSubjects` dans les vecteurs), sinon « Cours ».
- Erreurs `Erreur.G` 10/22/25 : même traitement que `protocol-reference.md`. Après toute reconnexion, relire le cahier de textes avant un téléchargement (identifiants liés à la session).

## 4. Réponses et champs utiles

Réponse 89 : `dataSec.data.ListeCahierDeTextes.V[]`, une entrée par séance publiée.

| Champ | Usage |
| --- | --- |
| `N` | identifiant de l’entrée (identifiant natif de la séance) |
| `cours.V.N` | identifiant du cours dans l’emploi du temps. Un cours récurrent garde le même identifiant d’une semaine à l’autre : il ne sert pas à dater l’entrée. |
| `Date.V` | **date de la séance publiée**, seule date retenue (`18/11/2026 08:30:00` ou `18/11/2026`) |
| `Matiere.V.L` | matière ; à défaut, matière du cours `cours.V.N` dans l’emploi du temps, sinon `Cours` |
| `listeContenus.V[]` | contenus multiples de la séance, tous conservés dans l’ordre |
| `listeContenus.V[].L` | titre, texte brut |
| `listeContenus.V[].descriptif.V` | description HTML |
| `listeContenus.V[].categorie.V.L` | catégorie (`Cours`, `Exercices`…), facultative |
| `listeContenus.V[].ListePieceJointe.V[]` | pièces jointes (ci-dessous) |

Réponse 88 : `dataSec.data.ListeTravauxAFaire.V[]`.

| Champ | Usage |
| --- | --- |
| `N` | identifiant du devoir |
| `PourLe.V` | **date d’échéance** |
| `descriptif.V` | consigne HTML |
| `Matiere.V.L` | matière, sinon `Cours` |
| `TAFFait` | booléen affiché tel quel (« fait » / « à faire »), jamais modifiable |
| `ListePieceJointe.V[]` | pièces jointes |
| `DonneLe.V`, `CouleurFond` | ignorés par l’aperçu |

Pièce jointe : `N` identifiant, `L` nom (pour un lien, souvent l’URL), `G` type (`0` lien, `1` fichier), `url` facultatif pour un lien (sinon `L`).

pronotepy considère comme obligatoires `ListePieceJointe` (contenus et devoirs), `descriptif`, `TAFFait`, `Matiere`, `PourLe` et `CouleurFond` (devoirs). Leur absence fait échouer toute la lecture côté web. En natif, ils sont facultatifs avec les valeurs par défaut du modèle.

## 5. Modèle natif

```text
NotebookSnapshot { from: Date, to: Date, sessions: [SessionEntry], homework: [HomeworkItem],
                   skippedEntries: Int, attachmentCount: Int }
SessionEntry     { id: String, lessonId: String, date: Date, subject: String, contents: [Content] }
Content          { title: String, description: String, links: [Link], category: String,
                   attachments: [Attachment] }
HomeworkItem     { id: String, dueDate: Date, subject: String, description: String, links: [Link],
                   done: Bool, attachments: [Attachment] }
Attachment       { kind: file | link, id: String, name: String, url: String? (lien seulement) }
Link             { label: String, url: String }
```

`Date` est une date calendaire locale (`LocalDate` / `DateComponents` année-mois-jour), sérialisée `YYYY-MM-DD` dans les vecteurs.

Règles (`notebook.expected` dans les vecteurs) :

1. Entrée 89 sans `Date.V` exploitable (absente, mauvais format, date impossible) : `skippedEntries += 1`, entrée non affichée. Devoir sans `PourLe.V` exploitable : idem (écart avec le web, section 9).
2. Entrée datée hors `[from, to]` : ignorée, non comptée. Devoir dont l’échéance est hors fenêtre : ignoré, non compté.
3. Titres, matières, catégories, noms de pièces jointes : texte brut, sans décodage d’entités. Les blancs (`\t \n \v \f \r`, espace, U+00A0) deviennent des espaces, les caractères de contrôle et de direction (section 7) sont retirés, les espaces répétés réduits, puis le tout rogné.
4. Un contenu sans titre, sans description (après conversion) et sans pièce jointe retenue est ignoré. Une séance sans contenu restant est ignorée, sans être comptée.
5. `SessionEntry.id = N`, sinon `cours:<lessonId>@<date>`.
6. Pièce jointe : `G == 0` donne un lien, retenu seulement si son URL passe la règle de la section 8, nom par défaut `Lien`. `G == 1` donne un fichier, nom par défaut `Document`. Une pièce jointe sans `N` ou avec un autre `G` est ignorée.
7. Tri : séances par date décroissante, devoirs par échéance croissante. Le tri est stable (ordre de la réponse pour une même date).
8. `attachmentCount` = nombre de pièces jointes affichées (fichiers + liens joints, hors liens extraits du HTML).

## 6. Dates

Formats acceptés, en correspondance exacte (deux chiffres pour le jour et le mois) :

| Format | Exemple |
| --- | --- |
| `dd/MM/yyyy` | `18/11/2026` |
| `dd/MM/yyyy HH:mm:ss` | `18/11/2026 08:30:00` |
| `dd/MM/yy` | `18/11/26` (année 2000 + yy) |
| `dd/MM/yy HH'h'mm` | `18/11/26 08h30` |

Une heure supérieure à 23, des minutes ou secondes supérieures à 59, ou une date impossible (`31/02/2026`) rendent la valeur inexploitable. Seule la partie date est gardée : aucune conversion de fuseau. Les formats lâches de `Util.date_parse` (`18/11` complété avec l’année courante, `0830` interprété comme aujourd’hui) sont refusés. Le vecteur `dates` donne aussi les résultats de pronotepy pour comparaison.

## 7. HTML vers texte sûr

Les descriptions sont du HTML saisi par les enseignants. On ne les rend jamais dans une WebView, avec `NSAttributedString(html:)` ou avec `Html.fromHtml`, qui peuvent charger des ressources distantes ou dépendre de WebKit. La conversion est un balayage simple, sans exécution ni requête réseau. Elle produit `text` (affiché comme texte brut) et `links`. Elle est identique sur les deux plateformes et vérifiée par `html.cases`.

Balayage, sur la chaîne d’entrée (tous les délimiteurs sont ASCII, donc un parcours en unités UTF-16 est correct) ; les noms sont mis en minuscules ASCII uniquement :

1. `<!--` : ignorer jusqu’à `-->` inclus (jusqu’à la fin si absent). `<!…>` et `<?…>` : ignorer jusqu’au prochain `>`.
2. `<` suivi d’une lettre ASCII, ou `</` suivi d’une lettre : balise. Nom = lettres, chiffres, `-`, `:`. La fin est le premier `>`. Une valeur entre guillemets (`"` ou `'`) ne compte qu’après un `=` (blancs permis entre les deux) et peut contenir `>`. Balise non terminée (ou guillemet non fermé) : **tout le reste est ignoré**.
3. Tout autre `<`, et tout `>` hors balise, est du texte.
4. Éléments supprimés avec leur contenu : `head iframe math noembed noframes noscript object script style svg template textarea title xmp`. Après la balise ouvrante, ignorer jusqu’à `</nom` suivi d’un blanc, de `/`, de `>` ou de la fin, puis jusqu’au `>` suivant. Sans fermeture, ignorer jusqu’à la fin. Un `/>` final n’y change rien.
5. Effets des balises (ouvrantes et fermantes) : `br` = saut dur ; `address article aside blockquote dd div dl dt figcaption figure footer h1…h6 header hr li main nav ol p pre section table tbody tfoot thead tr ul` = saut souple ; `li` ouvrant ajoute ensuite la puce `• ` (U+2022, espace) ; `td`, `th` = espace. Toutes les autres balises disparaissent avec leurs attributs (`onclick`, `style`, `img`/`src`, etc.).
6. `<a …>` ouvre un lien et ferme le précédent ; `</a>` le ferme ; la fin du document aussi. Le premier attribut `href` (nom insensible à la casse ; valeur entre guillemets ou jusqu’au blanc ou `>`) est décodé (entités), puis validé (section 8). S’il est valide, `links` reçoit `{label, url}`, où `label` est le rendu en ligne du contenu du lien (sauts remplacés par des espaces), ou l’URL si ce rendu est vide. Le texte du lien reste dans `text` dans tous les cas : un `href="javascript:…"` donne seulement le libellé, sans lien.

Rendu des segments de texte :

1. Entités, point-virgule obligatoire, sensibles à la casse : les 252 entités nommées HTML 4.01 plus `apos` (`html.entityTable`), `&#NNN;` (1 à 7 chiffres), `&#xHHHH;` (1 à 6 chiffres hexadécimaux). Les valeurs 0, U+D800–U+DFFF ou supérieures à U+10FFFF donnent U+FFFD. Les entités inconnues ou sans point-virgule restent telles quelles. Le décodage a lieu **après** la suppression des balises : `&lt;script&gt;` devient le texte `<script>`, affiché comme texte.
2. Blancs `\t \n \v \f \r`, espace et U+00A0 : remplacés par une espace. Retirés : U+0000–U+001F, U+007F–U+009F (donc `&#128;` disparaît), U+202A–U+202E, U+2066–U+2069, U+FEFF.
3. Saut dur : `\n`. Saut souple : `\n`, sauf si la sortie, espaces finales ignorées, est vide ou se termine déjà par `\n`.
4. Normalisation finale : espaces répétées réduites à une, chaque ligne rognée de ses espaces, trois `\n` ou plus réduits à deux, rognage final des `\n` et des espaces.

Le web affiche `Util.html_parse` de pronotepy (suppression de `<.*?>` sans traverser les lignes, puis `html.unescape`) : le contenu des `<script>` y reste visible comme texte, les balises sur plusieurs lignes ne sont pas retirées et U+00A0 est conservé. Les vecteurs donnent cette sortie dans `pronotepyHtmlParse` pour référence ; les ports natifs doivent produire `expected`.

## 8. Liens et pièces jointes

### URL de lien acceptée

Après suppression des blancs ASCII aux extrémités : commence par `http://` ou `https://` (insensible à la casse) ; aucun caractère ≤ U+0020 ni U+007F–U+009F ; autorité (jusqu’au premier `/`, `?` ou `#`) non vide, sans `@` ni `\`, de la forme `hôte[:port]` avec `hôte` en `[A-Za-z0-9.-]+` ou IPv6 entre crochets, et `port` sur 1 à 5 chiffres, au plus 65535. L’URL retenue est la chaîne rognée, sans réécriture. Les noms d’hôte non ASCII sont refusés (le web les accepte, de même que les espaces et les ports invalides : voir `linkUrls`). Ouverture : navigateur externe (`SFSafariViewController` ou `UIApplication.open` ; Custom Tabs ou `ACTION_VIEW`), jamais dans la WebView PRONOTE ni avec les cookies de la session.

### Téléchargement protégé

Construction (`dataClasses.Attachment`, type 1) :

```text
racine     = URL de la page PRONOTE privée de son dernier segment   (https://school.index-education.net/pronote)
plaintext  = UTF8('{"N":' + chaîneJSON(N) + ',"Actif":true}')       sans espace, N puis Actif
idChiffré  = hexMinuscule(AES-128-CBC(cléSession, ivSession, PKCS7(PKCS7(plaintext))))
url        = racine + "/FichiersExternes/" + idChiffré + "/" + encodeURIComponent(L) + "?Session=" + h
```

- `cléSession` = clé après authentification (MD5 des octets de `cle`) ; `ivSession` = IV courant (MD5 de l’IV temporaire) ; `h` = numéro de session de `Start({...})`. Ce sont les valeurs qui chiffrent les requêtes.
- **Double remplissage** : pronotepy applique `Padding.pad` puis `aes_encrypt`, qui remplit à nouveau. Un `plaintext` de 31 octets devient donc 32 puis 48 octets. Les vecteurs donnent aussi `singlePaddingVariantCiphertextHex` (point incertain, section 10).
- `encodeURIComponent` : UTF-8, `%XX` en majuscules, caractères non encodés `A–Z a–z 0–9 - _ . ! ~ * ' ( )` (identique à `quote(name, safe="~()*!.'")`). On utilise `L` brut, même vide (URL finissant par `/?Session=`).
- Identifiant refusé, sans requête, s’il ne correspond pas à `^[!#-\[\]-~]+$` (ASCII imprimable sans espace, `"` ni `\`) : `rejectedIds`.
- Requête : `GET` avec le même client HTTP et le même stockage de cookies que la session (`communication.session.get` côté pronotepy), même `User-Agent`. HTTPS, même hôte que la session. Les redirections hors de cet hôte, ou vers un autre schéma, interrompent le téléchargement. Pas de nouvel essai automatique. À sérialiser avec les appels `appelfonction` pour ne pas croiser une reconnexion.
- Limite : 20 Mo = 20 971 520 octets. Refuser si `Content-Length` dépasse cette taille, sinon interrompre la lecture en flux dès que le total la dépasse (20 971 520 accepté, 20 971 521 refusé). Statut autre que 200 : « Document indisponible. Actualise le cahier de textes puis réessaie. » Statut 413 local : « Ce document dépasse la limite de 20 Mo. »
- Enregistrement : dossier privé temporaire de l’app (exclu des sauvegardes), sous `saveAsName` : dernier segment après `/` ou `\`, `<>:"|?*` et caractères retirés en section 7 remplacés par `_`, espaces et points rognés aux extrémités, `document` si vide (`saveAsNames`). Ouverture par QuickLook ou `ACTION_VIEW` avec URI `content://` en lecture seule. Jamais dans une WebView avec JavaScript, même pour un `.html` ou un `.svg`. Les fichiers sont effacés à la déconnexion et au changement d’enfant.

À ne jamais exposer : l’URL contient le numéro de session et un identifiant chiffré avec la clé de session, et suffit, avec les cookies, à lire le document. Elle ne doit apparaître ni dans l’interface, ni dans les journaux, rapports de plantage, analyses, presse-papiers, partages, Handoff, intents, liens profonds ou stockage persistant. Elle ne doit jamais être ouverte dans un navigateur externe. Le modèle et l’interface ne manipulent que `Attachment.id` (`N`) et `name`. L’URL est construite au moment du `GET`, dans la couche transport, puis oubliée. `N` reste en mémoire et n’est pas persisté : il peut être périmé après reconnexion (`Erreur.G` 22).

## 9. Écarts avec le web (volontaires)

| Cas | Web (`pronote_notebook.py`) | Natif |
| --- | --- | --- |
| Description | `Util.html_parse` (script visible, pas de sauts de ligne, U+00A0 gardé) | texte sûr, section 7, avec `links` |
| Description faite seulement de U+00A0 | contenu affiché (`cdt-07`) | contenu ignoré |
| Pièce jointe `G` ni 0 ni 1 | traitée comme fichier | ignorée (`pj-unknown`) |
| Devoir sans échéance exploitable, champ obligatoire pronotepy absent | `ParsingError`, toute la lecture échoue (502) | devoir compté dans `skippedEntries` (`taf-05`) ou valeurs par défaut (`taf-07`) |
| Regroupement | liste à plat de contenus, triée par (date, matière) décroissantes | séances avec contenus multiples, date décroissante, tri stable |
| Semaine < 1 | envoyée telle quelle | ramenée à 1 |
| Lien avec espace, port > 65535, hôte non ASCII | accepté | refusé |

Le générateur exécute `fetch_notebook` sur les mêmes réponses, sans les éléments listés dans `webParity.excluded`. Il vérifie l’égalité des dates, matières, titres, catégories, pièces jointes, `done`, `skippedEntries` et `attachmentCount`.

## 10. Points incertains du protocole

- **Identifiant de fichier doublement remplissé** : c’est le comportement de pronotepy, repris par le web, mais aucun téléchargement réel n’a été validé dans ce projet (le parcours web a été testé avec des données fictives). Si un établissement renvoie une erreur, essayer la variante à remplissage simple, fournie dans les vecteurs, puis documenter le résultat.
- Comportement de `FichiersExternes` en cas d’erreur (404, page HTML d’erreur avec statut 200, expiration), rôle exact des cookies par rapport à `Session=h`, `Content-Type` et `Content-Disposition` renvoyés : non observés. Ne pas se fier au `Content-Type` pour choisir un rendu actif.
- Champs non lus par pronotepy (`N` des entrées 89, `DateFin`, `DonneLe`, `verrouille`, `categorie.V.G`, `url` des liens) : présents selon les versions. Ne rien rendre obligatoire au-delà de la section 4.
- `Date.V` est-elle toujours la date de séance, ou parfois une date de publication ? Le web la tient pour la date de séance publiée, et les cours récurrents le confirment. À vérifier sur un compte réel.
- `L` (titres) supposé en texte brut, sans entités ; `descriptif.V` supposé toujours HTML.
- `domaine` en début ou fin d’année (semaine 0, semaines après `DerniereDate`) : réponse du serveur inconnue. Le natif borne à 1 en bas, rien en haut.
- Autres valeurs de `G` (documents « cloud », QCM `executionQCM`…) : ignorées faute d’exemple.
- Les fonctions d’écriture citées viennent de pronotepy 2.15.7 ; d’autres (rendu de devoir, dépôt) existent sans nom vérifié. D’où le refus par préfixe et par liste blanche plutôt que par liste noire.

## Vecteurs

`notebook-vectors.json` : 100 % synthétique, hôte `school.index-education.net`, clé de session `0f0e…00`, IV `1ac1…a8` (= `md5TemporaryIVHex` de `protocol-vectors.json`), `h = 424242`, aujourd’hui = 18 novembre 2026, `PremierLundi` = 31 août 2026.

| Section | Contenu |
| --- | --- |
| `window`, `weeks` | fenêtre de 28 jours, numéros de semaine (pronotepy et natif) |
| `requests` | charges utiles exactes 89/88, élève et parent |
| `safety` | liste blanche, onglets et clés autorisés, refus, règles de téléchargement |
| `dates` | formats acceptés et refusés, comparés à pronotepy |
| `html` | règles, table des entités, 21 cas (script, `onclick`, `javascript:`, entités, accents, caractères hors BMP, balises sur plusieurs lignes, `>` entre guillemets…) avec la sortie pronotepy |
| `linkUrls` | validation des liens, comparée au web |
| `notebook` | réponses brutes 89/88 → modèle attendu (séance à 2 contenus, devoirs avec échéance, fichier, lien, entrées sans date, hors fenêtre, cours récurrent, matière de secours), parité web |
| `download` | texte clair, deux remplissages, chiffré, URL attendue, nom d’enregistrement, identifiants refusés, limite de taille |

Régénérer et vérifier la stabilité (le script vérifie aussi que deux constructions sont identiques) :

```sh
.venv/bin/python native/shared/notebook-vectors.py
.venv/bin/python native/shared/notebook-vectors.py --check
```

`provenance.sources` contient le SHA-256 de `scripts/pronote_notebook.py` et des fichiers pronotepy utilisés. Régénérer après toute modification de ces fichiers.
