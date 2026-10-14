# Focused native search screen: final preparation handoff

Status: **SCREENING_ONLY; prepared and checked offline, not launched.** Use `bound-control-final/` and `offline-check-final/preflight.json`. Earlier `bound-control/`, `offline-check-01/` and `offline-check-02/` are retained historical preparation receipts and do not cover the final packager. No source, frozen formal controller, active workflow, APK, or previous evidence was modified by this preparation.

Frozen readiness commit: `03a335f3d92e3682080f3ce7e4c661434956c075`. Source C0=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`; C1=`a9ccf2cc727a7957e8fb00115f0060d80b287de1`. Independent source reconstruction matches all963/967 files respectively. The common library probe is exactly SHA`90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c`; it contains its evidence helper inline and requires no invented KvmPairEvidence source. Only this data probe is overlaid. All underlying android/ci differences are in `frozen-readiness/candidate-source-delta.json`.

The final driver builds only `:data-local:assembleDebugAndroidTest` per independent source tree, serially with one worker and no task cache. It verifies the real APK signature, package plus the instrumentation element in `aapt dump xmltree`, four actual SQLite native ELF entries and dependency/source hashes. Each install records `pm path` and the installed base APK SHA256, compared against the verified host APK before executing tests. No app/MuPDF build or MuPDF validation claim; the data APK has no embedded source-identity asset. Provenance is explicitly external.

C1 correctness calls, one exact `OK(1)` each, with existing assertions/timeouts unchanged:

1. `LibraryPagingTest#sqlPagingMatchesUnicodePinnedOrderAndLoadsOnlyBoundedPreviews`
2. `LibraryPagingTest#snapshotRetainsOldClassificationAndSourceWhileWriterCommits`
3. `LibraryPagingTest#cancelledSourceReadSkipsDirectoryAssemblyAndNextReadKeepsClassification`
4. `LibraryPagingTest#cancelledSetupStopsBeforeNextStageAndNextReadSeesCommittedAuthorState`
5. `LibraryPagingTest#refreshedNoteBodyAndUnicodeBlankFolderUseAuthorState`
6. `LibrarySearchRepositoryTest#classificationUsesTheReadSnapshotAfterCancellableInitialIndexing`
7. `LibrarySearchRepositoryTest#unicodeShortQueriesAndBoundedPagesKeepOrderAndNavigation`
8. `LibrarySearchRepositoryTest#draftUnicodeFiltersPinsAndPageBeforeCardNavigationStayIntact`
9. `LibrarySearchRepositoryTest#pausedSourceReadCancelsOnRapidInputThenClearAndCategoryStayFresh`
10. `LibrarySearchRepositoryTest#deletedOrStalePageAndTrashedCardCannotReviveSearchHits`

Then eight unchanged `KvmLibraryPairProbe#coldSearchConcurrentSavesAndShelfReadScale` calls: seed4101 C0/C1;4102 C1/C0;4103 C0/C1;4104 C1/C0. Total18 calls, nine fresh data-package install groups. Any failed correctness/host/evidence gate stops without retry. The data process must exit naturally; no auxiliary APK exception or force-stop exists in this screen. This cannot close the original-B regression or the excluded large20k rapid-input10s gate.

Cumulative first-step **runner** clock deadlines: setup30/correctness35/common50/work55 minutes; standard runner hard job timeout60. This is explicitly a new screening clock, with actual job start unknown until external metadata; the old formal PR-server clock contract remains untouched. The first-step shell was actually executed only in isolated scratch: JSON loads, nine separate exports, second invocation refuses reuse. Existing cleanup is byte-identical, with the same minimal UID-only KVM grant and exact restoration. Upload stays <=20MiB whitelisted text, three-day retention, no APK/DB/keys/env dump/logcat.

Prior d66 library methods totaled171.874s across four seeds; a planning estimate for two similar versions is343.749s plus install overhead. Data-only CI build duration and new CTE runtime are **unmeasured**, so this is not an upper bound. Adding B would add a third unmeasured build and186.365s of previously measured library calls plus installs; B is deliberately excluded from this focused comparison, and prior B measurements must not be pooled across runners.

Seven forwarded marker types: `KVM_PAIR_FIXTURE`, `KVM_DIRECT_INTERVALS`, `LIBRARY_DIRECT_SAVE_CHECKPOINT`, `INDEX_SAVE_PROGRESS`, `LIBRARY_INDEX_SAVE_CHECKPOINT`, `KVM_INDEX_INTERVALS`, `LIBRARY_READ_EVIDENCE`. Closed schemas, exact counts (12 records per completed library call), finite numbers, fixed strings, sample arrays, writer settings and bounded sizes are validated before any payload is forwarded. Malformed/array/truncated/unknown/extra markers fail; raw JUnit PASS remains separate from host INCOMPLETE. Original JSON strings and their SHA256 remain unchanged.

Official-log prefixes are `INKWEFT_SEARCH_SCREEN_CLOCK`, `..._CONTEXT`, `..._RECORD`, `..._END`, `..._METHOD`, `..._FINAL`. Context binds source/probe/method manifest, selected and installed APK, signer, install/environment/raw-log/records hashes. END means only a complete marker block; consumers must also require METHOD PASS and FINAL18-method/9-group/cleanup/environment/evidence completion. FINAL always includes `missing_files`; completed execution missing a required raw receipt becomes EVIDENCE_INCOMPLETE while retaining original method/JUnit states and the before-packaging summary. Log-derived screening never implies independent original ZIP/APK binary verification.

Final offline evidence is `offline-check-final/preflight.json`, SHA`b035265e03fd4c4c8339b949179eb5b61b2f297018728a5b980bdade5e9c041a`:10 groups PASS, including58 prior actual raw JUnit logs and13 failure variants;8 prior actual complete library marker blocks and10 malformed variants;12 boundaries derived from actual CUTOFFS AST;3 natural-exit cases;actual local APK manifest plus4 negative variants;installed hash matching/mismatch/path checks;malformed marker rawPASS/hostINCOMPLETE;13 real new-packager synthetic cases including missing raw. These are offline tests, not new native measurements or Android speed evidence.

After root copies the final controls into the new execution clone, run the same checker against those exact files:

```sh
python3 /workspace/scratch/pr20-search-screen-native-20261014/check_screen.py \
  --controller /workspace/InkWeft-native-search-screen/ci/pr20-search-screen \
  --readiness /workspace/InkWeft-native-search-screen/android/verification/pr20-search-screen-20261014 \
  --out /workspace/scratch/pr20-search-screen-native-20261014/offline-check-execution-clone
```

Root still owns the independent final review, prep commit, single exact prep-parent/attempt1 workflow guard, execution SHA manifest and any activation. The draft guard remains all zeros and is not launchable. `final-handoff.json` binds all frozen inputs and final control/checker/preflight hashes. This handoff does not trigger a workflow or authorize a retry.
