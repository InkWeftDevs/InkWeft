# Streamed raster follow-up

For the latest source/APK binding, native results, final comparison status and
remaining acceptance failures, see the [PR #20 closure record](../pr20-closure-20261012/README.md).
The earlier measurements and diagnostic-v1 chronology below remain historical
evidence; they do not substitute for the final candidate's receipts.

Baseline: remote branch `codex/storage-optimization-20261011`, fixed commit `a128bb07`.
All fixtures use a new disposable database in the selected cloud environment. No user
device, existing notebook or AniMemo checkout is involved.

The baseline combines all dirty tiles into one bounding rectangle before reading
stroke payloads. Opposite dirty corners therefore decode the unchanged middle.
The change filters ordered spatial metadata by the individual dirty rectangles,
allowed IDs and omitted IDs before reading payloads. A stroke intersecting several
dirty rectangles is decoded once and retains its original paint order.

Each raster frame can also retain at most 512 immutable decoded strokes, within a
1 MiB conservative logical weight including all attached erase masks. The renderer
reserves that full allowance in `RenderResources` as `ink-geometry-reservation`, pins
it during background work, and releases it with the frame. Eligibility is checked
against the repository, page, ink revision, erase-mask epoch and spatial row identity.
Visibility, layer eligibility and final revision are still read from the database.
Oversized masked strokes bypass the cache. This is a cache bound, not a measured
process-memory bound.

Integer sample translation remains the requirement for exact bitmap reuse.
Fractional pans and zooms rebuild the necessary bitmaps with the exact current
matrix, using retained geometry when possible. Preview pixels never acknowledge
capture completion. Revision changes still discard incomplete builds.

`RasterPerformanceProbe.continuousTransformsAndSeparatedDirtyTiles` can be copied
unchanged onto the fixed baseline. It seeds 320 deterministic strokes, prepares
the spatial index, compares 12 consecutive pan/zoom steps and an append at opposite
corners with fresh full raster rebuilds, and emits `RASTER_PAIRED_EVIDENCE` JSON.
Each sample reports frame completion/metadata/raster/publication time, rebuilt
tiles, newly decoded strokes, successful snapshot-read rows, and `Bitmap.sameAs`.
Additional cache reuse/weight counters are optional reflections for baseline
compatibility. Snapshot-read rows include masks; they exclude initial index backfill.
The initial index is prepared identically before timing on both revisions.

Correctness checks added in `RasterReadCacheTest` cover empty metadata batches
between dirty areas, crossing-stroke order and deduplication, omitted IDs, repository
identity, same-revision payload replacement, erase-mask epoch invalidation,
revision changes during cache-only reads, 512-entry eviction and oversized masks.
Cached visits fix the first observed erase epoch and check it for each following
batch and final revision validation. A same-revision mask write inside the first
batch's callback must fail the whole read before a second epoch can be delivered;
a fresh visit then sees the new masks.
`TileLocalRedrawTest` additionally checks zero new stroke decodes on a warmed
same-revision zoom while retaining its cold-frame pixel equality assertion.
`StreamedInkRasterTest` checks cancellation of a pending transformed frame,
non-publication after clear, and release of geometry reservations. Its omitted-ink
test now pauses the metadata transaction: omitted payloads are intentionally no
longer decoded, so using their read callback as the old barrier would deadlock the
fixture. The 500 ms no-stale-pixels observation remains unchanged.

The metadata cursor still traverses the dirty regions' bounding rectangle. The
change removes intervening payload reads and decodes; it does not claim to remove
all intervening metadata work, reduce fractional-transform tile rebuild counts,
or reuse geometry beyond the explicit cache bound.

Actual paired execution is available in [the integration evidence](../android-perf-followup-20261012/README.md).
Both sides used the same API 35 x86_64 TCG/SwiftShader software emulator and
identical probes, with the common database-initialization fix applied to the
`a128bb07` comparator. This is real Android instrumentation, not a physical-device
performance claim.

All 14 continuous-transform/append frames and the pan, zoom and 1/4/8-layer local
frames match fresh full rebuilds within each revision pixel for pixel. Cross-revision
pixel hashes were not exported. Across 12 transforms, newly decoded
strokes and payload snapshot rows fell from 3,760 to zero; rebuilt tiles remained
212. The mixed-transform median was 6,590 to 6,136 ms. Opposite-corner updates
read/decode 24 instead of 322 strokes and still rebuild two tiles; frame completion
was 2,411 to 2,041 ms. Local append completion at 1/4/8 layers was respectively
1,398/5,851/11,011 to 1,509/4,826/8,650 ms, one sample per layer rather than repeated
medians. The single-layer sample did not improve. The previously reported 299 to 191 ms figures are prior measurements from a
different run; their environment was not reproduced here.

Raw records: `baseline-raster-probe.log`, `candidate-raster-probe.log`,
`baseline-tile-local.log`, `candidate-tile-local.log` and their runner receipts in
the integration directory. Correctness regression execution is tracked separately
there; paired timings do not replace the broader cancellation/undo/live-root tests.

The original `completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker`
gate remains unmet in the recorded matched follow-up: both baseline and candidate
threw the original 30,000 ms timeout (`baseline-followup-streamed-frame-control.log`
and `candidate-followup-streamed-frame-control.log` in the integration directory).
JUnit's whole-method times were 136.339 and 100.669 seconds, respectively. Those
totals are not frame timings. The timeout begins after writing 1,201 strokes and
reading the bounded editor window; it covers the background frame and its
publication notification. The old logs do not identify which frame stage stalled.

The same test now emits `STREAMED_FRAME_STAGE` fixture/window/frame checkpoints,
records successful snapshot batches in memory, and reports worker/Main stacks,
resource accounting and publication state on timeout before rethrowing it. Its
1,200 × 64-point strokes plus final red stroke, 30 s deadline and original pixel,
batch-size and worker-release assertions are unchanged. The independent
`completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary` uses 257 ×
32-point strokes plus the red stroke to exercise both the 8,192-point editor
window and 256-row database boundary with the same assertions and deadline.
It is additional functional coverage, not a replacement or pass for the original
dense-frame gate.

The frozen diagnostic-v1 APK ran both tests on the same software Android runtime.
Raw stage records are `diagnostic-v1-small-frame.log` and
`diagnostic-v1-large-frame.log` in the integrating task's
`/workspace/scratch/pr20-closure-evidence/` directory. These are new diagnostic
executions, not replacements for the earlier matched baseline/candidate receipts.

| Diagnostic-v1 case | Fixture writes | Editor window read | Timed frame | Result |
| --- | ---: | ---: | ---: | --- |
| 258 strokes / 8,226 points | 13.108 s | 11.283 s | 17.625 s | PASS; final pixels, both batches and worker/resource release verified |
| 1,201 strokes / 76,802 points | 31.840 s | 45.318 s | 30.446 s observed at timeout | FAIL; original 30,000 ms timeout rethrown |

Fixture writes and the editor-window read precede the frame timeout. The passing
small case read 256 rows / 8,192 points at frame time 2.974 s, then 2 rows / 34
points at 17.076 s. Its published stats report four rebuilt tiles, 258 new payload
decodes, 16.611 s raster time and 37.958 ms publication time. The passing test
checks the red stroke beyond the editor window and releases its worker; its
whole-method JUnit time was 50.584 s.

The dense case had completed only its first 256-row / 16,384-point snapshot by
frame time 11.565 s; that snapshot reports 1.195 s freezing and 1.498 s decoding.
At timeout the raster worker stack was in
`StrokeNative.createWithBrushAndInputs`, reached through `InkBrushes.stroke`.
The Main thread was in the idle Looper poll, with zero change callbacks,
`pending=true`, no ready frame and no published stats. Resource accounting was
1,848,576 of 134,217,728 bytes (800,000 frame bytes plus the 1,048,576-byte geometry
reservation), with one in-flight job. This observed failure is before frame
publication and is not a resource-admission wait. The first batch's native shape
construction/drawing path had not finished; the single stack sample does not show
that one native call consumed the entire roughly 18.882 s since the first snapshot,
nor distinguish native input/mesh cost, drawing cost and software-emulator CPU
overhead. It does not prove that TCG is the sole cause. The original gate remains
unmet, and its timeout does not verify eventual frame correctness or worker release.

Frame work is synchronous on the single raster worker after each database snapshot
transaction closes. It reaches Main publication only after all batches and final
revision validation; native Stroke construction cannot observe coroutine
cancellation until that call returns. A useful environment comparison therefore
keeps this dense fixture, the 30 s deadline, APK/native dependency versions and
database settings fixed, runs serially without a concurrent build, and repeats on
an accelerated x86_64 Android emulator or an ABI-matched physical Android device.
Such a run has not been performed here and is not guaranteed to meet the deadline.
Further production changes need stage/count evidence separating input assembly,
native mesh construction and drawing. Dropping author samples, altering the brush,
or accepting preview pixels would change semantics; cross-frame native caching
would additionally need measured native-memory accounting and invalidation work.
None is inferred as a safe dense-frame fix from this timeout alone.

A later, separate source change removes repeated native shape construction within
one stroke's dirty-tile loop. `InkBrushes.stroke` previously constructed a fresh
native input batch, Brush, Stroke and mesh for every intersected tile. The loop now
lazily creates one immutable native Stroke and drops its local reference after that
stroke's tiles finish. It adds no cross-stroke or cross-frame native cache.
`BuildStats.nativeStrokeBuilds` counts successful native constructions independently
of Kotlin payload decoding; `RasterPerformanceProbe` reports it when available.
The fixed AndroidX Ink 1.1.0-alpha09 cached classes were inspected: Stroke retains
final Brush, ImmutableStrokeInputBatch and PartitionedMesh fields, and the renderer
reads the shape while maintaining its own path cache.

`NativeStrokeTileReuseTest.oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips`
compares the changed path pixel-for-pixel with an explicit old per-tile-construction
reference across fractional transforms, cropped bounds, finite paper edges,
erase masks, omitted/allowed strokes and pencil exclusion. This source change was
**after the frozen diagnostic-v1 APK**; at that point its assertions awaited the
next build, and the old diagnostic and paired receipts did not cover it.

The later candidate-v2 APK ran this test successfully: measured native counts are
2/2/1/0 across its four phases, versus 24/32/16/0 actual constructions in the
test-only per-tile reference. All four phases rebuild 16 tiles and pass whole-bitmap
equality. These reference counts come from the same v2 process, not an installed
old production baseline. See `candidate-v2-native-stroke.log` and its runner receipt
in the [closure evidence directory](../pr20-closure-20261012/README.md). The original
dense fixture hits one tile per stroke, so reuse does not reduce its 1,201 native
constructions; its original 30 s gate also failed in the later v2 run. Neither this
passing differential test nor the smaller 258-stroke case closes that dense gate.
