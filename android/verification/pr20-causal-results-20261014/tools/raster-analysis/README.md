# Offline raster diagnostic arithmetic

Prepared for source `78a5babeaeb92292026423222d5e08635989198b`, readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`, method 13. No production/probe/schema changes, Android build, device execution or network access. No actual run has been analyzed at preparation time.

After the parent/runtime parser produces complete scoped official-log evidence:

```sh
python3 /workspace/scratch/pr20-causal-20261014/raster-analysis/analyze.py \
  --records /PATH/TO/VALIDATED/method-13-C1-records.json \
  --integrity-report /PATH/TO/VALIDATED/analysis.json \
  --out /PATH/TO/raster-analysis
```

The records file must be the exact compact `[{"marker":...,"data":...},...]+newline` reconstructed by the official-log parser. The script requires that parser's `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`, no issues, one closed PASS method-13 block, matching source/test/records hashes and official run/workflow context, all 13 final methods PASS, two install groups PASS, successful packaging, no missing files, both cleanups and stable forwarded environment hashes. The extracted-record receipt must say `whole_run_complete=true`. Its claimed official provenance remains the external parser's responsibility; this arithmetic script does not authenticate a GitHub service, audit original ZIP/APK files, reconstitute raw JUnit logs, or independently observe installation/ACL state.

The frozen pure validator and marker schema are reused without modification and hash-checked. The script then checks all 65 expected frame identities and metadata associations, required stage ordering, source-guarded same-thread metadata CPU, and local native-count agreement. Missing/duplicate records or incomplete terminal evidence return exit 2 and overwrite output with INCOMPLETE; three marker blocks or local method PASS alone cannot produce a complete result.

Outputs `analysis.json` and `analysis.md` preserve all three first/append/full timing records; per-frame request/worker/metadata/raster/prepare/Main/notification/ready intervals; per-phase metadata, native and repository-read costs/counts; pixels, joined and tracked resources. Absolute stage timestamps and thread IDs remain in JSON. Negative callback-to-ready intervals and absent callback times remain visible, because another layer/conflated event can wake draw first and Job join does not flush callbacks. CPU is never inferred from a suspend interval. Per-layer sums overlap; they are not additive exclusive end-to-end cost. Native timing includes render-input/brush preparation plus native construction, while payload decode has wall/counts only.

This is one opt-in run per fixture, no repeated-sample percentiles, no new threshold, no pooling with old default-off or diagnostic runs. The original single-layer regression signal, other unclosed performance comparisons, latest continuous-transform/sparse-update validation, and original dense 30-second gate remain open. A fix recommendation requires the parent to review complete actual evidence; this script does not infer noise or select a fix automatically.

`selfcheck.py` performs synthetic host-only checks. Current receipt has 19 passing checks: valid arithmetic/binding; absent and post-ready callbacks; unavailable CPU; rejection of missing/duplicate frames/metadata, mismatched native counts, cross-thread CPU, bad stage ordering, pixel failure, incomplete whole run, bad extraction/hash/source, duplicate blocks, packaging and cleanup failures. Synthetic records are not Android results or performance evidence.
