# Streamed raster follow-up

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
