# Raster all-miss screening and owned Job guards

Status: UNBOUND. This is an inactive diagnostic proposal. No workflow is activated, no CI has run, no native result is claimed, and no original acceptance gate is closed.

## Fixed source identities

- C1 `all_miss_control`: `207bc654e0c922bb58b599d0ca619aff85eeebeb`.
- C2 `all_miss_candidate`: `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353`.
- The entire Git trees differ only at `android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt`. C1 bytes SHA256 `e7241f4329ece5148f8d1e6f740e102f8326bd4ef0db7f0a6f0a2871e12c9950`; C2 `115cb7ea6f3d429d1637ca1be05bbe09ab3a68d2fac5ce617c4cf3519362b52f`. C1 is a real local child commit of C2 with this file restored from `28104e1334c79e68b4f9713cbb2aa2e3a0fa9993`. It is not the historical cb7e142 identity.
- Both `production_overrides` and `common_probes` are empty. Every test, fixture, diagnostic hook, dependency declaration and other production file is identical. Both use strict Job test changes and diagnostic seed allowlist 4101/4102. The public benchmark's seeds, sequence, limits and archived results remain unchanged.
- Freeze and reconstruction check full Git tree delta, exact file hashes, common tests, two separate source manifests and trees. App source identity embeds the actual source commit. Build receipts bind APK hashes, signatures, package/target/runner, resolved dependencies and full native ELF inventories; installed APK hashes must match built APKs.

C2 changes only the all-miss branch after the exact ordered-ID and revision checks: populate the cache in snapshot order and visit that list directly. Epoch checks, cursor order, filtering, cache puts, spatial keys and mixed-hit behavior remain. Fewer allocations are a hypothesis, not performance evidence, and need not explain the historical +39 ms full-path signal.

## Twelve calls, without retries

The complete selectors, exact ADB argument arrays, seeds and install groups are authoritative in `method-plan.json` (template before freeze). Each call is one JUnit method and runs once, on the same runner/emulator.

| Ordinal | Source | Method | Evidence |
|---|---|---|---|
| 1 | C2 | RasterReadCacheTest#coldBatchesPreservePaintOrderAndPopulateEveryCachedIdentity | No marker; 300 ordered strokes, 256/44 batch boundary, cold/warm identity |
| 2 | C2 | RasterReadCacheTest#sameRevisionMaskChangeDuringCachedVisitRejectsTheWholeRead | No marker; unchanged page revision cannot bypass epoch safety |
| 3 | C2 | NativeStrokeTileReuseTest#oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips | 4 NATIVE_STROKE_TILE_EVIDENCE records; transforms/clips and exact pixels |
| 4 | C2 | RasterPerformanceProbe#continuousTransformsAndSeparatedDirtyTiles | 14 RASTER_PAIRED_EVIDENCE and 1 RASTER_SQLITE_EVIDENCE; single raster sequence, no sample_count |
| 5 | C2 | StreamedInkRasterTest#completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker | 6 STREAMED_FRAME_STAGE records; original-dense 1201 strokes/76802 points |
| 6 | C2 | StreamedInkRasterTest#completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary | 6 STREAMED_FRAME_STAGE; bounded-two-batch 258 strokes/8226 points, separate closed fixture schema |
| 7 | C2 | TileLocalRedrawTest#appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames | No marker; layer/undo/redo/eraser cold-frame equality |
| 8 | C2 | TileLocalRedrawTest#interruptedPanWaitJoinsItsWorkerBeforeClosingResources | 1 TILE_PAN_STAGE expected interruption marker; JUnit still passes |
| 9 | C1 | RasterCausalDiagnosticsTest#oneFourEightLayerAppendReportsFrameStagesAndMatchesColdFrames | pairSeed=4101, rasterCausalDiagnostics=true |
| 10 | C2 | Same diagnostic | pairSeed=4101, rasterCausalDiagnostics=true |
| 11 | C2 | Same diagnostic | pairSeed=4102, rasterCausalDiagnostics=true |
| 12 | C1 | Same diagnostic | pairSeed=4102, rasterCausalDiagnostics=true |

Calls 9–12 each emit exactly three RASTER_CAUSAL_DIAGNOSTIC records for 1/4/8 layers, preserving the full seed stream and first/local/full/hidden-local/hidden-full sequence. Exact frame sets are 5/20/40, or 65 frames per method and 260 total. Every cold pixel, transform/layer revision and Job join/resource guard stays in place. The two common seed fixture objects, including hashes and SQLite settings, must match across sources. There is no fixture-setup shortcut and no shortened on-device timeout.

The six app calls cover strict owned Job success paths plus adjacent layer and interruption safety. Call 8 uses the existing nested RasterJobs helper; it is not direct fault injection into every new OwnedRasterJobs failure path. The dense release deadline now includes clear plus cancel/join, so the same 5 s bound is stricter than the old polling-only interval.

## Build and time limits

Build C1 app/appTest (2 APKs) then C2 app/appTest/dataTest (3 APKs), from separate source trees with one shared resolved dependency set. Five install groups: candidate data, candidate app guards, control 4101, candidate 4101+4102, control 4102. Complete owned-package natural exit and installed hash validation occur at the original boundaries; no am kill, force-stop or method rerun is permitted.

The original first-step runner clock remains setup 30 min, data 45 min, raster 50 min, work 55 min, whole job 60 min. All six phase wrapper subprocesses share those absolute cutoffs; no phase restarts its clock. Setup covers both builds and emulator preparation. No larger timeout is authorized if this does not fit. The reviewed subprocess ownership, cleanup and exact ACL restoration are retained.

Planning only: a second app identity adds roughly 8–12 min of build work and roughly 1–3 min for four diagnostic calls/install transitions; actual build/cache/network behavior may differ. Existing adjacent six-guard observations suggest sub-minute test work under normal conditions, not a performance guarantee. Two-build setup may still exceed 30 min; fail closed if it does. The shared host Kotlin compile passed in 374.626 s with 761 source/build inputs unchanged; this is not APK execution or native performance.

MuPDF must resolve the official com.artifex.mupdf:fitz:1.28.5 full native AAR, SHA256 `e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e`, POM `d66b443446cb2bb992c0de60115ae4988584bd44d16c877d0ba5e2d400b416ff`. The local cache and archived receipts prove these complete four-ABI bytes existed; they do not prove future official availability. Native AAR ELF entries must match the newly built app on both sources. No mirror, Java-only substitute or policy-denial bypass.

## Closed evidence and publication budget

The marker contract retains original complete JSON and bounds count, byte length and closed schema by method. Calls 3–8 together allow 352256 B of marker JSON; each diagnostic call allows 786432 B, with three records capped at 262144 B each. Unknown/missing/duplicate/malformed/nonfinite records fail validation. Normal forwarding validates the whole block before printing original JSON, source/APK/hash context and END. `normal_forward_started` is persisted before the first print; any later failure refuses a second failure-text stream.

The failure-only helper is copied unchanged from the separate GC/save proposal; its pure functions have a separate 44-case synthetic review. This raster caller and its aggregate budgets still require independent runtime review. It provides original UTF-8 bytes in 32 KiB base64 chunks with sequence, each/full hash and exact source/method binding, capped here at 1048576 raw bytes / 1439104 wire bytes. It is never PASS evidence and never rescues a failed method. The complete artifact raw log remains authoritative. Sensitive, invalid UTF-8, mismatched or oversized text is refused without partial forwarding. No truncation is allowed to make a cap pass. Arbitrary malformed output beyond the closed contract may prevent complete publication and must remain a visible failure.

`size_bounds.py` counts artifact raw + parsed records + official normal/failure streams together, with one shared 3 MiB reserve for source/control/build/dependency/context/cleanup/packaging text. Current conservative bound: normal completion 17795072 B; worst terminal failure 19245952 B; 20 MiB limit 20971520 B; headroom 1725568 B. The packager enforces eight mutually exclusive fixed-text buckets totaling 3 MiB, including a 128 KiB fixed official receipt reserve, and actual artifact bytes plus conservative per-method official evidence wire <=20 MiB. The buckets are frozen/control 768 KiB; build/dependency 1024 KiB; source/install/environment 256 KiB; both summaries and optional summary.tmp 512 KiB; cleanup/emulator 128 KiB; method JUnit/forwarding receipts 160 KiB; packaging 96 KiB; fixed official receipts 128 KiB. Normal forwarding that starts but fails reserves the full normal block plus at most 8192 B REFUSED metadata, and cannot add original failure text. A RUNNING/incomplete method or existing raw method log without a normal-start latch reserves the complete failure stream even if its final forwarding receipt was not saved. Existing upload directories and unknown evidence files are refused; optional summary.tmp is retained and counted once. Fixed selected inputs consume about 0.55 MiB before build/dependency/environment output; the remainder is still a runtime limit, not a promise. The combined rule needs independent review before activation. The packager refuses unsafe/oversize inputs, records missing files and never silently truncates. Static bounds and synthetic fault tests are not native results.

## Interpretation and activation boundary

This two-seed C1-to-C2 experiment screens a single candidate and localizes existing full-phase timings; it neither closes the original B-to-C1 four-pair acceptance gate nor estimates percentiles. Preserve original frozen PLAN line 114 and analyzer: three of four positive deltas plus paired median exceeding both bands. Eight-layer forced-full remains REGRESSION_SIGNAL, +39.038078 ms/+12.219478%, aggregate 45/5/4. Counts alone do not make it noise. A null screen stops this candidate; do not repeat trials until favorable.

Only root may prepare the reviewed readiness/controller commits and the one-use workflow guard. The proposed workflow stays outside .github/workflows, has an impossible zero parent SHA guard and run_attempt==1, and authorization remains false/UNBOUND. Root intends prep history based on C1 control, restoring candidate production bytes before adding the controller, so both source commits become reachable in the same authorized prep push. Do not merge the control rollback into final integration. No CI activation, source push, final delivery push, merge or deployment is performed by this proposal.
