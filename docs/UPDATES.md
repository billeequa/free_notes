# JNotes updates

Settings includes Check for updates, Download and install, Open GitHub Releases, and Install downloaded APK. Downloads must have the application ID `com.plainnotes.android`, a higher versionCode, and the same signing certificate. Android asks for approval before installation.

A normal update preserves the selected notes folder, its permission, app settings, and existing files. Notes remain usable locally without a cloud account or a working server. To Do files use format 2; do not downgrade to an app that only supports format 1.

## Signing and publishing

The permanent signing identity has been used since 1.3.4. Keep using the existing private signing backup so Android accepts updates in place. Keep the keystore and passwords outside Git, release assets, and logs.

The established release process, including 1.6.0, signs the tested APK locally and uploads it to GitHub Releases. It does not require Actions signing secrets.

1. Increase versionCode and versionName in `app/build.gradle.kts`; update `releases/NOTES.md`.
2. Merge after the required build, web-sync, and release-startup checks pass.
3. Use the unsigned release APK from that tested commit's Android workflow, or build that commit locally. Sign with the existing permanent key. A local Gradle build accepts `JNOTES_KEYSTORE`, `JNOTES_STORE_PASSWORD`, `JNOTES_KEY_ALIAS`, and `JNOTES_KEY_PASSWORD`.
4. Verify the APK's package ID, versionCode, optimized-build reflection checks, and signing certificate against the previous published release.
5. Publish a release tagged `v<versionName>` at the tested commit, attach `jnotes-<versionCode>.apk`, and use `releases/NOTES.md` for its notes.
6. Confirm GitHub's latest release contains the downloadable APK. The app checks GitHub Releases without an embedded GitHub token.

## Optional Actions publisher

`Publish signed Jnotes release` remains available through Run workflow. That optional path reads the four permanent signing secrets defined in `.github/workflows/release.yml`. It is separate from local signing and does not run automatically after ordinary merges.
