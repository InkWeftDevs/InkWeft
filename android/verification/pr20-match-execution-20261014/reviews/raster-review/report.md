# Independent raster review: run 38520196523

The fixed run completed all 62 methods, but raster performance acceptance is not fully closed. Independent recomputation agrees with all 27 frozen raster classifications and all 108 seed-paired values. B→C1 has eight metrics within the proposed band and one regression signal: 8-layer forced full. C0→C1 has seven within and two inconclusive: 8-layer first/full. No sample was dropped and no threshold changed.

## Evidence and identity

Run `38520196523`, job `115707901766`, workflow `115734b0ed9aab07a1100087ce76978c0d829e0f`, readiness `d70fcb23afecb6f80e8fc716a5145ecf851dd68e`. Strict parser: `COMPLETE_SCOPED_MATCH_LOG_EVIDENCE`, issues `[]`; official run/job completed successfully; packaging has no missing files and both cleanup receipts succeeded. This is scoped log evidence, not an independent APK/ZIP/JUnit audit.

B=`a128bb07e34cb82f00eeafc8e62a47bf7b95b128`, C0=`d66b60d6cf9587f646acb76dd8a00910b73a6d70`, C1=`cb7e14218ac83aa8cf6b9d7906051a10da1a6547`. B includes the disclosed `PreservingOpenHelperFactory.kt` production override from `7949011`; it is a controlled baseline, not an untouched old APK. Common probe bytes are identical (`a916d247…`). The context source-manifest and selected/installed APK hashes match per identity across all calls. Common fixture logical hashes match across the three identities for every seed/layer; writer SQLite is 1024/1/wal/2. All relevant context environment hashes match (`bd8a6a68…`); that does not measure host scheduling or CPU cycles. Full hashes and all contexts are in [report.json](report.json).

The audit re-hashed the supplied official log and provenance files, verified each selected RECORD original JSON byte count/hash/order against its END ledger, reconstructed canonical records SHA, and checked the extracted compact-file SHA. Source assertions for methods 60–62 were read from the exact C1 git commit and matched the context test hashes. Inputs were not modified.

## Fixed four-seed screening

Bands remain 10% and 5 ms. `REGRESSION_SIGNAL` means at least three positive deltas, paired median relative change >10%, and paired median absolute change >5 ms. Otherwise any single pair above max(10%,5 ms) is `INCONCLUSIVE`; the rest are `WITHIN_PROPOSED_BAND`. These are frozen screening labels, not significance estimates. B→C0 is an order-unbalanced ancillary comparison (9/9 within), retained in JSON.

| Layer / metric | B→C1 median Δ ms / % | Status | C0→C1 median Δ ms / % | Status |
|---|---:|---|---:|---|
| 1 / firstCompleteMs | -0.258647 / -0.072354% | WITHIN_PROPOSED_BAND | -6.034233 / -1.767930% | WITHIN_PROPOSED_BAND |
| 1 / appendCompleteMs | -16.542537 / -25.765618% | WITHIN_PROPOSED_BAND | -7.751109 / -11.901483% | WITHIN_PROPOSED_BAND |
| 1 / forcedFullCompleteMs | -16.356619 / -6.468273% | WITHIN_PROPOSED_BAND | -16.940630 / -6.684225% | WITHIN_PROPOSED_BAND |
| 4 / firstCompleteMs | -1.532798 / -0.605672% | WITHIN_PROPOSED_BAND | +1.912332 / +0.785437% | WITHIN_PROPOSED_BAND |
| 4 / appendCompleteMs | -7.970247 / -5.719986% | WITHIN_PROPOSED_BAND | -1.133707 / -0.863766% | WITHIN_PROPOSED_BAND |
| 4 / forcedFullCompleteMs | -18.846982 / -7.472881% | WITHIN_PROPOSED_BAND | -8.588096 / -3.552021% | WITHIN_PROPOSED_BAND |
| 8 / firstCompleteMs | -13.583490 / -4.027952% | WITHIN_PROPOSED_BAND | -4.002133 / -1.045389% | INCONCLUSIVE |
| 8 / appendCompleteMs | -38.947598 / -16.594130% | WITHIN_PROPOSED_BAND | +0.381272 / +0.241440% | WITHIN_PROPOSED_BAND |
| 8 / forcedFullCompleteMs | +39.038078 / +12.219478% | REGRESSION_SIGNAL | +29.610528 / +8.926004% | INCONCLUSIVE |

The 8-layer forced-full B→C1 deltas for seeds 4101–4104 are **−21.328534, +61.678235, +16.397921, +65.572811 ms**. Against C0, all four forced-full deltas are positive: **+23.892312, +35.328744, +12.896216, +43.148379 ms**; the median relative change is 8.926004%, so the unchanged rule returns INCONCLUSIVE. Neither pattern is explained by identical decode/rebuild counts. Do not call it noise or claim the drawing optimization fully accepted.

The previous full run’s 45b→d66 single-layer append signal (+9.468752 ms/+26.372281%) remains part of the history. This run compares d66→cb7: deltas −16.120839, −18.079657, +0.618621, +1.204933 ms; median −7.751109 ms/−11.901483%, within the band. The latest candidate does not reproduce that screening signal in these four current pairs; that does not identify the old cause or prove lazy Pencil construction alone produced the difference. These runs are not pooled.

## What the counters and pixels prove

All 36 local-versus-full pixel comparisons passed. For every seed, B local decoded payloads are 13/21/11 for 1/4/8 layers; both C0 and C1 decode 1/1/1. Each local update rebuilds exactly one tile; each forced full decodes 321 strokes and rebuilds 24/24/48 tiles. Candidate native Stroke constructions remain 13/21/11: old Kotlin payload/geometry is reused, but native Stroke objects are still constructed for the strokes actually drawn in each frame. B has no native counter; its value is null, never zero. These counters establish reduced payload decode work and correct local output, not universal wall-time improvement or cross-frame native reuse.

All 36 common cleanup rows report joined=true, totalBytes=0 and inFlightJobs=0. The frozen probe captures actual Jobs, cancels/joins them, checks completion and worker/geometry restoration, then emits the receipt. It also tests a destructive visibility update cannot display stale pixels while the replacement is pending and matches a complete reference afterward. Additional methods 19–30 all PASS: cache epochs/head/payload/masks/undo, revision jumps, bounded caches, viewport/zoom readiness, interrupted pan join, clear during pending work, and omitted strokes.

## Added method 60: native reuse oracle

| Phase | Candidate native builds | Per-tile reference builds | Rebuilt tiles | Pixels equal |
|---|---:|---:|---:|---|
| 0 | 2 | 24 | 16 | true |
| 1 | 2 | 32 | 16 | true |
| 2 | 1 | 16 | 16 | true |
| 3 | 0 | 0 | 16 | true |

The oracle independently executes the prior per-tile construction loop inside the C1 test; it is not the installed B APK. Mixed PEN/HIGHLIGHTER/PENCIL, fractional transforms/zoom, clipping/finite paper, omitted strokes and allowed-only PENCIL states pass whole-bitmap comparisons. Native construction reuse is within the current stroke’s dirty-tile loop. Lazy Pencil creation is exercised by this mixed fixture, but avoided allocation count/heap savings or speed are not measured. Cleanup uses clear plus timeout-or-null global-count polling; PASS alone does not prove a strict owned-Job join here.

## Added method 61: all 14 frame cases

| Case | Active ms | Own cold ms | Decode/read | Rebuild active/cold | Native builds | Reused geometry | Pixels |
|---|---:|---:|---:|---:|---:|---:|---|
| initial | 438.074562 | 281.724683 | 320/320 | 20/20 | 300 | 0 | true |
| transform-0-0 | 92.095968 | 251.900327 | 0/0 | 8/20 | 92 | 96 | true |
| transform-0-1 | 179.724512 | 199.549129 | 0/0 | 20/20 | 300 | 320 | true |
| transform-0-2 | 195.617829 | 183.023466 | 0/0 | 20/20 | 288 | 304 | true |
| transform-0-3 | 163.224551 | 199.559708 | 0/0 | 20/20 | 300 | 320 | true |
| transform-1-0 | 77.046878 | 167.811462 | 0/0 | 8/20 | 92 | 96 | true |
| transform-1-1 | 146.796042 | 182.999149 | 0/0 | 20/20 | 300 | 320 | true |
| transform-1-2 | 145.029444 | 166.987810 | 0/0 | 20/20 | 288 | 304 | true |
| transform-1-3 | 178.880183 | 183.167065 | 0/0 | 20/20 | 300 | 320 | true |
| transform-2-0 | 77.229219 | 168.817135 | 0/0 | 12/24 | 80 | 80 | true |
| transform-2-1 | 180.348082 | 168.060642 | 0/0 | 24/24 | 288 | 304 | true |
| transform-2-2 | 141.906255 | 183.580726 | 0/0 | 20/20 | 288 | 304 | true |
| transform-2-3 | 145.170772 | 166.318561 | 0/0 | 20/20 | 300 | 320 | true |
| separated-append | 58.821610 | 185.771036 | 2/2 | 2/20 | 21 | 22 | true |

Across the 12 different transforms, active decode/read totals are 0/0, compared with 3760/3760 for independent cold renders; active rebuild/native totals are 212/2916. Descriptive median active/cold duration is 145.983407/183.011307 ms. This is one sequence of differing transforms, not twelve repeated samples and not a B→C1 timing comparison. Initial active rendering and transform-0-2/transform-2-1 are slower than their own cold references and remain in the table.

The separated append reads/decodes only two new payloads, reuses 22 old geometries and rebuilds two tiles; its independent cold reference reads/decodes 322 and rebuilds 20. This supports narrow dirty-region reads plus append-safe geometry reuse. Fractional moves/scale still require fresh tile rasterization where exact pixels cannot be reused; preview is explicitly not frameReady/capture-ready. This evidence does not establish hardware display latency or cross-frame native caching. Cleanup has the same best-effort timeout-or-null limitation as method 60.

## Added method 62: original dense deadline

The original 1,201 strokes / 76,802 points fixture passed its unchanged 30,000 ms frame gate in **1362.832964 ms**. Fixture and bounded editor-window setup remain outside that gate. The editor initially held only 128 strokes / 8,192 points. Five snapshot batches read 256, 256, 256, 256, 177 rows (all 1,201 strokes) and the output pixel for the final red stroke passed the original check.

| Stage | Elapsed ms from test evidence start |
|---|---:|
| fixture-start | 135.964923 |
| fixture-complete | 808.090336 |
| editor-window-complete | 1599.201745 |
| frame-start | 1601.241066 |
| frame-complete | 2963.421878 |
| verified-and-released | 2965.015043 |

Published BuildStats: decoded/native=1201/1201, tiles/rebuilt=4/4, metadata=68.005780 ms, raster=1274.839024 ms, publication=0.980319 ms, bitmapBytes=800000, geometryBytes=1046656. Snapshot freeze/decode sums are 79.658182/94.645406 ms, already inside frame work, not extra time to add. One change callback is recorded.

verified-and-released is emitted only after clear, strict withTimeout(5 s) worker-count restoration, pending=false, and geometry-reservation restoration assertions. This proves the original bounded release assertions on this successful path; it is not a captured-Job join nor a full resource-snapshot zero assertion. All added methods exited naturally according to the process receipts; natural exit is a separate fact. Historical software-emulator dense failures remain preserved. One current KVM pass closes this run’s original functional deadline, without isolating the prior timeout cause or proving a cross-version dense speedup.

## Remaining scope and next minimum evidence

No evidence supports another production algorithm change from this run alone. The residual 8-layer forced-full regression concerns a cold rebuild; the public marker’s metadata/raster/publication fields describe the local append and sum per-layer intervals that may overlap. They cannot explain the cold-frame regression. The minimum useful follow-up is an independently selected 8-layer forced-full diagnostic using the existing default-off observer: collect each frame’s metadata stage, synchronous native preparation/construction wall plus same-thread CPU, worker-to-publication and enqueue/callback/ready timestamps. Bind source/APK/fixture/environment and compare the same forced-full phase across sources. Keep all samples and the public probe/thresholds unchanged. Only a repeatable work increase or identified redundant synchronous work justifies a targeted fix; scheduling intervals alone do not identify CPU work or an environmental cause.

The lazy Pencil renderer remains a source-supported elimination of unnecessary eager construction for pen-only frames. This run confirms mixed-pencil output but does not directly count its allocations. Geometry cache, sparse reads and per-stroke tile reuse have explicit work-count/pixel evidence; universal timing improvement, hardware screen behavior, and strict added60/61 owned-worker release remain outside that evidence.

## Complete common raw ledger (milliseconds)

| Ordinal/source/seed/layers | First | Append | Forced full | Append metadata | Append raster | Append publication | Decode/native | Local/full rebuild |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 32/B/4101/1 | 329.936532 | 63.872273 | 253.215940 | 27.747103 | 20.565931 | 0.570500 | 13/null | 1/24 |
| 32/B/4101/4 | 255.715001 | 132.297164 | 258.770404 | 52.689008 | 94.435258 | 85.433144 | 21/null | 1/24 |
| 32/B/4101/8 | 347.003134 | 169.160309 | 358.822529 | 77.824846 | 84.079301 | 227.261034 | 11/null | 1/48 |
| 34/C0/4101/1 | 345.286958 | 64.223127 | 253.195050 | 22.022957 | 17.784468 | 0.668506 | 1/13 | 1/24 |
| 34/C0/4101/4 | 268.864017 | 127.029750 | 247.103123 | 42.864480 | 72.963997 | 102.264678 | 1/21 | 1/24 |
| 34/C0/4101/8 | 334.002418 | 167.283180 | 313.601683 | 52.624796 | 46.793264 | 280.523759 | 1/11 | 1/48 |
| 36/C1/4101/1 | 339.192326 | 48.102288 | 236.151907 | 18.457774 | 16.664300 | 0.588810 | 1/13 | 1/24 |
| 36/C1/4101/4 | 251.895880 | 122.984125 | 217.608782 | 45.034836 | 65.585930 | 100.375705 | 1/21 | 1/24 |
| 36/C1/4101/8 | 343.988100 | 169.450269 | 337.493995 | 57.933027 | 61.163025 | 265.255514 | 1/11 | 1/48 |
| 38/C1/4102/1 | 328.533075 | 47.193895 | 236.290029 | 17.855143 | 17.949146 | 0.761097 | 1/13 | 1/24 |
| 38/C1/4102/4 | 247.285673 | 136.570807 | 235.980497 | 50.010511 | 77.428283 | 132.058795 | 1/21 | 1/24 |
| 38/C1/4102/8 | 336.109389 | 171.445097 | 380.560993 | 48.426490 | 55.909669 | 272.096902 | 1/11 | 1/48 |
| 40/B/4102/1 | 325.959405 | 64.508983 | 253.548367 | 28.277329 | 19.988768 | 0.646592 | 13/null | 1/24 |
| 40/B/4102/4 | 274.913205 | 135.358719 | 252.122224 | 58.522960 | 99.642636 | 93.597019 | 21/null | 1/24 |
| 40/B/4102/8 | 383.304194 | 245.564296 | 318.882758 | 112.615384 | 116.733539 | 310.466097 | 11/null | 1/48 |
| 42/C0/4102/1 | 333.811333 | 65.273552 | 253.895628 | 17.978102 | 20.635417 | 0.698830 | 1/13 | 1/24 |
| 42/C0/4102/4 | 244.418946 | 136.905322 | 242.607845 | 45.276837 | 73.770145 | 117.980378 | 1/21 | 1/24 |
| 42/C0/4102/8 | 354.099337 | 172.849642 | 345.232249 | 67.449647 | 59.538090 | 292.063072 | 1/11 | 1/48 |
| 44/C0/4103/1 | 337.358415 | 47.647305 | 253.692341 | 17.811156 | 18.163476 | 0.569791 | 1/13 | 1/24 |
| 44/C0/4103/4 | 240.688093 | 130.320244 | 226.086602 | 44.749797 | 72.271450 | 111.325715 | 1/21 | 1/24 |
| 44/C0/4103/8 | 306.142759 | 166.411739 | 325.220203 | 62.004568 | 73.304594 | 233.642181 | 1/11 | 1/48 |
| 46/C1/4103/1 | 331.384580 | 48.265926 | 236.854224 | 18.039309 | 17.429315 | 0.675074 | 1/13 | 1/24 |
| 46/C1/4103/4 | 241.646030 | 128.387344 | 245.405645 | 48.326192 | 67.535269 | 105.104637 | 1/21 | 1/24 |
| 46/C1/4103/8 | 363.544573 | 164.471563 | 338.116419 | 55.632516 | 61.671355 | 249.709602 | 1/11 | 1/48 |
| 48/B/4103/1 | 335.457325 | 67.016400 | 252.503429 | 27.577752 | 19.960860 | 0.600826 | 13/null | 1/24 |
| 48/B/4103/4 | 238.406311 | 136.585196 | 254.773049 | 57.162412 | 96.783050 | 109.325249 | 21/null | 1/24 |
| 48/B/4103/8 | 342.625784 | 171.696924 | 321.718498 | 82.159513 | 97.185860 | 225.244316 | 11/null | 1/48 |
| 50/B/4104/1 | 330.840927 | 63.991049 | 252.915965 | 28.663918 | 21.382964 | 0.741063 | 13/null | 1/24 |
| 50/B/4104/4 | 267.052681 | 142.381186 | 252.267107 | 53.119651 | 94.141887 | 120.932193 | 21/null | 1/24 |
| 50/B/4104/8 | 336.049277 | 243.856810 | 308.987158 | 92.904307 | 117.670034 | 319.344920 | 11/null | 1/48 |
| 52/C1/4104/1 | 327.749963 | 64.410344 | 253.331070 | 18.222677 | 18.097872 | 0.619951 | 1/13 | 1/24 |
| 52/C1/4104/4 | 267.806206 | 134.638545 | 230.714870 | 50.623922 | 82.414266 | 103.768210 | 1/21 | 1/24 |
| 52/C1/4104/8 | 311.897331 | 173.186976 | 374.559969 | 57.801851 | 67.749481 | 261.515134 | 1/11 | 1/48 |
| 54/C0/4104/1 | 337.109287 | 63.205411 | 253.424985 | 21.032847 | 18.945475 | 0.701059 | 1/13 | 1/24 |
| 54/C0/4104/4 | 252.217179 | 134.405821 | 241.263714 | 49.109657 | 73.040754 | 113.427319 | 1/21 | 1/24 |
| 54/C0/4104/8 | 341.687835 | 170.811143 | 331.411590 | 58.658392 | 58.252206 | 271.638086 | 1/11 | 1/48 |

Reproduce this read-only audit with `python raster-review/recompute.py` from this evidence root. [Machine-readable report](report.json) retains all contexts, original records, paired values, phase values, cleanup records and limitations. [Strict extraction receipt](../extracted/analysis.json), [frozen statistics](../extracted/statistics/analysis.json), [official decoded log](../job-decoded.log).
