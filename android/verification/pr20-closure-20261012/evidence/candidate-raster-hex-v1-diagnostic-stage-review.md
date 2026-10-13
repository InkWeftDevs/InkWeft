# Hex v1 diagnostic stage comparison

**The local hex/result stage cost is substantially lower; overall raster acceptance is still open.** New candidate Tile passed 2/2 in the 274.802s receipt. This review compares three separate diagnostic-on runs only: new hex-v1 candidate, prior Formatter diagnostic-v2 candidate, and prior Formatter diagnostic-v2 baseline. It does not pool default-off data or declare end-to-end regression closed.

## Stage evidence

Values below are milliseconds. Multi-layer values are sums of per-layer samples and can overlap; they are not extra append wall time. All CPU endpoint thread IDs match, but CPU remains cumulative guest-thread CPU, not exclusive coroutine CPU or host cycles.

| Layers | Version | Query+digest CPU | Hex+result wall | Hex+result CPU |
|---:|---|---:|---:|---:|
| 1 | Prior candidate / Formatter | 1006.581 | 520.046 | 477.805 |
| 1 | Prior baseline / Formatter | 316.041 | 253.398 | 242.914 |
| 1 | New candidate / stdlib | 279.072 | 7.439 | 7.000 |
| 4 | Prior candidate / Formatter | 1967.328 | 2524.585 | 1316.009 |
| 4 | Prior baseline / Formatter | 683.079 | 600.234 | 538.983 |
| 4 | New candidate / stdlib | 730.053 | 8.586 | 8.534 |
| 8 | Prior candidate / Formatter | 1570.476 | 1794.499 | 1510.980 |
| 8 | Prior baseline / Formatter | 1130.904 | 1085.961 | 943.419 |
| 8 | New candidate / stdlib | 1294.278 | 36.371 | 32.590 |

New hex/result CPU fell from the prior candidate by 98.53%, 99.35%, 97.84% for 1/4/8 layers. The measured interval includes SHA finalization and allocation as well as encoding. The source changed exactly one expression, removing per-byte Formatter calls, while all versions retain rows 321/321/321, regions 24/24/48, query batches 3/8/16 and zero preparation rows/batches. All decode 13/21/11 strokes and rebuild one dirty tile; candidate native builds stay 13/21/11. Baseline native counts remain absent/null. Existing pixel and destructive-frame assertions pass.

## Whole-frame limits

| Layers | Prior candidate append | Prior baseline append | New candidate append | New metadata | New raster | New publication sum |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 3362.609 | 1413.979 | 1686.031 | 425.233 | 866.229 | 17.473 |
| 4 | 16124.940 | 4750.426 | 5402.114 | 2048.067 | 2992.889 | 3349.669 |
| 8 | 11640.257 | 8275.651 | 10344.866 | 2099.431 | 2203.073 | 21885.553 |

Despite the smaller hex stage, new append remains +19.24%, +13.72%, +25.00% above the prior diagnostic baseline for 1/4/8 layers. These are one-run diagnostic comparisons, not statistical regressions or default-off acceptance. Query/digest CPU is still 279.072/730.053/1294.278ms; raster and publication remain substantial. Eight-layer publication sums exceed frame wall because waits overlap.

Unchanged query+digest times also fell substantially versus the prior candidate. The entire append reduction therefore cannot be assigned to the changed hex expression. Direct conversion work, allocation/GC effects, runtime scheduling and host conditions are not independently isolated. The reported three metadata subsegments also omit entry/return/dispatch overhead; they do not exactly sum to metadata wall.

New initial frames are 7746.502/9197.766/14274.377ms, versus prior baseline 6956.689/8303.278/11672.994ms. Preserve these as well as the append result; do not select only the favorable hex row or discard the original slow v4/default-off pair.

## Artifact and next-step boundary

New source manifest `f06615ffe9ae2faaf81ffcae984d82b018307c874755273339f62586f5fbe4ab` binds 744 source files, unchanged during the passing 214.550s build. New app APK is `d4af16103b601e7d73af673eeb89d6511003a0f0ef4c86785862d5e202714b5e`; appTest is `5ce3e01030cc7ce27d93ed3aed7027a4f670716785235c569c0d4e84e3528407`. Installed/signature receipts bind those bytes. New InkRepository source hash is `3cb60b773e4f358cc871e59f0837bb9304d8ae114bf12997bf3b897527801d34`. The appTest bytes and sources remain the prior candidate test build; the production app changed.

All six before/after configuration receipts across these three runs agree on `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. This is API35 software Android/TCG; matching configuration does not establish equal instantaneous load, JIT, cache or per-frame GC. No APK or full-source rehash was performed while the new default-off run was active.

Continue the fixed plan: new default-off C/B/B/C samples, FrameProbe on both sides, then candidate NativeStrokeTileReuse and complete Streamed class. Keep the original dense 30-second gate and all earlier failures. Tile-only success does not fulfill these remaining gates. No additional source change is recommended before their results. Detailed per-layer raw records, stage ratios, artifact receipts and hashes are in `candidate-raster-hex-v1-diagnostic-stage-review.json`.
