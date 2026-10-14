# GC author-only marker analyzer

This is a separate local analyzer for the new `author-boundary-v1` source, SHA256 `597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055`. It does not run Gradle, a controller, Android, or network operations. All supplied sample records and outputs in this directory are **SYNTHETIC ONLY**, not native measurements. No existing native result or frozen analyzer was replaced.

Input is original one-line `HISTORY_GC_BOUNDARY_EVENTS`, `HISTORY_GC_BOUNDARY_TRIAL`, and `HISTORY_GC_BOUNDARY_SUMMARY` JSON, in original order. An instrumentation `stream=` prefix is supported. For official RECORD envelopes, first use the independently reviewed whole-run extractor to recover their exact original JSON; do not pass JSON-escaped official envelopes directly. Keep source/run/APK/JUnit/cleanup provenance with the extraction. This numeric analyzer does not independently establish that provenance. It preserves original JSON, original payload hash, source line, input-file hash, and all parsed marker arrays.

Each mode must contain exactly30 ordered PASS trials, complete event shards and one closing SUMMARY. Schema/column mismatch, missing or duplicate trial/shard/stage, malformed/nonfinite/duplicate-key JSON, overflow, invalid/failure outcome, changed identity, illegal transaction nesting, missing phases, invalid clocks or summary discrepancies cannot produce COMPLETE. Failed/partial streams keep raw parsed records and issues and expose no complete-mode quantiles.

The analyzer independently reconstructs:

- Every recorded author event, preserving the10 columns. Full worker/Room/SQLite entry/exit chains, nested transaction depth, task/thread/transaction/success/outcome, and same-thread monotonic clocks are checked. An earlier SQLite setup transaction is excluded by selecting the unique depth-one transaction spanning the rename receipt.
- Eleven disjoint wall segments from outer request timing to returned rename, with exact duration conservation and comparison against the producer's summary. CPU is computed only where both endpoint clocks are recorded on the same thread; otherwise null with a reason. Cross-thread CPU is never subtracted. Segment wall and valid CPU distributions are separate.
- All30 original save durations and nearest-rank p50/p95/max. Conditional distributions independently use precommit-point exposure, positive DELETE-window overlap, and save-start-inside-DELETE. Each definition stays distinct; fewer than20 leaves p50/p95 null. An empty subset is not evidence of no blocking.
- Source scan sequence `after-roots → after-stats → after-scan`, successful batch guard/DELETE/transaction return and commit/after-batch phases, plus the terminal empty or deferred guard. First/finish scans and elapsed totals stay separate. DELETE intervals are geometrically unioned per trial, then clipped to save time. GC-window CPU is also a same-thread callback-envelope delta, not pure SQL CPU.

`pureSqliteLockWaitMs` always remains null. Callback boundaries include instrumentation overhead. Author-only input omits GC/finish Room/SQLite task chains, so it cannot recover their complete task attribution. Finish collection occurs after the author call and is not reported as concurrent save overhead. This is a single-source diagnostic and does not evaluate or close the original four-pair performance gates.

Run the synthetic checks:

```sh
python3 -B test_analyze.py
```

The current94 cases include legal0/19/20/30 exposure, distinct exposure definitions and inclusive endpoint behavior, earlier SQLite setup and nested Room/SQLite operations, multiple event shards, every15 required author endpoint independently deleted/duplicated with recounted totals, missing `after-stats`/precommit/terminal guard, CPU/thread/task/transaction/depth/outcome faults, malformed fields and summaries, and literal-marker parsing. Source cross-review caught the initially omitted `after-stats`; both parser and fixtures now require it, and a negative case protects that path. Synthetic intervals are deliberately constructed; they are never measured timings.

Run the standalone CLI into a new output file:

```sh
python3 -B analyze.py --markers synthetic-markers.log --input-kind synthetic --output /tmp/gc-synthetic-reviewed.json
```

For later externally verified marker text, use `--input-kind provided-marker-text` and retain the separate provenance receipt. That flag is a data label, not proof of native execution. The CLI refuses to overwrite an existing output. Exit0 means complete scoped marker arithmetic; it does not mean performance acceptance or full-run verification.

Deliverables: `analyze.py`, `test_analyze.py`, `schema-notes.json`, `selftest-results.json`, `synthetic-markers.log`, and `synthetic-analysis.json`. `binding.json` binds producer/current production dependencies and all analyzer inputs. `SHA256SUMS` covers this package. No additional producer data or source changes are needed for this analysis.

Version2 preserves the original v1 package and corrects one consumer-only overconstraint: `HistoryStorage.kt` checks the writer guard before the empty-candidate exit. After all1280 deletions, an author commit can change the final zero-candidate guard and validly yield `firstDeferred=true`. Version2 accepts that observed state only with a mismatched guard, and adds positive/negative synthetic cases. Source bytes and timing/acceptance thresholds are unchanged.

Version3 keeps v1/v2 intact and closes Python equality type coercion for boundary counts/flags, retained revisions, covered commit timestamps and DELETE-interval fields. Seven added invalid cases reject same-valued floats and booleans in integer fields. Controller copies of the v2 numeric core remain compatible when their strict outer wrapper applies those identical field checks; the independent controller review exercises both valid and invalid boundaries. This changes validation only, never native code or measurements.
