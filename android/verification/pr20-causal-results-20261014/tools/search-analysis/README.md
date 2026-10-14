# Offline search causal analysis

Prepared against code `78a5babeaeb92292026423222d5e08635989198b` and readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`. This directory does not contain a native result yet.

Input is android_runtime's complete scoped extraction. Methods 08/09/10 have compact per-method records arrays of `{marker,data}`. The extraction `analysis.json` must say `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`; each selected file must match its controller records hash, record count, closed/PASS block and `whole_run_complete=true`. Failed or partial input is retained but cannot become a complete causal result. Source/APK/environment verification remains in the extraction provenance, rather than being recreated from timing values.

Run after extraction, with the real directory substituted for `/path/to/extracted`:

```bash
python3 /workspace/scratch/pr20-causal-20261014/search-analysis/analyze_search.py \
  --warm /path/to/extracted/method-09-C1-records.json \
  --saves /path/to/extracted/method-10-C1-records.json \
  --unicode /path/to/extracted/method-08-C1-records.json \
  --extraction-analysis /path/to/extracted/analysis.json \
  --out /workspace/scratch/pr20-causal-20261014/search-analysis/actual
```

Outputs are `analysis.json`, `analysis.md`, `warm-udf.csv`, `save-samples.csv`, `save-phases.csv`, and `original-events.csv`. JSON retains every original record and event. CSVs retain all 360 saves in original case/sample order. CPU deltas across different thread IDs, or invalid counters, remain unavailable. Same-thread deltas across suspended operations are explicitly non-exclusive thread CPU; they can include other work.

All percentiles use the frozen **nearest-rank** convention: `ceil(n*p)-1`, so 30 saves use p50 index 14, p95 index 28, max index 29. No acceptance gate is added or weakened. Forward and reverse blocks remain separate; mode contrasts are exploratory and are not additive causal effects.

Warm output splits four field categories into calls, UTF-16 volume, `Args.getString` and Kotlin `contains` wall/CPU. Page residual excludes source callbacks, which run before page SQL. The original SQL, instrumented SQL, before/after plans, reference preload and all three individual reference timings are retained. Residual time does not isolate JNI, storage I/O or locks. Reference timing excludes FTS, SQLite, sorting and result construction.

Save output pairs each save with its seven actual stages. It records request-to-entry, body substeps and receipt-to-return, preserving endpoint threads. Request-to-entry includes scheduling, Room serialization and transaction begin; it is not a pure lock wait. Read/index intervals are paired only when both endpoints exist. Their union overlaps are clipped to each save and the whole save window; incomplete intervals are never filled in. Committed-row/source-count progress is recorded separately from timed writer/read intervals. Phase distributions that include readiness or cleanup are labeled accordingly.

`--self-check` checks hand-known percentile/overlap arithmetic, cross-thread CPU rejection and incomplete interval handling. `offline-interface-check/` was generated solely from android_runtime's synthetic interface fixture, with the mandatory `--synthetic-interface-check` output label. That fixture deliberately does not contain real stage sequences; relation issues are expected and retained. It is not native or performance evidence. No production, tests, public probes, previous evidence or thresholds were modified by this preparation.

Concrete next code changes are intentionally undecided until real complete records identify the dominant work and overlap. Diagnostic clocks and callbacks perturb timing; these records cannot replace a final frozen public performance comparison.
