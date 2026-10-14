# Fixed GC + snapshot official-log extractor

This scratch-only extractor is ready to inspect the **already-started** data run
`38553018139`, job `115815277181`, attempt 1. It has not read that run's logs or
produced any native result. The user requested pausing after this round: finish
the existing run's terminal evidence only; no retry, new experiment or activation.

`analyze_logs.py` adapts the historical
`../pr20-save-boundary-uuid-log-analysis-20261014/analyze_logs.py` without editing
the historical files. The final local self-test is `check-04/selftest-results.json`:
**101 checks passed**, using synthesized logs and metadata plus a read-only check
of the actual local Git chain. Synthetic log parsing is explicitly forced to
`SYNTHETIC_STRUCTURE_COMPLETE_UNBOUND`; it cannot mark synthetic output as real
whole-run evidence. This is offline validation, not Android performance evidence.

## Exact binding

| Item | Fixed identity |
| --- | --- |
| Source | `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353` |
| Readiness | `fbc502eb49c15018bb1ab2694dc256b7a0f9fc25` |
| Prep | `083a892133d818cf364865d3ee3edc1fdb6413c0` |
| Active workflow | `c3483b74e5db7cb7928d26445bd37af51700284c` |
| Official run / job | `38553018139` / `115815277181`, attempt 1 |
| Active workflow bytes | `5ebb88e2998315bbb021772b986d14baff95f23fe9a2284177400ebf56c1ab57` |
| Frozen marker validator | `850707164f4c89ae1920c1f9c99ad9e1a68be7f71b8c456b437671ddada96bda` |
| Frozen marker contract | `a0682c82d713cb9d2932f0a6c4cc93d5b1d37b33f2ca8580789241c92854084c` |
| Frozen method plan | `f9f80f29e083824fd82df6714df622963a15b5ad0f1c1a030ada7fd56a123643` |

`FIXED_BINDING.json` contains the complete input/control hashes and these
identities. Its four terminal metadata fields are intentionally absent until
official completed run/job metadata is available. `frozen-inputs/` copies all
eight readiness files byte for byte. The historical `draft-inputs/`,
`DRAFT_BINDING.json`, and `check-01` through `check-03` are superseded development
evidence; use the final files and `check-04` above.

The frozen method plan retains an obsolete `pending` description from preparation.
The parser preserves those bytes. Execution-contract review is `READY`; the
extractor checks the exact frozen eight-method plan and Git blobs. This does not
authorize changing the running commit or rerunning it.

## Scope and retained evidence

Methods 1–5 are the five fixed `HistoryCollectionTest` correctness guards;
methods 6 and 7 are same-Room and separate-Room natural-arrival boundary
diagnostics; method 8 is the idle / held snapshot / active search experiment.
Exactly one data test APK and one installation group are allowed.

The extractor checks the local source/readiness/prep/active chain, reviewed source
blobs, control files, active workflow bytes, official run/job identity, clock and
phase bounds, eight ordered METHOD receipts, original JUnit PASS, zero adb exit,
NATURAL_EXIT, installation/signer/environment identity, FINAL, both cleanup paths,
and exact ACL hash restoration. It retains each marker's original JSON string,
UTF-8 byte length and SHA-256 as well as the parsed values and ordered footer.

GC RECORD receipt handling validates marker names, strict JSON, bytes and hashes;
END calls the frozen whole-method `safe_marker_payloads` GC validator. It does
not invent generic GC schemas. Snapshot records receive per-record schema and
whole-method checks. Schema/receipt closure is distinct from numeric validity.

The schema-2 failure consumer is independently implemented. It checks exact
bindings, original byte caps, canonical 32 KiB base64 chunks, per-chunk/context/
whole-text hashes, complete UTF-8 reconstruction and fixed sensitive-text rules.
It retains full original bytes only after complete verification. Interrupted
streams retain envelope hashes; the supplied original official job log retains
all bytes. REFUSED streams retain their refusal envelope and cannot become PASS.
Normal marker forwarding and full failure-text forwarding are mutually exclusive.

After the first non-PASS METHOD the parser continues to audit FINAL, dual cleanup
and all remaining methods as NOT_RUN. A validated failure terminal is labeled
`FAILED_TERMINAL_AUDITED_NOT_PASS`, while overall analysis stays `INCOMPLETE` and
the CLI exits 1. Missing/unconfirmed cleanup cannot acquire that terminal label.
Missing failure text can still leave a terminal audit with
`failure_text_audited: false`. No failure log is interpreted as successful marker
evidence, and receipt-like text embedded in failure bytes remains opaque.

## Terminal invocation

The parent supplies the one official decoded job log, terminal run JSON, and the
single-job JSON response. No network request is made by this extractor. Copy
`FIXED_BINDING.json` to a new `terminal-binding.json` and fill exactly:

```python
binding["run_status"] = run["status"]
binding["run_conclusion"] = run["conclusion"]
binding["job_status"] = job["status"]
binding["job_conclusion"] = job["conclusion"]
```

Keep all existing identities and hashes unchanged. Use a fresh output directory:

```bash
python /workspace/scratch/pr20-gc-save-log-analysis-20261014/analyze_logs.py \
  --job-log /path/to/official-decoded-job.log \
  --run-metadata /path/to/official-run.json \
  --jobs-metadata /path/to/official-jobs.json \
  --binding /path/to/terminal-binding.json \
  --frozen /workspace/scratch/pr20-gc-save-log-analysis-20261014/frozen-inputs \
  --control-root /workspace/InkWeft-gc-save-run \
  --source-commit fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353 \
  --readiness-git fbc502eb49c15018bb1ab2694dc256b7a0f9fc25 \
  --prep-sha 083a892133d818cf364865d3ee3edc1fdb6413c0 \
  --workflow-sha c3483b74e5db7cb7928d26445bd37af51700284c \
  --run-id 38553018139 --job-id 115815277181 \
  --out /path/to/new-extraction-directory
```

Only complete official success and all receipt checks yield
`COMPLETE_SCOPED_GC_SAVE_LOG_EVIDENCE` / exit 0. This label is transport and
terminal evidence closure, not performance acceptance. `numeric_acceptance`
remains `NOT_EVALUATED`.

## Numeric follow-up for this existing run

Use the frozen GC v3 analyzer
`../pr20-gc-author-only-analysis-v3-20261014/analyze.py`, SHA
`d27991af251dd3031245d0db6ccf9b70e3b25f9eec3dc807e94d5244e67c59d2`,
on extracted method 6 and 7 marker logs. Use the snapshot analyzer
`../pr20-snapshot-control-analysis-20261014/analyze.py`, SHA
`b57adcc7017526bdf9d10dd31631cc08de3c0b9747f0fd86e84aaf2a0e04535e`,
on extracted method 8 markers. Numeric outputs require their own validity review;
partial/failed methods never satisfy a complete diagnostic gate.

Keep request-to-return segments, actual DELETE overlap, and original
before-commit exposure separate. Zero of 30 exposed samples is a valid observed
count; below 20 conditional samples p50/p95 remain null. Neither BEGIN nor low CPU
END proves pure lock wait; component quantiles are not additive. The snapshot
experiment is a single-source fixed-order controlled observation, not a product
speedup or causal acceptance result.

The supplied official log/metadata provenance is external. Compact receipts do
not independently inspect APK binaries, raw JUnit files, native libraries,
installed bytes or ACL files. Original instrumentation hashes are linked through
controller receipts; only a fully forwarded failure text can be independently
rehashed in full from these logs.
