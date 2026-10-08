# Jnotes updates (1.3)

Settings includes Check for updates, Download and install when a newer release is found, Open GitHub Releases, and Install downloaded APK. Downloads are checked for the application ID, strictly higher versionCode, and the same signing certificate before Android receives them. Android still asks the user to approve installation. No recovery/rollback is included.

## Existing notes and tasks

The application ID remains com.plainnotes.android. The selected-folder preference and to_do_jnotes.txt filename are unchanged. Version 1.4.5 reads Jnotes To-do: 1 and writes format 2 with combinable emoji flags. Older app versions cannot read format 2; do not downgrade after saving to-do changes. An ordinary same-key update preserves the folder permission and loads existing items. No migration or empty replacement is written at startup. Parse failures are shown without overwriting the original. A fixed 1.2-format fixture tests IDs, descriptions, flags, and completion history.

Old GitHub Actions debug builds used disposable signing keys. The old private key cannot be reconstructed from an APK. If Android rejects an upgrade from 1.2 due to a different signature, verify the selected folder contains every saved note and to_do_jnotes.txt and copy that folder before any uninstall. After reinstalling, select the SAME folder, not a new empty folder. External text files survive uninstall; internal drafts and settings do not. Do not uninstall while a save is failing.

## Signing identity

Version 1.3.4 starts a new permanent signing identity because the private key used for 1.3 through 1.3.3 was lost. Android requires uninstalling the old app before installing 1.3.4. First copy and verify the user-selected notes folder, including `to_do_jnotes.txt`. Uninstall clears the saved folder selection and settings; after installation, select the same folder to load notes and To-Do history. Keep the new private signing backup outside the repository so subsequent versions can update 1.3.4 in place.

## One-time signing setup

The private signing backup contains the permanent keystore and credentials. Keep it private, outside the repository, and backed up. Configure these Actions secrets for later releases:

- JNOTES_KEYSTORE_BASE64
- JNOTES_STORE_PASSWORD
- JNOTES_KEY_ALIAS
- JNOTES_KEY_PASSWORD

No private key or password belongs in Git history, releases, an APK, or workflow logs. The GitHub connector used for this change cannot set Actions secrets; the owner must run the script or enter the four values under Settings > Secrets and variables > Actions.

## Public releases

billeequa/free_notes is public. JNotes checks GitHub Releases for newer APKs without embedding a GitHub token. The published 1.3.4 release has a signed `JNotes-1.3.4.apk` asset. The app still verifies package ID, newer versionCode, and signing identity before requesting an in-place update, so the old-key app will reject 1.3.4 as an automatic update. The documented uninstall/reinstall path is required once.

## Publish the next version

1. Increase versionCode AND versionName in app/build.gradle.kts.
2. Update releases/NOTES.md.
3. Merge tested changes and run Actions > Publish signed Jnotes release > Run workflow.

The workflow uses the permanent signing secrets, tests, builds, verifies the APK, and publishes a release. APK asset names must be jnotes-<versionCode>.apk. Reusing a version/tag intentionally fails instead of replacing an existing release. Download the existing release if retrying a completed publication.

The 1.3.4 APK was signed from the tested release build and attached directly to the GitHub Release. The new private signing backup is kept in the owner's Library, outside Git. Configure the four Actions secrets from that backup before using the automated release workflow for a later version.

References: https://developer.android.com/studio/publish/versioning and https://docs.github.com/en/rest/releases/releases
