# Movie Selector -- Android

Native Kotlin + Jetpack Compose client of the same backend API the web client
uses (see `../backend`). Package: `com.luisbenedikt.movieselector`.

## Building

Requires an Android SDK (`local.properties` with `sdk.dir=...`, or `ANDROID_HOME`).

```
./gradlew :android:assembleDebug                       # debug APK
./gradlew :android:assembleRelease :android:bundleRelease  # release APK + AAB
```

Artifacts land at:
- `android/build/outputs/apk/debug/android-debug.apk`
- `android/build/outputs/apk/release/android-release-unsigned.apk` (unsigned -- no
  production keystore exists in this repo; add a real signing config before any
  Play Store distribution)
- `android/build/outputs/bundle/release/android-release.aab`

## Pointing at a backend

The default (`https://game.luisbenedikt.de/play/movie-selector/api`) is production. The app
forbids cleartext HTTP in every build, so `-PapiBaseUrl=` must be an `https://` URL.

## What's covered

Portrait-locked. Provider/runtime filters, drag-to-swipe with a haptic tick on accept, button
and keyboard-equivalent (D-pad/back) alternatives, undo, reject-all with
reshuffle/bring-back-5, result screen, loading/error states, and Android back
behavior (ends the session and returns to filters instead of exiting).

## Verification

Unit tests: `./gradlew :android:testDebugUnitTest` (GameState, API contract via Ktor MockEngine,
security/config guards). Acceptance was also run on an API 34 x86_64 emulator (1080x2340, 420dpi)
against the production backend.
