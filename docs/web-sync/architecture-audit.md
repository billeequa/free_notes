# Architecture audit — 1.4.10 (a81fa88)

JNotes is native Kotlin/Jetpack Compose, API 26–36, AGP 9.1, Java 17. Production package/signing is unchanged. The release workflow signs only when supplied private credentials; this feature never changes that workflow or publishes an APK.

## Existing authoritative storage

Notes and journals share `NoteTextContent`, `EditableNote`, `NoteFileParser`, and `NotesRepository`. Category, journal date, Double X date and template version are existing metadata. The readable UTF-8 formats must stay unchanged. Notes are files in a user-selected SAF tree; Trash and Exports are child directories. Tasks are one readable `to_do_jnotes.txt` v2 file; task UUIDs already exist. Notes currently identify by document URI, which may change on provider rename/copy. Sync adds a private SQLite mapping and transfers identity when the repository changes a URI. It never keys identity by title.

The private `NoteIndex` supplies immediate cached startup; normal app reconciliation is unchanged. Verified writes keep recovery copies in `pending-writes` until SAF readback succeeds. Note writes and task writes have separate mutexes; the sync integration shares these locks across repository instances. Completion is committed through `saveTodos` after the existing confirmation UI; pending gestures never become cloud changes. Double X templates are real editable body text, inserted once and identified by template version.

## Integration

Hooks run only after successful local file/index persistence, return immediately when sync is disabled, and catch sync failures without failing the local save. Enabled sync writes immutable outbox snapshots. The worker runs reconciliation to find missed commits, with no cloud scan during startup. Pulls use guarded repository APIs under the same locks, compare current local values against their base, and retain conflicts before advancing the cursor. Remote writes are applied without changing existing serializer/export formats. SAF access failure pauses sync; no broad storage permission is added.

WorkManager initialization is lazy; its startup initializer is removed. Startup schedules enabled work only after the first Compose frame. Credentials and vault key are wrapped by Android Keystore in `noBackupFilesDir`; sync DB and preferences are excluded from backup. Losing the key pauses sync and never gates local storage.

## Protocol and backend

Protocol v1 uses Argon2id 1.3, HKDF-SHA-256 with independent auth/wrap labels, AES-256-GCM and canonical JSON-array AAD. TS uses noble hashes; Kotlin uses Bouncy Castle Argon2 (not a handwritten primitive). All task/document metadata stays inside ciphertext. SQL uses one approved owner, explicitly revoked direct table writes/reads, fixed-search-path security-definer RPCs, live Supabase-session validation, per-owner row locking, revision CAS, immutable revision/change history and persistent tombstones. Device IDs are diagnostic only; v1 deliberately offers account-wide sign-out instead of pretending an ID revokes authentication.

Web is React/TypeScript, memory-only session/content, no service worker/IndexedDB/cache. Browser requires online reads/writes. Runtime `config.json` and SQL migrations keep hosting portable. Supabase can be managed or self-hosted; the static frontend can be served by Nginx/Caddy/Pages.

## Inventory and verification

`protocol/`: wire models, cryptography, three-way merge and adversarial tests. `supabase/`: migrations, local configuration and role/CAS/ordering tests. `web/`: shared document editor, tasks, auth/recovery, history/trash/conflicts. `app/.../sync/`: sidecar, secrets, transport, worker, onboarding/settings. `server/`: frontend container/runtime config and deployment instructions. `docs/web-sync/`: protocol, threat/recovery/operator documentation and acceptance report.

No populated physical Android device or SDK is present in the initial environment. Cold-start/tab/editor p50/p95 cannot honestly be reported here; retain the original baseline and run the documented populated-device acceptance drill before release. Automated parser/index tests and new sync tests complement, and cannot replace, this measurement. Independent security review, production credentials, SMTP verification and restore drill remain rollout gates, not claims that unit tests establish production safety.
