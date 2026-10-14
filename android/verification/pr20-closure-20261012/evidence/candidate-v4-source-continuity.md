Candidate v4 continuity and exact source reconstruction

V4 freeze: 744 files, manifest SHA-256 `8285c54019d0a4409a6dbf6f5d36e87298b5be9b6d47f93801ea9123bb7d859f`. All 744 current source hashes matched before and after reconstruction.

Compared with v3: 741 shared files are identical; two files are modified and one is added. **Production is no longer byte-identical to v3:** `LibraryTextIndex.kt` changes preparation/commit behavior. Of 392 production main-source/resource files, 391 are unchanged.

- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryTextIndexPreparationTest.kt` (added): New native tests cover concurrent save during preparation, stale/deleted/recreated sources, card changes, competing builders, cancellation, row limits and oversized-source byte-budget progress.
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/OriginalStorageRepositoryTest.kt` (modified): The incremental-vacuum fixture is now created on one DELETE-journal framework SQLite connection before Room opens its WAL pool; existing format and reclamation assertions remain.
- `android/data-local/src/main/kotlin/org/inkweft/data/LibraryTextIndex.kt` (modified): Production change: pending-source reads and term preparation move outside the Room writer transaction. The bounded commit rechecks pending membership and exact nullable source contents under the writer before modifying FTS or clearing pending; stale prepared work retries.

Exact differences and hashes are in `candidate-v4-source-continuity.json`. Original v3 bytes for both modified paths were independently checked against its frozen hashes.

Reconstruction source tree: `/workspace/scratch/v4-reconstruction`. Fixed base: `a75d7457cfa3ec2e15c802c07ee93bfc07753a6c`. Patch: `/workspace/scratch/pr20-closure-evidence/candidate-v4-reconstruction.patch`; SHA-256 `4c5cbb21e327d89578731f03f5c63167aa14b0aeb6c6335216316a26e1bb87a9`. The patch has 18 modified and 5 added files relative to a75, including new tests and test Manifest.

`git apply --check` and real replay succeeded in a second complete base tree. All 744 frozen paths matched after replay, all remaining base files stayed unchanged, and complete tree fingerprints matched (2235 files). Main, both baselines and listed historical evidence were unchanged. No original build/runtime artifact was replaced.

V3 results retain their own source/APK identities. Because v4 changes production indexing, v3 performance 5/5 cannot establish v4 correctness or performance. This task did not run builds, ADB, native tests, Docker, commit or push. Reconstructed source identity does not establish byte-identical APK reproducibility.
