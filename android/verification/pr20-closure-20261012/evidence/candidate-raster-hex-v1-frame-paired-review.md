# Candidate hex v1 paired FrameProbe review

**FrameProbe passes 1/1 on each side, with all 14 within-version cold-rebuild pixel comparisons passing per side.** This supplies native frame evidence; it does not close the failed Tile block or the original dense deadline.

| Measurement | Baseline | Candidate | Change |
|---|---:|---:|---:|
| Initial frame ms | 13555.015 | 13824.332 | +1.99% |
| 12 mixed transform cases: median ms | 6410.023 | 5730.360 | -10.60% |
| Separated append ms | 2170.598 | 1511.899 | -30.35% |

The initial frame is slower by 269.317ms (+1.99%) in this pair. Keep that result visible. The 12-transform median is descriptive of 12 different cases in one sequential run, not twelve repeated trials, a p95 estimate or evidence of statistical significance.

Across transforms, decode/snapshot rows are 3760→0 while rebuilt tiles remain 212→212. Candidate native constructions total 2916, reused strokes total 3088 and retained geometry is 204800 bytes. The baseline does not expose those native/cache counters, so they remain null. This is reduced read/decode work, not reduced tile rebuilding.

For separated append, decode/snapshot rows are 322→24, rebuilt tiles stay 2→2 and candidate native constructions are 21. Candidate sparse cache geometry is 15360 bytes. All counts and original stage/cold-reference values are preserved in the JSON.

## Every case

Times are milliseconds, rounded here; original numeric values remain in the JSON. Pixels pass within each version, not by comparing cross-version bitmap hashes.

| Case | Baseline time | Candidate time | Baseline read/decode | Candidate read/decode | Rebuilt each | Candidate native |
|---|---:|---:|---:|---:|---:|---:|
| initial | 13555.015 | 13824.332 | 320/320 | 320/320 | 20 | 300 |
| transform-0-0 | 3987.842 | 2940.230 | 320/320 | 0/0 | 8 | 92 |
| transform-0-1 | 8304.285 | 7837.711 | 320/320 | 0/0 | 20 | 300 |
| transform-0-2 | 7659.553 | 6710.086 | 304/304 | 0/0 | 20 | 288 |
| transform-0-3 | 7188.677 | 5760.675 | 320/320 | 0/0 | 20 | 300 |
| transform-1-0 | 3046.293 | 2082.442 | 320/320 | 0/0 | 8 | 92 |
| transform-1-1 | 7127.256 | 5882.535 | 320/320 | 0/0 | 20 | 300 |
| transform-1-2 | 6279.992 | 5714.867 | 304/304 | 0/0 | 20 | 288 |
| transform-1-3 | 6347.993 | 5745.854 | 320/320 | 0/0 | 20 | 300 |
| transform-2-0 | 3371.709 | 2292.239 | 304/304 | 0/0 | 12 | 80 |
| transform-2-1 | 6472.054 | 5509.485 | 304/304 | 0/0 | 24 | 288 |
| transform-2-2 | 6035.286 | 5294.086 | 304/304 | 0/0 | 20 | 288 |
| transform-2-3 | 6587.300 | 5849.712 | 320/320 | 0/0 | 20 | 300 |
| separated-append | 2170.598 | 1511.899 | 322/322 | 24/24 | 2 | 21 |

## Source, APK and environment binding

Frozen candidate-hex-v1 and baseline-diagnostic-v2 manifests contain identical `RasterPerformanceProbe.kt` bytes: `91ceacc2db7557509f923a2046ba0f8d8ccf9691265dbee12e10e674a9ba33c9`. Both source trees were unchanged during passing builds. The candidate uses stdlib hex plus the candidate raster optimizations; the baseline keeps the original Formatter/production algorithm. This comparison cannot isolate just the one-line hex substitution.

- baseline: manifest `ea53d39f9dc02b118c95f18cec166d8c4ca2c64b88dabe83adaecb65b9f8e1e2` (734 files); app `a7e964809b12e937c7c54e23e27114a54bedaba49530363d1c345a73d2f7e9ed`; appTest `a500d8bb925adce209945acbe2de6672cb499b45f0b43706d58a9fa14ef8881e`. Receipt: `baseline-raster-hex-pair-frame-targeted.json`, 215.968s, 1/1 PASS.
- candidate: manifest `f06615ffe9ae2faaf81ffcae984d82b018307c874755273339f62586f5fbe4ab` (744 files); app `d4af16103b601e7d73af673eeb89d6511003a0f0ef4c86785862d5e202714b5e`; appTest `5ce3e01030cc7ce27d93ed3aed7027a4f670716785235c569c0d4e84e3528407`. Receipt: `candidate-raster-hex-v1-frame-targeted.json`, 210.278s, 1/1 PASS.

All four before/after configuration receipts match `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`. Both writer connections report page_size1024, auto_vacuum1, WAL and synchronous2. The probe deliberately readies the spatial index before timing, so its initial frame is not cold index backfill. Software Android/TCG results do not imply physical-device performance; matching configuration does not isolate transient load, ART/JIT or GC. APK hashes are taken from the existing verification receipts; this review performed no APK/full-tree rehash during the next native work.

The C/B/B/C Tile block remains **7/8, strict INCOMPLETE** because C2 pan/zoom exceeded its original30s gate; its local cases stay isolated in `candidate-raster-hex-v1-off-pair-raw-ledger.json`. This passing FrameProbe neither replaces that failed run nor establishes strict release for it. Keep the original dense 30s result and pending candidate NativeStroke/Streamed acceptance separate. No source, build, device or benchmark changes were made by this review.
