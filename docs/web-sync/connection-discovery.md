# Automatic phone connection and hosting moves

Normal phone setup is **Settings → Web Sync → email and password**. The user does not enter a website or backend address. Settings keeps a **Website** label and the actual clickable address. Self-hosted installations can opt into **Advanced: self-hosted connection**; existing custom connections are retained when upgrading.

## Public service directory

`config/jnotes-service.json` is the maintained connection directory. The APK bundles this exact file as an asset and discovers current values from:

`https://raw.githubusercontent.com/billeequa/free_notes/main/config/jnotes-service.json`

The file contains only public website/API addresses, a publishable key, the original deployment UUID, supported protocol/schema versions, and an increasing revision. It contains no account email, password, session, encryption/recovery key, or privileged backend credential. The authenticated repository and HTTPS origin are the publishing trust root. Fork builds use `updateRepository` to select their directory, just as they select their update repository.

Discovery runs during explicit setup and enabled sync work, never during ordinary startup or local saves with sync off. It uses anonymous bounded requests, no redirects and no-store headers. A directory network outage can use the last anonymously validated configuration, or the bundled configuration on first use. An unsupported/malformed directory, changed deployment identity or unrecognized public-key type stops the connection. Older directory revisions cannot replace a newer cached revision; edits must increase `revision`. Do not reuse a revision with different values.

The phone verifies the backend's anonymous bootstrap against the directory and any existing local deployment identity before sending authentication data. A deployment UUID is public, so matching it alone never authorizes sending an old session to a new host.

## Moving a website or rotating a public API key

1. Bring up and verify the new website/public key. Keep the previous address/key available during the transition.
2. Update `web_url` and/or `publishable_key` in the directory and increase `revision`; commit to `main`.
3. Update the website host's corresponding public environment values and redeploy it. Browser clients read the website's `/config.json`; its values must match the directory. Neither location may contain an admin/service-role key.
4. On the next setup or enabled sync job, phones refresh the directory. Website changes and same-origin public-key rotations keep the existing session. The Website link follows the discovered address.

This control directory is independent of Cloudflare and Supabase hosting. Moving either host does not require a new APK while the stable directory URL remains available. A repository/path move must preserve that URL or be accompanied by an app update. Changing to an incompatible API/protocol also requires a reviewed client update.

## Moving the backend to another origin

1. Back up the readable phone files and full backend/Auth configuration. Quiesce writes for a consistent final transfer. Test the destination before changing discovery.
2. Preserve the original deployment UUID, complete bootstrap/KDF salt and parameters, owner Auth UUID and password hashes, wrapped vault keys/key ID, documents, revisions, operation IDs, tombstones and history. Preserve epoch/high-water continuity for an intact migration. Moving to a blank project is not a migration. Follow the official Supabase Auth/database migration procedures for sessions and server secrets.
3. Configure the destination's equivalent Auth, RLS, roles, SMTP and public API gateway. Update the website's public backend configuration and security headers. Independently verify authorization and decryption at the destination.
4. Update the directory's `supabase_url` and public key, keeping the original deployment identity, and increase `revision`.
5. Phones probe the destination without credentials and pause with **Reconnect Web Sync** instructions. Old access/refresh tokens are never forwarded across origins. The user reconnects with email verification and the same password, without entering a website address. The original owner and vault key must exist before setup can change credentials or create any vault. Only a successfully reviewed setup saves the new active connection.
6. The existing local files, stable record IDs, outbox, conflicts, cursor and rollback floor remain intact. Existing worker checks still reject a different vault key, epoch change or lower high-water value. A restore with a deliberately new epoch requires the separate explicit backup/repair procedure; discovery never resets these checks.

If discovery or the destination is unavailable, sync pauses and phone editing remains available. Never clear the phone sidecar or regenerate the KDF salt to fix a hosting move.

## Verification

Android regression tests cover address-free setup, legacy phone upgrades, same-origin key rotation, website/backend moves, anonymous identity validation, withholding sessions across origins, pending-edit preservation, self-hosted connections, directory outages/stale revisions, unsupported configuration, missing migrated vaults, response bounds and redirects. The Node suite validates that the bundled directory contains only expected public fields and valid production addresses. Physical-phone and real SMTP/recovery acceptance remain separate from these automated checks.
