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

Production builds default to `https://game.luisbenedikt.de/play/movie-selector/api`
(same origin as the web client, via the GamePage launcher proxy). For local
testing against a backend on your host machine from the emulator:

```
./gradlew :android:assembleDebug -PapiBaseUrl=http://10.0.2.2:8080/api
```

## What's covered

Provider/runtime filters, drag-to-swipe with a haptic tick on accept, button
and keyboard-equivalent (D-pad/back) alternatives, undo, reject-all with
reshuffle/bring-back-5, result screen, loading/error states, and Android back
behavior (ends the session and returns to filters instead of exiting).

## Known limitation

This sandbox has no `/dev/kvm`-backed emulator available, so no on-device or
emulator smoke launch was performed here -- only structural build success (both
APK variants + AAB) and the `GameState` unit tests (`:android:testDebugUnitTest`,
10 passing) were verified. Install an emulator image or connect a device and
run `adb install android-debug.apk` to do a real launch before shipping.
