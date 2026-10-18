# Threat model and review boundary

The phone's existing readable files remain its primary local copy. Sync authentication, decryption, key loss, outages and cloud rollback cannot become prerequisites for opening or saving them. Sidecar failures are caught after a verified local save. Neither disconnect nor account-wide logout deletes local content.

Cloud storage receives ciphertext plus UUIDs, revision numbers, tombstones, approximate sizes, timing and diagnostic device IDs. It receives a domain-separated derived auth credential, never the typed password or vault wrapping key. A stolen database, disk or export does not by itself reveal document content. An attacker can still make offline guesses at a weak user password, so password quality and KDF benchmarking matter.

The system does not protect plaintext from a compromised unlocked phone, malicious browser extensions, an attacker controlling the frontend delivery/build, or an attacker with both account access and a usable decryption credential. CSP, strict grants, HTTPS, no third-party runtime scripts, no analytics and memory-only web sessions reduce exposure; they cannot defeat a malicious web host serving changed JavaScript. Do not promise otherwise.

Android session/key state is wrapped by Android Keystore and stored under `noBackupFilesDir`. Sync sidecar/preferences are excluded from both cloud backup and device transfer. Local files remain readable independently. An Android update must preserve package/signing identity; a new debug certificate cannot update the distributed app. Never alter the distribution keystore or publish an unsigned/debug APK as an update.

The browser stores neither documents nor tokens in localStorage/IndexedDB and installs no service worker. Idle locking clears auth/key state. An unsaved draft can remain in volatile memory behind the login screen until the same tab is reauthorized; closing/reloading the tab can lose it. Failed server saves remain visibly unsaved, with retry/copy/download controls and navigation protection. This is online editing, not a browser offline notebook.

Independent security review remains required before sensitive-data rollout, especially KDF/auth composition, recovery transitions, backup restore, grants/RLS, live session checks and Android filesystem failure boundaries. CI is evidence of specified properties, not an independent audit. Never use real user notes as test fixtures.
