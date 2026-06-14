# microhone — Android app

Kotlin + Jetpack Compose. Captures the phone microphone and streams it to the
desktop host (see `microhone-plan.md`, section 9).

## Prerequisites

- **Android Studio** (Ladybug or newer) — easiest path; or
- **JDK 17** + **Android SDK** (compileSdk 35) for command-line builds.

## Open / build

Open `apps/mobile` in Android Studio. On first sync it generates the Gradle
wrapper jar and downloads the SDK components.

Command line (after the wrapper jar exists):

```bash
cd apps/mobile
./gradlew assembleDebug      # build APK
./gradlew installDebug       # install on a connected device
```

> **Note:** `gradle/wrapper/gradle-wrapper.jar` is a binary and is generated on
> first open in Android Studio, or by running `gradle wrapper` with a local
> Gradle install. It is not committed in this skeleton.

## Status

Faz 0: single Compose screen, permissions declared in the manifest. Audio
capture (AAudio/Oboe), foreground service and networking land in Faz 1+.
