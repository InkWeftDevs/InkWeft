Completed v4 evidence closure check

744/744 current source hashes match the frozen v4 manifest, which matches the existing reconstruction provenance. The completed build records exit 0 and unchanged sources; its log contains BUILD SUCCESSFUL. Recorded JVM tests are 498 with no failures/errors; lint has no errors, 188 warnings and 2 hints.

All three expected APK paths join consistently across artifact, successful installation, signature and post-performance records; recorded hashes match 3/3. The application artifact lists all four native MuPDF ABI members. No new APK hashing or apksigner execution was performed by this check.

V4 performance: four selections, five executed tests, five passed. Raw evidence contains 30 direct-search saves, 30 indexing-path saves with 30 progress samples, 30 paused-GC saves, 30 live-GC saves and 14 raster frame comparisons with same-run cold-reference pixel equality. All three recorded writer SQLite configurations match: `{"auto_vacuum": 1, "journal_mode": "wal", "page_size": 1024, "synchronous": 2}`.

V4 focused validation: 28 executed, 27 passed, one failed. The original 20,000-page rapid-search test retains its 10,000 ms timeout failure: required indexed rows 128, committed rows 0, last recorded stage first-fts-written. Its process exit code 0 does not mean JUnit passed.

Environment comparison before/after equals `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. This proves equality of recorded settings/process identity, not identical host load, cache state, database bytes or physical-device latency.

All 12 historical failed individual runs were indexed with their receipts/logs and hashes. These are failed attempts, not 12 unresolved distinct tests; later resolutions retain their own version identities.

Read-only receipt/source checks found no missing v4 link in this scope. Baseline-final build and matched baseline performance are still pending at delegation. Do not finalize the paired summary or treat mutable input manifests as frozen evidence yet. No source changes, reconstruction repeat, build/native/ADB/Docker operation, commit or push occurred.
