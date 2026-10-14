# Native causal conclusion: matching is actionable; save p50 is still open

Run **38514139119**, job **115689021611**, source **78a5babeaeb92292026423222d5e08635989198b**, readiness **b2e1d9f9de66a26f5a4b122ea6754d77198005f8**. The strict extraction is `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`, issues empty. The independent analyzer checked all **12 cases / 360 committed saves / 3,792 stage events**, with zero relation issues. Raw records and original sequence are retained. This is one instrumented causal run, not the frozen public acceptance comparison.

## Warm evidence supports the small matcher change

All three profiled reads execute **501 body callbacks over 10,010,025 UTF-16 units**, 501 title callbacks, 501 empty-tag callbacks and one 11-character source callback. The original SQL is identical before/after, and the instrumented query differs only in its diagnostic function names; the actual EXPLAIN plans agree.

| Read | Body getString CPU ms | Body contains CPU ms | Page CPU ms | Body contains / page CPU |
|---|---:|---:|---:|---:|
| 0 | 26.223482 | 572.012532 | 625.246933 | 91.4859% |
| 1 | 25.801384 | 564.928443 | 616.854709 | 91.5821% |
| 2 | 25.737401 | 572.703112 | 625.768858 | 91.5199% |

The independent in-memory equivalent Kotlin OR matcher takes **563.612656 / 576.886476 / 566.782013 ms thread CPU** after preload. Thus the heavy cost persists without SQL argument retrieval or cursor work. This directly supports targeting matching rather than connection reuse, a covering index, broader body materialization or SQL CASE rewrites. Page CPU not captured in title/body/tags conversion and matching remains **21.638–22.651 ms**; this remainder does not isolate JNI or I/O. The diagnostic callback clocks add overhead, but that overhead cannot explain hundreds of milliseconds reproduced by the uninstrumented in-memory matcher.

The smallest justified candidate is the ASCII-first-character rejection filter: skip a candidate only when query first character and source candidate character are both ASCII with unequal ASCII folds; otherwise use the unchanged full `query.regionMatches(0,text,candidate,query.length,true)`. Non-ASCII query starts use the original `contains(true)`. This keeps SQL, source selection, Unicode verification, callback counts, draft precedence and cancellation entry checks. Host semantic checks were separate from this native evidence; no native speedup has yet been measured. Parent has authorized this local implementation after this report.

## Save evidence does not close p50

All percentiles use nearest rank; for 30 samples, p50 is sorted index 14, p95 index 28, maximum index 29.

| Mode | Block 0 p50 / p95 / max ms | Block 1 p50 / p95 / max ms |
|---|---|---|
| IDLE | 2.550575 / 5.190518 / 5.382075 | 1.751062 / 2.207735 / 2.673494 |
| TRACKER | 2.602983 / 3.086596 / 4.078590 | 2.096276 / 3.049226 / 5.359753 |
| INDEX_ONLY | 2.758493 / 9.267573 / 11.525792 | 2.553644 / 7.144086 / 11.612343 |
| INDEX_TRACKER | 3.269979 / 10.979550 / 21.044074 | 2.666992 / 7.250645 / 10.564366 |
| READS_ONLY | 3.822479 / 7.099947 / 7.482048 | 2.855565 / 9.483978 / 10.379310 |
| FULL | 5.926872 / 15.977829 / 31.924389 | 5.606635 / 15.387647 / 41.530293 |

Equal logical fixture hashes are verified within each block. Mode readiness and background progress still differ, and order is reversed in block 1. These values cannot be treated as additive independent causal contributions or substituted for the old B/C public comparison.

Each FULL case has **23 started IO reads, all cancelled, zero page-start events**. Readers stop at connection/draft/scope/hit setup or pending page-source scanning; none reaches the warm author-body page matcher. The actual save windows overlap **183.303 / 210.381 ms** of recorded read IO and **73.294 / 68.747 ms** of complete scope construction, plus **25.857 / 36.688 ms** of draft preparation. These are union wall intervals clipped to each save window, not independent CPU totals. Connection opening occupies only **5.856 / 10.055 ms** in those windows. The warm helper therefore cannot be claimed to close save p50; it may reduce source verification or future completed reads, but that effect is not yet measured.

The two maximum FULL saves have direct overlap evidence:

- Block 0 sample 20: **31.924389 ms** total; request-to-entry **30.175421 ms**; **28.211965 ms** overlaps the index transaction body; committed row count advances 64→128.
- Block 1 sample 19: **41.530293 ms** total; request-to-entry **38.057174 ms**; **28.414072 ms** overlaps the index transaction body; committed row count advances 64→128.

This supports an index-serialization contribution to those particular tails, not to all saves. In each block, the other **29 samples without observed index-body overlap retain exactly the same p50**, 5.926872 and 5.606635 ms. Their presence is retained, not trimmed from the main distributions. FULL request-to-entry p50 is only **0.475846 / 0.491118 ms**. Receipt-written→transaction-returned p50 is **3.042345 / 2.681860 ms**, compared with IDLE **0.888609 / 0.545780 ms**. That tail includes Room transaction exit, invalidation and coroutine return scheduling; it is not a pure commit/lock measure. Its endpoint threads differ in all 30 samples of every mode, so CPU differences are deliberately unavailable. Inside the transaction, FULL body CPU p50 is **1.119861 / 1.120788 ms**, while corresponding wall p50 is **1.404564 / 1.171769 ms**.

Important censored interval: INDEX_ONLY and block 1 INDEX_TRACKER begin the next preparation during saves, but cancellation occurs before batch-precomputed. Complete-interval prepare overlap displays zero for those cases. This means **no complete preparation interval**, not no indexing work. Their first-source/first-tokenization witnesses remain in the original timeline; the missing endpoint is not invented. Likewise, READS_ONLY lacks Observation-level diagnostics by construction, so absence of observe-invalidation events cannot mean no invalidations.

## Next scope

Implement only the small matching helper supported by the warm measurements, then validate its exact semantics and the unchanged native/public gates. Do not change notes invalidation, debounce, batch size, snapshot freshness or SQL layout based on this run. Saving p50 remains a separate open requirement: the present evidence localizes much of its extra wall time to return/reader-restart overlap but does not identify a safe minimal production change that removes it. If further localization is authorized, the useful split is Room transaction end/invalidation completion versus caller resumption, keeping endpoints and thread IDs explicit; not another broad performance rewrite.
