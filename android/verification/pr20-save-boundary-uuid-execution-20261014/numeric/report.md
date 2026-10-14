# Actual save-boundary diagnostic, run 38543698114

The 90 real instrumented saves locate two distinct costs. Only OBSERVE sample 17 waits behind the index writer; that explains its maximum, not the OBSERVE median. The actual OBSERVE median and the DIRECT p95 spend most of their elapsed time inside the real SQLite END call, with little CPU on that worker. This identifies the boundary, but cannot distinguish synchronous storage, descheduling, checkpoint work, or other waiting inside that call. No production source, persistence setting, threshold, CI control, or frozen analyzer was changed by this analysis.

## Evidence and scope

- Run `38543698114`, job `115784421756`, source `94379190b9ad0e1034f136ea9887fb0ac12e7ea7`, workflow `ac63ce66ed4aaec0372c56b39be97e3335966c31`.
- Root-approved whole-run extraction: `/workspace/scratch/pr20-save-boundary-uuid-log-analysis-20261014/actual-output/analysis.json`, `COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE`, no issues; all 10 methods JUnit PASS, adb 0, NATURAL_EXIT. The two new guards passed on Android after the UUID fixture correction. This does not retrospectively recover the previous failed run's hidden stack.
- The unchanged frozen analyzer is SHA-256 `fd1c22004bb91e7e6673a6ac59eb0f2c2413c9f8ef454a00b2950c0581bbd1b7`; marker input `0ef0092547196143cb9a28e185052d29e63d5e2e25ea0ab5aaab60cffc702e22`; its output `05f728fae9fff2df0fc217ff6e3cb77a6cc69d0edae9bfa07853b7dc1c91f265`.
- The analyzer accepted all 90 complete author chains, the exact six-table fixture and initial logical hash `1c69bba77781ba0c0b642309b8e869ff21c9a440fd7714894e67c572b95c9dcf`, seed 4101, revisions 1/1/32, FULL synchronous=2, WAL, and zero overflow/invalid markers. Boundary event counts are 1386/1386/1434; read event counts 0/23/393.
- IDLE uses an independent fresh fixture. DIRECT then paused-reader save then OBSERVE retain the original continuous sequence. Their readiness and elapsed histories differ. Clocks have separate database-run origins; timestamps must not be compared across IDLE and the shared DIRECT/OBSERVE run.
- Each arm has 30 samples. Every p50/p95/max below is nearest rank (sorted zero-based positions 14/28/29), not interpolation. Arm values and segment quantiles must not be added or subtracted as causal mode costs. This is one candidate, one seed, one instrumented environment, with no observer-off overhead calibration; it does not establish B→C1 improvement or public acceptance.

## Save distributions

| Arm | p50 / p95 / max (ms) | Actual sample indices at those ranks | Window length (ms) |
|---|---|---|---:|
| IDLE | 2.035774 / 4.737515 / 5.314565 | 18 / 20 / 0 | 68.360126 |
| DIRECT | 2.268760 / 9.392518 / 9.446588 | 10 / 8 / 25 | 105.442867 |
| OBSERVE | 6.324345 / 11.146675 / 23.839401 | 20 / 4 / 17 | 218.188628 |

## Twelve disjoint wall segments

Each cell is **p50 / p95 / max in ms** for that component's own distribution. A column's component medians do not reconstruct its save median. Only the per-sample breakdowns below are additive. The test timer's small pre-request/post-return portions are separate and included in each actual save total.

| Segment | IDLE | DIRECT | OBSERVE |
|---|---|---|---|
| request → writer submit | 0.021031 / 0.106517 / 0.471949 | 0.020000 / 0.035523 / 0.065346 | 0.017576 / 0.064285 / 0.079237 |
| submit → writer run | 0.055747 / 0.296640 / 0.347444 | 0.093349 / 0.110223 / 0.319533 | 0.153097 / 1.888826 / 21.556330 |
| writer run → Room BEGIN entry | 0.077625 / 0.200226 / 0.227697 | 0.068271 / 0.095502 / 0.176762 | 0.069984 / 0.391159 / 1.259876 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.086088 / 0.515283 / 0.589402 | 0.081651 / 0.136522 / 0.431969 | 0.087960 / 0.862006 / 1.370710 |
| selected SQLite BEGIN call | 0.026379 / 0.078496 / 0.205164 | 0.025368 / 0.051526 / 0.390798 | 0.027701 / 3.426124 / 4.079642 |
| SQLite BEGIN exit → Room BEGIN exit | 0.004076 / 0.007591 / 0.016004 | 0.003515 / 0.004226 / 0.004236 | 0.003756 / 0.007241 / 0.039749 |
| Room BEGIN exit → body entry | 0.006319 / 0.025958 / 0.040881 | 0.005909 / 0.010987 / 0.011157 | 0.006329 / 0.017205 / 0.077614 |
| body entry → Room success entry | 0.954374 / 1.183763 / 2.733558 | 0.888836 / 1.365391 / 2.899373 | 0.968755 / 6.029438 / 7.157418 |
| Room success entry → SQLite END entry | 0.020881 / 0.025047 / 0.040931 | 0.019599 / 0.040300 / 0.127038 | 0.020651 / 0.026189 / 0.059458 |
| SQLite END call (commit + release) | 0.452054 / 0.638916 / 0.704212 | 0.560050 / 5.591750 / 7.674103 | 2.024548 / 5.823213 / 7.477272 |
| SQLite END exit → Room END exit | 0.057535 / 0.305022 / 0.317360 | 0.065497 / 0.776880 / 1.786325 | 0.058857 / 1.096824 / 1.242369 |
| Room END exit → caller return | 0.128705 / 0.413522 / 1.970228 | 0.275949 / 0.451419 / 3.540673 | 0.125437 / 1.652858 / 1.998359 |

## Same-thread CPU

All three arms have 30 valid same-thread endpoint deltas for each measured segment except submit→worker and Room-END→caller-return, whose endpoints differ in every sample. Those 180 cross-thread deltas are unavailable. CPU statistics below are independently ranked; they need not belong to the same sample as wall statistics. Same-thread CPU can include intervening work on a suspended event loop. Tiny CPU-over-wall differences are clock/endpoint measurement effects, not evidence of parallel CPU inside one thread.

| Segment | IDLE CPU p50 / p95 / max (ms) | DIRECT CPU | OBSERVE CPU |
|---|---|---|---|
| request → writer submit | 0.020971 / 0.106318 / 0.428635 | 0.019980 / 0.034952 / 0.065397 | 0.017496 / 0.064236 / 0.078937 |
| submit → writer run | — | — | — |
| writer run → Room BEGIN entry | 0.077685 / 0.199866 / 0.227337 | 0.068181 / 0.095391 / 0.115171 | 0.069533 / 0.131416 / 0.137514 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.086097 / 0.206025 / 0.515062 | 0.081571 / 0.136782 / 0.431839 | 0.082993 / 0.141168 / 0.155751 |
| selected SQLite BEGIN call | 0.026259 / 0.078747 / 0.135772 | 0.025268 / 0.047370 / 0.051476 | 0.027561 / 0.095482 / 0.376331 |
| SQLite BEGIN exit → Room BEGIN exit | 0.004056 / 0.007371 / 0.015853 | 0.003475 / 0.004186 / 0.004196 | 0.003755 / 0.007191 / 0.039528 |
| Room BEGIN exit → body entry | 0.006309 / 0.026058 / 0.040991 | 0.005929 / 0.010986 / 0.011077 | 0.006370 / 0.031747 / 0.077495 |
| body entry → Room success entry | 0.954253 / 1.038138 / 2.283622 | 0.888856 / 1.310531 / 1.336239 | 0.945981 / 1.331573 / 1.738855 |
| Room success entry → SQLite END entry | 0.020871 / 0.025077 / 0.040940 | 0.019599 / 0.040349 / 0.040570 | 0.020631 / 0.026159 / 0.059459 |
| SQLite END call (commit + release) | 0.095401 / 0.160403 / 0.175109 | 0.101651 / 0.357508 / 0.364540 | 0.112977 / 0.164345 / 0.194179 |
| SQLite END exit → Room END exit | 0.055613 / 0.088271 / 0.090113 | 0.050394 / 0.303368 / 0.306494 | 0.057285 / 0.083323 / 0.187508 |
| Room END exit → caller return | — | — | — |

The contiguous worker-run-entry→Room-END-exit envelope has wall / same-thread CPU medians of IDLE 1.725114 / 1.349900 ms, DIRECT 1.942877 / 1.262750 ms, OBSERVE 5.174003 / 1.367063 ms. These separately ranked medians also are not subtractable per-sample CPU deficits.

## Actual median and tail samples

OBSERVE's **actual p50, sample 20**, is 6.324345 ms. SQLite END alone takes 4.924512 ms with 0.119026 ms worker CPU. The author body is 0.887475 ms wall / 0.887474 ms CPU; submit→worker is 0.099858 ms. There is no index writer overlap. Read 20's live snapshot spans this save. This is not evidence that the reader caused exactly 4.924512 ms of delay.

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.019879 | 0.019829 | 48 → 48 |
| submit → writer run | 0.099858 | unavailable | 48 → 44 |
| writer run → Room BEGIN entry | 0.064105 | 0.064105 | 44 → 44 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.082101 | 0.082222 | 44 → 44 |
| selected SQLite BEGIN call | 0.026990 | 0.026880 | 44 → 44 |
| SQLite BEGIN exit → Room BEGIN exit | 0.003556 | 0.003525 | 44 → 44 |
| Room BEGIN exit → body entry | 0.005988 | 0.005999 | 44 → 44 |
| body entry → Room success entry | 0.887475 | 0.887474 | 44 → 44 |
| Room success entry → SQLite END entry | 0.018847 | 0.018838 | 44 → 44 |
| SQLite END call (commit + release) | 4.924512 | 0.119026 | 44 → 44 |
| SQLite END exit → Room END exit | 0.057685 | 0.057285 | 44 → 44 |
| Room END exit → caller return | 0.087270 | unavailable | 44 → 47 |
| Test timer outside request/return | 0.046079 | unmeasured | — |
| **Actual save total** | **6.324345** | not summed across threads | — |

OBSERVE's **actual p95, sample 4**, is 11.146675 ms. Its body takes 4.862550 ms / 1.126807 ms CPU. Within that body, head-written→revision-written is 3.957850 ms / 0.222218 ms CPU. END contributes 2.493974 ms / 0.096283 ms CPU; Room post-END 1.242369 ms / 0.076884 ms CPU; caller return 1.998359 ms with cross-thread CPU unavailable. Index rows stay 64→64 and there is no index writer overlap. Read lifetimes 4 and 5 overlap 10.579184 ms of this save; actual snapshots overlap 8.470251 ms. The raw endpoints do not identify why the worker was off CPU.

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.079237 | 0.078937 | 54 → 54 |
| submit → writer run | 0.051707 | unavailable | 54 → 44 |
| writer run → Room BEGIN entry | 0.137033 | 0.137514 | 44 → 44 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.138996 | 0.138745 | 44 → 44 |
| selected SQLite BEGIN call | 0.039969 | 0.039499 | 44 → 44 |
| SQLite BEGIN exit → Room BEGIN exit | 0.006790 | 0.006509 | 44 → 44 |
| Room BEGIN exit → body entry | 0.006409 | 0.006410 | 44 → 44 |
| body entry → Room success entry | 4.862550 | 1.126807 | 44 → 44 |
| Room success entry → SQLite END entry | 0.019629 | 0.019609 | 44 → 44 |
| SQLite END call (commit + release) | 2.493974 | 0.096283 | 44 → 44 |
| SQLite END exit → Room END exit | 1.242369 | 0.076884 | 44 → 44 |
| Room END exit → caller return | 1.998359 | unavailable | 44 → 48 |
| Test timer outside request/return | 0.069653 | unmeasured | — |
| **Actual save total** | **11.146675** | not summed across threads | — |

OBSERVE's **maximum, sample 17**, is 23.839401 ms. It queues 21.556330 ms before its worker starts; 21.523241 ms of that queue interval overlaps the immediately preceding index task 213. Only this sample of all 30 has an overlapping index writer. Its own author body and END are 0.963046 and 0.877750 ms. This is a direct serial-writer exposure, unlike an inference from merely seeing index progress change.

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.016604 | 0.016265 | 49 → 49 |
| submit → writer run | 21.556330 | unavailable | 49 → 44 |
| writer run → Room BEGIN entry | 0.075963 | 0.075912 | 44 → 44 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.119677 | 0.119857 | 44 → 44 |
| selected SQLite BEGIN call | 0.027521 | 0.027331 | 44 → 44 |
| SQLite BEGIN exit → Room BEGIN exit | 0.003605 | 0.003575 | 44 → 44 |
| Room BEGIN exit → body entry | 0.009224 | 0.009274 | 44 → 44 |
| body entry → Room success entry | 0.963046 | 0.963016 | 44 → 44 |
| Room success entry → SQLite END entry | 0.020039 | 0.020030 | 44 → 44 |
| SQLite END call (commit + release) | 0.877750 | 0.109231 | 44 → 44 |
| SQLite END exit → Room END exit | 0.062523 | 0.062082 | 44 → 44 |
| Room END exit → caller return | 0.075461 | unavailable | 44 → 49 |
| Test timer outside request/return | 0.031658 | unmeasured | — |
| **Actual save total** | **23.839401** | not summed across threads | — |

DIRECT's **actual p95, sample 8**, is 9.392518 ms. SQLite END is 7.674103 ms wall / 0.121790 ms CPU (about 81.7% of the save's wall duration); author body 0.982224 ms, submit→worker 0.069613 ms. No indexer runs during DIRECT. The direct search is still scanning authoritative pending page sources throughout this save.

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.022584 | 0.022503 | 48 → 48 |
| submit → writer run | 0.069613 | unavailable | 48 → 45 |
| writer run → Room BEGIN entry | 0.072828 | 0.072718 | 45 → 45 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.085396 | 0.085286 | 45 → 45 |
| selected SQLite BEGIN call | 0.032508 | 0.032489 | 45 → 45 |
| SQLite BEGIN exit → Room BEGIN exit | 0.003265 | 0.003234 | 45 → 45 |
| Room BEGIN exit → body entry | 0.005909 | 0.005929 | 45 → 45 |
| body entry → Room success entry | 0.982224 | 0.982225 | 45 → 45 |
| Room success entry → SQLite END entry | 0.017897 | 0.017886 | 45 → 45 |
| SQLite END call (commit + release) | 7.674103 | 0.121790 | 45 → 45 |
| SQLite END exit → Room END exit | 0.288107 | 0.048370 | 45 → 45 |
| Room END exit → caller return | 0.101600 | unavailable | 45 → 52 |
| Test timer outside request/return | 0.036484 | unmeasured | — |
| **Actual save total** | **9.392518** | not summed across threads | — |

DIRECT's **maximum, sample 25**, is 9.446588 ms: END 5.591750 ms / 0.115301 ms CPU, body 2.899373 ms / 1.336239 ms CPU; no index writer overlap. IDLE's maximum is its first save (sample 0), 5.314565 ms, with body 2.733558 ms / 2.283622 ms CPU and startup/prologue costs. Its p95 is sample 20, 4.737515 ms, including a 1.970228 ms cross-thread caller return tail.

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.028272 | 0.028352 | 52 → 52 |
| submit → writer run | 0.110223 | unavailable | 52 → 43 |
| writer run → Room BEGIN entry | 0.176762 | 0.115171 | 43 → 43 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.095712 | 0.095561 | 43 → 43 |
| selected SQLite BEGIN call | 0.028522 | 0.028432 | 43 → 43 |
| SQLite BEGIN exit → Room BEGIN exit | 0.003846 | 0.003816 | 43 → 43 |
| Room BEGIN exit → body entry | 0.010325 | 0.010435 | 43 → 43 |
| body entry → Room success entry | 2.899373 | 1.336239 | 43 → 43 |
| Room success entry → SQLite END entry | 0.024767 | 0.024747 | 43 → 43 |
| SQLite END call (commit + release) | 5.591750 | 0.115301 | 43 → 43 |
| SQLite END exit → Room END exit | 0.313514 | 0.061601 | 43 → 43 |
| Room END exit → caller return | 0.091986 | unavailable | 43 → 48 |
| Test timer outside request/return | 0.071536 | unmeasured | — |
| **Actual save total** | **9.446588** | not summed across threads | — |

| Segment | Wall ms | Same-thread CPU ms | Endpoint threads |
|---|---:|---:|---|
| request → writer submit | 0.471949 | 0.428635 | 47 → 47 |
| submit → writer run | 0.067936 | unavailable | 47 → 43 |
| writer run → Room BEGIN entry | 0.227697 | 0.227337 | 43 → 43 |
| Room BEGIN entry → selected SQLite BEGIN entry | 0.515283 | 0.515062 | 43 → 43 |
| selected SQLite BEGIN call | 0.016625 | 0.016485 | 43 → 43 |
| SQLite BEGIN exit → Room BEGIN exit | 0.004126 | 0.004116 | 43 → 43 |
| Room BEGIN exit → body entry | 0.011607 | 0.011627 | 43 → 43 |
| body entry → Room success entry | 2.733558 | 2.283622 | 43 → 43 |
| Room success entry → SQLite END entry | 0.024977 | 0.024957 | 43 → 43 |
| SQLite END call (commit + release) | 0.527050 | 0.153397 | 43 → 43 |
| SQLite END exit → Room END exit | 0.088611 | 0.088271 | 43 → 43 |
| Room END exit → caller return | 0.276159 | unavailable | 43 → 47 |
| Test timer outside request/return | 0.348987 | unmeasured | — |
| **Actual save total** | **5.314565** | not summed across threads | — |

## Index writer association

Both index batches are associated by the unique index writer-acquired event's thread and wall interval inside a background Room task; all raw task/transaction events remain in `derived.json`. The first 64-row commit finishes before OBSERVE readiness and before sample 0. The second 64-row commit advances index rows 64→128 during sample 17. The third batch starts preparation but is cancelled before a precomputed/writer marker; its unobserved completion is not fabricated.

| Index task / batch | Writer Runnable wall / CPU (ms) | Body acquired→written wall / CPU (ms) | SQLite END wall / CPU (ms) | Author queue overlap |
|---|---|---|---|---|
| 141 / 1 | 16.129524 / 11.537415 | 10.763138 / 10.693385 | 4.904642 / 0.383858 | none; before readiness |
| 213 / 2 | 21.543560 / 16.936590 | 20.208504 / 16.108864 | 0.913433 / 0.408024 | sample 17 only; 21.523241 ms |

Actual sequence for the blocking batch (all timestamps ms relative to the shared DIRECT/OBSERVE recorder origin):

- Index task 213 submitted 4966.858736; run entry 4967.450433; Room body acquired 4967.614235; batch-written 4987.822739; Room END exit 4988.901005; Runnable exit 4988.993993.
- Author sample 17 submitted 4967.470752; its writer task 214 enters 4989.027082. The handoff after task 213's exit is 0.033089 ms.
- The serial queue cannot execute the author Runnable while that earlier index Runnable is running. The evidence supports reducing this particular exposure as a targeted candidate, but **29/30 saves, including the actual median and p95, have zero such overlap**. Excluding the one exposed sample descriptively leaves a 29-save median of 6.324345 ms. This is not a counterfactual performance estimate.

## Natural read overlap and cancellation

The whole-window values below include the gaps between successive saves. They are geometric unions of actual observed lifecycle intervals clipped to that arm's first-save-start→last-save-end; they are not sums of concurrent elapsed times. Derived interval sorting is only for union geometry; raw event arrays and original event order are never sorted or rewritten.

| Arm | Window (ms) | IO lifecycle union overlap (ms) | Open snapshot union overlap (ms) | Index Runnable union overlap (ms) |
|---|---:|---:|---:|---:|
| IDLE | 68.360126 | 0.000000 | 0.000000 | 0.000000 |
| DIRECT | 105.442867 | 105.442867 | 105.442867 | 0.000000 |
| OBSERVE | 218.188628 | 195.717059 | 174.399279 | 21.543560 |

DIRECT has one completed read: its page-source hit scan takes 284.272668 ms wall / 271.004066 ms CPU on thread 49 and contains the complete 105.442867 ms save window. The later result-page query (97.289333 ms) and metadata query (5.756995 ms) do not overlap any DIRECT save. Source checks advance during saves; index rows stay zero. The independent reader does not enter the Room writer queue, but the trace does not imply zero shared CPU/storage/scheduler effects.

OBSERVE records 30 invalidation events, 29 scheduled read IDs, one cancelled debounce, 28 requested reads, two cancellations before IO starts, and 26 IO-started reads, all cancelled. Of those 26: 25 open a snapshot; 23 finish scope setup; nine reach page-hits-start but none finish that query. Last observed work stages are scope-end (13), page-hits-start (9), drafts-ready (2), connection-open (1), hits-ready (1). No result-page or metadata query starts in the recorded OBSERVE window. Those cancelled phase durations are **censored**: page-hits-start→snapshot-close includes unwinding and is not substituted for page query completion. In one read, read-start and read-cancel have different thread IDs; its IO-envelope CPU remains null.

| OBSERVE complete phase | Complete / incomplete count | Wall p50 / p95 / max (ms) | Same-thread CPU p50 / p95 / max (ms) | Complete-phase overlap with save window (ms) |
|---|---|---|---|---:|
| dispatch | 26 / 2 | 0.097354 / 1.186057 / 1.526601 | — | 7.298123 |
| connection_open | 26 / 0 | 0.148419 / 0.401184 / 0.718834 | 0.144233 / 0.277140 / 0.323619 | 5.671919 |
| register_functions_and_begin | 25 / 1 | 0.081010 / 0.177663 / 0.207678 | 0.080570 / 0.142922 / 0.143632 | 2.435942 |
| drafts | 25 / 0 | 0.796900 / 1.913083 / 3.502406 | 0.796770 / 1.029058 / 1.034932 | 24.537890 |
| scope | 23 / 0 | 2.469446 / 5.689354 / 6.296754 | 2.185494 / 2.492380 / 2.656746 | 69.054672 |
| hit_temp_setup | 10 / 13 | 0.415305 / 2.295739 / 2.295739 | 0.402385 / 0.649372 / 0.649372 | 6.880429 |
| page_hits | 0 / 9 | — | — | 0.000000 |

Index preparation is outside Room's author writer transaction. The second complete preparation spans 97.951393 ms of the save window; a third begins later and remains incomplete. Its CPU work and independent read lifecycle may overlap author work, but overlap alone is not a causal decomposition. The 69.054672 ms of completed scope intervals and 24.537890 ms of drafts intervals also must not be added to writer wait as alleged save overhead.

The old method58 had its own observed read/queue lifetimes and a longer window; this run has 26/26 cancelled IO reads and different scheduling. The new guard passes and equal initial fixture do not make these two instrumented probes a paired before/after benchmark. No old-run sample timings are pooled here.

## Decision and smallest missing evidence

There is enough evidence for a narrowly scoped **candidate experiment on index-writer maximum exposure**: reduce work per existing index commit while retaining the exact source recheck plus FTS/pending update in each transaction and allowing the serial writer between commits. The observed 64-row batch body consumes 20.208504 ms wall / 16.108864 ms CPU. A smaller commit would trade this exposure against additional transactions, callbacks, delays and total index completion time. No batch size or resulting speedup is established here, and the original 64-row readiness/fixture comparison must remain explicitly accounted for. It cannot be presented as a fix for the OBSERVE median/p95.

For the wider concurrent-save regression, **there is not enough evidence yet to select a production change**. Directly changing FULL, transaction durability, return semantics, cancellation, refresh scheduling, or search freshness would not follow from this trace. The next discriminating evidence is a narrowly bounded trace for the actual writer thread during long SQLite END/body intervals: distinguish runnable-but-descheduled time from a blocked native sync/checkpoint/connection operation, with the operation correlated to this task/transaction. A matched observer-off window is also required before interpreting instrumented timings as normal production latency. If that capability is unavailable under the existing environment permissions, the END off-CPU cause remains unresolved; no new framework or unbounded diagnostic run is justified by these measurements.

## Reproduction and deliverables

`analysis.json` is the untouched frozen analyzer output; `derive.py` performs only derived arithmetic with endpoint/thread checks, per-sample conservation assertions and exact input hash binding. `derived.json` retains all selected raw endpoints, reads, task association, all 90 per-sample overlaps and same-thread CPU. `segment-statistics.csv` and `save-overlaps.csv` are compact machine-readable tables. `binding.json` and `SHA256SUMS` bind the evidence and arithmetic script. Run `python3 -B derive.py` in this directory to reproduce derived JSON/CSV; it does not run Android, access the network, or modify production files.
