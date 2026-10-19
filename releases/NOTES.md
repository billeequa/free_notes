# JNotes 1.5.1 — website link in Settings

- Show **https://j4notes.pages.dev** directly in phone Settings, above Web Sync. Tap the address to open it in your browser.
- Includes all encrypted Web Sync features from 1.5.0:

- Optional encrypted Web Sync for To-Do, Notes and Journal, including Double X Day entries. Sync is off by default; ordinary local use still needs no account or internet.
- Set up the website connection in Settings → Web Sync. Verify your approved email, choose one password, save the recovery code, and review the initial upload preview. A verified readable phone backup is created before upload.
- Phone edits save locally first. Bounded background jobs sync encrypted changes, retain conflicting versions and detect unexpected cloud rollback. Losing account access or a server connection does not lock your local notes.
- Browser editing, encrypted revision history, Trash, recovery and portable website/backend hosting are included in the source. EPUB remains phone-only.
- Existing local text formats, selected-folder storage, package identity and release signing identity are retained.

Install **jnotes-24.apk** over the existing app; no uninstall is needed.

Web Sync is a first release. Automated Android, protocol, database, recovery and hosting checks pass; physical-device acceptance, actual phone-to-browser/email recovery testing and independent security review remain outstanding. Begin setup with sync off and review the recovery code and upload preview carefully. See `docs/web-sync/acceptance-results.md` for completed checks and remaining limits.
