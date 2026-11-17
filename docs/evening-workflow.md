# Aujourd’hui : préparer demain

L’onglet reste nommé Aujourd’hui et son en-tête garde la date réelle. Le programme utilise le lendemain comme jour de référence : le 17 au soir, les révisions prévues jusqu’au 18 sont proposées, avec les retards éventuels. Les cours Pronote affichés sont uniquement ceux du 18, sous « Ta journée de cours de demain ».

Les Jn conservent leurs libellés et leurs dates cibles dans le planning. Leur affichage dans Aujourd’hui est anticipé d’un jour. L’historique enregistre la date réelle de validation. La replanification part du jour préparé : un J0 du 18 fait le 17 au soir à 50/100 conduit au J1 du 19, à préparer le 18 au soir. Un rattrapage déclenché le 17 au soir sera lui aussi à travailler le 18 au soir (date cible le 19).

Les compteurs de navigation, la progression et le feu d’artifice utilisent le même programme du soir. La date est rafraîchie lorsque la page reste ouverte au changement de jour. Le flux de démonstration nouvellement chargé contient les cours du lendemain.

Vérifications : sélection des séances et retards, validation datée du jour réel, espacement J0/J1, rattrapage et changements de mois, d’année et d’heure été/hiver. Un scénario Chrome isolé à 390 px valide aussi le filtrage du calendrier Pronote, la progression et le passage au soir suivant. Compilation TypeScript/Vite vérifiée avec `/jmethod/`.
