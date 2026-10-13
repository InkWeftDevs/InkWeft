Completed-v4 evidence curation update

New snapshot inventory: `closure-curated-manifest-v4-completed.json`. It includes all 49 currently completed `candidate-v4-*` files, plus prior provenance and all historical failed individual receipts/logs, totaling 172 curated files (2,253,891 bytes). Previous curation/privacy records remain unchanged.

`candidate-v4-evidence-closure-check.json` joins the completed v4 source/build/APK/install/signature/runtime records: 744/744 source hashes, 3/3 artifact identities, performance 5/5, focused validation 27/28. The remaining original 20,000-page rapid-search test retains its 10-second failure. All 12 historical failed individual runs retain original version identities; that is not a count of 12 unresolved distinct tests.

Environment before/after matches `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. Three writer SQLite records agree on page_size=1024, auto_vacuum=1 (FULL), journal_mode=wal, synchronous=2. Raw sample/progress arrays and raster comparisons are present.

Privacy scan: 248 reviewed completed text snapshots (4,802,390 bytes), zero matches for the listed sensitive patterns. This is bounded to the hashed files, not a universal privacy guarantee. Baseline-final phase outputs (including its active log), mutable input manifests and provisional paired summaries are explicitly excluded. They must be frozen/reviewed after the existing planned baseline work completes.

The remaining work is closure of existing acceptance: baseline-final build/artifact receipts, planned baseline performance, exact paired summary and final Git/PR/Actions readback. This update requests no new optimization or expanded test scope. It did not run builds/native/ADB/Docker, repeat reconstruction, change sources, commit or push.
