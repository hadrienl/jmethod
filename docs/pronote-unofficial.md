# Expérience Pronote en lecture seule

Connecteur local utilisant [pronotepy](https://github.com/bain3/pronotepy) 2.15.7, dont la version annonce un correctif pour PRONOTE 2026.2.5.7. Il n’est pas officiel. La compatibilité avec un établissement doit être testée ; l’absence d’export iCal ne garantit ni n’empêche à elle seule l’accès à l’emploi du temps via la session utilisateur.

## Périmètre

Authentification QR via le compte autorisé, puis lecture de l’emploi du temps sur 1 à 31 jours (7 par défaut). Aucun appel de modification scolaire n’est autorisé. Les jetons restent en mémoire ; le script ne les exporte pas, n’enregistre pas de périphérique de confiance et ne modifie pas les paramètres de sécurité. Si une étape supplémentaire de sécurisation est requise, il s’arrête.

## Exécution locale

```sh
python3 -m venv /tmp/jmethod-pronote-venv
/tmp/jmethod-pronote-venv/bin/pip install -r scripts/requirements-pronote.txt
mkdir -p .pronote-local
/tmp/jmethod-pronote-venv/bin/python scripts/pronote_readonly.py \
  --qr-json .pronote-local/qr.json \
  --output .pronote-local/timetable.json \
  --days 7
```

`qr.json` contient le JSON décodé localement d’un QR d’ajout de compte généré sur la session Pronote autorisée (`url`, `login`, `jeton`). Le code temporaire est saisi de manière masquée au lancement. Ces fichiers restent privés et sont exclus de Git. Aucun QR ou jeton ne doit être publié dans une issue ou un commit.

L’export ne contient que les créneaux normalisés : matière, date, horaires, salle, enseignant et annulation. Il est aussi branché à l’interface locale J-Method via le service ci-dessous. GitHub Pages reste un hébergement statique et ne peut pas exécuter Python ; une intégration distribuée nécessiterait un service dédié ou une implémentation native côté mobile.

## Vérification

```sh
python3 -m unittest discover -s tests -p 'test_pronote_readonly.py' -v
```

Les tests vérifient le refus des opérations d’écriture, le refus de modification de la sécurité, la normalisation, la conservation des changements de salle et l’absence de secrets dans l’export.

Le 17 novembre 2026, le test sur le serveur de démonstration public d’Index Éducation a réussi : authentification puis lecture de 22 créneaux sur sept jours. Cette vérification ne valide pas à elle seule la connexion QR sur un compte réel.

Le test QR sur le compte réel autorisé a ensuite réussi avec PRONOTE 2026.2.8 : lecture de l’emploi du temps sur sept jours et récupération des statuts d’annulation. Le contenu de cet emploi du temps et les identifiants du compte ne figurent pas dans ce dépôt.

## Connexion depuis J-Method

Après `npm ci` et `npm run setup:pronote`, lancer `npm run dev`. Le lanceur démarre Vite sur localhost:5173 et le compagnon Python sur 127.0.0.1:8766. Vite relaie uniquement `/api/pronote` vers ce service.

Dans Réglages, importer une capture PNG/JPEG du QR d’ajout de compte et saisir le code temporaire défini dans Pronote. Le QR est décodé dans le navigateur ; l’image n’est pas transmise au service. Le JSON du QR et le PIN sont transmis uniquement au service local pour l’authentification, puis effacés du formulaire. Les jetons restent dans le client Python en mémoire. Le navigateur reçoit un cookie de session opaque HttpOnly et SameSite=Strict, jamais les jetons Pronote. Les réponses ne sont pas mises en cache.

Le service limite les requêtes aux instances HTTPS hébergées sous `*.index-education.net`, borne les délais réseau et refuse les origines étrangères. Il n’active pas CORS. Les appels exigent un en-tête applicatif ; les requêtes d’écriture scolaire restent refusées par le client de lecture seule. La session expire après huit heures et disparaît à l’arrêt du service. Les paramètres de sécurité Pronote ne sont pas modifiés.

Une synchronisation charge les 14 prochains jours. Les comptes parents peuvent sélectionner un enfant parmi ceux associés au compte. Déconnecter supprime la session locale et son cookie, sans effacer les créneaux déjà importés du navigateur. Les erreurs de connexion conservent le dernier emploi du temps importé.

Vérifications : six tests Python couvrent les appels refusés, les hôtes et origines autorisés, le cookie, l’isolation des sessions, leur expiration et les échecs réseau. Un scénario Chrome isolé à 390 px couvre le décodage QR, les codes incorrects, la connexion, le choix d’enfant, la synchronisation, l’affichage du lendemain et la déconnexion. Le parcours a également été exécuté avec succès dans l’interface locale sur le compte réel autorisé : connexion QR, import de 48 créneaux sur 14 jours, nouvelle synchronisation et affichage de demain. Aucune donnée personnelle n’est intégrée au dépôt.
