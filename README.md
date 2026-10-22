# Simple Gym

A small Android app for lifters who train at a busy shared gym. Walk in, pick today's workout, pick an exercise, and the app tells you what weight to lift for how many reps. It learns from what you actually lift and keeps you progressing without overtraining. It replaces a spreadsheet log plus a separate interval-timer app.

Everything stays on the phone. The app has no internet permission, no accounts, and sends no reminders: the only notification is the running timer.

## Features

- **Tap rule.** From opening the app to a running timer is at most 2 taps (start the suggested day, then start the timer on the highlighted exercise). A swap, starting the timer and jumping to any exercise are 1 tap each. There are no modal dialogs except on first launch and for destructive confirmations (delete, restore). `TapBudgetTest` pins this.
- **Per-variant working weights.** Every variant (bench press, chest press machine, dumbbell press, and so on) has its own remembered working weight, so swapping when equipment is busy is a normal part of a session.
- **Coaching engine.** Progression, plateau detection, build and deload rounds, age-aware rules, and a return-after-layoff mode. The engine is pure and deterministic (`engine` module): it takes the date as a parameter and never reads the clock.
- **Stiffness check-in.** Tap the muscles that feel stiff and Home suggests a day that avoids them. A stiff muscle can still be trained with a held target or a flush session, as an inline toggle rather than a dialog.
- **Hurt flags and lower-back protection.** A flagged joint shows an inline banner with a joint-friendly swap. With lower-back protection on, high-load exercises are hidden from the swap chips.
- **EMOM timer.** A ring countdown with adjustable rounds and round time, beeps and vibration, pause/resume/stop. It runs as a foreground service with the screen off and recovers its position from the system clock if the process is killed.
- **Log, progress and plans.** Quick logging with feel and an optional hurt flag, per-variant progress charts, an editor for days, slots, swaps and custom exercises, and a core/mobility add-on with its own interval timer.
- **Clubs.** Mark exercises as unavailable at a gym; they drop out of the chips there.
- **Backup and restore.** Save a full backup (`.gymbackup`) through the system file picker, which includes Google Drive, and restore it later.

## Build

Requirements:

- JDK 21
- Android SDK with API 37 (`ANDROID_HOME`, or `sdk.dir` in a git-ignored `local.properties`)

```
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

Tests (the pure engine module, then the app with Robolectric):

```
./gradlew :engine:test :app:testDebugUnitTest
```

### Signing

Every APK that goes on a phone, debug included, must be signed with the same key: Android refuses to update an app whose signature changed, and uninstalling it wipes its data.

The key is configured in a git-ignored `keystore.properties` in the repo root, a Java properties file with four entries: `storeFile` (the path to the keystore), `storePassword`, `keyAlias` and `keyPassword`.

- With `keystore.properties` present, both the debug and release builds are signed with that key. If the file exists but is missing one of the four values, the build fails rather than silently falling back to another key.
- Without it (for example on a fresh clone) the build still works: the debug build uses the default Android debug key, and the release build has no signing configuration, so `assembleRelease` produces an unsigned APK that has to be signed before it can be installed.

Keystores (`*.jks`, `*.keystore`), `keystore.properties` and `local.properties` are git-ignored and must never be committed.

## Personal data

The repo holds no personal data. Age, clubs and current working weights live only on the phone. At first launch the welcome screen offers **Import starting data**, which reads a versioned JSON file chosen through the system file picker (Google Drive appears there), or **Set up manually**. A fictional example of the format is in [`docs/examples/starting-data.example.json`](docs/examples/starting-data.example.json). Keep a real file outside the repo, or in the git-ignored `personal/` folder.

## Scripts

All in `scripts/`:

- `check-before-push.sh` scans the tracked files, and the commits about to be pushed, for key material, signing passwords, cloud tokens and tracked keystore or personal files. It also looks for personal markers listed one per line in the git-ignored `personal/markers.txt` (set `SKIP_MARKER_CHECK=1` to skip that part deliberately). Exit status 0 means clean.
- `install-hooks.sh` installs that check as the repo's `pre-push` hook.
- `test-check-before-push.sh` is the self-test for the check; it runs in throwaway clones and never touches the real repo.
- `check-latest-versions.py` checks that every pinned dependency, plugin and the Gradle wrapper is the newest stable release (needs network access): `python -I scripts/check-latest-versions.py`. Exit status 0 means everything is current.

## Docs

The design spec is in `docs/specs/` and the implementation plan in `docs/plans/`.
