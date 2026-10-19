# JNotes wire protocol v1

`protocol/vectors.json` is synthetic, public interoperability evidence, not a credential. Both TS and Kotlin tests must match it before any real content is synchronized.

## Password and key separation

The public singleton bootstrap contains protocol 1, a persistent random 16-byte salt (standard padded Base64), deployment UUID, Argon2id 1.3 memory 65536 KiB, iterations 3, lanes 1, output 32 bytes. Benchmark these costs on supported phones/browsers before rollout. V1 refuses parameter changes rather than silently weakening or changing the derivation. Preserve salt and deployment ID with every backup.

Password is exact UTF-8, no trimming or Unicode normalization; email is sent as the account identifier and is not used in the KDF. Client computes Argon2id(password,salt), then HKDF-SHA-256 with 32 zero salt bytes, UTF-8 info `jnotes.v1/auth` or `jnotes.v1/wrap`, 32-byte output. Only standard Base64(auth output) is sent to GoTrue/Supabase Auth as its opaque password. Intermediate and wrapping material never go to the provider. Android uses Bouncy Castle, TS uses noble hashes. Their composition still requires independent review.

The independent vault key is 32 random bytes, generated on Android. `key_id` is an immutable random UUID for that vault generation. AES-256-GCM uses a fresh CSPRNG 12-byte nonce for each newly prepared operation; the tag is 16 bytes appended to ciphertext. An ambiguous network retry reuses its exact immutable operation ID/envelope, not a fresh operation. Never use a counter that could roll back as the GCM nonce.

Envelope JSON: `protocol_version:1`, `key_id`, `algorithm:"A256GCM"`, `nonce`, `ciphertext_with_tag`. Binary fields use canonical padded Base64. AAD is reconstructed from authenticated routing context rather than trusting an AAD blob supplied inside ciphertext. UTF-8 compact JSON arrays, in this exact order:

- Record: `["jnotes.record",1,deployment_id,owner_uid,key_id,record_id,revision,deleted]`
- Vault: `["jnotes.vault",1,deployment_id,owner_uid,key_id,"password",0,false]`
- Recovery: same vault array with `"recovery"` instead of `"password"`.

UUIDs are canonical lower-case strings. Revisions/cursors stay within JavaScript's safe integer range; deployments must rotate protocol well before 2^53 operations. Payloads are UTF-8 JSON. All semantic metadata including kind, flags, title, text, dates, ordering and Double X markers is encrypted. `Payload` is defined in `protocol/types.ts`; Kotlin conversion lives in `SyncPayload`. Canonical comparison sorts JSON keys, normalizes flags/timestamps, and ignores a task's synthetic `modified_at` when comparing against the Android task format, which has no such on-disk field. Notes retain the existing serializer's second-resolution local timestamps; cloud timestamps are UTC instants, converted to local time before Android serialization. Task ordering follows its existing `addedAt` chronology. The protocol never rewrites existing files merely to assign IDs.

## Recovery

Recovery secret: 32 CSPRNG bytes, uppercase hex, followed by the first four SHA-256 digest bytes as checksum, grouped in eight-character blocks with `JN1-` prefix. Hyphens/case are presentation only; checksum must verify. Recovery wrapping key is HKDF with info `jnotes.v1/recovery`. The code is displayed during setup and never saved in plaintext by the app. Reconnection can stage a replacement code; server replacement occurs only after the user confirms saving it. The old code remains valid until then.

Recovery first requires an authenticated verified email session. Password change is: stage a new password-wrapped envelope with transition UUID → update Auth with the derived auth secret → finalize the staged envelope. The independent recovery envelope is retained throughout. Login tries the active envelope, then staged; successful staged unlock completes transition. After interruption, repeat authorized recovery with the recovery code or an already authorized phone's Keystore-wrapped key. Never pass the raw user password into a generic provider reset page.

## Server ordering and CAS

`commit_operation` locks the singleton owner's row until transaction commit, checks epoch/high-water floor, validates idempotency, performs expected-revision CAS, inserts record/history, and increments a transactional counter. This prevents the allocated-before-commit sequence race. `get_changes` returns immutable revision bodies ordered by that counter, bounded to 100 records / approximately 3 MB. Clients require contiguous sequences and only advance a durable Android cursor after content is applied or complete conflict evidence is persisted. Pulling one's own acknowledged write never skips another device's earlier events.

Direct record/vault/history/device reads and writes are revoked; exact RPC grants and RLS are both tested. Every private-data RPC validates approved owner, confirmed email, and a live `auth.sessions` row matching the JWT session ID. Device UUIDs are diagnostics, not credentials. V1 intentionally offers account-wide session revocation rather than an unenforced per-device control. No tombstone/history garbage collection in v1; retention is all revisions. The owner row has an administrator-configurable history byte quota (default 1 GiB) and record quota (default 100,000). Exceeding quota rejects new writes without discarding local drafts. A reviewed compaction protocol is required before reducing history or tombstone retention.

Record limit is approximately 2 MiB plaintext / 2.8 MB envelope. Oversized phone content stays locally readable and pending; do not split or truncate it silently. Cloud bulk responses and worker batches are bounded. SQL restores rotate the epoch and revoke sessions. A cloud identity/epoch change or a high-water rollback freezes Android sync and requires a verified backup and explicit repair.
