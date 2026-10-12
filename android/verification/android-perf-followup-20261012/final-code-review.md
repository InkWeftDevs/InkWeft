# Integration review

Reviewed production code through `300e8c9` without changing code or running Gradle/adb during performance probes.

No new deterministic author-body loss, mixed search snapshots, or deletion of live GC roots was found by inspection. This is a review conclusion, not proof of all possible concurrency interleavings.

- Search opens an independent deferred read-only snapshot. Warm reads now fetch current candidate note bodies and invoke Kotlin Unicode matching; the first paired warm median regressed 35.8%. Warm sampling happens after writes and index construction finish. Stage costs were not separately measured.
- The 30-save probe uses a serial writer. Baseline writer blocking delays subsequent requests until scanning ends, whereas the candidate permits actual overlap. Report p50/p95/max in full; do not infer intrinsic save-path cost from the changed overlap alone.
- Empty-query pagination materializes bounded preview rows after LIMIT. The measured 61 rows / 36,018 UTF-16 units exclude nonempty body matching, SQLite internal scans, and operating-system I/O. SQL substring limits Unicode characters; supplementary characters occupy two UTF-16 units.
- GC guard interpretation depends on the pinned Room/Requery behavior. Other writes conservatively defer remaining batches, including possible derived-index writes. Missing guard state aborts deletion. The UI already reports deferred maintenance.
- `PreservingOpenHelperFactory.prepareNewDatabase`, around lines 86–99, has an unsupported noncooperating-creator race: a creator bypassing the sidecar lock can finish after the empty-file precheck. Platform opening/journal configuration and auto-vacuum PRAGMA occur before schema/version checks and can alter its parameters. No author-row deletion path was found, but parameter/file-byte preservation is not guaranteed in that race. Ordinary pre-existing nonempty databases skip this initializer. All current production opening paths use the preserving factory; the native concurrency regression uses two cooperating helpers in one process.

A simple BEGIN-before-configuration rearrangement is not a complete cross-API solution. SQLite fixes a fresh database format when beginning a write transaction. A host SQLite build defaulting to NONE kept that mode when INCREMENTAL was requested afterwards; Android AOSP defaults to FULL and permits FULL-to-INCREMENTAL, so that host observation is not an Android result. Neither case removes the platform connection's journal configuration before callers can validate schema. No unverified low-level locking framework or partial remedy was introduced.

Primary implementation references inspected during review:

- https://raw.githubusercontent.com/sqlite/sqlite/version-3.49.0/src/btree.c
- https://raw.githubusercontent.com/aosp-mirror/platform_external_sqlite/android-15.0.0_r1/dist/Android.bp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-12.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java

No physical-device or independent-process stress execution was performed. The paired instrumentation and separate correctness runners remain the source of truth for executed checks.
