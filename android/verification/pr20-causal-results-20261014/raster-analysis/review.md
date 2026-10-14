# Actual raster causal diagnostic review — run 38514139119

**The diagnostic is complete; raster performance acceptance remains open.** It locates material metadata CPU and a notification interval in this run, while confirming local payload decode remains one stroke. It does not establish the cause of the old d66-versus-45b regression. A narrowly scoped, provable waste-removal candidate is lazy creation of the frame-local pencil renderer; its latency impact is not measured here.

The frozen analyzer completed with `COMPLETE_SCOPED_RASTER_DIAGNOSTIC_ARITHMETIC`: three records, 65 successful frame records, no altered samples or thresholds. The external parser reported `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`, issues `[]`, all 13 methods, two installation groups, packaging and both cleanup paths successful. Binding: run `38514139119`, job `115689021611`, workflow `d4a107dd84c6e309b5dbbbfb3a57d69371024408`, source `78a5babeaeb92292026423222d5e08635989198b`, readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`. See [complete arithmetic and binding](analysis.json), [all 65 frame intervals](analysis.md), and [external integrity report](../extracted/analysis.json).

App APK SHA256 `44474fdff519856a4e3cb6ba148d93e72bec1cb4a7d6ef953a6e8ae39f883a93`; app-test APK `4daa78a45ead67f6492d61c83e65af7262300ae8729adb111405a24e0c0f303d`; forwarded installed hashes match. Forwarded environment SHA256 is `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`. These are validated official-log claims; this review does not independently inspect APK/ZIP binaries or raw device files. Method-13 extracted records hash is `79d68d2b578ce0be909b4223858d8f1f62cd3ac49e9cfbdbf181266bd9b2f32f`.

## What actually ran

| Layers | First ms | Append ms | Forced full ms | Local/full payload decode | Local/full tile rebuild | Local native construction |
|---:|---:|---:|---:|---:|---:|---:|
|1|307.962945|52.993670|218.939697|1/321|1/24|13|
|4|265.418642|108.530612|209.268495|1/321|1/24|21|
|8|275.522921|142.719236|337.269194|1/321|1/48|11|

All three local/cold pixel comparisons passed; the method also retained the destructive-hide blank-pixel and subsequent cold-equality assertions, giving three further within-fixture bitmap comparisons. All captured Jobs were joined. Every group ended with tracked total, geometry reservation and in-flight jobs zero. Cumulative tracked peaks were 30,839,552 / 32,683,424 / 65,366,848 bytes; these are logical retained-resource counts, not native/GPU heap measurements. No recorded frame failed, was superseded, hit budget-busy or recorded cancellation.

Each local phase performed exactly one successful payload read: one candidate/entry, 140 payload bytes, two points; freeze wall was 2.655971 / 2.637407 / 3.276301 ms, decode wall 0.118843 / 0.080690 / 0.081833 ms. The other required strokes were obtained from the Kotlin geometry cache; native geometry is still rebuilt for all 13/21/11 strokes touching the dirty tile. There is no cross-frame native cache or evidence here of duplicate payload decoding. Destructive hide then decoded 12/20/10 entries rather than retaining the changed append state, and the cold pixel checks passed.

The three fixture logical hashes exactly match seed 4101 in the previous full run's B/C0/C1 `method-32-B`, `method-34-C0`, `method-36-C1` records. This supports fixture content equivalence only; it does not make this new opt-in, single-source run an old/new performance pair or remove observer/environment differences.

## Single-layer append: the previously unpartitioned region is now observable

The 52.993670 ms ready helper includes a 51.367483 ms request-to-ready frame interval and 1.626187 ms outside that interval (helper/dispatch/bitmap preparation and return boundaries, not assigned to one cause).

| Observed interval | ms |
|---|---:|
|Request → worker|0.388476|
|Worker → metadata start|0.267039|
|Metadata|16.382135|
|Raster section|18.196343|
|prepareToDraw|0.010730|
|Prepare end → Main publication entry|0.142366|
|Main publication|0.348471|
|Published → first notification enqueue|0.004458|
|Enqueue → actual Choreographer callback|11.506897|
|Callback → ready draw start|1.669319|
|Ready draw|2.451249|

The intervals sum to the request-to-ready duration. The enqueue-to-callback delay is an actual observed interval in this run. It is not evidence that the old roughly 12 ms residual difference had the same cause, is noise, or can safely be removed. The callback is the test's channel notification; actual InkCanvasView uses `postInvalidateOnAnimation`, so this is not direct displayed-frame latency.

Metadata had no preparation rows/batches, 24 regions, three metadata queries (including the terminating empty query), and 321 returned rows. Admission/preparation was 1.045853 ms; query-and-digest 14.134235 ms wall / 14.096875 ms same-thread CPU; hex/result 0.295442 / 0.294530 ms. The observer's internal segments total 15.475530 ms; the extra 0.906605 ms to the outer metadata interval is not assigned to SQL or lock waiting. Query/digest is material synchronous work, but the existing measurement does not separate SQL/row materialization, authoring guards and digest construction.

Native render-input/brush preparation plus Stroke construction was 2.916043 ms wall / 2.916566 ms reported thread CPU for 13 actual constructions. The tiny CPU-above-wall difference is preserved rather than clipped. Payload decode is only 0.118843 ms wall and has no CPU measurement. Subtracting payload freeze/decode and native wall from raster wall leaves 12.505486 ms arithmetically; it includes unmeasured spatial reads, validation, allocation, rendering and suspension/dispatch, and is not a pure rendering or waiting measurement.

## Multiple layers: do not mistake late callbacks for readiness blockers

Local metadata row totals remain 321, with 8/16 queries across 4/8 layers, zero preparation rows, and query/digest CPU sums 15.076328 / 24.718490 ms. Native preparation/build sums are 3.221538 / 4.831345 ms wall and 3.218191 / 1.603106 ms thread CPU. The larger eight-layer wall/CPU gap is not assigned to scheduler or GC without evidence.

Four-layer ready-draw intervals accumulate 32.160983 ms; eight-layer ready draws accumulate 66.009225 ms. In the eight-layer append, requests are issued at 0, 8.711234, 17.341397, 25.919632, 36.879297, 45.678024, 54.988437 and 64.057390 ms. Publication occurs at about 72.643–73.658 ms; completed layers draw sequentially from 74.848379 to 140.993298 ms. All eight recorded callbacks occur later, around 141.736–141.883 ms. Their 68–69 ms enqueue/callback intervals therefore did **not** gate those ready draws; another already-queued/conflated event allowed the draw loop to proceed.

This sequence and the synchronous layer loop are consistent with substantial Main work composing retained/completed bitmaps in the `Canvas(Bitmap)` fixture and delaying Main dispatch. It is not a same-thread CPU measurement or proof of identical cost in a hardware-accelerated View. The large per-layer raster/metadata/dispatch intervals overlap and cannot be added as exclusive total costs.

Across all 65 frames there are 130 enqueue requests and 76 observed callback dispatches; 11 frames receive two callbacks. That is not sufficient evidence to delete the finally notification: `InkCanvasView.rasterPending` depends on `job != null`, and capture readiness is only acknowledged after pending work clears. Removing that notification could strand the pending indicator/capture acknowledgement after pixels have already published. This run does not prove a broken coalescing algorithm.

## Minimal production candidate supported by code and fixture facts

`StreamedInkRaster` unconditionally constructs one `PencilTileRenderer` per frame, even when no dirty tile exists or every stroke is PEN. Its constructor immediately runs `GraphiteMaterial.alpha()` (4096 generated bytes), allocates an `IntArray(4096)` (16 KiB element storage), and creates its local maps/Paint/owner. The diagnostic fixture uses only `InkPen.PEN`; all 65 successful frames still execute that eager constructor.

A minimal safe candidate is to keep a nullable **worker-local** pencil renderer and instantiate it on the first actually drawn PENCIL stroke, keeping the original batch and finally `?.clear()` calls. No native cross-frame cache, renderer factory change, SQL, scheduling, publication, epoch, transform, clipping or resource-budget policy needs to change. For this fixed all-PEN execution, the old source implies 65 unnecessary constructions and 1,331,200 bytes of grain/pixel array element storage, excluding object headers and other allocations. **Those are source-path calculations, not measured constructor counts, heap savings or latency improvements.** They do not explain the metadata CPU or the old regression.

Existing test choices are sufficient without editing the common probe: `NativeStrokeTileReuseTest#oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips` includes PEN/HIGHLIGHTER/PENCIL and a pencil-only allowed phase with a per-tile pixel oracle; `TileLocalRedrawTest#integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture` contains mixed PEN/PENCIL and cold comparisons; `TileLocalRedrawTest#interruptedPanWaitJoinsItsWorkerBeforeClosingResources` checks interruption/join; the existing one/four/eight all-PEN append method exercises the unused-pencil branch. Original timeouts/assertions must remain.

For any additional metadata optimization, the next smallest causal prerequisite is distinguishing the existing synchronous `SpatialIndex.ink` query/materialization calls from the in-memory digest loop inside the measured query/digest interval, or a correspondingly narrow trace. The current 14 ms combined CPU measurement cannot justify changing the query, dropping revision/epoch checks or adding an incremental signature cache. No such extra diagnostic or production change was performed for this review.

## Acceptance remains open

This is one enabled diagnostic per fixture, not four balanced default-off repeats. It adds no threshold and does not supersede the previous C0→C1 single-layer regression classification (paired median +9.4687515 ms / +26.3722813%). Any lazy-pencil candidate needs the existing mixed-pencil pixel/lifecycle checks and the original public default-off end-to-end screen before claiming a speedup or closure. The latest continuous-transform/sparse-update comparison and original dense 1201-stroke 30-second gate were not executed here and remain separate open requirements. This evidence supports precise work accounting and a small unnecessary-allocation removal; it does not support a noise attribution or complete performance sign-off.
