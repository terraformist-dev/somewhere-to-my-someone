# Somewhere to my Someone

A compass for the Nothing Phone (3) that points to one person's phone (your someone) and
tells you how far away they are. A little red heart on the compass marks where they are.
When you're right next to each other, two hearts circle in and merge into one bigger heart.
When you part, it splits back into two.

It lives in:

- **Home-screen widget**, in two sizes. 2×2: direction and distance. 4×2: adds their name and
  how fresh the position is. On the widget, the top is north, like a map.
- **The app**: a full-screen compass whose arrow turns as you turn, with a button for walking
  directions. Tap the title at the top for settings: pause, share on/off, rename, unpair.
- **Glyph Toy**: on the Glyph Matrix on the back. A little heart on the edge points to them
  and beats faster the closer they are. When you're together, it's one bigger heart in the middle.
  Long-press the Glyph Button to see the distance in numbers.

Both phones need the app. It works on any Android phone (Android 14 or newer); the Glyph Toy
only on a Nothing Phone (3).

## Only one person

You pair with exactly one person using a six-digit code. Both of you have to agree: one shows
the code, the other types it in. Nobody else can join that pair. To pair with someone else,
you'd unpair first, which stops sharing for both of you.

## Privacy

- Your location is scrambled on your phone before it's sent. Only your someone's phone can
  unscramble it. The server in the middle (your own Firebase project) only holds gibberish.
- You get one notification when you start sharing. Android also needs a silent one while sharing
  runs in the background; it's tucked away at the bottom of the notification shade.
- Either of you can pause for an hour, switch sharing off, or unpair at any time.
  The other person sees that you've paused.

## How "together" works

GPS is only accurate to 5–20 metres outdoors, and indoors phones often fall back on Wi-Fi, which
can put two people in the same house on exactly the same spot. So when you're closer than the
positions can tell apart, the app says **NEAR** (with roughly how near) instead of showing a
distance and an arrow that would just be guesses.

When you're within 500 m, both phones switch on a private Bluetooth signal that only the other
recognises. A strong signal (about arm's length) for a couple of seconds means you're together.
The hearts split when the signal has been weak (another room) for 15 seconds, or gone for 25.

The settings screen (tap the title) shows the live Bluetooth signal and how accurate each GPS
position is, which helps fine-tune this.

## Getting the app onto your phone

1. On your phone, open **Releases → Latest** on this repo's GitHub page.
2. Tap **somewhere-to-my-someone.apk** to download it.
3. Open the download and tap **Install** (allow your browser to install apps the first time).

Then:

- Open **Somewhere to my Someone**. Allow location, Bluetooth ("Nearby devices") and notifications.
- One of you taps **Share my code**; the other taps **Enter their code** and types it.
- Add the widget: long-press the home screen → **Widgets** → **Somewhere to my Someone**.
- Pick the Glyph Toy: **Settings → Glyph Interface → Glyph Toys**.
- In the app's settings (tap the title), allow **Run in the background** so the battery saver
  doesn't stop it, and optionally **Start again after a restart**.

## Firebase

The two phones pass their scrambled locations through the owner's own Firebase project
(free plan). Its address and key are in `FirebaseSetup.kt`, and the rules that lock it down are
in `firebase-rules.json` (pasted into Firebase → Realtime Database → Rules).

## How it works

| File | What it does |
|---|---|
| `Pairing.kt` | Making and typing the six-digit code; unpairing. |
| `Crypto.kt` | The scrambling, and the rotating Bluetooth signal. |
| `Cloud.kt` / `FirebaseSetup.kt` | Talking to Firebase. |
| `Sync.kt` | Sending your position, fetching theirs. |
| `SharingService.kt` | Keeps it all going while the app is closed (with the notification). |
| `Near.kt` | The Bluetooth "are we together?" check. |
| `Us.kt` | What the phone remembers: the pairing, their last position, together or apart. |
| `Where.kt` / `Heading.kt` / `Geo.kt` | Where you are, which way you're facing, distance and direction. |
| `Dots.kt` | The dot-matrix numbers (single-dot decimal point, three-dot comma) and letters. |
| `Art.kt` | Draws the compass, hearts, numbers and labels, for the widget and the app. |
| `SomeoneWidget.kt` | The home-screen widgets. |
| `SomeoneActivity.kt` / `CompassView.kt` / `Views.kt` | The app: pairing, compass, settings. |
| `MatrixArt.kt` / `HeartToyService.kt` | The Glyph Toy. |

The Glyph Matrix SDK (`app/libs/glyph-matrix-sdk-2.0.aar`) is from
[Nothing-Developer-Programme/GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit) (MIT).
Space Mono is by Colophon Foundry, under the SIL Open Font Licence (`app/src/main/assets/SpaceMono-OFL.txt`).
This is a fan project and isn't made or endorsed by Nothing.
