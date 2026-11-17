# J-Method

POC web d’un assistant de révision pour collégiens et lycéens, basé sur la méthode des J. Objectif à terme : applications iOS et Android.

## Version de test

https://hadrienl.github.io/jmethod/

Chaque push sur `main` déclenche la compilation et la publication via GitHub Actions. Le build Pages utilise la base `/jmethod/`. Les données de chaque testeur restent dans son navigateur.

## Démarrer

```sh
npm ci
npm run dev
```

Ouvrir l’adresse affichée par Vite, généralement http://localhost:5173.

```sh
npm run build
npm run preview
```

## Prototype

- Aujourd’hui : prochaine séance, progression du jour et séances regroupées par matière.
- Planning : jour, semaine et mois avec filtres.
- Mes cours : création, détail, historique et suppression.
- Mes progrès : indicateurs de suivi.
- Réglages : intervalles, adaptation, matières et sauvegarde JSON.
- Autoévaluation de 0 à 100 : adaptation des intervalles autour du pivot 50, séance de rattrapage sous le seuil configuré.
- Import d’emploi du temps iCal et jeu de démonstration.

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
