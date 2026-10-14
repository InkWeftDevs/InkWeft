Candidate v3 source continuity

Checked: `2026-10-12T23:02:36.253900+00:00`. Frozen inputs: v2 `141f13c85e1fac7d71b5623f14650eaff4b2cd11c3e4bcdf5ccbc6e25b3cc0ae`; v3 `c52cbf318ce52a660d7040ad75f2a3c3c1828027c6f61633bf886deae8a214f7`.

Both manifests contain the same 743 paths. The v2 saved snapshot matches 743/743 v2 hashes; current candidate source matches 743/743 v3 hashes, rechecked after analysis. Exactly three paths changed, all in data-local Android instrumentation tests; 740 files are identical.

| Frozen scope | Files | Identical | Changed |
| --- | ---: | ---: | ---: |
| other_frozen_build_inputs | 22 | 22 | 0 |
| android_instrumentation_tests | 266 | 263 | 3 |
| production_main_sources_and_resources | 392 | 392 | 0 |
| jvm_tests | 63 | 63 | 0 |

The 392 production main-source/resource files and all 22 other frozen build inputs are byte-identical. No production change occurs between these two freezes. This statement is bounded to the recorded Android inputs. Docker was neither inspected nor changed.

`android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryBackupIsolationTest.kt`

Adds stage/fault/timing diagnostics and failure context to authorCommitContinuesWhilePrivateValidationTransactionIsPaused. Existing 10-second hook/snapshot waits, 2-second author-save budget, assertions and final cleanup remain in place.

v2 SHA-256: `d5dd1072bedeae268191a6f208a893ef00cb93f0970d3a17bc4cc07eafdf0fde`
v3 SHA-256: `d1419ac63b8d4bd3052d2be041c81e5b2a6650d957d46f204722a723c0d55ff2`

`android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryReadPerformanceTest.kt`

Replaces the standalone LibraryTextIndex.build step with the real observe/index path plus 30 author saves, progress and raw duration samples, committed-row/readiness measurements, three checkpoint labels and final persisted-author assertions. Existing direct-search timing remains; index progress logging can affect index wall time and scheduling.

v2 SHA-256: `9e4dc68ec757c5e2f1c97d73a5b93343ae4ec7b65165fa372cad1edc8808417b`
v3 SHA-256: `a47f892262d656dcc6ec65e984681265d593e19f88ecaa13d135d2a9f3e65bd2`

`android/data-local/src/androidTest/kotlin/org/inkweft/data/OriginalStorageRepositoryTest.kt`

Adds VACUUM immediately after the fixture PRAGMA auto_vacuum=INCREMENTAL in laterCorruptionStillReclaimsSuccessfullyMigratedDatabaseBytes; retains the auto_vacuum=2 assertion. This establishes the requested test database format before Room pooled reads; no production initialization change.

v2 SHA-256: `7645cf9bf542385d8468b4bdb3299cafb87137ef59113183699034bca490bd58`
v3 SHA-256: `6f71c49df4ef7ead6d501aaa55a5d193876ec94a8550035ae00c338390255c1f`

Exact unified diffs and their hashes are embedded in `candidate-v3-source-continuity.json`. Neither historical manifest was modified.

Existing v2 native receipts remain v2 execution evidence. Unchanged production and test sources support tracing that evidence, but do not establish a v3 build/test pass or binary-identical APK. The modified tests require their v3 execution evidence. The new baseline-final/candidate-v3 performance pair remains pending. Random fixture IDs and repository timestamps also preclude claiming identical database bytes; see `performance-fixture-comparability.md`.

No source edits, builds, ADB/native runs, remote reads or remote changes were performed for this source-continuity task. Final read-only delivery commands are drafted in `final-readonly-delivery-checks.md` and have not been executed against the remote.
