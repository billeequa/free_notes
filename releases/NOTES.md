# JNotes 1.4.3

Add five lines of scrollable empty space beneath a note's last line. The space scales with the note font and applies to Notes, Journal, and Double X Day. It is UI spacing; saved/exported note text receives no extra newlines.

The native editor sits in a full-height, transparent scroll container with padding clipping disabled. Text still draws behind Android's transparent gesture-navigation bar while scrolling, and the last line can scroll safely above it. The Scaffold/system-bar inset configuration remains intact; no fixed bottom ribbon or opaque overlay is added.

Preserve native selection handling and the stable viewport fix. Regression tests cover end clearance, font scaling, text preservation, and transitions between short/long documents and keyboard-sized viewports. To Do and Ebooks behavior is unchanged.

Install jnotes-15.apk over the existing app. It uses the same signing key; no uninstall is needed.
