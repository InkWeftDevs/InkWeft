# GC author-only diagnostic narrowing — host evidence

The only repository file edited by this task is `android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest.kt`: 12 additions, 8 removals. Source SHA-256 is `597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055`; before SHA-256 is `98b69ea1d09e0bce21646d79f50f9b7dd9396c9e73bb1a9baea647de7cdf6b72`. See `source.diff` and `source.kt`. No production, prior probe, controller, CI or network changes, commit or push were performed.

The diagnostic retains only events whose owner is the current author token (`3 * sample + 1`). Both database recorders still add every submitted writer task to `pending` and remove every terminal task, including GC, finish and unknown/background owners. These ledger operations are outside the recording filter and outside the enabled-window gate. Helper close and fixture deletion remain conditional on draining all workers. The separate-room collector therefore has zero boundary events, with its zero count and invalid/drop state retained in the trial. Its complete GC batch/phase evidence remains present.

The 30 same-room and 30 separate-room samples, 1280 detached source fixture, after-scan arrival notification, rename request/return, original exposure rule, actual DELETE intervals, batch guard values, reference scans, deferral, row counts, content checks, cleanup, failure handling, and all timing distributions are unchanged. Capacity remains 512 author events per database, 64 batch events and 32 phases. Overflow still invalidates the run; no truncation, sampling, retries, added pauses or enlarged fixture was introduced. `static-result.json` confirms byte-identical fixture/flow/raw results, summary and author validation sections.

## Exact event schema

Each `HISTORY_GC_BOUNDARY_EVENTS` JSON retains `sample`, `connection`, `database`, zero-based `part`, `parts`, and `events`, adding `eventSchema: "author-boundary-v1"` and `eventColumns`. Each event is a ten-element array in this exact order:

| Index | Column | Value type |
| --- | --- | --- |
| 0 | stage | string |
| 1 | ownerToken | int |
| 2 | task | long |
| 3 | transaction | long |
| 4 | depth | int |
| 5 | successRequested | boolean |
| 6 | outcome | string |
| 7 | atNs | long |
| 8 | cpuNs | long |
| 9 | thread | long |

The arrays preserve the old object's values in order; only repeated field names are removed. Every chunk carries the schema and columns, with at most 48 events. Parsing must validate the exact schema/columns/row length, preserve 64-bit integers (do not convert nanoseconds through floating-point), join all parts without loss or duplicates, and reconcile per-database counts/dropped/invalid values from the trial. The trial adds `boundaryScope: "AUTHOR_ONLY"`; other raw record fields stay unchanged. A zero-count database has no EVENTS chunks.

## Host verification

One offline `:data-local:compileDebugAndroidTestKotlin` passed (Gradle 9.4.1, JDK 17), exit 0, 123.698 seconds. Nine relevant source hashes were identical before and after; see `compile-start.json`, `compile-result.json`, and `compile.log`. No later experimental save test is included in this binding.

`run-host-check.py` compiled and ran `host-src/GcRecorderHostCheck.java` against the actual Kotlin-compiled private Recorder and Boundary classes. All five host scenarios passed; `host-result.json` records the source, class, harness and dependency hashes. The checks demonstrate:

- GC and finish tasks enter/leave the pending ledger without polluting author events; both database labels retain all 15 synthetic author executor/transaction endpoints.
- Real `awaitWorkers` remains pending while an unlogged finish task is outstanding, and returns after its terminal event. A disabled unknown-owner task is still tracked and awaited. The host-only 50 ms assertion window is a correctness check in scratch, not an Android workload pause or performance sample.
- 513 events retain the configured 512 and explicitly report one overflow; all tasks drain and `validate` rejects the overflow.
- Actual compiled `Boundary.json` forwards all ten values and JVM types exactly, including long/int edge values, into the array constructor.

The host harness substitutes Android Debug's clock with the JDK CPU clock and uses a JSONArray argument-capture stub. It executes no database work and does not validate Android JSON serialization, device timing, SQLite behavior or native performance. The compile and host checks do not establish that the save hook's native guards passed. This task ran no new Android instrumentation.

## Budget binding and remaining gates

The independently calculated budget at `../pr20-gc-author-only-budget-20261014/budget.json` is bound to the source SHA above. It retains up to 60 × 512 = 30,720 author events in 660 chunks, full batch/phase capacities and all necessary raw records. With up to 59 successful trials (at most five actual DELETE intervals each) plus one failed trial (up to all 64 before-delete slots), original framed instrumentation plus JSON forwarding envelopes/END totals 14,517,988 bytes. Including 256 KiB additional failure original text, its conservative escaped envelope, and 3 MiB shared other-text allowance totals 19,506,916 bytes, leaving 1,464,604 bytes below 20 MiB.

The five-interval success bound follows the exact 1280-source fixture, 256-row candidate limit, increasing candidate sequence, and the existing successful-trial assertions on full batches, totals and candidate sums. Failed trials retain their complete 64-slot allowance. `trial` exports in `finally` then rethrows; `runProbe` catches outside `repeat`, so it stops at the first failed trial. At most one failed trial across both methods additionally requires the controller to stop after any method non-PASS.

This is a static GC-only budget, not a frozen merged-run proof. It assumes the controller omits the redundant reconstructable `records.json` upload copy, forwards every original JSON record with its complete envelope/hash, and stops on non-PASS. The old controller/packager must not be treated as satisfying those assumptions. Other methods and all controller/build/cleanup text must fit the shared allowance; arbitrary oversized failure text must fail closed, never truncate or suppress samples. Root owns these integration gates and the native hook guard prerequisite.

Interpretation remains unchanged: 0/30 natural exposure is a valid observed result and an insufficient exposure conclusion, not a functional failure or proof of no writer blocking. Exposed and actual-DELETE distributions below 20 samples keep their p50/p95 null. The complete 30-call overall distribution can still describe the overall workload. Preserve actual DELETE intersection separately from the original before-batch-commit exposure flag. Request→return segments, BEGIN/END wall time and thread CPU can localize a delay, but BEGIN elapsed or wall-minus-CPU is not a pure SQLite lock-wait measurement; `pureSqliteLockWaitMs` remains null.
