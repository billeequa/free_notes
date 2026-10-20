# Personal and Work

Enable Work mode in Android Settings. The 𝒲 button beside Settings on Notes enters Work; 𝒫 returns to Personal. Work offers only Notes and To Do. Double X Day remains on personal Journal. The selection survives restart; disabling the feature returns to Personal without deleting anything.

Personal files remain directly in the selected notes folder. Work notes and `to_do_jnotes.txt` live in `Work/`; trash and exports are in `Work/Trash/` and `Work/Exports/`. Both scopes use the existing readable formats, independent metadata indexes and fixed repository/ViewModel instances. Queued edits retain their original scope across switches. Work cannot move notes into a hidden journal.

The personal website is `/`; Work is `/work` (and `/work/`). Both use the same email/password. Enable the mode button in website Settings; the direct Work address always works. Only the active scope's records, trash, conflicts, history, search and counts load. Switching first saves the draft, refuses unresolved operations, locks the current notebook and navigates to a fresh sign-in.

The Cloudflare Pages project `j4notes` must use `main` as its production branch, with automatic production deployments enabled. Branch preview URLs do not update `j4notes.pages.dev`. After changing the production branch, deploy a new `main` commit and verify `/work` on the production address.

## Cloud boundaries

The existing Personal wire protocol, vault and tables are unchanged. Work uses `work_records`, `work_record_history`, `work_client_devices`, `jnotes_private.work_owner_account` and `work_*` RPCs. They retain the existing confirmed-owner and live-session checks, private-table grants, RLS, immutable history, CAS, quotas and serialized cursors. Epochs, sequence counters and operation IDs are independent. Apply migration `20261020122057_jnotes_work_workspace.sql` before deploying clients.

The account master key and password/recovery envelopes remain shared for one-password recovery. Work record encryption uses HKDF-SHA256(master, zero salt, `jnotes.v1/workspace/work`, 32). Its record AAD uses the deployment identifier suffixed with `/work`. After unlocking, the Work browser immediately wipes its temporary master-key byte buffer and retains the Work key only. No personal record/feed/history RPC is requested. Browser sessions/keys are memory-only; the mode preference and theme are the only persistent UI settings.

This is a data-loading and key-retention boundary, not a separate account or server authorization principal. Someone who knows the JNotes password can intentionally sign into Personal. Both routes also share a web origin; a separate work hostname/account would be needed for stronger browser or credential isolation. Do not claim that a work computer cannot capture a password typed into it.

Android keeps the existing Keystore-wrapped account secret and a second private sidecar `web-sync-work.db`. A single scheduled worker processes Personal and enabled/previously-linked Work in their own scopes. No Work database/folder/network request is added while Work has never been enabled. Disabling all sync keeps both sets of local files available. A verified scoped backup precedes the initial Work upload.

Cloud exports include `workspace` (`personal` or `work`); old exports default to Personal. Work restore requires the matching existing Personal bootstrap and key ID, refuses a nonempty Work target, and preserves the Personal feed/vault/epoch.
