# Web Sync v1 — development acceptance results

Source: `feat/encrypted-web-sync-v1`, PR #20. Checks completed on 2026-10-19 UTC. This is a development implementation and review checkpoint, not a production-readiness certification.

| Check | Result |
| --- | --- |
| Android `testDebugUnitTest` | 81 passed, zero failures/errors/skips, including existing regressions and new sync tests |
| Android `assembleDebug` | Passed with JDK 17, SDK 36, Gradle 9.3.1; signing/package identity unchanged |
| TypeScript/protocol/client tests | 10 passed: cross-platform full-cost KDF/AES vectors, tamper/wrong-key/AAD checks, recovery encoding, nonce sampling, merge/conflict, rollback, ambiguous-commit retry |
| PostgreSQL suite | Passed in PGlite's PostgreSQL engine: grants, RLS, singleton/session authorization, revision CAS, replay idempotency, immutable cursor pages, 26 revisions, tombstones, staged password recovery, partial-restore detection |
| Encrypted export restore | Synthetic round-trip passed: exact ciphertext/history, original bootstrap, new epoch, revoked sessions |
| Portable HTTP/Pages host tests | 3 passed: public-key validation, path/method handling, public config generation and security headers |
| Production frontend and Pages builds | Passed; Pages generates `config.json` and `_headers` |
| npm dependency audit | Zero vulnerabilities |
| Browser interaction/visual QA | Pending; not executed in this environment |
| Physical-device timings and populated phone fault drills | Pending; no physical phone measurements claimed |
| Independent security review | Pending, especially credential derivation/provider recovery composition |
| Real SMTP/Auth gateway/Android↔browser end-to-end | Pending approved owner email, SMTP configuration, reviewed Android install and live frontend |

## Connected development backend

With explicit user approval, migrations were applied to the previously empty Supabase project `jnotes` (`aqamrwetutembtrxrfso`). Local migration version filenames match the versions recorded by the managed service. No new paid project was created. No personal content was uploaded. The singleton owner is deliberately unprovisioned until the exact approved login email is supplied; no first-registrant claim exists.

Live SQL checks confirm: one public bootstrap, zero owners/records/history, RLS on all six JNotes tables, no direct record reads for anon/authenticated, no direct membership reads for authenticated, and no anonymous `get_vault` execution. Live anonymous HTTPS gateway checks also passed: public bootstrap returns 200; direct record reads and protected vault RPC return 401. Vault state checks share the owner-row lock with commits, preventing a concurrent commit from causing a false partial-restore alarm.

The Supabase security advisor reports the nine intentional authenticated SECURITY DEFINER RPCs. These fixed-search-path functions enforce approved owner + confirmed email + live session before data access and keep direct tables unavailable. Their composition still needs independent review. See the [advisor's explanation](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable). It also notes [RLS without a policy](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy) on the private membership table; this is intentional default-deny, with no client grants. No public membership policy should be added.

## Behaviors covered on Android

Crypto uses the same golden vectors as TypeScript. SQLite/outbox tests cover lost-ack replay and newer local edits, conflict persistence, cursor state and queue identity. SAF fault tests cover interrupted rename and pull, local CAS protecting newer writing, open-editor pull deferral, Unicode recovery and verified readable backup, cloud restore after permanent local Trash cleanup, and resumable reconciliation with stable IDs across batches.

Reconciliation is bounded to 50 files/ten seconds per pass with a persisted cursor; outbox batches are bounded by operation count and approximate payload bytes. Normal completed batches continue without failure backoff. Disabled sync returns before any cloud scan/key derivation/network operation. These code paths and unit checks do not replace real-device launch/tab/editor p50/p95 and typing measurements.

## Release boundary

No signed APK release has been published. No frontend address has been verified live. Cloudflare GitHub access has been connected by the user; Pages project/build/environment configuration still needs verification. Follow [the live-address guide](cloudflare-pages.md) and [operator checklist](operator-guide.md) for the remaining setup and review. Existing Android local files remain authoritative and upload requires the actual phone's verified-backup/counts preview and explicit confirmation.

## 1.5.3 authentication repair

The browser now handles Supabase invitation, magic-link and recovery callbacks, removes their tokens from the URL, validates the live Auth user and notebook owner, and offers initial encrypted-notebook/password creation. Normal phone/browser sign-in uses the same derived authentication credential and never sends email or rotates recovery codes. Website recovery requires verified email and the saved recovery code. Numeric OTP onboarding was removed.

Local HTTP gateway tests exercise the real Supabase client, full-cost KDF, email-link requests, first-time setup, normal login, wrong passwords, recovery, lost create acknowledgments, callback cleanup, owner denial and missing-key/history refusal. Android HTTP tests cover derived-password sign-in, missing setup, wrong credentials and preserved deployment/owner/key checks. Existing database and hosting suites remain required. Live Cloudflare deployment and signed release are verified separately; the owner's password entry and mailbox remain private and are not automated.
