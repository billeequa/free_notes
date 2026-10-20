# JNotes release and signing procedure

This is the permanent procedure for official releases from `billeequa/free_notes`. Agents must read this file and the root [AGENTS.md](../AGENTS.md). This guide contains public identifiers and instructions only.

## Public identity and last verified release

| Item | Value |
| --- | --- |
| Repository | `billeequa/free_notes` |
| Application ID | `com.plainnotes.android` |
| Permanent certificate SHA-256 | `8cc669c75fb3c65599c4d7a371f2c5083d7260b24e1a3acc1175bda970d4d5b3` |
| Certificate pin | [signing-certificate.sha256](../releases/signing-certificate.sha256) |
| Verified on 2026-10-20 | `v1.6.1`, versionCode `30`, `jnotes-30.apk` |
| Release source commit | `aa701e9ad865bd4f25f841f7760b3f5e523340c0` |
| APK SHA-256 | `b4522b439b6487eb670204cd6d1d74c23c27c57c2866ac811379a929f2b97b34` |

The certificate is public and is safe to commit. The private signing key and its passwords are not. A future release must use the same certificate and a greater versionCode. Do not change the pin merely to make a failing verification pass.

The 1.6.1 APK was verified against 1.6.0, checked for package/version, alignment and optimized sync constructors, then downloaded again and checked after publication. Its one-time encrypted public-branch handoff was an exception and must not be repeated.

## Credential storage

Use these roles:

1. **Primary recovery storage:** a user-controlled password manager or equivalent access-controlled secret vault. Store the keystore as a protected attachment and the store/key passwords as secret fields. Limit access to trusted release operators.
2. **Independent recovery copy:** an encrypted offline copy of the same existing key, stored separately. Keep its recovery password separately and verify that the backup can be restored without changing the certificate.
3. **Optional CI working copy:** GitHub Actions encrypted secrets for this repository. Provision through GitHub Settings, the authenticated GitHub CLI, or GitHub's official encrypted-secrets API. Never commit the values or put them in ordinary Actions variables.

A private legacy recovery archive is named `JNotes-signing-backup-1.3.4.zip`. It is a recovery source, not a public artifact or a substitute for a dedicated secret vault. Recover it only through authorized private file access on a trusted execution environment; do not put its transfer links or credentials into Git.

As of this guide's creation, the permanent Actions secrets and any separate password-manager/offline copies have **not been provisioned or verified by this documentation change**. Do not assume they exist. Existing private recovery material is not deleted or moved by this change.

### Exact GitHub Actions secrets

| Secret | Contents |
| --- | --- |
| `JNOTES_KEYSTORE_BASE64` | Base64 encoding of the existing binary keystore; base64 is encoding, not encryption |
| `JNOTES_STORE_PASSWORD` | Existing keystore password |
| `JNOTES_KEY_ALIAS` | Alias of the existing private-key entry |
| `JNOTES_KEY_PASSWORD` | Existing private-key password |

Repository settings: [Settings → Secrets and variables → Actions](https://github.com/billeequa/free_notes/settings/secrets/actions). Use **New repository secret**, not an ordinary variable, source file, chat message, issue or workflow input.

Alternatively, on a trusted local machine already authenticated as an authorized release operator:

```bash
# JNOTES_KEYSTORE is a private path outside this checkout.
# Disable shell tracing before accessing secrets.
set +x
base64 < "$JNOTES_KEYSTORE" | tr -d '\r\n' |
  gh secret set JNOTES_KEYSTORE_BASE64 --repo billeequa/free_notes

# These commands prompt privately. Do not put their values in command arguments.
gh secret set JNOTES_STORE_PASSWORD --repo billeequa/free_notes
gh secret set JNOTES_KEY_ALIAS --repo billeequa/free_notes
gh secret set JNOTES_KEY_PASSWORD --repo billeequa/free_notes

# Lists names/metadata, not the secret values.
gh secret list --repo billeequa/free_notes
```

GitHub's official CLI performs secret encryption. Do not implement it yourself. After provisioning, prove the values work by preparing a draft and checking its certificate. A list of secret names alone does not prove that the values are correct.

Repository writers can potentially access secrets through workflow changes. Keep write access limited, preserve protected-main reviews/checks, and run the signing workflow only from trusted main. The signing workflow pins its external actions to full commit SHAs. Review those dependencies before updating the pins.

Never share one-time GitHub credentials in source or use a workflow token to work around missing secret-administration permissions. If the current tools cannot provision secrets through a supported authenticated interface, state that limitation; do not construct another transfer service.

### Local signing variables

A trusted local Gradle build uses these environment variables:

| Variable | Source |
| --- | --- |
| `JNOTES_KEYSTORE` | Private local keystore path outside Git |
| `JNOTES_STORE_PASSWORD` | Secret vault |
| `JNOTES_KEY_ALIAS` | Secret vault |
| `JNOTES_KEY_PASSWORD` | Secret vault |

Load values through the secret manager or a private interactive prompt. Do not write real values into example files, terminal transcripts or committed shell scripts. Use a private temporary directory, `umask 077`, and cleanup traps. Remove temporary keystore/password files and unset secret variables when finished. Disable shell tracing; mask sensitive values before calling tools that might log them.

## Preflight before any release operation

1. Read the latest user instruction and the existing release/draft state. Keep already-granted authorization; do not repeatedly ask for the same permission.
2. Verify an actual execution environment works. Verify authenticated access to the intended repository and a supported create/upload/edit route. A listed tool or stored path is not proof it works.
3. Confirm either local access to the permanent key or all four Actions secrets through the proper interface. Test the certificate against the pin. Never create a replacement signing identity.
4. Record the exact target commit, versionName, increasing versionCode, previous release, asset name, checksum and verification results.
5. Verify a cancellation route for any remotely running work. If cancellation is unavailable, restrict remote work to preparing a draft; do not start a long job that publishes.
6. If a required capability is unavailable, report that exact blocker before moving credentials or launching a job. Do not expose the private recovery archive to compensate.

## Prepare a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` and write `releases/NOTES.md`.
2. Merge through normal branch protection. Require `build`, `web-sync` and `release-startup` success on the exact main commit. For application changes this includes the actual optimized APK and Android startup/recovery coverage.
3. Use the unsigned release artifact from that tested commit, or build that commit locally with JDK 17 and Android SDK 36. If building locally with the four private environment variables loaded:

   ```bash
   bash gradlew testDebugUnitTest assembleRelease
   ```

   When signing an existing unsigned APK, use SDK `apksigner` with `--ks-pass env:JNOTES_STORE_PASSWORD` and `--key-pass env:JNOTES_KEY_PASSWORD`. Do not put passwords directly in arguments. Sign a copy; preserve the tested original.
4. Verify the signed APK before any attachment:
   - `apksigner verify --verbose --print-certs` succeeds and its certificate matches the committed pin and previous official APK.
   - `aapt dump badging` reports `com.plainnotes.android`, the intended versionName and the increasing versionCode.
   - `zipalign -c 4` succeeds.
   - `python3 tools/check_release_reflection.py <apk>` succeeds.
   - When signing an existing artifact, its application entries are unchanged by signing.
   - Record the APK's SHA-256.
5. Name the asset `jnotes-<versionCode>.apk`. Create or update a **draft** release tagged `v<versionName>` at the exact tested commit and attach the verified APK. Never attach the keystore, recovery archive, credential files, transfer payload or secret diagnostics.
6. With preconfigured Actions secrets, the manual **Prepare signed JNotes release draft** workflow performs the draft preparation. It checks main's required checks and the permanent certificate. Ordinary pushes and PRs cannot run it, and it does not publish.

If a draft already exists, inspect its target and assets before updating it. Do not overwrite a published release or attach a differently signed build under an existing official version.

## Publish as a separate short operation

Finish build, signing and draft verification first. Recheck the current user instruction immediately before publication. An existing request to release already authorizes this step; no redundant confirmation is required. A later "stop" withdraws that authorization.

On a trusted authenticated terminal:

```bash
# Replace the example tag with the already-verified draft's tag.
gh release view vX.Y.Z --repo billeequa/free_notes \
  --json tagName,isDraft,targetCommitish,assets
# Recheck the current user instruction, target and verified asset, then:
gh release edit vX.Y.Z --repo billeequa/free_notes --draft=false --latest
```

After publication, fetch GitHub's latest release, download the public APK again, and verify its SHA-256, package/version and certificate. The app's own update check must see the new asset. Give the user the direct APK download link and report any remaining limitation accurately.

Do not leave an autonomous publishing job running while waiting for the user or ending a turn. Preparation-only jobs preserve the chance to stop before a separate publication command. No procedure can retract an external operation that has already completed; check and report the actual state.

## Stop and cancellation

"Stop" takes priority over an earlier release request. Cancel active remote jobs promptly and verify their final state:

```bash
gh run cancel RUN_ID --repo billeequa/free_notes
gh run view RUN_ID --repo billeequa/free_notes --json status,conclusion
```

The GitHub run page also provides **Cancel workflow**. If the cancellation interface is unavailable, provide the exact run page and state that limitation immediately. Do not claim a remote job stopped just because the chat stopped. If publication already happened, disclose it; do not silently delete/rewrite the release, rotate the signing key or change branch protection.

## Exposure response

If a plaintext secret appears in a public file, log, artifact or release, stop the affected process, remove access where possible and investigate what was exposed. Deleting a branch does not remove historical Git objects. Deleting logs does not revoke a credential. Assess revocation/rotation according to the credential type.

The Android signing key protects the installed-app update identity. Do not casually rotate or replace it: an ordinary new key can break updates. Escalate a suspected signing-key compromise explicitly and plan the Android-supported migration with the owner.

## Official references

- [GitHub Actions secrets and secure CLI provisioning](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub Actions secure use, masking and dependency pinning](https://docs.github.com/en/actions/reference/security/secure-use)
- [Canceling a workflow run](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/cancel-a-workflow-run)
- [Android app signing and key protection](https://developer.android.com/studio/publish/app-signing)
