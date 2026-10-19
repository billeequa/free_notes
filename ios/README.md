# JNotes for iOS — first native version

Open **`ios/JNotes.xcodeproj`** in Xcode. This is a native Swift/SwiftUI/UIKit implementation alongside the Android app; the Android project and deployed website are unchanged.

The app targets iOS/iPadOS **17.0+**, uses Swift 5 language mode, and is intended for **Xcode 16 or newer** on a supported Mac. The repository includes a shared scheme, application icon, Info.plist, privacy manifest, vendored Argon2 C implementation, pinned ZIPFoundation dependency and compatibility/persistence tests. No project generator, Homebrew, CocoaPods, Android SDK or backend admin credentials are needed to open and build it.

## Build on your Mac

1. Clone this repository, or pull the latest source. Open `ios/JNotes.xcodeproj` (not the Android project).
2. Let Xcode resolve **ZIPFoundation 0.9.19**. Its exact revision is committed in `Package.resolved`.
3. Choose the **JNotes** scheme and an installed iPhone Simulator, then **Product → Run**. Simulator builds need no paid account or signing team.
4. Run **Product → Test** (⌘U) to check Android/browser encryption interoperability, file round trips, task merge behavior, local saving, export/import and corruption handling.
5. To install on a real iPhone, select the **JNotes target → Signing & Capabilities**, enable automatic signing, and choose your Apple team. If Apple says the bundle identifier is unavailable, change it from `com.billeequa.jnotes.ios` to a unique reverse-DNS identifier. The background refresh identifier follows that setting automatically.
6. Select your connected iPhone, enable Developer Mode on the device if requested, then Run. Resolve signing prompts locally in Xcode. Do not commit personal signing changes, certificates or provisioning profiles.

Free personal-team signing is suitable for a first device test and expires periodically. TestFlight/App Store distribution is a separate Apple account/signing process; an `.ipa` is not a universally installable GitHub download.

Command-line simulator build:

```bash
xcodebuild -project ios/JNotes.xcodeproj -scheme JNotes \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios-build CODE_SIGNING_ALLOWED=NO build-for-testing
```

Then choose an available simulator from `xcrun simctl list devices available` and run:

```bash
xcodebuild -project ios/JNotes.xcodeproj -scheme JNotes \
  -configuration Debug -destination 'platform=iOS Simulator,name=YOUR INSTALLED IPHONE' \
  -derivedDataPath ios-build CODE_SIGNING_ALLOWED=NO test-without-building
```

The GitHub **iOS build and compatibility tests** workflow performs the simulator build and XCTest run on a Mac. No Apple credentials are in CI.

## First launch and files

Choose your existing JNotes folder through Files, or select **Use JNotes folder on this iPhone**. The default folder is `On My iPhone/JNotes/JNotes` in Files. iOS may present the containing app folder differently depending on Files settings. Notes remain ordinary UTF-8 `.txt` files; tasks remain `to_do_jnotes.txt` in Android's v2 format, with v1 read support. Journal and Double X Day metadata follow Android's serializer.

Settings provides text/ZIP import, ZIP export and Trash. Imports never silently replace a different task sharing the same ID. Notes imported as separate files receive fresh identities; use cloud sync to move an already-synced notebook between devices without duplicating it.

The `.jnotes-ios-index.json` sidecar holds stable local/cloud IDs. Keep it when moving an entire iOS notes folder. A short-lived `.jnotes-ios-journal.json` makes a save/rename/trash operation recoverable after interruption. These are app bookkeeping files; note bodies and tasks remain directly readable without them. If either bookkeeping file is damaged, the app stops writes rather than silently inventing new identities over a synced notebook.

The default notes folder and imported ebook library are inside the app container and are removed if the app is deleted. Export or move important files through Files before uninstalling. A separately chosen external folder is managed by its Files provider.

Folder access uses iOS security-scoped bookmarks. Choose the folder again if its provider withdraws access. As with any Files document provider, avoid having multiple unrelated applications overwrite the same folder simultaneously. The app reconciles external file changes on launch. It does not require a network connection to launch or edit.

## Android UX reference

See [PARITY.md](PARITY.md) for the implemented behaviors and device acceptance checklist. The four fixed tabs are **Ebooks, Notes, Journal, To Do**; Notes is the startup tab. The UI uses Android's palette values and card/flag conventions, with native iOS text selection, keyboard and Files controls.

This source handoff does not assert pixel-for-pixel or real-device parity before an iPhone test. Font metrics, system dialogs, gestures and keyboard insets must be compared on hardware. Record failures with the iPhone/iOS/Xcode version and exact steps.

## Web Sync

Sync starts off and adds no startup network requests while disabled. Existing local files remain readable and editable after logout, expired credentials, server errors or lost account access.

1. Finish account/password/notebook setup on the JNotes website, as for Android.
2. In **Settings → Web Sync**, sign in using the same email and JNotes password.
3. Review the counts of local tasks, notes and journal entries before enabling first sync.
4. Sync while the app is open, on return to the foreground, or with **Sync now**. Background refresh is requested opportunistically; iOS controls whether and when it runs.

The website link and backend configuration are bundled from `config/jnotes-service.json`. The client checks that public directory for service moves. It validates the deployment and refuses to forward an existing refresh token to a different backend origin; a cross-origin move requires a fresh sign-in. Users do not have to type a website/backend URL.

Passwords are processed with the same Argon2id/HKDF derivation as Android/browser; only the derived authentication secret goes to Auth. Vault/session secrets are in the device Keychain. Content uses CryptoKit AES-256-GCM with the existing protocol AAD and fresh random nonces. The shared Unicode test vector must pass before syncing real data.

Pending uploads are durable immutable operations, so a timeout/restart retries the same operation rather than creating duplicate writes. Cloud cursors advance after local application or durable conflict evidence. Concurrent task changes to different fields merge; conflicting changes are listed individually with phone/web/both choices. Selecting the web version preserves a local conflict backup. Notes expose cloud revision history and restore through the editor menu.

The current hosted deployment's account-approval rules still apply. Building this iOS client does **not** provision or authorize another user, change database policies, or give a friend access to the existing owner's notebook. Use an approved account for server integration tests; use local mode otherwise.

Password setup and recovery remain on the website, matching the Android flow. Disconnecting removes the phone's session/key from Keychain but preserves local content and sync identities/conflicts for reconnection to the same notebook. Switching to another account/notebook on the same populated folder is deliberately blocked to prevent mixing encrypted vaults.

## Dependencies and maintenance

- SwiftUI, UIKit, Foundation, CryptoKit, Security, BackgroundTasks and WebKit: Apple frameworks.
- ZIPFoundation **0.9.19**, revision `02b6abe5f6eef7e3cbd5f247c5cc24e246efcfe0`: SwiftPM, used for ZIP/EPUB. License supplied by its package.
- Argon2 **20190702**, upstream commit `62358ba2123abd17fccf2a108a301d4b52c01a7c`: vendored portable reference implementation in `Vendor/argon2`, built with `ARGON2_NO_THREADS`. [Upstream](https://github.com/P-H-C/phc-winner-argon2/tree/20190702); original license included.
- EPUB import supports reflowable, DRM-free books, chapter navigation, covers, text sizing, last-read position and progress. Publication scripts and remote HTTP subresources are blocked. Imported folders are copied into the app library, rather than relying on persistent Android-style folder mounts. Advanced Readium features and EPUB conformance are subject to device testing.

If you add/remove Swift files, regenerate the committed Xcode project using the standard-library-only script:

```bash
python3 ios/scripts/generate_project.py
python3 ios/scripts/validate_project.py
```

When the shared public configuration or encryption vectors change, update their bundled copies and rerun the tests. No secrets belong in these files. Store personal signing data locally; `.gitignore` excludes typical signing/build artifacts.

Before App Store/TestFlight distribution, complete Apple's current signing, privacy, encryption questionnaire, screenshots and review requirements. This handoff contains source/build ingredients, not an Apple-distributed release.
