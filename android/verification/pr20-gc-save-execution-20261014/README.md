# PAUSED_BY_USER — GC / snapshot diagnostic round

The user requested: “这轮结束就先暂停”. The existing native run has ended, its available evidence has been preserved, and no repair, retry, further experiment or raster activation follows this round. The four optimization tasks are **not declared complete**. Nothing was merged into main or deployed.

## Actual result

[Run 38553018139](https://github.com/InkWeftDevs/InkWeft/actions/runs/38553018139), job `115815277181`, attempt 1, ended **failure**. The official job interval was 2026-10-14 10:56:05–11:02:53 UTC: **408 seconds**. Source reconstruction, data-test APK build/native-library checks, and KVM emulator preparation succeeded. This was a data-only build; it makes no new app/MuPDF APK claim.

Method 1, `HistoryCollectionTest#sameRoomAuthorQueuedBeforeDeleteCommitPreservesNewRoot`, returned `OK (1 test)`, adb exit 0 and `NATURAL_EXIT`. Its controller status was nevertheless **INCOMPLETE**. Methods 2–8 were all **NOT_RUN**. There are **zero new GC boundary trials and zero new snapshot-control save samples**; no new latency quantile or optimization acceptance result can be calculated.

The failure is a frozen controller-contract mismatch. The test emits `HISTORY_DELETE_WRITER_CORRECTNESS` at `HistoryCollectionTest.kt:139`; the method-1 contract declares `marker_policy=none`, `kind=none`, empty `markers`, and zero marker payload bytes. The frozen validator at `markers.py:63` rejects the existing legitimate marker. Replaying only the already-recorded text through that unchanged validator reproduces `AssertionError: Unknown/malformed marker`; this was not a device rerun. The official controller log contains the outer `EvidenceIncomplete`, not the inner assertion stack. Method 2 shares the same emitting test helper and the same `none` declaration, but was not executed.

The first test's artificial-gate correctness record says `firstDeleted=256`, `finishDeleted=1023`, author revision 2→3, retained revisions `[300,1281]`, and verified content. It is a correctness observation, **not natural-arrival performance evidence**. The preparation suites had accepted synthetic guard output without this marker, so their green results did not establish actual guard-output compatibility. Their original reports are retained without rewriting them as native success.

## Cleanup and evidence

The failure trap and the always cleanup both report emulator and command groups `STOPPED_CONFIRMED`, ACL `RESTORED_EXACT`, zero errors, and identical before/after ACL SHA-256 `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`. The extractor records `FAILED_TERMINAL_AUDITED_NOT_PASS`; overall analysis remains `INCOMPLETE`.

Packaging correctly exited nonzero because 24 expected later files were absent after fail-stop. Available text upload succeeded. No evidence-overflow failure was reported. The official decoded job log was fetched once through the authorized connector; no artifact ZIP, APK, AAR or database was downloaded. Compact receipts do not independently inspect the APK binaries or original ACL files.

- `official/official-decoded-job.log`: 42,301 bytes, SHA-256 `6927c9f875e69d5be178c0402961b4811c843f7136abfedab2e95d0ad54b6469`.
- `extracted/failure-method-01-C1-instrumentation.log`: full 1,142-byte failure-path text, SHA-256 `f1ee6adafc9195b66a3e5f9abbc84086ce8118e2339951846aebd05cfdc38204`. It was independently reconstructed from the complete base64 stream and agrees with the FINAL hash.
- `extracted/analysis.json`: exact source/run binding, original marker/transport receipts, all eight method states, and dual-cleanup audit.
- `extractor/`: fixed analyzer and its 101 synthetic checks; `unexecuted-gc-analyzer/` is the prepared GC numeric analyzer with synthetic examples only. It was not applied to nonexistent native samples.
- `controller-template/`, `control/`, `frozen/`, `preparation/`, and `reviews/`: unchanged preparation inputs and review records. These archived copies are evidence, not an active workflow.

The frozen method plan also retains an obsolete preparation `pending` description; the executable contract was `READY`. Those historical bytes remain unchanged. Neither this documentation issue nor the marker mismatch is repaired during the pause.

## Recoverable state and remaining work

The pushed execution chain is source `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353` → readiness `fbc502eb49c15018bb1ab2694dc256b7a0f9fc25` → prep `083a892133d818cf364865d3ee3edc1fdb6413c0` → active `c3483b74e5db7cb7928d26445bd37af51700284c`, on `codex/kvm-pr20-gc-save-20261014`. The readiness parent is integration archive `404588360490e52f093c07541313fb29cc7c0ffc`; the source is an ancestor, not its direct parent.

This final evidence checkpoint is local and is not a final-delivery push to Draft PR20. The user source remains `a128bb07e34cb82f00eeafc8e62a47bf7b95b128`; main remains `aa43d43bb7841b04b8a8df091b98c47b66d86be3`. The new raster candidate and strict Job follow-up remain unverified natively; its unpublished preparation is preserved in `../pr20-raster-paused-20261014/`.

The prior frozen 45 within-band / 5 inconclusive / 4 regression-signal classification remains unchanged. In particular, B→C1 eight-layer full redraw remains +39.038078 ms / +12.219478%, and the previous concurrent-save signals remain open. The earlier search/paging/GC improvements and their evidence are preserved; this failed round neither erases them nor closes their remaining gates.

On an explicit user request to resume, first review the guard-output contract against the complete actual marker and its existing source; preserve the old failure and thresholds. Any corrected native execution requires a separately reviewed, newly bound attempt. Do not rerun this run automatically. No user notebook data or additional user information is needed to understand the current blocker.
