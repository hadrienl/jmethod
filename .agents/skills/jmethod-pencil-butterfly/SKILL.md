---
name: jmethod-pencil-butterfly
description: Générer ou décliner la mascotte papillon crayonnée validée de J-Method en conservant son identité, ses ailes, ses textures et ses expressions, pour illustrations, poses et séquences. Utiliser pour cette mascotte précise, pas pour inventer un autre personnage ou gérer un pet ChatGPT.
---

# Papillon crayonné de J-Method

Produire **le même personnage dans une nouvelle situation**, et non un nouveau papillon « dans un style similaire ». Le choix officiel est le **papillon de la deuxième colonne « 2 · Dessiné à la main »** de [la planche validée](assets/approved-style-board.png), ticket #11. Le nom « Papi » était une proposition : aucun nom définitif ni genre du personnage n'est validé.

## Référence et limites

- Ouvrir la planche avec l'outil de visualisation avant toute génération. Utiliser cette image comme référence visuelle à chaque requête d'image ; un prompt seul ne suffit pas.
- **Seule la deuxième colonne fait autorité** : grand personnage en haut, visage souriant en bas. Les colonnes géométrique, cartoon et 3D sont des contre-exemples, pas des variantes à mélanger.
- Ne pas présenter les nouvelles vues de dos, profils, dessous ou poses comme déjà validées : ce sont des extrapolations cohérentes à partir d'une référence unique.
- Aucune génération probabiliste ne garantit une identité parfaite dans toutes les poses. Comparer le résultat à l'original et corriger les dérives. Pour une identité géométrique exacte, produire ensuite un modèle dessiné/riggé et approuvé ; ce skill n'en tient pas lieu.

## Workflow

1. Lire [la bible du personnage](references/character-bible.md). Identifier ce qui peut changer (pose, regard, émotion) et ce qui reste fixe.
2. Déduire du contexte la pose, le cadrage, le fond et la destination. Par défaut : une seule illustration, personnage entier en trois-quarts, aucune légende ni accessoire. Ne pas demander à l'utilisateur des détails déjà connus.
3. Pour une orientation nouvelle, une pose complexe, une expression ou une animation, lire [poses et expressions](references/poses-and-expressions.md).
4. Construire la requête avec [le gabarit de génération](references/generation.md). Inclure la référence officielle et, si utile, une seule déclinaison récente explicitement approuvée. La référence officielle garde la priorité.
5. Employer l'outil de génération/édition d'images disponible et son workflow imagegen. Avec les outils de cette session : `image_gen.imagegen`, `referenced_image_paths` pour fichiers locaux après visualisation ; ne pas combiner avec `num_last_images_to_include`. Ne pas substituer un SVG ou une illustration CSS au crayonné.
6. Vérifier avec [les critères de fidélité](references/quality-and-delivery.md). Corriger une dérive par une édition ciblée, en repartant de l'original si nécessaire. Une référence générée non validée ne remplace jamais l'original.
7. Livrer la déclinaison et préciser toute extrapolation importante. Pour un asset destiné au projet, conserver le fichier dans le dépôt à l'emplacement du consommateur ou à la destination demandée. Une exploration seule peut rester en aperçu.

## Priorités de fidélité

1. Silhouette, visage, implantation des ailes et antennes.
2. Trait irrégulier de crayon violet foncé, hachures visibles, papier apparent.
3. Couleurs lavande/lilas, ombres prune, touches jaune-vert et joues roses.
4. Pose et expression demandées.

Un rendu plus « propre », plus lisse ou plus 3D est une régression même s'il semble plus fini. Ne pas épaissir les contours façon cartoon. Ne pas grossir le corps jusqu'à en faire une peluche. Garder la finesse et la légèreté du dessin validé.

## Portée

Ce skill autorise la production graphique demandée, pas la publication, le déploiement, la modification des interfaces, la création d'un pet animé ou l'adoption de nouvelles variantes comme canon. Les illustrations restent propres à J-Method : aucun logo Pronote ni personnage Duolingo à reproduire.
