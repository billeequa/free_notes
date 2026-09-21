# Jnotes updates (1.3)

Settings includes Check for updates, Download and install when a newer release is found, Open GitHub Releases, and Install downloaded APK. Downloads are checked for the application ID, strictly higher versionCode, and the same signing certificate before Android receives them. Android still asks the user to approve installation. No recovery/rollback is included.

## Existing notes and tasks

The application ID remains com.plainnotes.android. The selected-folder preference, to_do_jnotes.txt filename, and Jnotes To-do: 1 format are unchanged. An ordinary same-key update preserves the folder permission and loads existing items. No migration or empty replacement is written at startup. Parse failures are shown without overwriting the original. A fixed 1.2-format fixture tests IDs, descriptions, flags, and completion history.

Old GitHub Actions debug builds used disposable signing keys. The old private key cannot be reconstructed from an APK. If Android rejects an upgrade from 1.2 due to a different signature, verify the selected folder contains every saved note and to_do_jnotes.txt and copy that folder before any uninstall. After reinstalling, select the SAME folder, not a new empty folder. External text files survive uninstall; internal drafts and settings do not. Do not uninstall while a save is failing.

## One-time signing setup

The delivered private signing setup archive contains a permanent keystore and an authenticated gh CLI setup script. Keep it private, outside the repository, and backed up. Its script configures these Actions secrets after you run gh auth login:

- JNOTES_KEYSTORE_BASE64
- JNOTES_STORE_PASSWORD
- JNOTES_KEY_ALIAS
- JNOTES_KEY_PASSWORD

No private key or password belongs in Git history, releases, an APK, or workflow logs. The GitHub connector used for this change cannot set Actions secrets; the owner must run the script or enter the four values under Settings > Secrets and variables > Actions.

## Private vs public releases

billeequa/free_notes is private. Unauthenticated GitHub release checks return 404, even if a release exists. The app explains this and offers the signed-in browser. No GitHub token is embedded in the APK. Browser download plus Install downloaded APK works with private releases.

For direct checks/downloads without signing in, publish APK releases in a public distribution repository. The source repository can stay private. Set the Actions variable JNOTES_UPDATE_REPOSITORY to owner/distribution-repo for builds; publishing to another repository additionally needs a narrowly scoped credential and a destination change in release.yml. The supplied workflow publishes only in this repository. Making source public is not required and has not been done.

## Publish the next version

1. Increase versionCode AND versionName in app/build.gradle.kts.
2. Update releases/NOTES.md.
3. Merge tested changes and run Actions > Publish signed Jnotes release > Run workflow.

The workflow uses the permanent signing secrets, tests, builds, verifies the APK, and publishes a release. APK asset names must be jnotes-<versionCode>.apk. Reusing a version/tag intentionally fails instead of replacing an existing release. Download the existing release if retrying a completed publication.

The initial 1.3 signed APK is checked into releases/ with current.json, matching the repository's existing APK-distribution pattern. A push of current.json to main verifies and publishes that exact signed file, without requiring the signing secrets. Later manually dispatched releases build from source using the secrets.

References: https://developer.android.com/studio/publish/versioning and https://docs.github.com/en/rest/releases/releases
