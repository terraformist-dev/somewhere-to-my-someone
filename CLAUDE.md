# Somewhere to my Someone: notes for Claude

A compass that points to one paired person's phone and says how far away they are, with a red
heart as the marker. When the two phones are right next to each other, two hearts circle in and
merge; when they part, the heart splits. Three places: home-screen widget (2×2, 4×2), full-screen
app, and a Glyph Toy on the Phone (3)'s Glyph Matrix.

Owner has no coding experience and works only from a phone. Explain changes in plain English,
never ask them to run commands, and always finish by pushing to `main` and confirming the
GitHub Actions build went green (the build publishes `somewhere-to-my-someone.apk` as the latest
release). Sister projects with the same setup: `terraformist-dev/somewhere-to-nothing` (the
compass this was built from) and `terraformist-dev/glyph-pet`.

## Project

Android app (Kotlin, no AndroidX, no Compose, no Google libraries), package
`dev.terraformist.someone`, minSdk 34. Builds only in GitHub Actions; the sandbox cannot reach
Google's Maven or Gradle servers. If a build fails, the workflow lists the compile errors as
annotations on the run (readable from the public check-runs API).

The debug build is signed with `app/someone.keystore` (password `someone123`, committed on
purpose) so updates install over the old app. Don't change the key or applicationId.

Both phones are Android (the owner's someone has Android too); no iPhone version.

## Firebase (owner's own project, free plan)

- REST only, no SDK: anonymous sign-in via Identity Toolkit (`accounts:signUp`, refresh via
  `securetoken`), Realtime Database via `<db>/<path>.json?auth=<idToken>`. PATCH is sent as
  POST + `X-HTTP-Method-Override: PATCH`.
- `FirebaseSetup.kt` holds `API_KEY` and `DATABASE_URL`. Empty means the app shows "NOT SET UP".
- `firebase-rules.json` must be pasted into the console's Rules tab. Data:
  `codes/<6 digits>` = `{p: pairId, o: ownerUid, x: expiresMs}` (10 min);
  `pairs/<pairId>` = `{a, ka, b, kb, loc: {<uid>: {c: ciphertext, t: server time}}}`.
  `b` is write-once, so a pair can never have a third member. Either member can delete the pair
  (unpair); the other phone notices the pair is gone and forgets it too.

## Crypto (`Crypto.kt`)

ECDH P-256 key pairs swapped via `ka`/`kb`; HKDF-SHA256 (salt = pairId) gives the shared secret.
Locations: AES-GCM with an HMAC-derived subkey. Message JSON: `t` (send time), `tg` (together),
`bt` (can do Bluetooth), and either `la/lo/ac` or `p: 1` (paused/off; no position is ever sent then).
Bluetooth token: first 8 bytes of HMAC(subkey, "role|window"), window = 15 min.
`allowBackup="false"` so the secret isn't backed up.

## Sharing service (`SharingService.kt`)

Foreground service, type location (+ connectedDevice when Bluetooth is allowed), with a
notification (Pause for 1 hour / Share again). Started from the app (while-in-use location is
enough), from boot/update only with background location. Sync interval: 5 s app open,
30 s under 1 km, 2 min under 5 km or together, else 5 min. Bluetooth (`Near.kt`) advertises
and scans only under 500 m or while together. `TogetherJudge`: together after RSSI ≥ −72 dBm
(smoothed) for 8 s; apart after nothing ≥ −88 dBm for 60 s. If either phone can't do Bluetooth,
falls back on GPS (≤ 20 m together, > 60 m apart). Merge triggers a heartbeat vibration and the
widget flip-book.

## Design (agreed with the owner from a mockup canvas)

- Nothing look: black, `#1A1A1A` cards, white dots, ghost dots, red `#D71921` only for the hearts.
  Labels in Space Mono.
- Numbers as dots (`DotFont.LARGE` 5×7, `DotFont.SMALL` 3×5). **The decimal point is one dot.
  The comma is three dots swooping down to the left.** The owner asked for exactly this.
- Heart shape: the cubic path in `Art.HEART` (same as the mockup). Compass marker = heart at the
  top of the ring. Merge (`Merge`, `Art.hearts`): 3 s; two hearts orbit 1.5 turns while closing in
  (0–80 %), then a ring of red dots bursts and one bigger heart settles and beats (1.6 s heartbeat).
  Parting plays it backwards over 2 s.
- Widget: north up; flip-book of 15 frames for merge/part (widgets can't animate smoothly).
- Glyph: white only. Small 5×5 dot heart at the edge pointing to them (pulse speeds up when
  close, needle within 2 km); together = 9×8 heart in the middle; merge animation in dots.
- Names are typed by each person on their own phone ("what do you call them?"), stored locally only.
