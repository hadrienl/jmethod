# J-Method native connection POC contract

Two standalone apps: SwiftUI in `native/ios`, Kotlin/Compose in `native/android`. No Capacitor, JavaScript app shell, Python server, public proxy, or cloud credential storage. A small JS automation asset operates the official login WebView only after the user has signed in. API transport, cryptography, QR decoding, UI and secure storage are native.

## User flow

1. User supplies the school address; a bare host (`xxxx.index-education.net`) is normalised to `https://xxxx.index-education.net/pronote/eleve.html` (`mobile.` prefix, query and fragment are dropped). Two entry points:
   - **Native form (default)**: the user types identifier and password in a native form. The official WebView loads hidden; the credentials are passed once, in `__JM_CONFIG.credentials = {username, password}`, at the first injection on a trusted page, then erased from native memory and from the page config. The script fills and submits the official form exactly once, never reads existing field values and never sends credentials back over the bridge. The mobile token is kept in Keychain/Keystore. When the user keeps "Rester connecté" on (default), the school address, account type, identifier and password are also stored there (iOS `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud sync; Android Keystore AES-GCM key, excluded from backup) so a refused token can be replaced by a silent foreground re-login. Rules: at most one silent attempt per refused token; `LOGIN_REJECTED` deletes the stored password and shows the sign-in screen; MFA/ENT reveal the page as usual; disconnect deletes the token, the selected child, web data and imported slots but keeps the stored identifiers for prefill and disables automatic reconnection (user decision, 2026-11-17); « Se déconnecter et oublier mes identifiants » deletes identifier and password. A stored password is prefilled masked and can never be revealed (the reveal control only applies to a password the user types). `LOGIN_REJECTED` is only sent when Pronote shows a rejection message; a slow answer is `LOGIN_STALLED`. The mobile application UUID is generated once and kept stable across QR logins. Error 25 (too many attempts) stops every automatic retry for at least one hour. Credentials never leave the device except to the official Pronote page.
   - **Official page**: the WebView is visible and the user signs in by hand (ENT/CAS, unusual forms).
   ENT redirects (untrusted origin after load) and MFA reveal the WebView; credentials are never typed outside the trusted Pronote origin.
2. Native generates a random four-digit ephemeral PIN and a random nonce (>=16 characters) per attempt. In the official WebView inject `window.__JM_CONFIG = {nonce, pin, allowedOrigin, demo:false}` then the bundled `PronoteAutomation.js` after main-frame navigation completes. Do not inject into external ENT pages. Reinject after trusted full navigations; SPA changes are watched by the script.
3. When the authenticated app QR button appears, the script emits `authenticated`. Native may hide WebView without destroying it, preserving non-zero layout size and JS execution. Display progress and a way to show it again.
4. Script clicks the QR button once, fills only the temporary verification code, clicks generate once, reads the generated image. It never touches normal credentials or school data writes.
5. Receive `qr` event, decode image natively, decrypt QR fields with PIN, authenticate through direct native HTTPS and fetch seven days of timetable. Save rotated token only in native secure storage. Do not log tokens, PINs, QR data, cookies, challenge bodies, or personal timetable records.
6. On timeout, cancellation or MFA, reveal login WebView and give actionable recovery. No invisible retry loops. QR processing must be claimed atomically once; preserve user cancellation across await/async boundaries.

## Bridge

- iOS handler name `jmethodPronote`: `window.webkit.messageHandlers.jmethodPronote.postMessage(message)`.
- Android interface name `JMethodNative`: `window.JMethodNative.postMessage(JSON.stringify(message))`.
- Native must validate exact current WebView origin/path, nonce, version and payload shape/size before handling. Bridge visible to other domains must not trigger actions.

Event shape:

```ts
{ version: 1, nonce: string, type: 'authenticated' | 'progress' | 'qr' | 'error',
  origin: string, url: string, stage?: string, image?: string, pin?: string, code?: string }
```

`authenticated` is sent once after the actual app QR button is found. `progress` stages: `filling-login`, `submitting-login`, `mfa-done`, `opening-qr`, `filling-code`, `generating-qr`. `error.code`: `LOGIN_REJECTED` (keep the WebView hidden, hand back to the native form), `LOGIN_STALLED`, `LOGIN_FORM_NOT_FOUND`, `MFA_REQUIRED` (non-terminal: reveal the WebView and keep the attempt alive; the script keeps watching and sends `progress` `mfa-done` then `authenticated` once the user has completed the check, so native hides the page again and continues), `LOGIN_TIMEOUT`, `QR_TIMEOUT`, `QR_TOO_LARGE`, `AUTOMATION_FAILED`. `qr` includes PNG data URL (max 2 MB) and PIN matching the native ephemeral PIN. `error.code` is a fixed safe code, never a page error message. Native does not log the full event. Config/bridge state dies with the flow. On received QR, run `window.__JM_PRONOTE_POC?.stop()` to stop script; close/hide lifecycle is native owned.

The JS asset watches the DOM on 500 ms intervals and stops within 35 seconds after detecting login. A ten-minute pre-login ceiling prevents forgotten automation (90 s natively when the native form is used); the form must appear within 30 s and Pronote must answer within 25 s after submission. Config `demo:true` allows filling only the public demo credentials, only on `https://demo.index-education.net`; this must be initiated explicitly by the public-demo button/deep link.

## Shared models

- QRData: `url`, `login`, `jeton`.
- Credentials: `url`, `username`, `password` (mobile token), `uuid`, `clientIdentifier` optional.
- Lesson: `id`, `subject`, `start`, `end`, `teacher`, `room`, `cancelled`.
- States: idle, opening, login, qr, authenticating, syncing, ready, manual, error. Diagnostics contain stages only.
- app id/bundle id `fr.jmethod.connectionpoc`. French UI, no emoji. Public-demo deep link `jmethodpoc://demo` can start ONLY the public demo flow.

## Verification

Use synthetic shared protocol vectors for byte-level interoperability. Public Index Education demo can validate native transport and QR automation, but its reusable tokens may not work after restart (demo server behavior). Actual school login requires the user to sign in on each native device and is not implied by successful local Python/web tests. Do not commit real account-derived diagnostics, QR images, tokens, exported timetable, private host defaults, signing profiles, local Gradle properties or device identifiers.
