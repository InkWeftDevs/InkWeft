# Independent library review — run 38520196523

C1 reverses the prior warm-search slowdown on this frozen synthetic fixture: all four paired warm medians improve, with paired median changes of −71.49% versus B and −75.23% versus C0. Warm has no predeclared acceptance band; this is a measured improvement, not a newly invented PASS gate. Save acceptance remains open: B→C1 observe p50 is REGRESSION_SIGNAL, direct p95 is REGRESSION_SIGNAL, and direct p50 is INCONCLUSIVE. Faster cold search and much lower worst save latency do not offset those failures.

The independent calculation validates all 365 original forwarded JSON payload byte counts/SHA-256 values from the official decoded log, equality to all 62 extracted record arrays/hashes, all 720 real committed saves in 12 public library calls, and all 54 original metric classifications. Results agree with the supplied statistics, with no arithmetic/record mismatch. The 54 classifications are 45 WITHIN_PROPOSED_BAND, 5 INCONCLUSIVE, 4 REGRESSION_SIGNAL; one of the signals is the separate B→C1 raster eight-layer forced-full metric. This review does not reinterpret that raster result as a search result.

Source identities: B=a128bb07 plus the fixed common PreservingOpenHelperFactory override from 7949011; C0=d66b60d6; C1=cb7e1421. Readiness=d70fcb23, workflow=115734b0, job=115707901766, attempt=1. Public library probe SHA-256 is 90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c. The measured fixtures have matching logical row hashes per seed across all three versions. Verified writer settings are page_size=1024, auto_vacuum=FULL(1), WAL, synchronous=FULL(2). These are controlled-baseline results, not an untouched a128 APK comparison.

Evidence scope: the forwarded records and local frozen readiness bytes were independently checked; original APK/ELF files, installed bytes and full JUnit logs were not newly acquired or audited. PASS below means the strictly parsed bound-controller/JUnit receipts report PASS. All 62 methods completed, but neither run closure nor the engineering screening classification is final product acceptance. No network, build, device execution, production edit or input edit was performed in this review.

## Quantiles and medians

Each save group keeps its original 30 samples. p50/p95/max use nearest rank, indices 14/28/29 (zero based). Embedded legacy p50 fields use index 15 and are preserved only as historical fields; they are not used below. Warm uses the median of its five original consecutive readings. “Median” below is the median of four seed-specific values, never a pool of 120 saves or 20 warm readings. Paired median differences/relative changes are calculated per seed first.

| Metric (ms) | B median | C0 median | C1 median | B→C1 original classification |
|---|---:|---:|---:|---|
| library.allIndexRowsCommittedMs | 33782.391 | 34127.902 | 33827.302 | WITHIN_PROPOSED_BAND |
| library.coldMs | 4773.525 | 2168.929 | 700.518 | WITHIN_PROPOSED_BAND |
| library.direct.max | 4564.623 | 9.665 | 11.077 | WITHIN_PROPOSED_BAND |
| library.direct.p50 | 2.634 | 3.155 | 3.303 | INCONCLUSIVE |
| library.direct.p95 | 3.738 | 8.390 | 10.475 | REGRESSION_SIGNAL |
| library.observe.max | 4538.259 | 53.793 | 50.929 | WITHIN_PROPOSED_BAND |
| library.observe.p50 | 3.323 | 7.818 | 7.858 | REGRESSION_SIGNAL |
| library.observe.p95 | 98.186 | 22.607 | 19.042 | WITHIN_PROPOSED_BAND |
| library.searchIndexReadyMs | 34335.362 | 34738.629 | 33994.835 | WITHIN_PROPOSED_BAND |
| warm | 531.339 | 606.972 | 150.039 | DESCRIPTIVE; no band |

B→C1 observe p50 increases in all four pairs, paired median +4.406300 ms/+128.576%. Direct p95 exceeds its original 15% AND 5 ms band in seeds 4102–4104, with paired median +6.736854 ms/+180.559%; seed 4101 is slower by 4.151494 ms and therefore does not exceed the absolute band. Direct p50 has only seed 4102 over its band, yielding INCONCLUSIVE rather than a pass. C0→C1 observe p50 is also INCONCLUSIVE: paired median +0.039934 ms/+0.569%, with one seed over the band. Thus the matcher has not demonstrated a save-median fix.

## Original per-seed save distributions

| Seed | Version | Direct p50 / p95 / max (ms) | Observe p50 / p95 / max (ms) |
|---|---|---:|---:|
| 4101 | B | 2.599 / 3.978 / 6297.390 | 3.198 / 102.197 / 4493.680 |
| 4101 | C0 | 3.127 / 7.915 / 9.343 | 8.018 / 15.498 / 52.267 |
| 4101 | C1 | 3.340 / 8.129 / 8.342 | 7.880 / 15.512 / 50.612 |
| 4102 | B | 2.844 / 3.564 / 4498.116 | 3.798 / 99.344 / 4515.041 |
| 4102 | C0 | 2.975 / 8.430 / 11.603 | 7.057 / 24.086 / 58.387 |
| 4102 | C1 | 3.889 / 11.474 / 12.487 | 8.067 / 18.413 / 51.246 |
| 4103 | B | 2.637 / 3.887 / 4501.478 | 3.293 / 96.410 / 4561.478 |
| 4103 | C0 | 3.184 / 8.967 / 9.987 | 7.619 / 25.272 / 55.320 |
| 4103 | C1 | 2.972 / 10.577 / 11.746 | 7.837 / 19.670 / 43.894 |
| 4104 | B | 2.631 / 3.589 / 4627.768 | 3.354 / 97.028 / 4650.297 |
| 4104 | C0 | 3.241 / 8.350 / 8.767 | 8.209 / 21.129 / 44.861 |
| 4104 | C1 | 3.266 / 10.373 / 10.408 | 7.350 / 22.615 / 56.028 |

All 720 ordered samples, intervals, Committed flags and revisions are retained in search-review.json. Direct saves are revisions 2–31; the separately paused save advances revision 32; observe saves are revisions 33–62. The probe asserts the final author body/revision and on-demand full-body equality outside timing. The paused save is not one of these 720 latency samples.

## Cold search, index completion and warm reads

| Seed | Version | Cold ms | First64 ms | All20k ms | Index-ready ms | Warm median ms |
|---|---|---:|---:|---:|---:|---:|
| 4101 | B | 6500.287 | 113.544 | 33507.536 | 34054.624 | 524.785 |
| 4101 | C0 | 2127.940 | 135.561 | 34174.982 | 34784.213 | 596.976 |
| 4101 | C1 | 687.271 | 137.028 | 33747.269 | 33919.826 | 150.268 |
| 4102 | B | 4705.915 | 129.799 | 35378.685 | 36532.632 | 537.893 |
| 4102 | C0 | 2189.465 | 135.255 | 34737.814 | 35378.126 | 618.501 |
| 4102 | C1 | 713.495 | 138.861 | 34794.231 | 34963.790 | 152.702 |
| 4103 | B | 4707.009 | 120.171 | 33681.907 | 34218.129 | 519.772 |
| 4103 | C0 | 2173.954 | 136.518 | 33878.961 | 34502.999 | 612.380 |
| 4103 | C1 | 694.374 | 133.969 | 33447.639 | 33611.112 | 149.810 |
| 4104 | B | 4840.041 | 119.335 | 33882.875 | 34452.595 | 553.335 |
| 4104 | C0 | 2163.904 | 134.877 | 34080.821 | 34693.044 | 601.564 |
| 4104 | C1 | 706.661 | 139.920 | 33907.335 | 34069.845 | 149.536 |

All 12 index traces contain 313 ordered commit callbacks: 64 rows per batch except the final 32, ending at 20,000. First-batch callbacks are post-transaction observations; they are not writer-entry timestamps. All-index time is from observe start; index-ready time also includes completion of the final fresh search. All-index B→C1 paired median change is −104.904 ms/−0.312%, and index-ready change is −494.884 ms/−1.442%; neither justifies claiming materially improved indexing throughput.

| Version | First→last incremental rows/s, seeds 4101–4104 | Median rows/s | Median direct joint ms | Median observe joint ms |
|---|---|---:|---:|---:|
| B | 596.994, 565.578, 594.010, 590.459 | 592.235 | 4857.885 | 34335.362 |
| C0 | 585.674, 576.142, 590.829, 587.287 | 586.480 | 2168.699 | 34738.629 |
| C1 | 593.153, 575.264, 598.433, 590.392 | 591.772 | 700.314 | 33994.835 |

Incremental throughput is (20,000−64)/(last callback−first callback); it excludes the first 64 rows and startup. Direct joint is the later of cold-worker exit and the final direct save, relative to cold start. Observe joint is the later of index-ready and the final observe save, relative to observe start; here every observe joint equals index-ready. Callback logging may contribute to index wall time. Warm raw five-reading sequences remain in JSON, including the two >1 second B/4102 values; none is removed. Cold means an initially unindexed fixture, not cold device boot or guaranteed cold OS/database caches: fixture creation and logical-hash reads precede measurement.

## Real concurrent exposure

| Seed | Version | Direct saves overlapping cold / advancing scan | Observe index rows before→after saves | Observe saves advancing rows / scan / both |
|---|---|---:|---:|---:|
| 4101 | B | 1 / 1 | 64→512 | 7 / 1 / 0 |
| 4101 | C0 | 30 / 30 | 64→128 | 1 / 1 / 1 |
| 4101 | C1 | 30 / 30 | 64→128 | 1 / 2 / 1 |
| 4102 | B | 1 / 1 | 64→576 | 8 / 1 / 0 |
| 4102 | C0 | 30 / 30 | 64→128 | 1 / 1 / 1 |
| 4102 | C1 | 30 / 30 | 64→128 | 1 / 2 / 1 |
| 4103 | B | 1 / 1 | 64→576 | 8 / 1 / 0 |
| 4103 | C0 | 30 / 30 | 64→128 | 1 / 1 / 1 |
| 4103 | C1 | 30 / 30 | 64→128 | 1 / 2 / 1 |
| 4104 | B | 1 / 1 | 64→512 | 7 / 1 / 0 |
| 4104 | C0 | 30 / 30 | 64→128 | 1 / 2 / 1 |
| 4104 | C1 | 30 / 30 | 64→128 | 1 / 1 / 1 |

B has only its first direct save overlap the cold reader; it blocks for roughly 4.5–6.3 seconds, then the other 29 run after the reader. C0/C1 permit all 30 direct saves while scanning continues. Consequently the original direct p95 comparison has different naturally occurring concurrency exposure. This explains a comparison boundary; it does not waive its REGRESSION_SIGNAL or remove samples. The paused-reader writer completes within 750 ms for all C0/C1 seeds and for no B seed.

Every candidate observe group spans a real 64→128 committed index advance, and its same save window also has source checks advance. B spans seven or eight batch callbacks and one long scanning save; index row callbacks and source-count movement are proof of progress/concurrency, not a measurement of writer lock duration. Candidate source totals after saves are C0=[284,320,269,222], C1=[792,665,704,677]; the greater C1 count indicates more scanning during these windows, not automatically wasted work or a causal allocation of save latency.

B observe() does not subscribe to notes invalidation; C0/C1 do, so current author edits cause real fresh-read cancellation/restart only in candidates in this fixed flowOf(request) fixture. That intentional freshness requirement must remain. B’s missing invalidation is an exposure difference, not permission to weaken refresh semantics or exempt the original p50 band.

## Read scale and correctness boundaries

| Returned UTF-16 / row measure (same in all four seeds) | B | C0 | C1 |
|---|---:|---:|---:|
| Initial header body characters | 10010000 | 0 | 0 |
| Shelf directory body characters | 10010000 | 0 | 0 |
| SQL projection preview rows | unavailable | 61 | 61 |
| SQL projection preview characters | unavailable | 36025 | 36025 |
| Returned 60 item body/preview characters | 1181180 | 35425 | 35425 |

All versions report fullBodyOnDemandMatches=true. Candidate previews are 60 long previews ×600 plus the final 25-character author head =36,025 fetched characters; 60 returned items contain 59 long previews plus the author =35,425. Header/directory bodies are zero, but directory metadata/classification may still include all 501 notes. These are SQL projection/result character counts, not physical SQLite page reads, disk bytes, or a promise of O(pageSize) metadata work.

Related C1 receipts PASS: Unicode/pinned/draft paging with complete ordered traversal and no duplicate IDs (method1), read snapshot/classification consistency while a writer commits (2), stale request header and Unicode-blank folder author freshness (3), cancellation/clear/category freshness (4), stale/deleted page and trashed card exclusion (5), source freshness/validation (6–7), actual UI second-page reset from sourceVersion (17), cancelled read skips directory assembly (18), six cancelled SQL-setup boundaries followed by a fresh committed author read (31), and profiled/default Unicode/source-result equivalence (59). Method1 also asserts the MATERIALIZE page plan and id-index body lookups. These assertions strengthen bounded projection/ordering/freshness evidence; receipts are not a new physical-I/O measurement or a substitute for every original UI/20k deadline test.

Fixture dimensions are 500 long-body notebooks ×40 searchable pages plus one author notebook/blank page: 501 notes, 20,001 page rows, 20,000 search-text/pending rows initially. Book bodies are 20,020 UTF-16 each; 19,999 page texts are 1,031 each and one target is 11 characters. It is repeated-character-heavy, mostly ASCII, with a single target-only hit, no drafts, and empty category/tag state in the timed public fixture. Four seeds vary IDs/order ties, not text distributions. The first-ASCII matcher is consistent with the measured warm benefit, but broad real-note/Unicode workloads or a universal speedup are not demonstrated. No warmthreshold or pooled sample was introduced.

The current C1-only stage diagnostic (method58) is excluded from all public numbers above. A separate causal-plan.md reviews it and the already obtained 78a causal evidence; no future run or speculative production change is authorized by this numerical review.
