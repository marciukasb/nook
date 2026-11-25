# Nook

Android app for the household's Todoist lists: **Groceries**, **Shopping**, **Tasks**, **Achievements** — four tabs, nothing else.
Todoist stores everything; Nook is only a nicer front end. Design: `docs/superpowers/specs/2026-11-21-nook-design.md`.

## Build (Mac)

```sh
. scripts/env.sh                 # JDK bundled with Android Studio + Android SDK
./gradlew testDebugUnitTest      # all tests (JVM + Robolectric, no device needed)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

## Signing key

`scripts/make-signing-key.sh` creates `~/.nook/release.jks` + `~/.nook/keystore.properties` (never in this repo).
**Back up both files** (password manager). Android only installs updates signed with the same key — losing it
means uninstalling and reinstalling Nook on both phones.

## Token

Each person pastes their own Todoist API token (Todoist → Settings → Integrations → Developer) into Nook. It is
stored encrypted on the phone only.
