# Pan-fixed raster screen recovery: ten-call preparation

Status: UNBOUND. PREPARATION ONLY. No build, Android run, CI activation, push, merge, or deployment is authorized by these files.

## Source scope

Candidate is 1c542a708c709715e11eedbd8398d5434ec9f061, tree 205b1338f1d3ed047aa7b2640908700ae9760db9, an exact child of published 01011eacec7d0d6411703631f49fb64f2f9e7aab with only the reviewed RasterReadCacheTest value-assertion repair. Control is a9a335a535de05398c066ae6f68028efa17977be; its whole tree differs only by reversing the six-line all-miss branch in android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt. Both use test SHA256 1776b54cd89f9130509c563764ec7ed39b0f63ab67fb5e51e85e757530c7c43c. Production bytes remain those of the original candidate/control. Sources are local until exact remote verification.

Both sources retain the completed pan-continuity fix, all test sources, fixtures, epochs, cleanup, dependency declarations and build settings. No production optimization is added by this preparation. Whole-tree delta and all source hashes must be checked before binding. No production/test runtime overlays are permitted. Historical control 207bc6 is not the new run base.

## Exact ten-call scope

The complete selectors and arguments are in method-plan.template.json; marker source hashes and closed output schemas are in marker-contract.template.json.

1. C2 RasterReadCacheTest#coldBatchesPreservePaintOrderAndPopulateEveryCachedIdentity
2. C2 RasterReadCacheTest#sameRevisionMaskChangeDuringCachedVisitRejectsTheWholeRead
3. C2 NativeStrokeTileReuseTest#oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips
4. C2 RasterPerformanceProbe#continuousTransformsAndSeparatedDirtyTiles
5. C2 TileLocalRedrawTest#appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames
6. C2 TileLocalRedrawTest#interruptedPanWaitJoinsItsWorkerBeforeClosingResources
7. C1 RasterCausalDiagnosticsTest#oneFourEightLayerAppendReportsFrameStagesAndMatchesColdFrames, seed4101
8. C2 same diagnostic, seed4101
9. C2 same diagnostic, seed4102
10. C1 same diagnostic, seed4102

Data selectors use org.inkweft.data; app selectors use org.inkweft.app. The four diagnostic calls require rasterCausalDiagnostics=true and the exact pairSeed. All calls use raw am instrument -w -r, one expected JUnit method, once only. Six correctness guards precede four comparisons. All original on-device timeouts and assertions remain.

Retain all 1/4/8-layer records and first/local/full/hidden-local/hidden-full phases: 65 frames per diagnostic, 260 total. Compare the two eight-layer result.forcedFullCompleteMs pairs; corresponding stage detail is role=full, phase=full. Require equal paired fixture objects/settings, exact pixels, complete frames, joined jobs and released resources. Do not pool layers/phases as repeated samples, sum overlapping layer wall time, estimate percentiles, trim samples, or retry for a favorable result.

## Prior correctness reuse

prior-correctness-reuse.json records the exact source and archive hashes supporting omission of the two StreamedInkRasterTest dense/batch-boundary calls from the historical twelve-call proposal. The pan-run2 archive reports both PASS and binds the exact current StreamedInkRasterTest and OwnedRasterJobs source hashes. The only Android source difference is the reviewed RasterReadCacheTest assertion repair. The source freeze requires exactly this path and both old/new hashes; all production, omitted dense-test bytes, app guard/probe bytes and build inputs remain unchanged.

The prior APK used org.inkweft.app.a0.insertion. This screen uses org.inkweft.app.a0. Reuse is source-bound prior correctness evidence, not identical package/configuration, new-run execution, APK reuse, performance evidence, or proof of any speedup. Any other Android source or build-input change invalidates this omission and needs review. The old twelve-call plan/evidence is preserved unchanged.

## Bounded runtime and safe stopping

Build C1 app/appTest and C2 app/appTest/dataTest: five APKs, five install groups. Reconstruct committed sources separately, keep one shared dependency cache, no Gradle task build cache, one worker, genuine official native dependencies, installed-vs-built APK hashes and source identities. Use an explicitly owned foreground adb server on a prebound loopback listener; refuse a foreign default server. Controller and emulator-child clients inherit an explicit 127.0.0.1 server route that cannot auto-start a replacement daemon. Android user configuration stays inside the owned temporary directory, created before adb startup. Preserve owned-process natural exit, exact minimal ACL restoration and both cleanup passes including adb ownership. No process force-stop, new dependency mirror or permissions, or automatic retries.

Planning estimate: about 20–30 minutes total; two builds dominate. Original absolute limits remain setup30/data45/raster50/work55/job60 minutes from one first-step clock. User hard stop is 2026-10-18 00:00:00 UTC (Beijing08:00). Refuse to start unless the full60-minute envelope ends before that deadline; no clock reset or extension. If the earlier quota-reset signal is received, complete this one round and pause; do not start another round. That external signal is owned by the parent and is not a polling loop in this controller.

Correctness assertion failures stop the run. Missing/malformed/unknown marker output, incomplete instrumentation, crash/timeout, identity mismatch or unconfirmed cleanup never count as PASS. Preserve original raw output; the existing exclusive normal/failure forwarding and20MiB combined raw+parsed+official-stream limit remain. Expected interrupted-pan diagnostic output must still end in JUnit PASS. Complete measurements with mixed/null speed effects are a valid screen without demonstrated benefit, not a reason to rerun.

This isolated two-seed screen does not close the old four-pair gate. Historical B-to-cb7 eight-layer forced-full remains +39.038078ms/+12.219478%, REGRESSION_SIGNAL under the unchanged original three-of-four-direction rule; aggregate45/5/4 is unchanged. No old numerical samples are pooled with the new pan-fixed pair.

## Recovery classification

Original run 39053188644 failed its first guard because InkStroke uses reference equality and two independent decodes were compared as objects. The remaining nine calls were NOT_RUN, so there are no prior diagnostic samples to retry or pool. That failure remains recorded. Recovery changes only the value assertion and adb server ownership; all five APKs are rebuilt from the new exact source pair, with installed/source hashes checked. Reuse only official dependency cache entries through normal Gradle resolution, never old APKs or injected outputs. The old cleanup receipts verified recorded command groups, emulator and ACL only; GitHub later swept one detached adb process. They are not proof all host processes had already stopped.

## Activation boundary

The repaired source pair is locally verified; execution source-spec hash and readiness remain UNBOUND; approved=false; workflow exists only as workflow.proposed.yml under ci, with a zero before-SHA guard. Freeze and binding require actual source objects, reviewed immutable readiness and separate approval by the parent before expensive execution. The proposed workflow is not installed in .github/workflows. This preparation cannot start a native run.
