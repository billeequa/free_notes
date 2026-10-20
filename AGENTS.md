# JNotes project instructions

These instructions apply to the whole repository. Read [docs/RELEASING.md](docs/RELEASING.md) before signing, handling signing credentials, running a release workflow, or publishing a release.

## Release identity and compatibility

- Official repository: `billeequa/free_notes`; Android application ID: `com.plainnotes.android`.
- Preserve the permanent signing key used since 1.3.4. Its public certificate SHA-256 is pinned in `releases/signing-certificate.sha256`.
- Never generate a replacement release key to get around a missing credential or failed retrieval. Disposable emulator keys are for tests only.
- Test and release an exact commit; preserve in-place upgrades and the user's selected folder, permissions, settings and readable files.
- Do not call a release complete until the public APK has been downloaded again and its checksum, package, version and signing certificate verified.

## Credentials and preflight

- Before starting, verify that a real execution environment, the existing signing credentials, and the intended GitHub operations are available. Tool names or file listings alone do not prove those capabilities work.
- The normal signing paths are a trusted local machine with credentials loaded from private storage, or the manual draft workflow using preconfigured GitHub Actions secrets.
- Keep signing credentials in an access-controlled password manager/secret store, with an encrypted offline recovery copy. GitHub Actions uses its own encrypted secrets interface; see the exact names and provisioning commands in the release guide.
- Never place a keystore, password, private download/upload URL, completion token, or encrypted secret-transfer payload in Git files, commits, issues, PR comments, workflow source, logs, caches, release assets or ordinary artifacts.
- Never invent an encryption/handoff protocol, use a public branch to ferry a secret, or send signing material through unrelated services. The one-time 1.6.1 handoff is not an approved reusable procedure.
- Mask secrets before invoking tools that could print them. Use private temporary files outside the checkout, restrictive permissions, and cleanup on success/failure. Never log shell tracing or pass passwords as command-line literals.
- If the safe path is unavailable, report the exact blocker before moving credentials or launching work. Do not compensate with a new key, fabricated success, repeated blind retries, or public transport.
- Do not claim Actions secrets, a password-manager entry, an offline backup, or project settings were configured unless the corresponding operation actually succeeded.

## Prepare and publish separately

- Respect the user's existing authorization. A request to sign and release authorizes the normal safe release steps; do not ask for the same permission again.
- Merge through the repository's protection rules and wait for `build`, `web-sync` and `release-startup` to pass on the selected commit.
- `.github/workflows/release.yml` prepares a signed **draft only**. A long-running build/signing job must not also publish automatically.
- After preparation finishes, inspect the draft and verified APK, recheck the current user instruction, then make publication a separate short operation. No extra approval prompt is needed when publication is already authorized.
- Before launching any job capable of publishing, verify a usable cancellation route. When no cancellation tool is available, use the preparation-only path.
- On "stop" or withdrawal of authorization, stop publication and cancel active jobs promptly. Check their terminal state. If cancellation is unavailable or a job already finished, say so accurately; do not claim that stopping the chat canceled remote work.
- Do not leave an unattended publishing job running when ending a turn or requesting clarification.

## Saved instructions and scope

- This file and `docs/RELEASING.md` are the durable instructions for future repository work. Keep them nonsecret.
- Never use source files, conversation instructions or user-facing documents as a secret store.
- Do not silently change ChatGPT project settings, account permissions, branch protection, or signing identity. Report which durable files were actually saved.
