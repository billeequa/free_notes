# Operator guide — development first

## What address do I open?

The current website is **https://j4notes.pages.dev**. Normal Android setup discovers its connection automatically: open Settings → Web Sync and use your email and password. The labeled Website link in Settings shows the browser destination; no address entry is required. Read [automatic discovery and hosting moves](connection-discovery.md) before relocating a host. Explicit self-hosted connections remain under Advanced and read that host's `/config.json`. Desktop testing can use **http://localhost:8080**; physical phones require reachable HTTPS. GitHub stores the source and public connection directory, rather than the notebook login page.

## 1. Development Supabase

Create an isolated development project in Supabase (or run the official CLI/Docker local development stack with `supabase start`). Do not point migrations at an existing unrelated production database. The checked-in `supabase/config.toml` disables public signup/anonymous auth and uses a short JWT lifetime. For managed/self-hosted production, set the equivalent provider options explicitly; local CLI configuration does not configure a managed project.

Apply all SQL files in `supabase/migrations/` in version order through versioned migrations. Run `npm --prefix web ci`, `npm --prefix web test`, `npm --prefix web run test:sql`, `node --test server/server.test.mjs`, and `npm --prefix web run build`. The SQL suite uses a real embedded PostgreSQL engine and a minimal mock auth schema; it is not a replacement for testing your actual GoTrue gateway, SMTP and auth schema. Run the owner/other-user/anonymous access checks on the development deployment too.

Obtain the backend HTTPS URL and `sb_publishable_...` key. A self-hosted stack may use its legacy **anon** JWT; clients validate that role. Never ship `service_role`, `sb_secret_...`, database passwords or admin API tokens. No such value belongs in GitHub, APKs, frontend assets or logs.

## 2. Provision exactly one approved user

In the Supabase admin dashboard invite the specific approved email; public self-registration stays off. After the auth user exists, insert its exact UUID once into the private singleton account table, using administrator SQL:

```sql
insert into jnotes_private.owner_account(owner_uid)
values ('THE-INVITED-AUTH-USER-UUID');
```

Do not expose this insert to clients or permit a first-registrant claim. Require confirmed email. Configure your exact HTTPS site URL/allowed redirect URLs, SMTP provider, delivery/rate limits and account recovery controls. Configure the Magic Link/OTP email template to show `{{ .Token }}` as a transcribable verification code; the apps use OTP for email ownership and never ask the provider to store a raw human password. Verify delivery to the approved email and use development mail capture for synthetic accounts. Follow the invite verification link once if the account is initially unconfirmed, then use the emailed OTP in Android. Avoid generic password-reset pages that store a raw password: they break the derived-auth scheme. MFA can be enabled only after its challenge flow is implemented/reviewed; v1 does not pretend to support an unimplemented MFA UI.

Each protected RPC checks owner membership, confirmed email, and a live auth.sessions row. Keep PostgREST's exposed schema list to `public`; `jnotes_private` must not be exposed. Audit grants, executable functions and search paths. Do not grant direct data-table access or create a public record-storage bucket.

## 3. Host the frontend

Use [Cloudflare Pages setup](cloudflare-pages.md) for a free HTTPS website address or [server/README.md](../../server/README.md) to run your own Node/Docker host. Configure public SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY through Pages build variables or the Node host's runtime environment. HTTPS is mandatory outside loopback development. For your own server, bind the container to localhost and reverse-proxy it on your chosen domain. Restrict domain/DNS/GitHub/host admin access with MFA. No third-party runtime scripts or analytics should be added. Protect the build chain: a malicious frontend can access plaintext after login even when the database is encrypted.

For a fully self-hosted backend use the official [Supabase Docker guide](https://supabase.com/docs/guides/self-hosting/docker); preserve its reviewed Auth/PostgREST routing and database roles. Apply the same migrations and singleton provisioning. Do not replace the backend with a static JSON-file server: its transactional revision and authorization semantics matter. Only public endpoint/config changes are needed when moving the static frontend.

## 4. Android debug build and onboarding

Build with existing JDK/SDK requirements:

```sh
./gradlew testDebugUnitTest assembleDebug
```

No signing material or package identity is changed. A debug APK is for an isolated test install; it cannot update your distributed APK if signed with a different certificate. Production signing/publication is a separate authorized rollout after review.

On the phone: Settings → Web Sync → Set up → enter your actual web HTTPS address, approved email and one password → Send verification code → enter emailed code → Verify and preview. Save the displayed recovery code and type its final eight characters to confirm. Review all task/note/journal counts, Trash, approximate text size, account and existing cloud revision count. Confirm upload/merge. Only then does the app create and verify a consistent readable local ZIP, cache Keystore-wrapped cloud credentials, build the durable outbox and schedule bounded WorkManager jobs. EPUB is not uploaded. No network/KDF/bulk scan is added to the normal disabled-sync startup path.

In the browser, go to that same web address and enter the same email/password once. To-Do, Notes and Journal are editable; EPUB remains Android-only. Content is decrypted in memory. Saves turn Synced only after server acknowledgment. If a save fails, keep the tab open and Retry/Copy/Download; closing it can lose unsent text. Browser inactivity locks the session after 15 minutes. Defaults do not persist tokens/plaintext across browser restarts.

## 5. Conflict/rollback/disabled behavior

Phone edits save locally first. Workers use network constraints and bounded batches (20 operations / about 2 MiB / a short time budget). Reconciliation reads document metadata outside the storage lock, processes up to 50 changed files or ten seconds at a time, and persists a resume cursor. Each file uses a short shared lock; unchanged size/mtime entries are periodically revalidated. Foreground task hooks enqueue only changed tasks. Successful partial batches schedule an immediate continuation; actual transient failures retry with exponential backoff. WorkManager periodic scheduling is best effort, not an exact 15-minute guarantee. Changing the selected notes folder pauses sync and requires relinking the original folder, preventing a new folder's contents from silently becoming the old vault.

Task changes merge by disjoint field; same-field changes and concurrent note/journal changes are retained as conflicts. Keep Phone/Keep Web/Save Both are explicit. A discarded local variant is retained as a recoverable conflict copy in Trash after upload. Server history is immutable and keeps every revision in v1; restoring appends a new revision. Administrator-configurable `owner_account.max_history_bytes` (default 1 GiB) and `max_records` (100,000) bound storage growth; quota failures keep phone edits pending/browser drafts unsaved. Tombstones are not time-expired.

Disable sync cancels jobs and keeps all phone files. Lost auth/keys/server state never gates local editing. Cloud identity/epoch/high-water rollback freezes sync. After investigating the server and backing up, the phone's explicit REPAIR action re-baselines metadata while retaining divergent content as conflicts; it does not overwrite local files. A changed vault key/owner requires a separately reviewed new-vault reset, not a silent repair.

## 6. Backups and moving hosts

Browser Settings → Export encrypted cloud backup exports wrapped vault keys, bootstrap configuration and every ciphertext/history/tombstone through an accepted high-water snapshot. This is ciphertext, not a plaintext export. Keep this outside the server. For infrastructure recovery also preserve Supabase Auth/configuration, the original owner auth UUID, SMTP configuration and private server secrets through your own secret manager.

`server/export-backup.sh` creates a PostgreSQL custom archive of the public/private application and Auth schemas using PG environment variables. Do not put database secrets in command arguments. Test restore into an empty development stack with matching provider roles/extensions. The generic pg_restore helper expects an empty target; a standard Supabase stack already has Auth tables, so for provider-auth disaster recovery follow Supabase's supported restore procedure, not blind restore over existing Auth tables.

The browser JSON export can be restored into an empty migrated development database with `node server/restore-encrypted-export.mjs archive.json --empty-development-target`, using psql/PG environment variables. Restore the matching confirmed auth user UUID first. The helper refuses non-empty application tables or a different owner, verifies the revision chain, preserves original bootstrap/ciphertext/wrapped keys, changes the cloud epoch and invalidates sessions. A synthetic round-trip is exercised in the SQL suite. Clients must reauthorize and explicitly repair after restore.

Never regenerate the public KDF salt to fix a login issue. Never rename the original auth UUID without unlocking and re-encrypting through a reviewed client migration; UUID/deployment are authenticated in AAD. Moving an intact database to Linux/self-hosting must retain those values. Update the public service directory and website configuration after a verified migration. A changed backend origin requires reconnecting with email/password; the phone discovers the address automatically and preserves its pending edits. See the [migration procedure](connection-discovery.md).

## 7. Before sensitive-data rollout

Review the cryptography/account-reset composition and SQL independently. Verify production email/OTP delivery and browser/session revocation. Perform populated-phone offline/server-down/expired-auth/missing-key tests, sync crash/fault tests and the real restore drill. Record baseline and enabled-sync p50/p95 launch/tab/editor timings on a representative dataset (1,000 tasks, 500 documents), typing smoothness and worker memory/CPU. No more than about 10% median timing change is the target; do not invent measurements from code inspection. Then obtain approval for production migration and encrypted initial upload through the actual phone preview.

Official references: [Supabase passwords](https://supabase.com/docs/guides/auth/passwords), [RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [API keys](https://supabase.com/docs/guides/getting-started/api-keys), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
