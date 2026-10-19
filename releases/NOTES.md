# JNotes 1.5.4 — Android sync startup fix

- Fix background sync failing with `androidx.work.impl.WorkDatabase_Impl.<init> []` when enabling sync. The optimized release now preserves the database and worker constructors Android loads at runtime.
- Failed startup leaves sync setup ready to retry and keeps the verified phone backup, saved credentials and pending edits.
- Background scheduler failures no longer interrupt opening the app or disabling sync.
- Release builds and publication now check these constructors inside the actual optimized APK.

Install **jnotes-27.apk** over your existing app; no uninstall, account reset or new recovery code is needed. If sync was already enabled, open the app and choose **Settings → Web Sync → Sync now**. Otherwise finish the existing sign-in and upload preview.
