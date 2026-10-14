# Minimal native scope and full-path follow-up (read-only proposal)

Read HEAD: `28104e1334c79e68b4f9713cbb2aa2e3a0fa9993`. No production/test/controller edits, network, build, CI, or native execution were performed. `scope-evidence.json` records exact selectors, arguments, current test hashes, marker bounds, dependency hashes, and the independent four-pair reconstruction. This is a proposed scope, not a frozen source manifest or an execution result.

## Six calls, each exactly once

All selectors have prefix `org.inkweft.app.`. Each call is `adb -s emulator-5554 shell am instrument -w -r -e class <selector> org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner`, with one expected JUnit test, no seed and no diagnostic arguments.

| Selector | Purpose | Markers on success | Previous adjacent completion interval |
|---|---|---|---:|
| `NativeStrokeTileReuseTest#oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips` | e310 helper; old per-tile native oracle, all four transform/omit/allowed phases, exact pixels, cuts/crop/paper edges | `NATIVE_STROKE_TILE_EVIDENCE` ×4 | 1.591051 s |
| `RasterPerformanceProbe#continuousTransformsAndSeparatedDirtyTiles` | e310 helper; initial frame, 12 continuous transforms and separated append, exact independent cold pixels and actual read/decode/rebuild counters | `RASTER_PAIRED_EVIDENCE` ×14; `RASTER_SQLITE_EVIDENCE` ×1 | 6.456047 s |
| `StreamedInkRasterTest#completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker` | e310 helper; original dense 1201 strokes/76802 points, complete final red stroke and 256-row batching | `STREAMED_FRAME_STAGE` ×6, `original-dense` | 3.994411 s |
| `StreamedInkRasterTest#completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary` | same changed helper; 258 strokes/8226 points crossing both limits | `STREAMED_FRAME_STAGE` ×6, `bounded-two-batch` | Not established by this run |
| `TileLocalRedrawTest#appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames` | existing two-layer idle/append/hide/undo/cut/viewport and cancelled pending-frame guard | none | 2.006930 s |
| `TileLocalRedrawTest#interruptedPanWaitJoinsItsWorkerBeforeClosingResources` | existing deliberately interrupted wait and strict owned Job cleanup, no suppressed cleanup failure | `TILE_PAN_STAGE` ×1, expected `initial-cached/failed/InterruptedPanWait` | 1.232313 s |

The previous intervals come from adjacent official METHOD completion timestamps in completed run38520196523; they include small controller overhead and are not exact instrumentation durations. Those methods predate e310. They do not validate e310, and the new run must have a distinct identity. The interrupted guard uses its existing nested `RasterJobs`, not the new `OwnedRasterJobs`: its failure injection is useful adjacent coverage, not proof that every failure point in the new helper was injected.

`RasterPerformanceProbe` is one raster sequence, not a 1/4/8-layer benchmark or 14 repeated samples. Its labels are `initial`, `transform-{0..2}-{0..3}`, and `separated-append`; preserve all 14 and do not report a synthetic sample_count or pooled percentile. The 30 s dense frame gate and 90 s continuous ready gate remain. Dense's 5 s release assertion now includes clear plus actual join, which is stricter than the previous polling start point. Actual owned jobs must finish before DB/bitmap release, including finally paths; a stuck join is stopped only by the existing process/job deadline and cannot count as PASS. The new helper emits no joined marker: source binding, successful JUnit completion and natural process exit are necessary; do not manufacture an extra receipt.

## Marker and budget accounting

Reuse the archived `android/verification/pr20-match-candidate-20261014/marker-contract.json` schema and bounds for ordinals 60,61,62,26,28. Oracle: 4096 B/record and 16384 B/method. Continuous: 8192 B/record and 65536 B/method across its two markers. Each dense method: 65536 B/record and 131072 B/method. Interrupt: 8192 B/record and method. Layer guard: zero. Both the per-record and per-method limits apply. This gives **32 successful records and 352256 B of original JSON** before raw text, forwarded escaping, END inventories, contexts and package copies. Failure prefixes must remain present even when exact successful counts are absent; preserve failure output and reject unexpected extra markers instead of trimming.

The old dense schema pins `original-dense/1201/76802`; the new bounded method needs a separate schema pinned to `bounded-two-batch/258/8226`. Both keep the ordered stages `fixture-start`, `fixture-complete`, `editor-window-complete`, `frame-start`, `frame-complete`, `verified-and-released`; timeout/failure records are not a successful six-stage set. Their snapshotReads retain ordered batch details. The legacy schema must not be silently relabelled. The complete schema shapes and byte limits are in the archived contract; selectors and bounds are copied to the scratch JSON.

Expected normal cost is under one minute for these six calls, plus one app installation group, sharing the same final candidate and emulator as the data calls. This is an estimate, not a new shortened deadline. One final source needs app, appTest and dataTest APKs; do not build a second source merely for cleanup. Historical three-source preparation took about 30 minutes, so roughly 8–12 minutes for a full single-source build is only a planning estimate; boot/install add roughly 1–3 minutes. The integration controller must calculate its combined bound and all artifact copies/escaping against 20 MiB; 352256 B is not the final publication budget. No 16+16 attribution matrix or old completed-run rerun is included.

## Real MuPDF dependency and build entry

Declared official repository: `android/settings.gradle.kts:7`, `https://maven.ghostscript.com`, restricted to `com.artifex.mupdf`; `android/app/build.gradle.kts:140` uses `libs.mupdf` (`com.artifex.mupdf:fitz:1.28.5`). The current local AAR is **21636412 B**, SHA256 `e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e`; POM **662 B**, SHA256 `d66b443446cb2bb992c0de60115ae4988584bd44d16c877d0ba5e2d400b416ff`. Both exactly match the archived real dependencies-B/C receipts in `android/verification/pr20-kvm-paired-20261013/evidence/`.

All four AAR `jni/<ABI>/libmupdf_java.so` entries were read and verified as ELF, with byte count and SHA256 exactly matching the old APK-C native receipt; all values are in `scope-evidence.json`. x86_64 is 11264136 B / `e9907d1e11f886662915376a1f059608e539c75cb1bc5ef57ccdf887a0367349`. This is a complete native AAR, not a Java-only substitute. This check does not establish that a future runner already has the cache or that an official network request will succeed. Respect a policy denial; do not substitute a mirror or fetch an old APK as the final candidate.

Reusable reviewed build entry is `ci/pr20-kvm-triple/run.py:319–381`: from the frozen source's android directory, Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest`, with the fixed head identity, then source hash recheck, signatures, target packages, embedded source identity, complete native ABI ELF/hash inventory and dependency receipt. Fresh artifacts must be built from the final single source and installed SHA must match; the local/archived evidence is only dependency provenance. The current data-only save controller deliberately omitted app/MuPDF checks, so the combined scope must restore the reviewed native-app verification rather than infer it from a data APK.

## Four-pair full result, retained without reinterpretation

The scratch script independently rebuilt the eight relevant methods from the official log, verified 72 original payload byte/hash bindings, and extracted eight-layer full values. It also compared frozen PLAN to the actual pre-run Git object `c37ba03232b1a0985e85cbaec4d349dbab945919`.

| Seed | B ms | C1 ms | Delta ms | Positive | Individually over max(10% B,5 ms) |
|---|---:|---:|---:|---|---|
| 4101 | 358.822529 | 337.493995 | -21.328534 | no | no |
| 4102 | 318.882758 | 380.560993 | +61.678235 | yes | yes |
| 4103 | 321.718498 | 338.116419 | +16.397921 | yes | no |
| 4104 | 308.987158 | 374.559969 | +65.572811 | yes | yes |

PLAN:114 and the pre-run analyzer:63 require **3/4 positive deltas**, plus paired median relative and absolute increases over the bands. Current frozen analyzer:43–46 implements the same definition. Median is +39.038078 ms/+12.219478%, so the original result remains REGRESSION_SIGNAL and aggregate **45/5/4**. The descriptive fact that only 2/4 individual pairs exceed both bands does not replace the original rule. No historical source, sample, label or output was changed.
