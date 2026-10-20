# JNotes updates

Settings includes Check for updates, Download and install, Open GitHub Releases, and Install downloaded APK. Downloads must have the application ID `com.plainnotes.android`, a higher versionCode, and the same signing certificate. Android asks for approval before installation.

A normal update preserves the selected notes folder, its permission, app settings, and existing files. Notes remain usable locally without a cloud account or a working server. To Do files use format 2; do not downgrade to an app that only supports format 1.

## Signing and publishing

Read the permanent [release and secret-storage procedure](RELEASING.md) and the root [agent instructions](../AGENTS.md) before handling signing material or starting release work.

Use the existing permanent signing key. Its public certificate is pinned in [signing-certificate.sha256](../releases/signing-certificate.sha256). Keep the keystore and passwords in private secret storage, with an encrypted recovery copy; never put secret values or encrypted transfer payloads in Git or logs.

The supported paths are local signing on a trusted machine or the manual **Prepare signed JNotes release draft** workflow with the four preconfigured Actions secrets. That workflow prepares a signed draft only. Publication is a separate short operation after verification and a check of the current user instruction. Existing release authorization does not need to be requested again; a later "stop" cancels it.

The guide includes the exact secret names, secure provisioning commands, artifact/certificate checks, draft publication and cancellation steps, and the legacy private backup's filename. Missing tools or credentials must be reported before a job starts. Do not use a custom encrypted public-branch handoff as a fallback.
