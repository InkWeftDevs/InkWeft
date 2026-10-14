# Raster metadata diagnostics v2 syntax correction

The candidate diagnostic-v1 build failed: the null arm in the `onBatch` conditional was parsed as a Unit block, so the conditional was inferred as `Any` rather than the declared suspend function type. The earlier static review did not catch this error. V1 build log/receipt, source records and original patch are retained unchanged; no v1 native result exists.

V2 only parenthesizes both lambda branch expressions and explicitly ignores the BuildBatch argument in the null branch. The same two-line syntax change is applied to candidate and baseline InkRepository. TileLocalRedrawTest and all diagnostic boundaries/algorithms/fixtures/deadlines remain unchanged.

`raster-metadata-diagnostics-v2-portable.patch` is relative to candidate c60fba7455cfc0b919d6f9c2d3b06f17ec25713d and passes zero-fuzz dry-run against the preserved pre-diagnostics baseline files. Applying it only in a temporary staging directory reproduces the current baseline exactly for both scoped files. Do not apply this full patch again to the already-instrumented baseline.

Hashes, the retained v1 failure hashes, and reconstruction checks are in `raster-metadata-diagnostics-v2-patch-check.json`. Implementation boundaries are retained in `raster-metadata-diagnostics-v2-implementation.json`. `git diff --check` passes; compilation and native execution await the root's new diagnostic-v2 phase. This agent ran no build, ADB, commit or push.
