# JNotes Web v1 — Codex implementation specification

**Status:** Approved requirements / implementation handoff (not yet implemented)  
**Platforms:** Existing JNotes Android app, responsive browser web app, Supabase backend  
**Scope:** To-Do, Notes, Journal (including Double X Day compatibility), single owner account  
**Non-goals:** EPUB synchronization, multi-user collaboration, browser-offline editing, public sharing, calendar integration  
**Change management:** Development branch and PR only; no production migration, upload of personal data, APK publication, or deployment without explicit approval.

## 0. Highest-priority invariant: local Android access never depends on sync

**Existing Android documents and tasks must remain readable and editable regardless of Supabase, internet connectivity, credential loss, encryption-key problems, authentication expiry, client version mismatch, or failed migration.**

1. Keep existing phone files in their current usable local format. Cloud/vault encryption applies only to synchronized cloud payloads, **not** to existing Android document files. Do not retrofit a login gate onto the app.
2. No authentication, network request, server availability, vault unlock, or sync prerequisite for normal Android startup, task editing, note editing, journaling, exports, or local search.
3. All local edits are committed locally before any sync attempt. Failed sync only increases a durable pending queue; it never rolls back a valid local edit.
4. Disabled sync is the default. When disabled, perform no sync network calls, background jobs, cloud-related filesystem scans, or key derivations during normal operation. Keep current UI performance and storage behavior, apart from a dormant settings section.
5. Signing out, revoking access, forgetting credentials, turning sync off, or deleting the cloud account must **not** delete or lock the local Android contents.
6. Corrupt ciphertext, bad keys, stale revisions, unexpected empty-server responses, or a server restore must **not** be allowed to overwrite, delete, or replace valid local contents. Freeze affected synchronization and show a nonblocking repair state.
7. Separate destructive actions (`Delete remote synced data`, `Clear local data`) from `Disable sync`; never offer local deletion as part of ordinary disconnect.
8. A phone whose files have actually been erased/destroyed can recover *cloud* content only with valid authorized credentials plus its encryption passphrase or recovery code. The product cannot promise impossible restoration without any remaining copy or key.

**Priority order for every engineering decision:** preserve Android local data > confidentiality and recoverability > sync correctness > user experience > schedule/performance optimization.

## 1. Product behavior and v1 scope

- Full editable To-Do, Notes, Journal from web, with closely matched Android visual language. Web login uses the same **email and one user-facing password** established on the phone. Browser internet connection is required; no service-worker/offline editing in v1.
- Android sync is optional and off by default. Initial Android setup uses an `Enable Web Sync` section in app settings and creates/claims the one authorized account; starts only after explicit user confirmation of the complete upload preview.
- Initial import includes **all existing tasks, notes, and journal entries**; preview item counts and approximate data sizes and make a consistent local backup first. The initial upload is resumable and idempotent. Do not include EPUB files.
- Bidirectional edits on both clients. Phone continues working offline indefinitely. Web shows error rather than pretending it saved if the server rejects a write.
- Notes and Journal are one underlying **document entity + editor + sync pipeline**, differentiated by `kind` and journal-specific metadata. Double X Day is a journal subtype/template and must survive import/export and sync. Avoid changing existing on-disk file formats.
- Respect established JNotes To-Do semantics: urgent 🚨, important ❗, long-term 🎯 flags (and legacy flags), colors, completed-item text styling, completion confirmation, order, completed folders/grouping, reopen, deletion, date/time metadata.
- Match existing Notes, Journal, Double X Day layout, theme, autosave, and editor semantics where applicable. Web may use keyboard/mouse equivalents for swipe and long-press.
- Preserve existing Android EPUB reader and settings; web EPUB is explicitly deferred.

## 2. Repo assessment / discovery gate (must precede edits)

Codex must inspect **the actual current GitHub repository**, release branch, Android framework, signing/release conventions, permissions, file format, metadata-index implementation, task/journal identity, current UI, and tests. Some requirements below specify behavior, not assumptions about code structure. Codex must write `docs/web-sync/architecture-audit.md` with:

- Existing data models and location/permissions for all three categories.
- Local write flow, metadata-index update points, task completion confirmation, journaling/Double X Day triggers.
- Whether records already have stable IDs and how IDs can be introduced without reformatting exported files.
- Safe hooks for enqueueing sync operations without blocking UI.
- Risks/mitigations for current SAF URI permissions and app-specific storage.
- Baseline cold-start, tab-switch, and editor-return times on a representative populated dataset.
- Proposed implementation file inventory, dependencies, key libraries, migration path, and test plan.

Do not modify release signing material. Do not push secrets, exported notes, or any real user content. Stop at an architectural review checkpoint before importing personal data or activating production sync.

## 3. Proposed architecture

### Android
- Existing file store and metadata index **remain authoritative for normal Android UI**.
- Isolated optional `SyncAdapter`: immutable record IDs / local mapping, durable outbox, pulled revision cursor, device ID, conflict store, key wrapping, WorkManager tasks.
- Use a private sidecar database (e.g., Room/SQLite) for sync metadata, **not** a replacement of JNotes' existing local storage. Existing content/export formats remain unchanged.
- On local commit: save current JNotes file and index normally, then durably register a local change. If a crash happens between local commit and enqueue, enabled-sync background reconciliation must detect missed modifications without an expensive startup scan.

### Web
- Responsive React + TypeScript frontend, preferably bundled static assets hosted on Cloudflare Pages or similar. Existing Android app is the UI specification.
- Browser is online-only. Client decrypts record payloads in memory after sign-in. Writes are encrypted before upload, immediately acknowledged only after server commit.
- No local plaintext cache, IndexedDB document database, service-worker document caching, or third-party analytics in v1. Use an authenticated-session strategy that defaults to no persistent session on shared browsers; optionally remember trusted devices later.

### Supabase
- Supabase Auth for the single owner, PostgreSQL for ciphertext/revisions/device acknowledgments, strict RLS and minimal grants.
- Version-controlled SQL migrations and RLS tests, not hand-edited tables.
- A secure transactional RPC/API for conditional commit + append to change feed + idempotency key; direct table access should be limited to strictly required reads.
- Supabase holds no note titles, bodies, task descriptions, flags, journal narrative, or encryption passphrase in plaintext.

## 4. Single-password login, account provisioning, and vault cryptography

### User experience

**On first Android setup:** `Settings → Web Sync → Enable` displays email and create/confirm-password fields. Account must belong to explicitly approved email. Following email approval/verification, client generates vault and recovery code, user confirms recovery code safely stored, chooses initial upload categories (all three default), sees preview, and confirms. Password is shown only during initial entry if the user taps Reveal; thereafter settings show email, status, device name, and actions; do **not** store or re-display a plaintext password.

**Everyday Android:** Keystore-protected authorization/session and vault-key wrapping allow sync without retyping password, subject to device security. Normal app editing never requests vault password. App updates signed with same identity preserve secure app state; app reinstall, cleared app data, or keystore invalidation may require account/vault recovery, but local documents must never be made inaccessible by this change.

**Web login:** User enters same email and password once. Client derives Supabase authentication credential and distinct vault-wrapping credential; signs into Supabase with the derived auth secret, fetches the encrypted vault key, decrypts it on the browser, then obtains encrypted records. Do not ask for a second encryption password. Web logout discards memory-held key and session; support automatic inactivity locking.

### One user-facing password, independent derived secrets

This is a **client-side credential derivation protocol**, not two passwords typed by the user. Define and version byte-level formats and cross-platform test vectors before using real data:

1. Public, randomly generated, per-deployment KDF salt and Argon2id parameters are provisioned and preserved in a bootstrap configuration that *both clients can retrieve prior to authentication*. Bootstrap exposes no vault contents or account credentials; it is intentionally public and independent of an email-account enumeration endpoint. Preserve it in migration/backup exports. Choose Argon2id parameters benchmarked for supported mobile/browser devices (e.g., approximately 64 MiB memory, t≈3, p=1 as a starting benchmark, not a hard-coded guarantee).
2. Canonicalize email and passwords consistently: treat email as the account identifier (no surprise transformations); preserve password bytes exactly according to a documented Unicode encoding and explicitly define any normalization decision. Define unambiguous versioned serialization.
3. Run Argon2id(password, bootstrap salt) on the client to create a high-entropy intermediate value. Use HKDF-SHA-256 with **distinct, protocol-versioned context labels** to derive `K_auth_material` and `K_wrap_material`. Never send intermediate or wrap material to Supabase.
4. Encode `K_auth_material` as a high-entropy opaque password and send only that encoded auth secret through Supabase Auth's normal email/password endpoint. Supabase does not receive the user-entered password or the wrapping key. Both clients must produce exactly the same auth secret.
5. Generate a separate random 256-bit vault content-encryption key (`K_vault`) using a CSPRNG on Android. `K_wrap_material` encrypts an **envelope** containing `K_vault` via AES-256-GCM (unique 96-bit nonce). Store this envelope on Supabase.
6. Encrypt each full document/task logical payload with `K_vault` (or a labeled per-record key derived from it) using AES-256-GCM and a unique cryptographically generated nonce. Bind version, account, record ID, record kind if exposed, and revision as authenticated associated data (AAD). Never reuse a key/nonce pair. Test tampering and wrong-record swaps.
7. Use a documented, versioned ciphertext envelope: `{protocol_version, key_id, algorithm, nonce, ciphertext_with_tag, aad_fields}`. Base64/binary encoding and canonical AAD layout must be byte-for-byte portable between Kotlin and TypeScript.
8. Android wraps a cached `K_vault` under a private Android Keystore protected wrapping key and stores only encrypted material in app-private storage; Android Keystore keys generally are non-exportable. Handle keystore loss as a **sync recovery problem**, never as an Android notes lockout.
9. Do not include decrypted content or keys in analytics, logs, browser localStorage/IndexedDB, crash reports, clipboard by default, HTTP URLs, GitHub, or build artifacts. Avoid third-party script sources and protect deployment/build permissions.

**Security boundary:** Supabase's database, keys, backups, and provider operators cannot decrypt *record content* from stored ciphertext and auth credentials alone if the clients and KDF are secure. Cloud ciphertext still exposes unavoidable record count, rough sizes, encrypted revision frequency, device timing, and some routing metadata. An attacker serving malicious web JavaScript, compromising an unlocked browser, or compromising an Android device can read plaintext. Do not imply a universal guarantee against leaks.

### Recovery code
- At initial setup generate a CSPRNG **at least 256-bit** recovery secret formatted in a human-transcribable checked encoding. Display it **once**; require confirmation that it has been saved. This is a recovery credential, not the everyday login password.
- Encrypt a separate `K_vault` recovery envelope under a domain-separated key from the high-entropy recovery secret; store envelope on Supabase. No recovery plaintext/server escrow.
- Recovery requires proving ownership of the Supabase identity (e.g. a verified recovery email session) **and** supplying the recovery code, unless another already-authorized Android device still has the key. Recovery code by itself must not bypass account authorization.
- Recovery flow: verify email identity and recover authenticated session; decrypt recovery envelope locally; set new user password; derive new auth secret and wrapping key; update Supabase Auth password and vault envelope with a retryable, staged transition; retain an independently recoverable envelope until completion. Show a replacement recovery code if rotating the old one; never invalidate all available recovery mechanisms during an interrupted reset.
- A user who still possesses Android local data can always disable sync or provision a fresh account/vault and re-upload after an explicit, reviewed reset. If old email is lost, cloud recovery may be impossible; existing phone notes remain locally accessible.
- No generic Supabase reset-password screen that sets a raw user-entered password directly: it would break the derived-auth login scheme. Own the reset UI and test every transition and failure state. Document that authentication/reset delivery may require approved email and production SMTP configuration.
- Review the novel *composition* of password-based derivation + provider auth + recovery carefully with an independent security review before real-data deployment; use audited implementations of primitives, do not invent cryptographic primitives.

## 5. Supabase provisioning and access control

1. Create a **development Supabase project** now; production project only after security/testing review. Add migration directories, environment sample, and deployment instructions to repository.
2. Only one preapproved email may claim a vault. Prefer invite/provisioning from the Supabase Dashboard while public signup is disabled; do not let the first anonymous internet registrant become owner. Use website email-link onboarding to create the password and encrypted notebook, then ordinary email/password sign-in on Android. Do not embed Supabase admin secrets in shipped clients.
3. Disable self-service public signups and anonymous sign-in, require confirmed email, configure allowed origins/redirect URLs, abuse controls, and (recommended) Supabase MFA once configured. Validate email delivery and secure recovery flows, including provider SMTP restrictions.
4. Use `sb_publishable_...` on Android/browser, never `sb_secret_...` or legacy `service_role`. Keep admin credentials only in trusted admin tooling or dashboard. Read environment values at build/configuration and avoid hard-coded private account data.
5. All exposed tables must have explicit least-privilege grants and RLS, with `owner_uid = auth.uid()` plus membership in the approved singleton account. Do not rely on RLS policy alone if grants or executable RPCs permit access. Audit `SECURITY DEFINER` functions, fixed `search_path`, and SQL injection surfaces.
6. Test operations as anonymous, correct owner, and a different authenticated Supabase user. Verify read, insert, update, delete, RPC, storage APIs, and vault key envelopes are all denied to non-owner.
7. Minimal public bootstrap config may be readable without auth, containing only KDF salt, parameters, schema version, and public endpoints; no user email or vault record contents. No public storage bucket containing plaintext or ciphertext without owner-bound authorization.

## 6. Data model and server schema (proposed, refine in audit)

Use stable random UUIDs, not file path, title, or timestamp as record IDs. Suggested logical schema:

**`public_bootstrap`** (single static/read-only public row): `protocol_version`, `kdf_salt`, `kdf_params`, `deployment_id`.

**`vaults`**: `owner_uid`, `key_version`, encrypted password-wrapped vault key, encrypted recovery-wrapped vault key, format versions, timestamps. No plain key material.

**`records`**: `id`, `owner_uid`, `revision`, `server_change_seq`, `ciphertext_envelope`, `deleted` tombstone, `created_at_server`, `updated_at_server`. Keep title, content, flags, completion, journal date/title/subtype, and other semantic metadata *inside* ciphertext. Whether a cleartext `kind` is necessary should be explicitly justified; preferentially encrypt it.

**`record_history`**: `owner_uid`, `record_id`, `revision`, `ciphertext_envelope`, `changed_at`, `op_id`, `device_id`. At least the latest 20 revisions per record; configurable historical retention and space quota. Restore creates a **new** revision, never edits history.

**`sync_change_feed`**: `owner_uid`, `seq`, `record_id`, `revision`, `op_id`, `deleted`, `created_at`. `seq` must represent **commit-visible order** reliably. A naive Postgres sequence allocated before commit can race and skip invisible rows; implement per-owner serialization of commits/change counter or another proven ordered cursor strategy.

**`client_devices`**: `owner_uid`, `device_id`, `friendly_name`, `device_public_key` (if per-device proof-of-possession is used), `last_seen`, `last_ack_seq`, `revoked_at`, local protocol version metadata. **Security requirement:** a client-supplied device ID alone is not authentication; merely setting `revoked_at` cannot revoke an otherwise valid Supabase session. Provide enforceable per-device revocation using a reviewed device-bound signing credential/proof of possession enforced by **every** record/revision read and mutation endpoint, or explicitly omit the per-device revoke control from v1 and provide only account-wide session revocation/password rotation. Do not ship a misleading nonfunctional revoke button. Re-enrollment after revocation requires fresh login and appropriate MFA. Session/JWT expiry windows and Supabase token revocation limitations must be documented.

**`operations`**: unique `(owner_uid, op_id)` idempotency key, affected record, outcome/revision, device. Conditional write and history/feed insertion must be in one DB transaction.

Do not permit unrestricted client updates that bypass conditional revision checking. Expose a minimal transactional API such as:

- `pull_changes(after_cursor, limit)` returns a stable page of encrypted change events and next cursor.
- `commit_operation(op_id, device_id, record_id, expected_revision, envelope, deleted)` atomically validates owner and current revision, appends new revision/feed event, and returns committed revision or explicit `CONFLICT` with current encrypted envelope.
- `get_history(record_id, page)` and `get_device_status()` owner-only.
- `ack_cursor(device_id, cursor)` owner-only.

Bound batch sizes and payload sizes. Never allow client-supplied owner IDs to override authenticated owner. Browser and Android share protocol version and envelope parser.

**Retention:** tombstones cannot be garbage collected merely because 30 days passed if an old device could reconnect and resurrect deleted items. Retain tombstone identity or a provably safe acknowledged/compacted representation, and provide a full resnapshot protocol for far-behind clients.

## 7. Local data representation and mapping

- Generic `Document` sync payload: `{id, kind: note|journal, subtype: standard|double_x_day, title, body, created_at, modified_at, journal_date, existing_template_fields, existing_metadata, ...}`. Use the current on-disk schema as authority; this is a proposed **canonical transport model**, not permission to reformat local files.
- Generic `Task` sync payload: `{id, text/title, flags, completion state, completion timestamp, created/modified metadata, stable sort/order/group metadata, legacy flags, ...}` following actual repo structure.
- Stable sidecar ID mapping, if needed, keyed to existing file/document identity; never identify by title alone.
- Do not accidentally resurface Journal entries in Notes, change Double X prompts/templates, reflag legacy items, move completed tasks, or change sort after a pull.
- For direct file edits outside the app (if currently supported), detect via low-priority background reconciliation when sync enabled; never perform complete scan on normal startup.
- Preserve existing export formats, Unicode, multiline text, long notes, and date/time behavior with integration tests.

## 8. Initial upload and lifecycle

1. User enables sync in Android settings, authenticates/provisions account and locally creates vault; at this stage nothing from Notes/Journal/To-Do has been uploaded.
2. Display preview: task count, note count, journal count, estimated bytes, cloud destination account, reminder that data will be encrypted before leaving phone. Require affirmative confirmation.
3. Take verified consistent local snapshot/backup before migration. Assign stable IDs and create encrypted bounded upload batches; persist progress and resume tokens.
4. If remote vault contains data, do **not** treat phone as a blank-master nor silently overwrite remote. Detect identity/session mismatch and require explicit safe merge/recovery strategy.
5. Commit upload atomically per record with idempotent op IDs; mark initial sync complete only when remote acknowledgments and local revision map have been durably saved.
6. Client then runs ordinary bidirectional sync. New web items arrive via incremental pulls and appear in existing Android sections following established ordering.
7. Disabling sync: cancel periodic/queued sync work, drop session/unwrapped cloud keys if appropriate, preserve all phone files and existing metadata. Cloud copy remains unless user explicitly chooses `Delete remote data` with strong confirmation; no automatic deletion.
8. Re-enabling previously configured sync validates vault identity and device epoch. Never accept an unexpectedly empty/replaced server as authoritative.

## 9. Sync algorithm, conflict policy, and recovery

**Android local-first event path:** local write → existing file and metadata index persisted → durable outbox entry → opportunistic background upload. Never wait on network to display locally committed data. Ack outbox entry only after server response is persisted.

**Pull path:** background worker fetches bounded change pages after durable cursor → validates envelope/AAD, decrypts → compares local base and pending edits → applies via existing safe file write API → updates metadata index and cursor durably → refreshes UI as ordinary local data change. If any step fails, retain cursor and local data for retry. Avoid duplicate replay with op IDs and revision map.

**Task conflicts:** use base/local/remote three-way merge at field level where disjoint. Completion/reopening must be explicit revisioned fields/events; preserve user intent, especially the confirmation gesture. Same-field concurrent changes are retained as a conflict with choices; never unconditionally use timestamps as truth.

**Note/Journal conflicts:** whole-document optimistic CAS; if both edited same base, do not silently merge. Keep complete local and remote encrypted revisions, display a conflict copy/compare interface allowing Keep Phone / Keep Web / Save Both. A chosen resolution creates a new revision, never erases historical evidence. No collaborative CRDT or OT in v1.

**Delete:** use tombstone with revision, not physical row disappearance. `Trash` means deliberately deleted, separate from `Previous versions`. A restore creates a new revision. Completed tasks are not deleted tasks.

**Server restore/rollback:** client records last accepted `deployment_id`, server epoch and high-water cursor. If current server appears older, reset, changed identity, or missing formerly acknowledged records, **freeze destructive reconciliation**; retain all phone files and pending operations; show `Cloud state changed — repair required`. Provide a guided, explicit re-baseline from current phone after a verified backup, never automatic reupload/wipe.

**Device reinstall/old offline device:** new device must not announce deletions because its local store is empty. Old devices that missed tombstones must request full snapshot or pass a server epoch validation before replay. Disable stale writes if base no longer exists; preserve content as conflicts.

**Backups:** sync is not backup. Provide encrypted versioned server exports (including ciphertext, encrypted vault envelopes, bootstrap salt/config, history, and tombstones), independently stored and test-restored. Local Android exports remain independent and readable with existing app. Restrict export access and never export plaintext cloud documents unless user explicitly asks from an unlocked client.

## 10. Android background synchronization and performance

- Main-thread JNotes startup always follows existing metadata-index path. **No blocking network, KDF, bulk sync, or full filesystem scan before first frame.** Sync work only enqueued after local UI is ready and only when enabled.
- Use Jetpack WorkManager `CoroutineWorker` or equivalent for resilient deferrable work with network constraints, bounded execution time, retries with exponential backoff, and unique work IDs preventing duplicate workers. Use periodic work around 15–30 minutes plus event-driven one-off work after local changes / foreground entry (nonblocking). These are best-effort schedules, not exact synchronization guarantees.
- Keep upload/download batches limited by byte count and record count; stream/decrypt in worker thread, not UI thread. Avoid large memory copies of full note library. Yield promptly to foreground workloads.
- Filesystem writes use existing app's access: app-private storage or persisted Storage Access Framework URI grants. Do not add broad `MANAGE_EXTERNAL_STORAGE` just for sync. If a URI is revoked, pause affected sync and ask user to relink only if needed, while leaving unaffected local functions usable.
- Sync queue may have locks or local snapshot records for file writes; tests must prove metadata index and files remain consistent across crashes.
- With sync disabled, assert **zero sync network requests and zero active scheduled sync work** and no measurable performance regression. With sync enabled, measure p50/p95 launch, tab change, editor return, foreground typing, and batch worker memory/CPU using representative dataset (e.g., 1,000 tasks + 500 notes/journal entries). Target no more than ~10% change in median startup/tab timing under comparable test conditions, with no visible frame stalls. Do not force huge downloads on boot.

## 11. Web UI and UX

- Authentication page: email + password; generic incorrect-credentials message; separate, well-documented recovery flow. Never reveal stored password.
- Main UI: familiar theme, Notes, Journal, To-Do, Settings; responsive desktop sidebar/top tabs depending viewport. EPUB absent or marked Android-only; do not show a broken functional tab.
- Notes + Journal: shared editor and autosave/debounce with explicit server-save indication. When a write fails, keep unsent editor contents in memory and offer Retry / Copy / Download instead of losing changes; alert before navigating away. No persistent browser offline edit promise.
- Journal creation uses existing title/date conventions. Double X Day subtype/template displayed and editable. Android Double X thresholds/prompt semantics unchanged; web does not auto-trigger new daily prompts until separately specified.
- To-Do: preserve urgent/important/long-term emoji, color, selected completed styling, check/confirm behavior, completed folders, order, and edit flows. Keyboard controls should be accessible; don't implement arbitrary hidden gesture-only actions.
- Sync states: `Saved on phone`, `Pending upload`, `Synced`, `Offline`, `Conflict — action needed`, `Account recovery needed`, `Cloud rollback detected`. No modal prompts during normal startup.
- Settings: Sync toggle, account email, last successful sync, pending item count, manual Sync Now, device management/revoke, export/restore, recovery instructions. Never show plaintext cloud secret on Settings after onboarding.
- Session: HTTPS only, strict CSP, no unrelated third-party scripts, sanitized note rendering (no executable HTML), hardened dependencies, clear logout, idle timeout on shared computers, default memory-only client storage where feasible. Prevent web build cache of plaintext. A web host serving malicious JS remains in the trust model. If implementing per-device revocation, protect every fetch and write, not just the mutation RPC; direct Supabase table access must not bypass revocation.

## 12. Security, threat model, and privacy controls

**Protect against:** unauthenticated public access, unrelated Supabase accounts, leaked database backup, stolen cloud storage disk, network interception, accidental deletion, bad sync implementation, and credential loss while Android remains available.

**Not guaranteed against:** compromised unlocked phone, browser extension/malware, malicious/tampered frontend delivery, attacker who obtains both account access and encryption passphrase (or recovery code), and total loss of every local copy and recovery credential.

- Enforce TLS/HTTPS, robust security headers including strict Content Security Policy, avoid CDNs for runtime script execution where possible, reduce web-host admin attack surface with MFA on GitHub and hosting, protected branches and CI secrets.
- CSRF/session issues depend on auth-token storage model; threat-model XSS, replay, refresh token theft, and device revocation. No blind trust in JWT claims without owner checks in SQL.
- Public email/password flow needs brute-force defenses and account recovery rate limiting. Do not leak plaintext record metadata through logs, notifications, URL query strings, error traces, backups, analytics, search indexes, or database columns.
- Authenticated encryption must reject tampered payloads and wrong AAD, never silently fall back to plaintext.
- Server cannot full-text-search ciphertext. Web search is performed locally after decrypting records in an authenticated session; no plaintext search index stored in Supabase.
- Independent review of cryptography/account reset/SQL/RLS and a dependency audit required before first upload of sensitive records.

## 13. Supabase / deployment instructions to produce

Codex must create a concise operator guide:

1. Create development Supabase project, obtain URL + publishable key, install CLI, apply versioned migrations, run all RLS tests.
2. Invite/preapprove single user, disable new public signups and anonymous access, require email confirmation; set allowed redirect URLs and secure deployment domain. Configure SMTP appropriately for working invites and reset emails.
3. Deploy static web frontend to Cloudflare Pages or approved equivalent, with only public URL/publishable key/client public KDF config; no secret key in frontend environment or GitHub. Configure secure HTTP headers and routing/deep-link handling.
4. Build Android debug APK from existing source, preserving signing and package identity for later production continuity. Set development endpoint via build configuration, not by modifying core app storage paths.
5. Test with synthetic dataset. Only after review, create production Supabase project, repeat migrations and tests, deploy reviewed frontend, test auth recovery, back up local data, get explicit user confirmation, perform encrypted initial upload.
6. Provide steps to stop sync, revoke browser/device, export and restore encrypted cloud backups, rebuild project from backup, and relocate to Linux/self-hosted backend later. Separate hosting/vendor adapter from sync protocol.

## 14. Test plan and strict acceptance criteria

### Android unchanged with sync off
- Install/update on populated phone; all existing tasks, notes, journal, Double X Day, flags, completed folders, EPUB, settings, note exports, animations, and ordering are preserved.
- No cloud request, background worker, key derivation or sync file scan when disabled; startup and tab/editor speed remain comparable to baseline.
- Internet off, expired login, Supabase down, wrong password, missing Keystore key, cloud deleted: local notes and tasks still open, edit, create, export, and persist after restart.

### Account and cryptography
- One password works on phone and web across test vectors; cloud sees **derived auth secret**, not typed password; wraps vault separately; no plaintext contents in database/logs/backups.
- Wrong password cannot decrypt vault; tampered ciphertext/AAD fails closed; nonce uniqueness and key version migration tests.
- Recovery code unwraps from a fresh device only after account authorization. Forgotten password with Android present is repairable without loss of local files; password reset without key/recovery gives no false promise to unlock cloud.
- Partial reset/rewrap crash can be resumed without orphaning ciphertext; old and new credentials/key-envelopes transition safely.
- Browser account logout, shared-computer inactivity, session-expiry, unauthorized-account RLS attempts all tested.

### Sync functional
- Complete initial migration of all tasks, notes, journal after preview. Verify record counts, Unicode, long notes, metadata, content hashes/decrypted equivalence; no duplicate uploads on retry.
- Phone edit offline → later web; web edit → later phone; add/complete/reopen/delete; flags and order; journal and Double X subtype; note edits on both.
- Concurrent edits to different task fields merge; same field conflict preserved; concurrent note edits preserve both variants/history.
- Interrupted upload/pull every possible boundary, out-of-order operations, duplicate op IDs, long offline periods, old device and freshly installed device do not destroy data.
- Database rollback, empty server, swapped deployment ID, corrupt download, and inconsistent cursor cause safe sync quarantine without destroying phone files.
- Revision-history restore creates new revision; Trash restore and tombstone reuse behave correctly.

### Performance / hardening
- JNotes opens immediately from existing local index; web/network/decryption and bulk apply never block first frame.
- Batched workers remain within resource/time budgets; indexing behaves correctly after pulled updates; no duplicate scheduled work.
- SQL tests prove explicit grants + RLS deny access to anonymous and unrelated authenticated accounts, including functions and archives; CI checks migrations and static frontend security.
- No release allowed until a code-review checkpoint, a working test environment, restore drill, and user approval to migrate real data.

## 15. Deliverables and stages for Codex

**Stage A — Audit/design:** architecture report, threat model, KDF/envelope proposal with test vectors, schema and RLS plans, Android compatibility/performance baseline. Review before high-risk changes.

**Stage B — Backend/security:** SQL migrations, invite/setup flow, encrypted vault and recovery, conditional sync RPC, row-security tests, encrypted history, snapshot/export scheme.

**Stage C — Web:** accessible responsive To-Do, Notes, Journal interfaces, login, encryption, save/retry, conflict UI, integration tests.

**Stage D — Android:** opt-in settings, initial preview + encrypted import, Keystore-protected sync credentials, outbox and cursor, WorkManager bounded batches, conflict handling, no-regression instrumentation tests.

**Stage E — E2E and deployment:** staging deployment, automated fault injection, user walkthrough, security review, encrypted backup restore drill, production plan, PR + reproducible build. No automatic merge, signing, public release, or live-data upload.

**Final delivered repository should contain:** source, migrations, `README` quick start, architecture/threat/recovery docs, developer setup, example environment configuration with no secrets, test fixtures using synthetic data, test results, screenshots, reviewable PR, and a documented list of unresolved risks.

## 16. Definition of done

JNotes Android behaves essentially identically with Web Sync off. With Sync on, the initial encrypted upload of all tasks, notes and journals succeeds only after preview and consent; browser login uses the same email/password; Supabase holds no plaintext content; normal tasks/notes/journal edits work in both directions; previous versions and conflicts preserve data; phone startup stays fast; interrupted uploads, lost credentials, Supabase outages and server rollback never lock or erase existing Android notes; recovery and security tests pass; user authorizes production rollout.

## Source documentation used to ground platform choices

- Supabase Auth email/password and password update: https://supabase.com/docs/guides/auth/passwords
- Supabase account invites: https://supabase.com/docs/guides/auth/users
- Supabase Auth config: https://supabase.com/docs/guides/auth/general-configuration
- Supabase RLS and grants: https://supabase.com/docs/guides/database/postgres/row-level-security
- Supabase API key categories: https://supabase.com/docs/guides/getting-started/api-keys
- Android WorkManager: https://developer.android.com/develop/background-work/background-tasks/persistent
- Android SAF URI permissions: https://developer.android.com/training/data-storage/shared/documents-files
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- OWASP cryptographic storage: https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html