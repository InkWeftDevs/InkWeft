# Single-tile regression: source investigation before repeat results

Status: read-only source investigation and proposals; **no cause established and no
production fix applied**. Existing APK repeats are owned and executed by the root
task. The original final-pair timing regressions remain valid recorded observations.

## Existing runnable fixture

The smallest existing method selection is:

`org.inkweft.app.TileLocalRedrawTest#oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions`

There is no separate one-layer method or instrumentation layer-count argument in
the frozen APK. The method iterates `listOf(1,4,8)`, with one layer first. The root
has deliberately chosen the complete `org.inkweft.app.TileLocalRedrawTest` class
(2 tests/run) to preserve the original pan/zoom warmup and all layer phases. Planned
order is candidate-repeat1, baseline-repeat1, baseline-repeat2, candidate-repeat2.
No claim of a stable distribution follows merely from adding two runs per side.

## What the recorded single-layer stages show

| Run | Append wall | Metadata | Raster | Publication | Wall minus the three stages |
| --- | ---: | ---: | ---: | ---: | ---: |
| Candidate-v3 | 1,868.806 ms | 824.248 ms | 662.507 ms | 13.491 ms | 368.560 ms |
| Candidate-v4 | 6,427.580 ms | 3,020.313 ms | 2,381.227 ms | 274.363 ms | 751.678 ms |
| Final baseline | 1,435.516 ms | 728.762 ms | 518.699 ms | 9.981 ms | 178.073 ms |

All three records decode 13 strokes and rebuild one tile. These are single-layer
phase wall durations, including suspension, scheduling and any GC pauses; they
are not measured thread CPU times. The remainder includes setup before metadata,
frame notification and final drawing/waiting, rather than one isolated operation.
For multiple layers, phase sums can additionally overlap and cannot be subtracted
from wall time this way.

Frozen v3/v4 manifests have identical bytes for `StreamedInkRaster.kt`,
`InkBrushes.kt`, `InkRepository.kt`, `SpatialIndex.kt`, `InkRasterCache.kt`,
`RenderResources.kt`, `TileLocalRedrawTest.kt` and `PreservingOpenHelperFactory.kt`.
The only v4 production delta is `LibraryTextIndex` build/prepare/commit logic.
This fixture calls neither `LibrarySearchRepository.observe` nor
`LibraryTextIndex.build`; database open calls its unchanged `install` path.
Thus that source delta is not an identified direct execution-path change here.
This fact does not prove the timing difference is noise or exclude indirect runtime
effects, random fixture identities, scheduling, allocation or other unmeasured state.

## Timed path and baseline differences

1. Metadata timing surrounds `InkRepository.rasterRegions`, before dirty tiles are
   selected and before `visitVisible`/native shape construction. `rasterRegions`
   and `SpatialIndex` have no baseline-to-candidate source changes. It enters
   `SpatialIndex.read`/Room transaction handling, checks revisions/layers/erase epoch,
   scans ordered spatial candidates and hashes per-tile metadata. The complete page
   is still scanned for the 24 tile signatures even though only one tile will change.
   Normal `save(Add)` writes the new spatial entry before returning, and deleting
   its pending row also clears the corresponding unknown/hint rows through existing
   triggers. The append should therefore not require initial-page backfill; a
   diagnostic should confirm that rather than infer it from `decoded=13`.
2. Candidate creates a new decoded cache when append changes revision 1 to 2.
   That cache is cold: old-revision entries cannot be reused. `visitVisible` adds
   exact-region/allowed/omitted filtering, cache lookup/mapping, cache population,
   and epoch checks. For this 13-row/one-batch visit there are structurally three
   extra epoch reads (populated candidate batch, empty terminating batch, final
   validation), assuming the normal successful no-budget-retry path. These checks
   preserve same-revision erase-mask consistency. Existing receipts do not measure
   their individual durations.
3. The one-dirty-tile case constructs one native Stroke for each of the 13 visited
   pen strokes on either implementation. Candidate's new local variable/null check
   and counter do not add a native input/mesh construction. Cross-tile reuse can
   change allocation lifetime/cache behavior in other cases, but none of its
   source executes inside the preceding metadata timer. Reverting it cannot be
   presented as a demonstrated remedy for the 3,020 ms metadata observation.
4. Candidate reserves/tracks geometry and publishes the new cache; the old frame's
   decoded cache remains live until replacement publication. This is bounded extra
   retention (logical initial payload weight 204,800 bytes, reservation 1 MiB), not
   proof of process/native allocation cost or absence of GC pressure. Both v3 and
   v4 already use this same design.

Fixture geometry, stroke count, crop and layer distribution are the same rules,
but stroke/page/layer UUIDs are random. Their values affect ordering and spatial
index insertion/storage arrangement. The databases are not byte-identical. Existing
logs do not expose a query-plan or row-count difference explaining this run.

## Smallest useful diagnosis if repeats retain the regression

Keep the fixture, class order, 30/60 s test gates, pixel assertions and original
receipts. Add only opt-in in-memory phase recording, emitted after the measured
frame, rather than per-stroke logging. For this one-layer case distinguish:

- Metadata transaction queue/acquisition and any index preparation batches from
  the actual ordered spatial queries and per-region digest work; count query rows,
  preparation rows and region count. Existing `InkWeft.spatial.query` trace sections
  are available to a permitted sampled trace, but no trace was collected here.
- Metadata work's CPU time on its actual transaction thread from elapsed wall time;
  a clock on the raster thread alone would miss work performed on Room's thread.
- The visit's snapshot freeze/decode (existing `readObserver`), cache/filter work,
  native Stroke construction, native/Canvas drawing, and Main publication delay.
  Include native construction count, dirty-region count and cache hit/miss totals.
- GC/process observations around the same phase if available, without asserting
  that a matched before/after device settings hash captures scheduler/GC state.

The digest conversion currently does 32 `"%02x".format(byte)` calls per tile
(768 format calls for the single-layer fixture's 24 signatures) inside metadata
work. This is a concrete repeated allocation candidate in unchanged code, not yet a
measured hotspot. Timing digest conversion separately would test it. Replacing that
conversion with equivalent fixed lowercase hexadecimal would be a small possible
fix **only after evidence warrants it**, and would need exact signature equality.

## Narrow fix boundaries

- For exactly one dirty region equal to the SQL bounds, avoid a redundant region
  scan; preserve allowed/omitted filtering, stable order and revision/epoch guards.
  This removes a small collection/filter step, not the whole metadata pass. A
  dense union with multiple regions cannot automatically use that shortcut without
  losing sparse-gap filtering.
- A cold decoded cache can avoid constructing the reuse lookup and decoded-remap
  maps when there are no hits: validate snapshot ordering, populate the cache from
  that ordered snapshot, then visit it directly. Keep byte/count budgets, cursor
  progress, mask epoch validation and cancellation unchanged. This would target
  raster-stage allocation, not explain the observed metadata rise by itself.
- Do not simply disable the cache argument or route to the pre-cache reader: that
  also removes the newer fixed-epoch consistency protection and future same-revision
  zoom reuse. Do not revert cross-tile native reuse solely because this single-tile
  sample slowed; the native construction count did not increase.

These are limited proposals, not changes or confirmed performance remedies. The
next decision should use the root's repeated existing-APK results and, if necessary,
the targeted stage evidence above. No source, APK, raw receipt, build, native test,
ADB session, commit or push was modified or run by this investigation.
