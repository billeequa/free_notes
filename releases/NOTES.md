# JNotes 1.5.5 — recover from failed sync without a startup crash

- Handles WorkManager initialization and scheduling failures on their own background threads, keeping the notes interface available.
- Pauses background sync after an interrupted scheduler startup instead of repeatedly restarting it on every launch. Local edits remain queued; Settings → Web Sync → Sync now explicitly retries.
- Excludes the scheduler cache from device backups and keeps account/queue data separate from scheduler health.
- Tests the actual optimized release APK on Android 14: fresh installation, missing session, broken scheduler storage, reopening after failure, interrupted startup, and reinstall.

Install **jnotes-28.apk**. For a fresh installation, choose your **existing notes folder** to load its notes and tasks. Reconnect Web Sync with your existing JNotes email/password and review the upload preview.
