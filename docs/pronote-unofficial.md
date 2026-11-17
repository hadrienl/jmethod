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

L’export ne contient que les créneaux normalisés : matière, date, horaires, salle, enseignant et annulation. Il n’est pas encore branché à l’interface J-Method. GitHub Pages reste un hébergement statique et ne peut pas exécuter Python ; une intégration distribuée nécessiterait un service dédié ou une implémentation native côté mobile.

## Vérification

```sh
python3 -m unittest discover -s tests -p 'test_pronote_readonly.py' -v
```

Les tests vérifient le refus des opérations d’écriture, le refus de modification de la sécurité, la normalisation, la conservation des changements de salle et l’absence de secrets dans l’export.

Le 17 novembre 2026, le test sur le serveur de démonstration public d’Index Éducation a réussi : authentification puis lecture de 22 créneaux sur sept jours. Cette vérification ne valide pas à elle seule la connexion QR sur un compte réel.

Le test QR sur le compte réel autorisé a ensuite réussi avec PRONOTE 2026.2.8 : lecture de l’emploi du temps sur sept jours et récupération des statuts d’annulation. Le contenu de cet emploi du temps et les identifiants du compte ne figurent pas dans ce dépôt.
