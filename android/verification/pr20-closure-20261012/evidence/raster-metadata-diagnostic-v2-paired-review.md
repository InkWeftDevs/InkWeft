# Paired raster metadata diagnostic v2 review

**Both full Tile classes pass 2/2; the latency regression remains open.** The recommended next step is the one-line candidate-only `digest().toHexString()` substitution, with output equivalence and fresh native verification. This removes a measured, shared hot segment; it is not proof that Formatter caused the original regression. No source was modified during this review.

## Bound sources, APKs and environment

Candidate and baseline builds passed in 484.229s and 317.332s, with 744/734 frozen source files unchanged during build. The source-chain receipt identifies exactly two diagnostic paths changed per side. These runs use new diagnostic-v2 APKs, not the prior v4/default-off APKs. Installed and preserved APK receipts agree; signatures and four native MuPDF ABIs were verified. This review checks those receipts and small file hashes, without rehashing APKs.

- baseline: source manifest `ea53d39f9dc02b118c95f18cec166d8c4ca2c64b88dabe83adaecb65b9f8e1e2`; app `a7e964809b12e937c7c54e23e27114a54bedaba49530363d1c345a73d2f7e9ed`; appTest `a500d8bb925adce209945acbe2de6672cb499b45f0b43706d58a9fa14ef8881e`.
- candidate: source manifest `730b12637f1ee898b2b439603017c787c6323ba5411412662e003eb1066edf0f`; app `825f59bf50c1104a51fcb4aac108822a712e768502c3c606d029bb450612253d`; appTest `5ce3e01030cc7ce27d93ed3aed7027a4f670716785235c569c0d4e84e3528407`.

All four before/after environment configuration hashes equal `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. This is the same API35 x86_64 software/TCG emulator configuration; instantaneous host load, ART/GC and cache conditions are not established. Candidate then baseline full-class receipt durations were 343.644s and 198.276s (JUnit times 307.253s and 178.141s).

## Append results (milliseconds)

| Layers | Baseline append | Candidate append | Change | Baseline metadata | Candidate metadata |
|---:|---:|---:|---:|---:|---:|
| 1 | 1413.979 | 3362.609 | +137.8% | 706.129 | 1857.755 |
| 4 | 4750.426 | 16124.940 | +239.4% | 2415.475 | 10573.860 |
| 8 | 8275.651 | 11640.257 | +40.7% | 3040.826 | 4510.068 |

Initial frame times also regressed in this pair: baseline 6956.689/8303.278/11672.994ms versus candidate 10870.568/17781.881/21071.265ms for 1/4/8 layers. These are single diagnostic-on samples; do not pool them into the earlier default-off three-per-side sample set. The original 1435.516→6427.580ms single-layer result and all repeats remain in `raster-repeat-review.json`.

Both sides return exactly 321 metadata rows, query batch totals 3/8/16 and region totals 24/24/48. Preparation batches and preparation rows are zero in every layer. Both decode 13/21/11 strokes and rebuild one dirty tile; all local/full and destructive-frame pixel assertions pass. Candidate measured native constructions are 13/21/11. The baseline has no native counter: it stays absent in raw output and null in this review.

## Diagnostic phase sums (milliseconds)

Wall values are summed across layers and can overlap; they are not extra append time. CPU values are cumulative guest-thread samples, not exclusive coroutine CPU or host cycles. All endpoint thread IDs match for these samples.

| Layers / side | Admission+preparation wall | Query+digest wall / CPU | Hex+result wall / CPU |
|---|---:|---:|---:|
| 1 / baseline | 85.689 | 341.189 / 316.041 | 253.398 / 242.914 |
| 1 / candidate | 109.566 | 1205.127 / 1006.581 | 520.046 / 477.805 |
| 4 / baseline | 931.087 | 808.372 / 683.079 | 600.234 / 538.983 |
| 4 / candidate | 5006.815 | 2904.067 / 1967.328 | 2524.585 / 1316.009 |
| 8 / baseline | 531.339 | 1278.116 / 1130.904 | 1085.961 / 943.419 |
| 8 / candidate | 500.028 | 1962.960 / 1570.476 | 1794.499 / 1510.980 |

For one layer, candidate query+digest wall/CPU is 1205.127/1006.581ms versus baseline 341.189/316.041ms; hex+result is 520.046/477.805ms versus 253.398/242.914ms. The query segment is about 65% of candidate metadata wall and hex/result about 28%. Thus both shared segments become slower; Formatter alone does not explain the regression. The query segment also includes owner/revision/layer/epoch checks and digest updates, so it does not yet isolate SQL from hashing/allocation.

The source audit finds no a128→c60 change to owner, page DAO, layerFilter, NotebookPages, PageAuthoringCodec.layerHeader, PageAuthoringRepository, SpatialIndex or relevant Gradle dependency declarations. NoteDatabase changes only note-list observation APIs, outside the successful owner/get path. Both paired versions include the same initialization fix. There is no evidence here to roll back native reuse, remove epoch guards or rewrite DAO queries.

The four-layer candidate admission/preparation sum is 5006.815ms versus baseline 931.087ms, despite zero preparation batches. That interval includes transaction scheduling and is not pure lock wait. Eight-layer publication sums are 27519.830ms candidate and 17416.161ms baseline, exceeding their append walls because waits overlap across layers. Do not add these stage sums.

## Minimal next step and acceptance boundary

Recommend changing only the candidate hex expression to `digests[it].digest().toHexString()` using the existing Kotlin stdlib. The shared hex/result stage consumes material CPU: candidate 477.805/1316.009/1510.980ms and baseline 242.914/538.983/943.419ms. Existing code creates 768/768/1536 per-byte Formatter calls for the 24/24/48 SHA digests. This is a bounded hotspot optimization, not an asserted root cause or a measured speedup yet. Keep the baseline original Formatter implementation.

After authorization, first check all 256 byte values and representative SHA-256 arrays across locales in a one-off scratch comparison with the original expression. Do not add a wrapper, generic encoder or unrelated framework. Then freeze the new source/APKs and use existing Tile, NativeStrokeReuse, Streamed release/cancel, original dense deadline and paired FrameProbe coverage. Default-off end-to-end measurements must stay separate from diagnostic-on timings. If regression remains, obtain a minimal query+digest sub-stage split before a speculative query/cache change.

No claim of closed regression, physical-device performance, hardware screenshots, or cross-version pixel equality is supported. The original dense 1201-stroke 30-second failure remains open; this diagnostic ran only Tile. The v1 compiler failure is retained. Detailed cases, source/APK receipts, raw hashes and limitations are in `raster-metadata-diagnostic-v2-paired-review.json`.
