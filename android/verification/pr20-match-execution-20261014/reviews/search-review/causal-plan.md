# Save latency after the matcher — bounded causal follow-up

Keep cb7's demonstrated search improvement. The public comparison still has a B→C1 observe-p50 regression signal, a direct-p95 regression signal and an inconclusive direct-p50 result. The current evidence identifies where further measurement belongs; it does not yet justify a production save/scheduler change. No code change, new CI, native run or altered threshold is proposed as already authorized or performed here.

This analysis uses C1-only instrumented method58 from run38520196523, separately from all public samples. [stage58-analysis.json](stage58-analysis.json) retains the stage events, read IDs, absolute save intervals, all 30 observe samples and phase overlaps. Its input SHA-256 is `a1f189ea81451332eeb353026b598b27332255a64828fdf4fa0ea61a54402016`. Comparisons to run38514139119/source78a below are structural observations from different diagnostic methods/runs, not a paired performance estimate.

## Current instrumented save exposure

The 30 observe saves span **322.940540 ms**, all Committed, with nearest-rank p50/p95/max **8.032342 / 20.415507 / 67.170518 ms**. The observed flow schedules 32 reads: 31 actual read requests after one initial debounce cancellation. Of those requests, 10 cancel before entering IO, 19 enter IO and cancel, and two enter IO and complete. **Both successful reads start after the last save. No page SQL or directory assembly starts during the save window.** The 19 cancelled IO reads finish with their last work stage at scope-end (11), drafts-ready (6), page-hits-start (1), or connection-open (1).

There are 31 invalidation events, one index-build-completed event and one corresponding observed-index-completed event. This count is consistent with one initial invalidation plus the 30 author saves, and one final indexing completion. There is no evidence of an extra virtual seed or an index-completion restart for each 64-row batch. Table-level events carry no command ID, so their exact one-to-one attribution to revisions is not independently proven. The existing fresh-read requirement explains cancellation pressure without establishing that any invalidation is redundant.

Complete observed intervals clipped to the 30-save wall-clock window:

| Phase | Complete intervals among cancelled IO reads | Cancelled-read phase p50 (ms) | Overlap with save window (ms) |
|---|---:|---:|---:|
| Read requested → IO entry | 19 | 0.231 | 9.128 |
| IO entry → connection open | 19 | 0.286 | 5.569 |
| Connection open → snapshot open | 18 | 0.123 | 4.095 |
| Snapshot open → drafts ready | 18 | 1.727 | 50.026 |
| Scope start → scope end | 12 | 9.187 | 111.773 |
| Scope end → hit tables ready | 1 | 0.789 | 0.789 |
| Whole IO start → terminal event | 19 | 14.298 | 277.929 |

These are wall-clock overlaps, not CPU charges to the author or sums to add to save latency. Parent intervals contain child intervals. Incomplete SQL phases are retained as censored, not extended to the next event to invent phase completion. The last cancellation finishes shortly after the save window, which is why its full interval is longer than its clipped overlap. The connection-open total is small relative to preparation; current evidence does not favor a reader-pool redesign.

Save sample15 is the 67.170518 ms maximum: index count advances 64→128, source checks advance 0→1,442, and it intersects 63.333831 ms of a read IO interval. This proves overlapping real progress, but method58 lacks writer-entry/exit events; it does not prove 67 ms of writer lock wait. Sample21 takes 20.415507 ms with no index/source advance and intersects 10.120431 ms of scope preparation. That also prevents attributing every slow save to an index commit.

The cold direct scenario has **no observe background indexer**. Its 30-save window is 129.900823 ms, entirely inside the reader's 545.831031 ms page-hit/source-scan phase; page SQL and metadata occur later. Thus index-writer queue contention cannot explain the direct-p95 regression in that scenario. The matcher is also used by the source UDF, so saying “no page SQL” does not mean no matcher invocation. It does mean the large note-body scan measured in warm page SQL is absent from these save windows.

Across the unchanged public probe, B exposes only its first of 30 direct saves to cold search; that first save blocks roughly 4.5–6.3 seconds, and the other 29 follow after the reader. C1 allows every one of the 30 to commit while source scanning advances. This is a real concurrency improvement and a real difference in p95 exposure. It is **not** grounds to drop B's first save, pool samples, change the band, or rename the remaining regression as a pass.

Method58's five warm page-stage walls are 131.003, 131.799, 131.427, 128.842 and 128.588 ms; corresponding complete read walls are 150.196, 157.851, 149.958, 146.824 and 147.072 ms. The phase still accounts for much of warm read wall time, but method58 has no UDF CPU breakdown. Those instrumented observations remain separate from public warm medians and from the older per-UDF CPU experiment.

## What the previous causal run adds—and does not add

In source78a's six-mode run, both FULL blocks had 23 IO reads, all cancelled before page SQL. Save p50 was 5.927/5.607 ms; request-to-writer-body entry p50 was only 0.476/0.491 ms, while receipt-written→transaction-returned p50 was 3.042/2.682 ms versus IDLE's 0.889/0.546 ms. The latter endpoints were on different threads in all 360 saves, so thread CPU could not be subtracted there. That experiment ended observation immediately after the 30 saves; method58 keeps observation alive through all indexing and a ready result, hence its two later completed reads are not evidence that save-time cancellation disappeared.

The prior run directly measured one ~28 ms index writer body in each FULL save window, overlapping the worst save's entry delay. Removing those two samples from a *conditional diagnostic view* did not change the original FULL medians; all original distributions were retained. Together with current method58, this motivates splitting the transaction-return tail before attempting another save optimization. It does not establish that the same tail proportions or index hold times recur on cb7.

The offline Room2.8.5/Requery review also constrains the theory: outer `endTransaction` synchronously commits/releases the connection, then schedules an independently scoped refresh. Room coalesces pending refresh requests; save does not await refresh completion or a new search result. DAO operations inside the author transaction do not each durably commit or independently refresh. There is no proven duplicate refresh to delete. See the already completed `pr20-match-candidate-20261014/save-return-review.md`.

## Three falsifiable alternatives

| Alternative | A discriminating observation | Observation that would weaken it |
|---|---|---|
| Transaction admission/active index writer explains the remaining observe tail | Many slow current saves accumulate before outer transaction-body entry; that interval aligns with a separately recorded index writer body. A begin-entry/exit split distinguishes dispatch/admission from SQLite begin work. | Current median request→entry stays small while the receipt→return tail grows; direct-save regression persists without any indexer. Prior78a already shows this distinction for the median. |
| Synchronous commit/finalization or caller resumption explains the median | Record outer endTransaction entry/exit on the transaction thread, then compare exit→caller-return. Long end entry→exit points to commit/release/refresh-enqueue work; a short end but long exit→return points to coroutine dispatch/resumption or other post-end work. | Both intervals are small and author body CPU accounts for the delay instead. A receipt→return wall interval alone cannot decide. |
| Concurrent cancelled reader preparation consumes CPU/allocation/scheduling capacity | Same-thread CPU measured at existing coarse read stages accounts for much of drafts/scope wall time during saves, and slow author-return intervals overlap that work in matched IDLE/READS_ONLY/FULL controls. | Draft/scope wall is mostly off-CPU, or return delay remains comparable in a matched no-reader control. Wall overlap alone is insufficient. |

More source checks after cb7 may mean a faster source matcher permits more progress before cancellation; it is not by itself proof of more reader CPU consumption. The prior controls support reader-related exposure but do not establish its exact mechanism on the new source. A pure “Room lock” explanation is too narrow, especially for direct saves where the independent WAL reader does not reserve the author writer.

## Minimum next diagnostic, if separately authorized

First reuse existing null-default hooks in a candidate-only diagnostic collector: `NoteRepository.saveStageObserver`, `LibrarySearchRepository.stageObserver`, and `indexBatchStepObserver`. Capture a run-local save/read/batch ID, monotonic wall timestamp, thread ID, and `Debug.threadCpuTimeNanos()` at these coarse boundaries. This is already the collection pattern in `LibrarySaveCausalTest`; default production and the immutable common probe remain unchanged. It would establish current request/body/tail allocation and actual index-writer overlap without per-row logging. Compare CPU differences only for endpoints on the same thread; retain cancelled/censored intervals and all samples.

If the tail is again dominant, the single missing implementation boundary is an outermost `RoomDatabase.beginTransaction` and `endTransaction` entry/exit pair, preserving the original synchronous calls and executor. A tiny nullable diagnostic hook around those boundaries, correlated with the existing author/index body hooks and nesting depth, is preferable to changing executors or inventing a pool. Before/after begin separates the broad queue/coroutine-admission interval from trigger synchronization/SQLite begin work; after end separates transaction-thread completion from caller return. The end interval still includes refresh enqueue and connection release, so do not call it pure fsync. No such hook is implemented or authorized by this report.

Do not substitute Room's asynchronous QueryCallback for execution timing, or SQLiteTransactionListener.onCommit for an after-durable-commit event: the latter runs before native COMMIT in the cached Requery bytecode. Do not subtract CPU counters across threads or reinterpret callback count as lock hold. Any new enabled instrumentation has overhead and belongs only to causal evidence, not to the 720 public save samples.

If current CPU/end-boundary evidence remains ambiguous, one bounded matched control comparison (same fixture/readiness, sequential real saves, IDLE versus READS_ONLY with natural notes refresh) is the next way to test reader competition; use existing six-mode concepts rather than a production scheduling intervention. A full acceptance rerun before resolving the missing boundary would reproduce the gap without identifying it. No additional run, changed freshness/debounce/durability, or speculative production fix is recommended now.
