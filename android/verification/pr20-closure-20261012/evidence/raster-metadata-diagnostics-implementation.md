# Opt-in raster metadata diagnostics

Source changes are complete in candidate `InkRepository.kt` and `TileLocalRedrawTest.kt` only (65 added lines, 3 removed). Static review and `git diff --check` passed. No build, device command, commit, or push was run by this agent.

Use instrumentation argument `rasterMetadataDiagnostics=true` with the unchanged full `org.inkweft.app.TileLocalRedrawTest` class. Default-off calls allocate no probe, read no clocks, emit no records, and perform no per-row diagnostic timing. The observer is per repository, captured once at call entry and invoked after the successful read transaction returns. The test callback only appends to a thread-safe list; `finally` disables it as soon as the original timed local-append ready call finishes. JSON is emitted at the existing end-of-case evidence point.

Three wall segments are reported in nanoseconds:

- `admissionAndPreparationNanos`: call preparation through entry into the ready Room callback. Includes preparation, yields, transaction scheduling/admission; it is not pure lock wait.
- `queryAndDigestNanos`: ready callback queries and incremental digest work, including owner/revision validation.
- `hexAndResultNanos`: unchanged SHA finalization, Formatter hex expression and result allocation. A hotspot here alone cannot prove Formatter is the sole cause.

The latter two also report cumulative thread CPU nanoseconds, with endpoint Linux thread IDs. CPU is null for different endpoint threads, unsupported/negative samples, or a negative difference; it is not exclusive coroutine CPU. Existing `onBatch` counts report preparation work, potentially hints/cuts/repeated identities, not unique strokes. Query counts include the terminal empty query; metadata rows count returned candidates before per-row filtering.

`nativeStrokeBuilds` reads the existing candidate counter outside frame timing using an optional getter. On the baseline the getter is absent, so the JSON field remains absent rather than becoming zero. The same diagnostic patch is portable to both versions.

`raster-metadata-diagnostics-portable.patch` passed `patch --dry-run --batch --fuzz=0 -p1` against `/workspace/scratch/inkweft-baseline-closure-a128bb07`. Only line offsets were required; both original baseline source hashes remained unchanged. The root can apply that reviewed scoped patch (not copy candidate files):

```sh
patch --batch --fuzz=0 -p1 -d /workspace/scratch/inkweft-baseline-closure-a128bb07 -i /workspace/scratch/pr20-closure-evidence/raster-metadata-diagnostics-portable.patch
```

This agent has **not** applied it to the baseline. Source hashes, patch hash and dry-run output are in `raster-metadata-diagnostics-patch-check.json`; field boundaries and completion state are also recorded in `raster-metadata-diagnostic-plan.json` and `raster-metadata-diagnostics-implementation.json`.

Original fixture sizes/order, timeouts, assertions, SHA/Formatter expression, and rendering algorithms are unchanged. The single-layer regression and original dense 30-second failure remain unresolved. New diagnostic-on runs require new frozen sources/APKs; their overhead cannot be presented as default-off production performance or substituted for existing failures.
