# Aide de connexion (« En savoir plus »)

Texte de référence du panneau ouvert depuis le formulaire de connexion, repris à l'identique par `apps/ios/JMethod/UI/SignIn/SignInHelpSheet.swift` et `apps/android/.../ui/pronote/SignInHelpSheet.kt`.

Sous le titre du formulaire : « Utilise ton identifiant et ton mot de passe Pronote, pas ceux d'ÉduConnect. », puis le bouton « En savoir plus ».

## Se connecter à Pronote

### Le code de l'établissement

Si tu passes d'habitude par l'ENT pour ouvrir Pronote, voici comment trouver le code :

1. Connecte-toi à l'ENT comme d'habitude, depuis un navigateur.
2. Ouvre Pronote depuis l'ENT.
3. Regarde l'adresse de la page Pronote. Sur téléphone, touche la barre d'adresse pour l'afficher en entier. Elle ressemble à : `https://abcd1234.index-education.net/pronote/eleve.html`
4. Le code est la partie avant « .index-education.net », ici abcd1234. Tu peux aussi coller l'adresse entière.

### Quel identifiant et quel mot de passe ?

Il faut l'identifiant et le mot de passe Pronote, pas ceux d'ÉduConnect ni de l'ENT. L'app se connecte directement à Pronote, sans passer par l'ENT.

L'établissement les donne en général à la rentrée. Si tu ne les as pas ou plus, demande-les à la vie scolaire.

Pour les essayer dans un navigateur, ouvre l'adresse de Pronote en remplaçant la fin par « eleve.html?login=true » (ou « parent.html?login=true ») : la page de connexion de Pronote s'affiche sans passer par l'ENT.

### Élève ou Parent

Choisis Élève pour le compte de l'élève, Parent pour celui d'un responsable. Chaque compte a ses propres identifiants.

Bouton : « J'ai compris ».

Refus de Pronote (`LOGIN_REJECTED`) : « Pronote a refusé l'identifiant ou le mot de passe. Utilise ceux de Pronote, pas ceux d'ÉduConnect, puis réessaie. »
