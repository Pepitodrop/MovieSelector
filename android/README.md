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
- `android/build/outputs/apk/release/android-release.apk` (signed) or
  `android-release-unsigned.apk` when no signing config is available
- `android/build/outputs/bundle/release/android-release.aab`

## Release signing

The release key is generated and kept locally, never committed. The build reads it from
environment variables (`MOVIE_SELECTOR_KEYSTORE`, `MOVIE_SELECTOR_KEYSTORE_PASSWORD`,
`MOVIE_SELECTOR_KEY_ALIAS`, `MOVIE_SELECTOR_KEY_PASSWORD`) or from
`~/.config/movie-selector/signing/signing.properties` (override the path with
`MOVIE_SELECTOR_SIGNING_PROPERTIES`), a file with `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`. If neither exists the release build is simply unsigned.

Back up the `.jks` and the properties file: losing the key means you can never ship an update
that installs over an existing release build.

Create a key (PKCS12 uses one password for store and key):

```
keytool -genkeypair -keystore movie-selector-release.jks -alias movie-selector-release \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Movie Selector, O=..., C=..."
```

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
