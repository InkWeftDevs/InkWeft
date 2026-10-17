# Pan-fixed all-miss raster screen: ten-call preparation

Status: UNBOUND. PREPARATION ONLY. No build, Android run, CI activation, push, merge, or deployment is authorized by these files.

## Source scope

Candidate is published 01011eacec7d0d6411703631f49fb64f2f9e7aab, tree ab7f2565176cf67d6c2c7538a17d8f38d9750c6a. Local bb7e3bb4609a4e13cb20167fa09afbaf06a4dc24 has that exact tree. The new isolated local control is 33e8e60f35f3c60339d2595928857006b77af082; it is not yet published. It must contain the same complete tree, except for reversing the six-line all-miss branch in android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt. Candidate file SHA256 is 115cb7ea6f3d429d1637ca1be05bbe09ab3a68d2fac5ce617c4cf3519362b52f; control is e7241f4329ece5148f8d1e6f740e102f8326bd4ef0db7f0a6f0a2871e12c9950.

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

prior-correctness-reuse.json records the exact source and archive hashes supporting omission of the two StreamedInkRasterTest dense/batch-boundary calls from the historical twelve-call proposal. The pan-run2 archive reports both PASS and binds the exact current StreamedInkRasterTest and OwnedRasterJobs source hashes. Candidate Android source is unchanged from that tested fixed source; verification-only documents are outside build inputs.

The prior APK used org.inkweft.app.a0.insertion. This screen uses org.inkweft.app.a0. Reuse is source-bound prior correctness evidence, not identical package/configuration, new-run execution, APK reuse, performance evidence, or proof of any speedup. Any Android implementation/test/build-input change invalidates this omission and needs review. The old twelve-call plan/evidence is preserved unchanged.

## Bounded runtime and safe stopping

Build C1 app/appTest and C2 app/appTest/dataTest: five APKs, five install groups. Reconstruct committed sources separately, keep one shared dependency cache, no Gradle task build cache, one worker, genuine official native dependencies, installed-vs-built APK hashes and source identities. Preserve owned-process natural exit, exact minimal ACL restoration and both cleanup passes. No process force-stop, new dependency mirror or permissions, or automatic retries.

Planning estimate: about 20–30 minutes total; two builds dominate. Original absolute limits remain setup30/data45/raster50/work55/job60 minutes from one first-step clock. User hard stop is 2026-10-18 00:00:00 UTC (Beijing08:00). Refuse to start unless the full60-minute envelope ends before that deadline; no clock reset or extension. If the earlier quota-reset signal is received, complete this one round and pause; do not start another round. That external signal is owned by the parent and is not a polling loop in this controller.

Correctness assertion failures stop the run. Missing/malformed/unknown marker output, incomplete instrumentation, crash/timeout, identity mismatch or unconfirmed cleanup never count as PASS. Preserve original raw output; the existing exclusive normal/failure forwarding and20MiB combined raw+parsed+official-stream limit remain. Expected interrupted-pan diagnostic output must still end in JUnit PASS. Complete measurements with mixed/null speed effects are a valid screen without demonstrated benefit, not a reason to rerun.

This isolated two-seed screen does not close the old four-pair gate. Historical B-to-cb7 eight-layer forced-full remains +39.038078ms/+12.219478%, REGRESSION_SIGNAL under the unchanged original three-of-four-direction rule; aggregate45/5/4 is unchanged. No old numerical samples are pooled with the new pan-fixed pair.

## Activation boundary

The source pair is locally verified; execution source-spec hash and readiness remain UNBOUND; approved=false; workflow exists only as workflow.proposed.yml under ci, with a zero before-SHA guard. Freeze and binding require actual source objects, reviewed immutable readiness and separate approval by the parent before expensive execution. The proposed workflow is not installed in .github/workflows. This preparation cannot start a native run.
