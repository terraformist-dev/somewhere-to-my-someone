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

Foreground service, type location (+ connectedDevice when Bluetooth is allowed). Started from
the app (while-in-use location is enough), from boot/update only with background location.
Sync interval: 5 s app open, 30 s under 1 km, 2 min under 5 km or together, else 5 min.

**Notifications (owner's request after testing):** exactly one visible notification when sharing
starts (`announce()`, channel `news`, shown again only if sharing is switched off and on). The
foreground-service notification is on channel `sharing_quiet` (IMPORTANCE_MIN: silent, no status
bar icon), posted once by `startForeground` (never re-posted on later `start()` calls) and only
re-notified when sending/paused/off changes. Don't add per-minute or "together" text to it.

**Together** (`Near.kt`): Bluetooth advertises/scans under 500 m or while together; FAST
(low-latency) while the app is open, BALANCED otherwise, low-power scan while together.
`TogetherJudge` uses the median RSSI of recent readings: together if ≥ 2 readings in 5 s with
median ≥ −67 dBm, or any one ≥ −55; apart if median < −80 for 15 s or nothing heard for 25 s.
A 3 s `judgeTick` re-evaluates while Bluetooth is on (before this, parting waited for the 2-min sync).
Without Bluetooth: GPS ≤ 15 m together, > 40 m apart. Thresholds are first guesses; the settings
screen shows live diagnostics (RSSI, both GPS accuracies, GPS distance) for tuning from real use.
Merge triggers a heartbeat vibration and the widget flip-book.

**NEAR** (`Reading.near`): indoors both phones often get the same Wi-Fi position, so distance and
bearing under the combined GPS accuracy (hypot of both, min 20 m) are noise. Shown as "NEAR /
WITHIN ~N M" with no arrow (heart beating in the middle of the dial; Glyph: small heart in the
middle). `Where.offer` keeps a rough fix from overwriting a recent good one. The compass shows
"COMPASS UNSURE: WAVE PHONE IN A FIGURE OF 8" when the rotation sensor reports low accuracy.

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
