# J-Method

POC web d’un assistant de révision pour collégiens et lycéens, basé sur la méthode des J. Objectif à terme : applications iOS et Android.

## Version de test

https://hadrienl.github.io/jmethod/

Chaque push sur `main` déclenche la compilation et la publication via GitHub Actions. Le build Pages utilise la base `/jmethod/`. Les données de chaque testeur restent dans son navigateur.

## Démarrer

```sh
npm ci
npm run setup:pronote
npm run dev
```

Ouvrir http://localhost:5173, puis **Réglages → Connecter Pronote par QR code**. Importe une capture du QR d’ajout de compte généré dans Pronote et saisis le même code temporaire à quatre chiffres. Les 14 prochains jours sont importés dans le planning et les cours de demain.

`npm run dev` lance l’interface et le connecteur Python local (127.0.0.1:8766). `npm run dev:web` lance uniquement l’interface. Le service garde la session Pronote en mémoire pendant huit heures au maximum ; un arrêt du service nécessite une nouvelle connexion. Les créneaux importés restent dans le navigateur. Aucun QR, mot de passe ou jeton n’est sauvegardé dans le projet.

La connexion QR est locale pour ce POC. Elle n’est pas disponible sur GitHub Pages. Les comptes hébergés sur `*.index-education.net` sont pris en charge ; les hébergements personnalisés et l’enregistrement de périphériques de confiance ne le sont pas encore.

```sh
npm run build
npm run preview
```

## Prototype

- Aujourd’hui : révisions du lendemain à préparer ce soir, progression de la soirée et séances regroupées par matière. Les séances antérieures non terminées restent visibles.
- Planning : jour, semaine et mois avec filtres.
- Mes cours : création, détail, historique et suppression.
- Mes progrès : indicateurs de suivi.
- Réglages : intervalles, adaptation, matières et sauvegarde JSON.
- Autoévaluation de 0 à 100 : adaptation des intervalles autour du pivot 50, séance de rattrapage sous le seuil configuré.
- Connexion Pronote par QR dans la version locale, synchronisation de l’emploi du temps en lecture seule et sélection de l’enfant pour les comptes parents.
- Aperçu du cahier de textes réel : contenus publiés, devoirs et pièces jointes téléchargeables, sans création automatique de fiches de révision.
- Emploi du temps iCal du lendemain et jeu de démonstration.

Le premier lancement installe des **données de démonstration**. Les données restent dans le stockage local du navigateur ; aucun compte ou serveur de synchronisation n’est implémenté.

## Direction visuelle

Thème clair violet et citron, textes directs, étapes Découvrir / S’entraîner / Se tester et navigation adaptée aux petits écrans. Voir [les références et choix de design](docs/design-direction.md).

## Limites du POC

- La récupération directe du flux Pronote dépend des règles CORS du serveur distant. Pas de synchronisation automatique en arrière-plan.
- Le parseur iCal ne couvre pas l’intégralité du format (notamment les récurrences et fuseaux nommés).
- Les boutons « Faire le point » ouvrent l’autoévaluation ; pas de quiz ni d’exercices générés.
- Les imports JSON et les cas particuliers de replanification doivent être renforcés avant usage réel.
- Pas encore de PWA installable avec fonctionnement hors ligne, ni de projet mobile natif.
- Audit d’accessibilité complet et tests avec des ados à prévoir.

## Stack

React 19, TypeScript, Vite, Tailwind CSS, date-fns et lucide-react.

## Tests du connecteur

```sh
npm run test:pronote
```

Tests du mode lecture seule, des sessions et des protections du service local. Voir [le connecteur Pronote](docs/pronote-unofficial.md).
