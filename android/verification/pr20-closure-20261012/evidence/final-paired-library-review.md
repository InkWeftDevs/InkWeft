# Final library pair: candidate v4 versus controlled a128 baseline

Reviewed 2026-10-13T01:24:31.944552+00:00. Both saved raw JUnit logs and matching runner receipts independently confirm exactly one passing `LibraryReadPerformanceTest`. This is **one sequence per revision on the recorded software Android emulator**, not physical-device performance or closure of every original acceptance gate. The separate original 20,000-page rapid-input test still fails its unchanged 10-second/128-row gate.

The final comparison uses `baseline-final-performance-library-probe` and `candidate-v4-performance-library-probe` only. Baseline is fixed `a128bb07e34cb82f00eeafc8e62a47bf7b95b128` **plus the shared safe initialization factory and three identical performance probes**, not an unchanged original checkout. Older 4096/INCREMENTAL data and the intermediate v3 sequence are excluded.

## Timings

All values below are milliseconds; change = candidate / baseline − 1. p50 is sorted sample 16 of 30, p95 sample 29, max sample 30. Direct and observe save vectors remain separate and in original order in the companion JSON.

| Metric | Final baseline | Candidate v4 | Change |
| --- | ---: | ---: | ---: |
| Header load | 8,570.677 | 794.462 | -90.7% |
| Cold complete search | 178,905.009 | 46,567.369 | -74.0% |
| Warm search median (5 reads) | 13,667.067 | 9,561.469 | -30.0% |
| Direct cold-read saves p50 | 83.457 | 157.758 | +89.0% |
| Direct cold-read saves p95 | 339.204 | 254.405 | -25.0% |
| Direct cold-read saves max | 167,728.723 | 285.397 | -99.8% |
| Automatic observe saves p50 | 2,156.253 | 408.263 | -81.1% |
| Automatic observe saves p95 | 2,392.470 | 1,066.333 | -55.4% |
| Automatic observe saves max | 91,932.643 | 2,935.302 | -96.8% |
| First 64 committed index rows | 3,414.331 | 10,212.738 | +199.1% |
| All 20,000 index rows committed | 855,496.790 | 1,060,403.504 | +24.0% |
| Saves/index/search joint completion | 868,629.186 | 1,070,059.684 | +23.2% |

Positive changes in this run are regressions: `saveP50Ms, observe.firstCommittedBatchMs, observe.allIndexRowsCommittedMs, observe.searchIndexReadyMs`. The table retains these costs alongside the reduced long save stalls. No repeated-run uncertainty bounds are available. Warm-query timing follows completed indexing and all saves; the baseline searches already-preloaded note bodies whereas the candidate checks saved bodies in its current read snapshot. That path difference can contribute to the result; this evidence does not assign the entire difference to one stage.

The separate paused-reader probe recorded writer completion within 750 ms as **false → true**. That controlled window supports the independent read-snapshot behavior; it is not a general save-latency bound.

## What actually overlapped

Both sides launch the real repository `observe()` path, await its natural first committed batch, then issue 30 sequential saves. There is no artificial pause in this second stage. Baseline committed index rows advance 64→1920 during the series; candidate advances 64→128.

- Baseline: index advances in 29/30 enclosing save windows; source checks advance in 1/30; both advance in 0/30. Sample 0 takes 91,932.643 ms while source checks advance 0→19936 and index commits remain at 64. Samples 1–29 each contain one 64-row commit and no source-check advance.
- Candidate: index advances in 1/30 windows, source checks in 4/30, both in 1/30. Index/both progress occurs in zero-based sample [21]; source progress occurs in [0, 1, 21, 22].

These are before/after counter windows, not simultaneous CPU execution or SQL lock-wait measurements. Shorter candidate saves expose much less total indexing work within the 30-save series; 64→128 versus 64→1920 is not a throughput comparison. The final all-row timings above supply the full-run duration. Baseline has no save window proving simultaneous read/index/save progress; do not label all samples on either side “three-way concurrent.”

The direct cold-read save group has no background index builder. Its first baseline save is 167,728.723 ms; the old Room read transaction serializes its writer, leaving later serial saves with different contention exposure. The faster baseline p50 therefore does not contradict the larger first-save stall. The probe's fixed `request.notes` also means baseline uses the original preloaded bodies; candidate headers plus note invalidation naturally cause fresh snapshot checks. This is the same probe exercising each repository's actual behavior, not an identical schedule or full Compose interaction.

`searchIndexReadyMs` is sampled after saves plus both all-rows and correct pending=0 result awaits, so it is joint completion, not first-ready callback time or isolated index work. Sparse post-commit progress logging may contribute to index wall time and scheduling. Total source callbacks are 19,937→22,435; they include repeated scans and are not counts of unique records.

## Actual returned data and assertions

| Observable | Final baseline | Candidate v4 |
| --- | ---: | ---: |
| Header returned body UTF-16 characters | 10,010,000 | 0 |
| Returned 60-item shelf body/preview characters | 1,181,180 | 35,425 |
| Full directory returned body characters | 10,010,000 | 0 |
| Preview projection rows | unavailable | 61 |
| Preview projection characters | unavailable | 36025 |
| Full body loaded on demand equals source | True | True |

Both runs preserve the 500-book/20,000-page fixture plus its independent author note, correct target page, 20,000 cold pending/source checks, final zero pending, final author content and +30 revision assertions. The candidate's 61 rows are 60 requested previews plus lookahead. Baseline has no projection counter; `null` means unavailable, not zero. Character counts measure values returned over the cursor boundary, not disk I/O bytes or total metadata examined; all directory metadata can still be scanned. Each run creates fresh synthetic IDs/timestamps, and the author body changes during the probe. No real notes were used. The five individual warm samples were not emitted, so their reported median cannot be independently recomputed from raw timings.

## Source, APK and environment binding

Read-only verification checked all 1,894 baseline manifest files against their SHA-256 values and Git blob identities, matching all 1,891 fixed a128 tracked paths except the common factory; exactly three probe files are added. The final shared library probe SHA-256 is `a47f892262d656dcc6ec65e984681265d593e19f88ecaa13d135d2a9f3e65bd2`. All 734 baseline and 744 candidate frozen build-source entries still match the corresponding working trees; both build receipts record unchanged sources and exit zero.

The candidate reconstruction base is `a75d7457cfa3ec2e15c802c07ee93bfc07753a6c`; Git confirms fixed a128 is its ancestor. Its reconstruction record matches all 744 frozen source paths. The artifact SHA values for all three APKs on each side agree with the matching successful installation records, and the signature records pass. Data-test APK SHA-256: baseline `bbd78f8daa9139acdaeaac7c01fd0dc9fe762723c3750a0f26a55f5df2e2b505`; candidate `79a7280c9667ef4721b4606ffc17a4bd7c476ceeb661d4d015ec9c19769324a3`. This checks the recorded source/build/install chain; it does not re-extract installed APKs or prove binary-reproducible builds.

Both actual Room writer samples are `{"auto_vacuum": 1, "journal_mode": "wal", "page_size": 1024, "synchronous": 2}`. Both revisions now have matching before/after environment snapshots: all four comparison hashes are `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`, and all four recorded comparison-value objects independently compare equal. The previously pending baseline post-phase snapshot is **closed: recorded and matched**. The snapshots identify the same emulator PID/start time, API35 x86_64 AOSP ATD, TCG/SwiftShader, no KVM, 2 virtual cores/2 GiB; display 1920×1200/240 dpi and recorded font/animation settings agree. The hash excludes changing CPU/memory load and cache state and does not establish settings throughout a run. Source/APK references and their hashes are in the companion JSON.

Host runner/JUnit duration: baseline 1,540.108/1,521.985 seconds; candidate 1,389.931/1,380.400 seconds. These total fixture/probe durations are not search timings.

## Remaining boundary

Candidate v4's original rapid-input test cold search is correct at 41,649.824 ms, but the unchanged 10-second gate fails at logged 10,560 ms with 0 committed rows and 1,441 source checks. First-batch precomputation completes at 9,373 ms, writer entry is 9,392→9,448 ms, and first FTS write is 9,534 ms. Later Main-response, generation cancellation, clear/category and 120-second readiness assertions are not reached in that original test. The complete performance probe has no equivalent 10-second gate; its PASS cannot substitute for it. The five small search regression tests and the 11 guarded-precompute tests passed separately, with no claim that they cover the missing large-scale deadline assertions.

The intermediate v3 comparison remains in `candidate-v4-library-review.md/.json`: v4 saves improve against that version, while all-row indexing is about 16.5% slower. It is explanatory history only, not pooled with this fixed-base pair. New preparation retains a bounded batch of generated terms in addition to source text; the 1 MB raw UTF-16 budget is not a measured peak-heap bound. No physical-device, peak-memory or independent SQL-lock-wait result is claimed.

Review performed saved-file reads, source/Git checks and scratch report writes only. No production/test change, build, ADB call or instrumentation was launched.

## Completed continuity and summary audit

Updated 2026-10-13T01:33:12.551016+00:00. Both post-performance APK continuity records report MATCH. This review independently rehashed all six local APK artifacts and matched each to its original artifact record, successful installation record and post-performance continuity record. This checks local artifact continuity, not a fresh device APK extraction.

The library section of `paired-performance-summary-new-format.json` matches direct JSON extraction from both selected raw logs and their matching runner receipts. All four original-order 30-save vectors, their p50/p95/max values, both pairs of checkpoints, per-save index/source overlap counts, and all 12 timing percentage changes were independently recalculated without importing or rerunning the summary parser. All agree; performance values and the outstanding original 10-second acceptance failure are unchanged. The baseline post-environment evidence gap is now closed; single-run, software-emulator and in-between-snapshot limitations remain.
