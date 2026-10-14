# Candidate-v4 raster performance receipt review

**PASS for these candidate-v4 probes:** `RasterPerformanceProbe` passed 1/1 test;
`TileLocalRedrawTest` passed 2/2. This review covers those three raster tests, not
the other two tests in the overall five-test performance run. The final fixed-base
comparison has not run at the time of review, so these are candidate observations,
not evidence of improvement over `a128bb07`. The original 1,201-stroke 30 s dense
gate remains failed in its recorded diagnostic-v1/v2 executions.

## Source, APK and runtime binding

- Frozen manifest: `candidate-v4-source-sha256.json`, 744 files, SHA-256
  `8285c54019d0a4409a6dbf6f5d36e87298b5be9b6d47f93801ea9123bb7d859f`.
- App APK: 241,512,357 bytes, SHA-256
  `79fe8a40024d0cbd7283145f52febc3d54a6312ec231acea11182822e8f6aaf4`.
- App test APK: 18,387,376 bytes, SHA-256
  `37dc4f858fdf13fde56fd514515f6e043356e482db0a8a0bc7339fce4504c5b2`.
- `candidate-v4-build.json` records successful build with unchanged frozen source;
  `candidate-v4-apk-installation.json` records successful installation of these APK
  hashes. `candidate-v4-performance-artifact-continuity.json` records matching hashes
  after performance execution. This reviewer checked the receipts without issuing
  build, installation or device commands.
- Seven inspected source files (raster, brush, layer layout, repository, decoded
  cache and both probes) independently match their v4 frozen hashes. Exact paths,
  hashes, parsed records and raw-file hashes are in `candidate-v4-raster-review.json`.
- Runtime: API 35 x86_64 Android emulator, TCG CPU/no KVM, SwiftShader, two guest
  cores and 2 GiB guest RAM. Before/after environment comparison SHA-256 is the same:
  `425d701e4a8711a84ca963b1c4769b76c47d1644970d72e2941666412be56378`.
  Equal recorded settings do not prove identical scheduler load, cache state or all
  conditions throughout the run. This is Android native execution, not physical-device
  performance.

Raw records are `candidate-v4-performance-raster-probe.log` / `.json` and
`candidate-v4-performance-tile-local.log` / `.json`. The first runner began at
2026-10-13 00:34:39 UTC and the second at 00:37:59 UTC. The raster probe reports
writer SQLite settings `page_size=1024`, `auto_vacuum=1`, `journal_mode=wal`,
`synchronous=2`, read after timed frames. Those settings are not independently
reported by the tile-local test.

## Continuous transforms and separated dirty regions

All **14 frame comparisons** (initial, 12 transforms, separated append) returned
`pixelsEqual=true` against a fresh complete rebuild using **the same candidate-v4
implementation**. This is within-version pixel equivalence, not comparison with an
old production binary. The deterministic fixture starts with 320 two-point strokes;
its spatial index is prepared before frame timing, and erase/authoring setup costs
are not included in the decode counters.

| Scope | Complete time | Newly decoded payload strokes | Successful snapshot rows | Rebuilt tiles | Native Stroke constructions |
| --- | ---: | ---: | ---: | ---: | ---: |
| Initial frame | 12,580.622 ms | 320 | 320 | 20 | 300 |
| 12 transforms, totals except median time | median 5,415.061 ms | 0 | 0 | 212 | 2,916 |
| Separated append at opposite corners | 1,713.317 ms | 24 | 24 | 2 | 21 |
| Fresh full rebuild after that append | 5,674.108 ms | 322 | 322 | 20 | not emitted |

The transform median is the median of 12 different sequential pan/zoom cases
(range 1,926.162–6,812.705 ms), not a repeated-trial distribution for one operation.
They reuse 3,088 decoded-stroke visits with logical retained geometry at 204,800
bytes; the same-revision payload-read elimination does **not** eliminate metadata
reads, native shape construction or bitmap rebuilds. Zero payload decodes therefore
does not mean zero native work. The counts agree with the raw receipts, including
the 2,916 native constructions. Snapshot-row counts cover successful payload reads;
they do not count the earlier spatial-index backfill or metadata rows.

## Integral pan, preview state and local append

The pan test rebuilt 12 of 20 tiles; both final pan and zoom bitmaps equal fresh
full rebuilds. `previewCaptureReady=false` verifies that the intermediate preview
does not mark this raster's `frameReady` as complete. The test also checks zero new
payload decodes and positive reuse for the final same-revision zoom. It does **not**
exercise a hardware screenshot, compositor, export/capture pipeline or physical
display acceptance.

| Layers | Append complete | Fresh full complete | Local decoded strokes | Local/full rebuilt tiles | Pixels equal |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 6,427.580 ms | 9,800.771 ms | 13 | 1 / 24 | true |
| 4 | 6,600.951 ms | 13,071.941 ms | 21 | 1 / 24 | true |
| 8 | 8,622.743 ms | 15,021.319 ms | 11 | 1 / 48 | true |

Each layer count starts with 320 strokes and appends one through the normal scoped
author commit; the full build decodes all 321. These local decode counts are
production `BuildStats` counters, not separately captured snapshot-row callbacks.
The same test also hides the appended stroke, checks that stale pixels are absent
while replacement is pending, then compares the completed destructive update with
a clean full frame. Both append and destructive-update pixel assertions passed for
all three layer counts, including locked visible layers. Logged bitmap byte counts
(14,371,200 / 12,147,408 / 24,294,816) are collected **after** that destructive update;
they are not append-frame peak process-memory readings.

The `metadataMs`, `rasterMs` and `publicationMs` fields are sums of per-layer build
durations and do not add up to end-to-end frame wall time. In the eight-layer record,
summed publication duration alone is 17,981.109 ms while append wall time is
8,622.743 ms. Layer jobs may overlap their pending-publication intervals. Reporting
the sum as serial CPU time or adding it to `appendCompleteMs` would be incorrect.

The one-layer sample is substantially slower than the separately recorded v3
sample: **1,868.806 → 6,427.580 ms**, a 4,558.774 ms increase (3.439×). The four/eight
layer v3 observations were 7,572.644/9,639.414 ms versus this run's
6,600.951/8,622.743 ms. These are one sample per configuration from separate
candidate runs; they are disclosed to avoid selecting only favorable timings.
Neither a deterministic regression cause nor a robust speedup follows from them.
They are not the pending fixed-base comparison.

## Acceptance limits

These probes confirm candidate-v4 sparse reads, reuse/rebuild counts and software
bitmap equivalence for their fixtures. They do not replace the original dense
30,000 ms gate, establish its unique failure cause, or provide physical-device
latency. In that dense fixture each stroke hits only one tile, so the per-stroke
native reuse across tiles removes none of its 1,201 constructions. Earlier passing
258-stroke/two-batch and resource tests retain their own v2 source/APK identity;
they are not silently relabeled as v4 runs.

No source files, build outputs or raw receipts were changed by this review. Only
this scratch Markdown and its derived JSON were written. No Gradle, ADB,
instrumentation, commit or push was run.
