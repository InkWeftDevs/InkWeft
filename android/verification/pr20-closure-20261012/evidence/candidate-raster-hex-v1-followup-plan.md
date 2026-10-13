# Fixed candidate raster hex v1 follow-up plan

**Nine serialized instrumentation invocations, 17 planned JUnit executions.** Test counts were audited from source: TileLocalRedrawTest 2, RasterPerformanceProbe 1, NativeStrokeTileReuseTest 1, StreamedInkRasterTest 4 (8 distinct methods). This is a plan, not a test result. Source remains unchanged.

Use the new frozen `candidate-raster-hex-v1` app/appTest APKs after the root build succeeds. Pair against preserved `baseline-raster-diagnostic-v2` APKs, which keep Formatter and the same optional diagnostic hook. The candidate source change is one hex conversion line; do not substitute older candidate APKs.

| Order | Version | Full class selection (`org.inkweft.app.` prefix) | Diagnostics | Expected tests |
|---:|---|---|---|---:|
| 1 | candidate | TileLocalRedrawTest | true | 2 |
| 2 | candidate | TileLocalRedrawTest | omitted (default off) | 2 |
| 3 | baseline | TileLocalRedrawTest | omitted (default off) | 2 |
| 4 | baseline | TileLocalRedrawTest | omitted (default off) | 2 |
| 5 | candidate | TileLocalRedrawTest | omitted (default off) | 2 |
| 6 | candidate | RasterPerformanceProbe | omitted (default off) | 1 |
| 7 | baseline | RasterPerformanceProbe | omitted (default off) | 1 |
| 8 | candidate | NativeStrokeTileReuseTest | omitted (default off) | 1 |
| 9 | candidate | StreamedInkRasterTest | omitted (default off) | 4 |

Orders 2–5 form the fixed default-off C/B/B/C block, two fresh whole-class invocations per side. Retain the original Tile pan/zoom prelude and 1/4/8 loop; do not filter out the prelude, reduce fixtures or extend deadlines. FrameProbe runs are one per side. Streamed runs last so a known dense failure cannot contaminate the preceding planned performance runs.

## Names and original gates

- `org.inkweft.app.TileLocalRedrawTest` (2):
  - `integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture`
  - `oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions`
- `org.inkweft.app.RasterPerformanceProbe` (1):
  - `continuousTransformsAndSeparatedDirtyTiles`
- `org.inkweft.app.NativeStrokeTileReuseTest` (1):
  - `oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips`
- `org.inkweft.app.StreamedInkRasterTest` (4):
  - `clearingPendingTransformReleasesPinnedGeometryWithoutPublishing`
  - `omittedStrokeCannotReappearWhileItsReplacementFrameIsDelayed`
  - `completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker`
  - `completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary`

Tile pan/zoom ready gates remain 30s; local frame gates remain 60s. FrameProbe retains 90s/frame and exactly 14 evidence rows (initial, 12 mixed transforms, separated append). NativeStrokeTileReuse retains 30s/phase and four phases. Streamed retains all four methods: its original dense fixture is 1201 strokes/76802 points and smaller fixture 258/8226; both complete-frame gates remain 30s, excluding fixture/editor setup. Cancellation/omission gates and worker-release checks stay unchanged.

## What must be established

- **Diagnostic stage:** One new candidate full Tile run with `rasterMetadataDiagnostics=true`. Keep all per-layer samples. Expected totals for 1/4/8 layers are rows 321/321/321, regions 24/24/48, query batches 3/8/16, preparation batches/rows zero, decode/native 13/21/11 and one dirty tile. A reduction in hex/result cost supports only the targeted change; query+digest and total append must still be assessed. The new hex interval includes SHA finalization, stdlib conversion and allocation.
- **Default-off latency:** Omit runner `--diagnostics` and the instrumentation diagnostic argument; the test defaults off and the receipt records `diagnostics=false`. The runner needs no change. Report all C/B/B/C raw samples, per-layer initial/append/cold times, stages, work counts and pixels. Describe new two-per-side median/range without significance claims. Check each layer separately; no overall mean or fast tail can hide single-layer regression. Stage sums can overlap and are not append wall time.
- **FrameProbe:** All 14 same-version cold bitmap comparisons and budget assertions pass on each side. Report initial separately, the median of 12 *different* transform cases, and separated append separately. Preserve candidate zero transform decode/read and sparse-read behavior; missing baseline native/cache counters remain null.
- **Native stroke:** The existing four-phase differential test must retain counts 2/2/1/0, whole bitmap equality, masks, crop and finite-paper clipping. Its per-tile reference is a same-process oracle, not an old-production native measurement.
- **Streamed:** Execute and report all four methods. Success includes frame content, full snapshot rows, >1 batch where asserted and worker/cache release. Keep the original dense failure visible if it recurs; smaller-frame success cannot substitute for it.

The existing Kotlin 2.3.10 host compatibility check passed 1869 cases across 7 locales. It validates tested byte/string representation only. New APK build/native runs still need to establish Android linkage/execution and rendering behavior; no native speed claim follows from the host check.

## Evidence and unmet conditions

Freeze source/build/APK identities, record installs and before/after environment per invocation, and preserve raw logs/receipts with actual counts. Run serially without concurrent Gradle, CPU checks, hashing or instrumentation. Matching environment configuration does not prove equal instantaneous host load, ART/GC or caches. Check process/worker cleanup after failures before subsequent runs.

Keep these groups separate: original v4/baseline-final pair; four old unchanged repeats; Formatter diagnostic-v2 pair; new default-off hex-v1 block; new hex-v1 diagnostic run. Preserve the original 1435.516→6427.580ms single-layer result, every slower repeat and v1 compile failure. No old result is relabeled as a new APK or silently dropped.

Missing test/stage data means incomplete evidence. Pixel/state/release failure is a functional blocker. A repeated dense timeout leaves its original gate failed, with reached/unreached assertions and stage/stack evidence recorded; do not attribute it solely to software emulation. If the hex interval or default-off latency does not improve adequately, do not mark the regression closed: retain all samples and obtain a minimal query+digest split or a specific reproducible code issue before further changes. Do not relax assertions, blindly revert native reuse or expand the refactor.

Exact selections, artifact identities, expected metrics, limitations and failure handling are fixed in `candidate-raster-hex-v1-followup-plan.json`. This author ran no build, ADB, CPU check or large hash and made no source changes.

Plan erratum 1: the existing runner exposes only `--diagnostics`. An enabled run supplies that flag; a disabled run omits it, rather than explicitly passing false. Old plan hashes and corrected hashes are retained in `candidate-raster-hex-v1-followup-plan-erratum-1.json`; the nine-run order and all acceptance conditions remain unchanged.
