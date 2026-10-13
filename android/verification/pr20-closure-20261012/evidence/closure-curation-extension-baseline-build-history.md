Baseline-final curation extension: completed build and History only

Extends `closure-curated-manifest-v4-completed.json` without changing it. All 172 inherited file hashes were rechecked; all historical failures remain included. Adds 12 stable completed baseline files: source freeze, build receipt/log, artifacts, signatures, explicit JVM/lint status, recording/install receipts, environment before, and History setup/receipt/raw log.

Source checks: candidate 744/744 and baseline 734/734 frozen inputs match current bytes; all four shared factory/probe files match byte-for-byte. Baseline build succeeded with unchanged sources. All three APK artifact/signature/install records join, with four real native MuPDF ELF members recorded for the application. Baseline JVM and lint were not run in this build; null counts are preserved.

History passed 1/1 with 30 paused-save and 30 live-save samples. Recorded writer SQLite configuration matches candidate v4: `{"auto_vacuum": 1, "journal_mode": "wal", "page_size": 1024, "synchronous": 2}`. Baseline pre-performance settings match candidate v4 hash `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. The baseline after-environment and after-performance APK continuity are still pending.

`run-inkweft-gradle` literal contents are suitable for publication at SHA-256 `d38003b3ac698ade56d447ebd37ae7f5ccea37388ef247ba16634980a1dd191c`: no credential literal or secret pattern, only fixed tool paths/arguments and a runtime HTTPS_PROXY hostname/port parser; no username/password extraction or xtrace. Runtime environment values were not read. Thirteen extension/script snapshots passed the configured pattern scan; the review is bounded to those exact hashes.

Library is active; Library/Raster/Tile outputs, mutable aggregate, after-environment and final paired summary stay deferred until the parent explicitly reports completion. No repository copy, source/log edits, build/native/ADB/Docker execution, commit or push occurred.
