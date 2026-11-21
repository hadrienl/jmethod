# Génération et édition avec référence

## Préparer les entrées

1. Ouvrir `assets/approved-style-board.png` depuis le dossier du skill ; ne pas dépendre d'un chemin utilisateur ou d'un ancien historique de conversation.
2. Indiquer au moteur que cette planche est une **référence d'identité et de style**, et que seule la deuxième colonne est sélectionnée.
3. Si une image existante est à modifier, distinguer explicitement **cible d'édition** et **référence d'identité**. Visualiser aussi la cible avant édition.
4. Réserver une éventuelle troisième entrée au croquis de pose ou au décor demandé. Le croquis ne fournit pas le style du personnage.

Avec `image_gen.imagegen`, les fichiers locaux passent par `referenced_image_paths` après visualisation. Une image récente sans chemin local peut passer par `num_last_images_to_include` ; ne pas mélanger ces deux mécanismes. Pour un skill transporté dans une autre installation, résoudre les chemins relativement au dossier courant du skill.

## Prompt maître réutilisable

Remplacer les champs entre accolades avant l'envoi. Ne pas envoyer les variantes géométrique/cartoon/3D comme choix à explorer : le choix est fait.

```text
Use case: identity-preserving mascot illustration.
Reference image: the attached J-Method butterfly style board. ONLY the SECOND
COLUMN labelled “2 · Dessiné à la main” is the approved character. The large
full-body drawing and its small smiling face below are identity/style references.
Ignore the other three columns completely. Do not reproduce the board or its text.

Draw THE SAME J-Method butterfly, not a redesign or another butterfly.
Action and pose: {specific action, arms/legs, body orientation, wing openness}.
Camera and framing: {view, full body or crop, placement, breathing room}.
Expression and gaze: {emotion, intensity, gaze target}.

Identity invariants: light round-oval lavender head, large dark plum expressive
eyes with white highlights, delicate outer eye accents, faint pink pencil cheeks,
tiny expressive mouth; very slender elongated lavender-purple torso; two fine
arms with small stylized hands and two tapered legs; two long curved fine purple
antennae with small elongated lime-yellow tips. Four wings attached behind the
upper torso: large elongated rounded upper pair, smaller tapered lower pair.
Pale lilac paper-visible wings with purple hatching and flowing lime-yellow marks.
Allow correct occlusion for the specified viewpoint; do not force all wings visible.

Medium: genuine-looking COLORED PENCIL drawing, thin irregular dark-violet
sketched contours, visible directional hatching, soft pressure variation,
imperfect translucent fills, fine paper grain. Preserve the original airy,
slender, handmade character. Do not clean it into vector art or thicken outlines.
Keep the same lavender/lilac/plum/lime palette and the approved facial proportions.

Background: {transparent or specified background}.
Output: {one illustration / explicitly requested pose layout and aspect ratio}.
No text, no labels, no watermark, no added logo, clothing or props unless requested.
Avoid: 3D/clay/plush, glossy lighting, smooth airbrush gradients, thick black
cartoon strokes, flat geometric fills, photoreal insect anatomy, extra limbs,
extra antennae or wings, substituted face, baby proportions, unrelated mascots.
The attached approved second-column drawing takes priority over prose if ambiguous.
```

Les formulations en anglais servent la portabilité du prompt ; communiquer avec l'utilisateur dans sa langue.

## Exemple : encourager vers un bouton à droite

Ajouter au prompt maître :

> Full-body three-quarter butterfly hovering on the left half of a wide canvas,
> head and gaze turned toward image-right. One small hand gently points toward
> the empty right side, other hand relaxed near torso. Wings half-open, legs
> lightly trailing. Warm confident closed-mouth smile, not a victory pose.
> Keep at least 10% clearance around antennae and wings. No button or UI drawn.

## Exemple : fin du programme

> The same full-body pencil butterfly makes a small upward celebratory hop,
> arms open, upper wings spread, lower wings visible where not occluded.
> Eyes closed in happy arcs like the approved smiling expression, open pink smile.
> A few short hand-drawn lime/purple joy marks, no confetti cloud or lettering.

## Exemple : repos

> Same three-quarter pencil butterfly hovering calmly, slightly lowered relaxed
> arms, softly curved antennae and half-folded wings. Small gentle smile and relaxed
> eyelids. Keep the same slim body and head proportions, no pillow, bed or clothes.

## Nouvelle orientation non documentée

> This is a proposed extrapolation of the approved character into a rear
> three-quarter view, not an already approved turnaround. Preserve wing roots,
> upper/lower wing size relationship, hatching and antenna-tip shape. Head turns
> just enough to reveal one eye naturally; no face drawn on the back of the head.

Signaler cette extrapolation dans la livraison et attendre sa validation avant de la considérer comme référence de dos.

## Retouches ciblées

Pour changer uniquement la pose d'une image : conserver explicitement visage, motifs d'ailes, palette et technique ; modifier les articulations demandées. Pour changer uniquement l'émotion : conserver pose et silhouette. Pour changer le fond : conserver tous les contours crayonnés, notamment les traits fins et les réserves de papier.

Si une génération a dérivé, une correction utile nomme la différence observable :

> Keep this pose. Restore the approved second-column butterfly's slim torso,
> longer curved antennae and thin violet pencil contour. Remove the thick black
> outline and plastic shading. Preserve the pale paper-visible wing hatching.

Ne pas empiler des négations générales sans relire la référence. Après deux corrections sans amélioration sur la même dérive, repartir de l'original et d'un prompt plus simple. Si elle persiste, livrer le résultat comme étude imparfaite en précisant le défaut ; ne pas prétendre à une identité parfaite et ne pas relancer indéfiniment.

## Fond et livraison

- Si l'utilisateur demande un détourage/asset sans fond, demander un vrai canal alpha (`transparent_background: true` dans l'outil disponible). « Fond blanc » et damier dessiné ne sont pas transparents.
- Préserver les blancs internes du dessin et la texture des ailes ; ne pas utiliser une suppression automatique de tous les pixels blancs.
- Pour une illustration sur fond papier, préciser que le grain du support appartient au fond et que les hachures appartiennent au personnage.
- Définir les dimensions selon l'emplacement réel ; ne pas inventer une résolution technique si le moteur ne la supporte pas. Éviter de promettre un export vectoriel natif d'une image raster.
- Les noms d'assets peuvent décrire pose, vue et version : `butterfly-pencil-wave-three-quarter-v1.png`. Le nom de fichier n'officialise aucun nom de mascotte.
