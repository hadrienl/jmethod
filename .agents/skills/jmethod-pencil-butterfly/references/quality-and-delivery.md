# Contrôle de fidélité et livraison

## Contrôle visuel obligatoire

Comparer côte à côte mentalement ou avec un outil d'affichage la deuxième colonne de la planche et le résultat. Examiner à taille normale puis à la taille d'affichage prévue. Ne pas déclarer la fidélité sur la seule réussite de l'appel de génération.

| Critère | Conforme | Dérive à corriger |
|---|---|---|
| Silhouette | Corps fin, tête ronde, ailes dominantes | Corps de peluche, bébé trapu, insecte réaliste |
| Ailes | Supérieures grandes, inférieures plus petites, motifs effilés citron/lilas | Quatre pétales identiques, cape, motifs absents ou nouvelle décoration |
| Visage | Regard prune, accents fins, joues roses discrètes | Nouveau visage, gros nez, cils excessifs, contour noir épais |
| Antennes | Deux fines courbes, bouts ovales allongés | Boules plastiques, cornes, antennes courtes ou supplémentaires |
| Médium | Hachures, pression variable, contours violet irréguliers, papier visible | Vectoriel propre, airbrush, 3D, aquarelle ou feutre uniforme |
| Palette | Lavande/lilas/prune avec touches citron et rose | Recoloration de matière, saturation néon, grands aplats opaques |
| Anatomie | Membres attachés au corps, occultation plausible | Bras issus d'une aile, aile en plus, yeux flottants |
| Pose | Action et direction demandées, silhouette lisible | Accessoire ou geste qui cache tous les marqueurs d'identité |
| Livraison | Marges suffisantes, fond demandé, pas de texte parasite | Aile/antenne coupée, titre de planche, damier imprimé |

Une différence de style, de visage ou d'anatomie est bloquante pour qualifier un résultat de déclinaison fidèle. Un défaut mineur de pose peut se corriger localement. Les hex indicatifs de la bible ne sont pas un test automatique de correspondance pixel à pixel.

## Petites tailles et interface

- Vérifier que tête, ailes et antennes restent lisibles à la taille prévue, notamment vers 64–128 pixels pour un petit widget. Il s'agit d'un contrôle de lisibilité, pas d'une taille d'export universelle.
- Ne pas épaissir tout le dessin pour compenser une taille trop petite. Préférer un cadrage buste/visage si demandé, ou un asset spécifique à cette taille dont le style reste crayonné.
- Garder une marge autour des antennes, mains et ailes ; une pose qui pointe doit laisser l'espace libre du bon côté.
- Le texte fonctionnel reste du vrai texte d'interface séparé de l'image. Fournir une courte description alternative de la pose si l'illustration porte une information ; si elle est décorative, l'intégrateur peut la masquer aux lecteurs d'écran.
- Les illustrations de réussite/repos ne décident pas des états métier du programme ou de la flamme.

## Références et traçabilité

- Ne jamais remplacer `assets/approved-style-board.png` par une nouvelle génération. Garder les quatre colonnes originales inchangées : la sélection canonique est documentée.
- Les nouvelles images sont des déclinaisons, pas des références approuvées par défaut. Si l'utilisateur approuve un profil/dos ou une nouvelle pose comme référence secondaire, l'enregistrer séparément avec date/contexte d'approbation.
- Chaque asset destiné à la production doit pouvoir être relié à la référence d'origine, au prompt réellement utilisé et à la version du fichier. Conserver ces informations dans le mécanisme de suivi déjà utilisé par le projet ou un petit manifeste adjacent, sans imposer une base de données.
- Exemple minimal de métadonnées, à remplir avec les vraies valeurs : fichier, pose/vue, référence utilisée, outil, prompt, date, statut `proposal` ou `approved`, défauts connus. Ne jamais marquer une sortie `approved` sans validation réelle.

## Avant de livrer

1. Image effectivement visualisée et comparaison de fidélité effectuée.
2. Pose et expression conformes à la demande ; déviations et extrapolations signalées.
3. Alpha réel si fond transparent demandé ; pas de suppression des réserves blanches du crayon.
4. Fichier sauvegardé dans le projet si destiné à son intégration ; pas de dépendance à un répertoire temporaire ou à un historique privé pour les références.
5. Indiquer le chemin pour un asset de projet, le prompt final et l'outil de génération utilisés, selon le workflow imagegen disponible.

## Validation du skill lui-même

La validation de structure du skill contrôle frontmatter et références, pas la ressemblance de toutes les poses possibles. Pour un essai graphique, demander une pose qui diffère de l'original (par exemple main ouverte pointant à gauche) avec la planche officielle, puis appliquer la grille ci-dessus. Une réussite isolée ne prouve pas une reproduction universelle ; ajouter seulement les corrections soutenues par des dérives réellement observées.
