# Native PRONOTE protocol reference

This POC reference and its synthetic fixtures were checked against the installed MIT-licensed `pronotepy` 2.15.7 sources (`clients.py`, `pronoteAPI.py`, `dataClasses.py`). It targets the 2026 authentication flow investigated for this project. It is an unofficial, version-sensitive protocol; fixtures prove byte compatibility offline, not acceptance by every school server.

## Fixture contract

`protocol-vectors.json` contains no real credentials, tokens, school URL or personal information. The QR PIN `1234`, student resources, credentials, session number and cryptographic material are synthetic. Never use these deterministic values in live sessions.

`*Hex` values represent raw bytes in hexadecimal; `*Utf8` values are strings to encode as UTF-8. Explicit uppercase wire values are retained. `serializedDataUtf8` is the exact input to byte comparison tests: do not parse and reserialize it before comparing ciphertext. JSON whitespace, property order and Unicode escaping are not canonical on the wire.

| Section | Checks |
| --- | --- |
| `hashes` | MD5(empty), QR PIN derivation, temporary IV derivation |
| `aesCbcPkcs7` | Empty input, exact-block padding, multiblock UTF-8 |
| `qr` | Encrypted login/token, PIN derivation, mobile URL |
| `initialization` | Initial key, zero request IV, changed response IV, order 1 → 3 |
| `authentication` | Username normalization, uppercase SHA-256, 2026 challenge encryption |
| `afterAuthentication` | Decryption of comma-separated bytes and session key derivation |
| `requests`, `responses` | All four compression/encryption combinations; request order 7 → 9 |
| `parent` | Nested child resources and selected-child signature |
| `htmlParsing`, `dates`, `safety` | Boolean typing, local dates, permitted function identifiers |

Request fixtures also expose intermediate JSON, hexadecimal and deflate values. Deflate encoders can produce different valid byte streams; the supplied streams are the Python zlib level-6 reference. Native decoding must accept them, and encryption comparisons must consume the supplied exact bytes.

## Cryptography and session initialization

Use AES-CBC with PKCS#7 padding and a 16-byte IV. MD5-derived keys are 16 bytes, therefore AES-128, despite older descriptions calling the protocol AES-256. PKCS#7 adds a full padding block for empty or block-aligned plaintext. MD5 and SHA-256 inputs are bytes, not hexadecimal text unless stated below.

Read the mobile HTML `Start({...})` attributes: `a` is the numeric account space, `h` the session, `CrA` enables encryption and `CoA` compression. Parse booleans as booleans; the string `"false"` must not become true through truthiness.

Generate a fresh random 16-byte temporary IV. The initial AES key is MD5(empty bytes), initial IV is 16 zero bytes and initial order is 1. Send `FonctionParametres` with inner `{"data":{"Uuid":"…","identifiantNav":null}}`. `Uuid` is base64 of the temporary bytes, or of their RSA PKCS#1 v1.5 encryption when the server's `http` initialization flag requires it. The fixture covers the non-RSA case. The request and its order use the zero IV. Before decoding its response, change the IV to MD5(raw temporary IV bytes), retaining the initial key. Preserve that IV for authentication and subsequent calls.

## Request and response envelopes

POST to `<pronote-root>/appelfonction/{a}/{h}/{encrypted-order}` with:

```json
{"session":424242,"no":"encrypted-order","id":"PageEmploiDuTemps","dataSec":"encoded-data"}
```

Encrypt the decimal UTF-8 order number with the current key and IV, even when payload encryption is disabled. Use lowercase hex for the order in both URL and envelope. Serialize calls: advance the order by two after each HTTP request; concurrent requests can desynchronize the session. Maintain server cookies through authentication.

The inner payload has optional `Signature` and `data` members. Tab-bound calls use `Signature.onglet`, and must respect the user's authorized tabs from `ParametresUtilisateur`.

Request transformation:

1. JSON → UTF-8.
2. If compressed: lowercase hex of those bytes → ASCII → raw DEFLATE level 6, with no zlib header/checksum.
3. If encrypted: AES-CBC of the previous bytes → uppercase hex in `dataSec`.
4. If compression alone: uppercase hex of deflated bytes. If neither: `dataSec` remains a JSON object.

Response transformation is **asymmetric**: hex-decode/decrypt if encrypted, then raw-inflate if compressed, then parse JSON UTF-8 directly. There is no hexadecimal-text layer after response inflation. The decoded envelope normally contains `dataSec.data` with the requested result. Handle outer `Erreur.G` before decoding: 10 expired session, 22 stale-session object, 25 excessive authentication attempts. Stop retries on 25.

## QR and mobile authentication

The official QR contains `url`, `login`, `jeton`. Derive its key as MD5(UTF-8 four-digit PIN), use a zero IV, hex-decode and decrypt `login` and `jeton`; decode resulting bytes as UTF-8. Normalize a non-mobile page filename by adding `mobile.` and use the mobile login query `fd=1&bydlg=A6ABB224-12DD-4E31-AD3E-8A39A1C2C335&login=true`. Keep the mobile application UUID stable across reconnects.

`Identification` uses `genreConnexion:0`, numeric `genreEspace`, `identifiant`, `pourENT:false`, `enConnexionAuto:false`, `demandeConnexionAuto:false`, `loginTokenSAV:""`, and `uuidAppliMobile`.

| Mode | `demandeConnexionAppliMobile` | `demandeConnexionAppliMobileJeton` | `enConnexionAppliMobile` |
| --- | --- | --- | --- |
| New QR | true | true | false |
| Saved token | false | false | true |

For the non-ENT QR/token route, read `Identification.dataSec.data`: `modeCompLog` lowercases the username, `modeCompMdp` lowercases the password, and optional `alea` defaults to empty. Compute:

```text
passwordHash = uppercaseHex(SHA256(UTF8(alea + normalizedPassword)))
authKey = MD5(UTF8(normalizedUsername + passwordHash))
challengeResponse = lowercaseHex(AES-CBC(authKey, sessionIV, UTF8(challenge)))
```

The 2026 flow **encrypts** the challenge string; older challenge-decryption/alternate-character algorithms do not apply. Send `Authentification` with `connexion:0`, `challenge:challengeResponse`, numeric `espace`. On success, decrypt `cle` with `authKey` and the current IV. Its plaintext is comma-separated decimal byte values, such as `0,1,2,…`; parse into raw bytes, then MD5 those bytes to obtain the session key. Do not hash the comma string or its UTF-8 bytes.

Persist each returned `jetonConnexionAppliMobile` immediately in Keychain/Keystore-backed storage: it rotates the mobile password token. Retrieve `ParametresUtilisateur` separately after successful authentication. A new QR login can require token reauthentication when `PageInfosPerso` tab 49 returns nonzero `securisation.mode`; retain the stable application UUID and use the rotated token.

`actionsDoubleAuth.V` contains JSON-encoded actions. Action 3 requests account PIN verification plus device registration; action 5 requests device registration. Return control to the visible official login/security UI when additional verification is needed. Do not bypass MFA or silently invoke security mutations. Never log credentials, QR payload, PIN, authentication bodies or refreshed tokens.

## Parent mapping and timetable

Parent children are in **`ParametresUtilisateur.dataSec.data.ressource.listeRessources`**, not a top-level `listeRessources`. Select one child explicitly when multiple exist. For child-specific calls retain the selected raw resource in both `data.ressource` and `data.Ressource`, and add `Signature.membre:{N:childID,G:4}` alongside `onglet:16`.

Read `PageEmploiDuTemps` on tab 16. Supply both `NumeroSemaine` and `numeroSemaine`; week 1 begins at `General.PremierLundi.V`. Use school-local calendar days for the day difference divided by seven. Current source flags are `avecAbsencesEleve:false`, `avecConseilDeClasse:true`, `estEDTPermanence:false`, `avecAbsencesRessource:true`, `avecDisponibilites:true`, `avecInfosPrefsGrille:true`.

Read lessons from `dataSec.data.ListeCours`. `DateDuCours.V` and optional `DateDuCoursFin.V` use local date/time text such as `18/11/2026 08:30:00`; date-only values also occur. Do not append a UTC `Z` or shift these school wall times implicitly.

If the end date is absent, use `General.ListeHeuresFin.V`: calculate `endPlace = place % (count - 1) + duree - 1`; if `endPlace > count`, normalize again modulo `(count - 1)`, matching `Util.place2time`. Find the entry whose `G == endPlace`, parse its `L` as `HHhmm`, and replace the start date's hour/minute. Reject missing/degenerate grids clearly rather than inventing a lesson duration.

`ListeContenus.V` entries use `G:16` subject, `G:3` teacher, `G:17` room, `G:2` group. Preserve `estAnnule` cancellation and `Statut` changes. `P` differentiates alternatives occupying the same slot; do not silently duplicate all variants as independent simultaneous lessons.

## Read-only POC boundary

The native HTTPS allowlist is `FonctionParametres`, `Identification`, `Authentification`, `ParametresUtilisateur`, `PageInfosPerso`, `Navigation`, `PageEmploiDuTemps`. POST transport alone does not imply a school-data write. Official UI QR generation is the separately authorized connection step; it does not authorize arbitrary API writes. Reject unknown functions and school mutations such as `SaisieTAFFaitEleve`, `SaisieMessage`, security mutation `SecurisationCompteDoubleAuth`, and direct QR-generation API `JetonAppliMobile`.

Milestone 5 adds `PageCahierDeTexte` as a READ call (tabs 88 and 89 only, `data` limited to `domaine`) and a same-host `GET /FichiersExternes/…` attachment download capped at 20 MB; see `notebook-reference.md`. Any `Saisie*` function, including `SaisieTAFFaitEleve` (mark homework done), stays refused.
