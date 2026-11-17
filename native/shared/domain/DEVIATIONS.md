# Écarts volontaires des apps natives par rapport au web

Règle : les apps natives reproduisent le web à l’identique pour les calculs, l’ordre et les données (algorithme adaptatif, programme du soir, planning, numérotation, import/export). Elles **ne recopient pas les défauts d’affichage évidents** listés ici. iOS et Android appliquent exactement la même liste ; un cas de vecteur touché par un écart est ignoré par son nom, avec un renvoi à la ligne correspondante, et remplacé par un test natif du comportement corrigé. Ces écarts sont à corriger aussi côté web, puis les vecteurs seront régénérés.

| # | Écran | Web | Apps natives |
|---|---|---|---|
| D1 | Fiche de cours | « NaN% » pour un cours sans révision | 0 % |
| D2 | Fiche de cours | « Créé le » affiche la date du cours | « Créé le » = `createdAt` ; la date du cours reste affichée comme « Cours du » |
| D3 | Mes progrès | « Régularité » constante « 5 j » | Série réelle : nombre de jours consécutifs, jusqu’à aujourd’hui (ou hier si rien aujourd’hui), ayant au moins une séance validée (`completedDate`) |
| D4 | Mes progrès | Score moyen 50 sans aucun score | « — » quand il n’y a aucun score |
| D5 | Mes progrès | Note d’une séance non faite comptée | Seules les séances `completed` avec un score comptent |
| D6 | Mes progrès | `score: null` importé compté comme 0 | `null` = pas de score |
| D7 | Tous | Pluriels fautifs (« 1 jours », « 1 matières ») | Accord singulier/pluriel |
| D8 | Création de cours | « 10 échéances » en dur | Nombre réel d’échéances générées |
| D9 | Mes cours | Recherche sans `trim` ni repli des accents | Requête `trim`, comparaison insensible à la casse et aux accents ; « c1 » reste un préfixe (C10–C19 inclus, comme le web) |
| D10 | Planning, détail | Libellés Encodage / Consolidation / Récupération | Découvrir / S’entraîner / Se tester partout |
| D11 | Planning | Titres en minuscules capitalisés mot à mot par CSS (« Lundi 30 Mars ») | Typographie française : « lundi 30 mars » en minuscules, majuscule initiale seulement en début de titre |
| D12 | Fiche de cours | Score absent affiché « 50/100 » | « —/100 » comme le Planning |
| D13 | Import | Aucune validation de forme | Refuser un fichier dont `courses`, `revisions` ou `subjects` ne sont pas des tableaux d’objets du bon type, avec un message clair ; les autres règles d’import (remplacement des réglages, conservation des créneaux Pronote) restent identiques |

Tout le reste des particularités listées dans `README.md` est reproduit tel quel.
