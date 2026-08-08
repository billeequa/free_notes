# Building Jnotes v1

## Requirements

- Android Studio with Android SDK 36, or equivalent command-line SDK tools
- JDK 17 or newer compatible with the included Android Gradle Plugin
- An Android device or emulator running Android 8.0 / API 26 or newer

The repository includes the Gradle wrapper, so a separate Gradle installation is not required.

## Android Studio

1. Open the repository root in Android Studio.
2. Allow Gradle sync to complete.
3. Install Android SDK 36 if prompted.
4. Select the `app` configuration and an emulator or connected device.
5. Run the app.

The first launch asks the user to select a notes folder through Android's system folder picker.

## Command line

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

On macOS or Linux:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The installable debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Optimized release build

The release build enables R8 code shrinking and resource shrinking:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease
```

Signing credentials are deliberately absent from the repository. Configure a private release keystore before distributing your own release build. Never commit the keystore, passwords, or `keystore.properties`.

The checked-in [`releases/jnotes_v1.apk`](../releases/jnotes_v1.apk) is the installable v1 build produced from this source baseline. It is provided for direct sideloading and is not a substitute for a Play Store signing configuration.

## Tests

The current local unit suite covers note serialization, current timestamps, legacy timestamp parsing, malformed metadata recovery, and verbatim body preservation. Run it with:

```powershell
.\gradlew.bat testDebugUnitTest
```

Keyboard, cursor, scrolling, document-provider, and system folder-picker behavior depend on Android framework components and should also be checked on a real device.
