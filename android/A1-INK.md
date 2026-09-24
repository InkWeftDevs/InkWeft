# A1 single-page native ink preview

Built on the merged A0 text foundation. This is not the complete G1 save contract,
PDF/card-axis release, front-buffer latency qualification, or a physical-device test.
The old Web sample is unchanged. There are no cloud, recording, camera or model features.

## Implemented path

Create a note (text identity saved first), open Handwriting, use black/red/highlighter,
whole-stroke eraser, session undo/redo, fit page, pan and two-finger zoom. Finger writing
is OFF by default; enabling it allows touch-only devices/emulator interaction. Real stylus
pressure/tilt are collected only if the device reports the axes. No predicted point is
saved. Rendering and brush geometry use pinned AndroidX Ink 1.0.0, not a custom smoothing
engine. This preview uses a native View/Canvas renderer, not WebView or the specialized
front-buffer authoring surface; no latency or palm-rejection superiority is claimed.

Pen-up produces an immutable, bounded command. A single session queue persists each stroke
(or visibility change) with the page revision and receipt in one Room transaction. Until
that transaction returns, the UI says saving; exceptions near commit retain the exact
pending command for replay. A conflicting page does not overwrite its newer head.
A stroke already in flight may finish into retained draft state if an earlier write fails.
New input is paused until storage is reconciled. Erase/undo change visibility, not author
payload bytes. Undo history is bounded and session-local; it is not restored after process
death. Text and handwriting have independent revision streams in the same database.

The first schema migration is additive (Room 1 -> 2) and preserves existing text/revisions/
receipts. No destructive migration or failure-triggered database reset was added. The app
ID remains org.inkweft.app.a0; versionCode is 2. CI debug certificate may differ between
runs: do not silently uninstall an older app to bypass signature mismatch. Export important
experiment text/content first. Release-signing management is still separate work.

## Explicit limits

- One 1000 x 1414 canonical hand-written surface per note, with a light ruled background.
- At most 8192 accepted samples per gesture, 1000 stored strokes and 100000 page samples,
  including hidden ink. These are safety bounds, not paid quotas. A capacity notice appears.
- A gesture is not saved until pen-up. Active gestures are cancelled on resize/detach/
  ACTION_CANCEL. Process death can lose the active stroke and unconfirmed queue. Durable
  mid-stroke checkpoints and full accepted_seq/durable_seq recovery remain NOT_IMPLEMENTED.
- No PDF editor, card axis, OCR, free lasso, partial-stroke eraser, automatic shapes or FSRS.
- This reuses Ink brushes/mesh generation but uses our small bounded/versioned raw-input
  envelope. It does not claim official Ink storage serialization compatibility. Renderer
  upgrades need fixture validation; the current brush implementation is pinned.
- Dirty text drafts remain session-only until explicit text save, as in A0.

## Data exit

"Export page copy" writes an explicitly unencrypted .iwpage content file using the system
picker. It contains the current title/text and visible complete strokes, including queued
unconfirmed content, but NO hidden ink, undo history, receipts or whole-vault history. It is
not a full backup. Import validates SHA-256/lengths/IDs/numeric bounds and creates a NEW
note and NEW stroke IDs, never replaces an old workspace. Import is one transaction;
retrying a user file selection after a lost UI response may create another harmless copy.
A provider selected by the user may be a cloud provider. No outbound network permission was
added. Existing automatic backup exclusions remain.

## Checks

JVM tests cover immutable input, bounded decoding, swept erasing, queue/replay, compensating
undo and portable content copies. Instrumented tests use real Room files, a schema1 fixture,
actual transaction failures before receipt/after commit, two DB instances, reopen and copy
import. Compose device tests inject touch events, cancellation and activity recreation;
they do not prove real stylus support. CI records actual XML, emulator logs and a screenshot
from the tested app, not a generated visual. A successful build alone is not a test pass.

The first Draft CI generates schema2; its exact output must be reviewed and committed before
merge. Standard-wrapper-in-repository and full transitive-dependency verification from A0
remain follow-ups; the existing fixed Gradle bootstrap and wrapper JAR hash are retained.
Device/Pencil3 tests, sustained heat/power, physical power-loss recovery and 37 AX cases
remain NOT_RUN. Read the current PR and its exact-head CI artifacts for actual results.
