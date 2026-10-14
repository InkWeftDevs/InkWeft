# UUID-scope save-boundary log extractor

This is a separate local-only derivative of the reviewed old save-boundary extractor. The old extractor, failed run, controller and readiness remain frozen. This tool does not fetch metadata/artifacts, download ZIPs, trigger CI, run Android, write Git state or classify performance acceptance.

The exact source/readiness/prep/active chain is `94379190b9ad0e1034f136ea9887fb0ac12e7ea7` / `a69c173c16bd8a434cc1eab9ef8ff2ad56070c19` / `818493a1e8a4d2121e125d5ba39c4219f1977720` / `ac63ce66ed4aaec0372c56b39be97e3335966c31`. All four are pinned in CLI admission. Actual run/job IDs remain CLI inputs and must match the supplied official metadata and binding. The local Git helper verifies exact single parents, source ancestry/tree, eight readiness blobs, six reviewed source blobs and seven runtime/workflow blobs using read-only Git commands.

The successful path remains ten ordered JUnit PASS closures, one data APK and installation group, exactly the unchanged 90-save diagnostic markers, bound source/fixture/payload hashes, matching official completed/success statuses, attempt1, original phase deadlines and both exact cleanup claims. The imported frozen marker validator remains SHA256 `d27e19a7ae5b090436b8e70d5a2d914119e343eeb40e01352359d27f30215857`. No app/raster/MuPDF or formal performance success is implied.

The only new envelope is `INKWEFT_CAUSAL_FAILURE_LOG`. Its frozen closed schema, source/run/method identity, input/test hashes, original UTF8 byte count/SHA256, <=256KiB cap, worst escaped envelope cap and existing secret pattern are checked before any original content is retained. Refusal records must contain no original content and must give a reason. Accepted originals are exported byte-for-byte; invalid originals are not exported. A valid failure envelope must agree with the following failed METHOD receipt and cannot supply a PASS closure, rescue a failed official run or produce whole-run completeness. A failure after an otherwise complete marker block still leaves that method and the whole run incomplete. Missing/duplicate/corrupt evidence fails closed.

Failure observations may be retained until the real job's end even after a phase cutoff; successful evidence still must meet the original setup30/data45/work55/job60 limits. The parser stops at the first failure/error. Any later FINAL or extra receipts remain only in the hashed original official log and are not promoted into complete evidence. A retained failure envelope can therefore be partial: `matched_method_receipt` explicitly records whether its METHOD receipt was reached and matched.

## Invocation

Root owns official retrieval and raw retention. Copy `binding.template.json` to a new terminal binding; fill its run/job IDs and four status/conclusion fields verbatim from captured official metadata. Do not infer success from logs. Use a new output directory:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 /workspace/scratch/pr20-save-boundary-uuid-log-analysis-20261014/analyze_logs.py \
  --job-log /PATH/official-job.log \
  --run-metadata /PATH/official-terminal-run.json \
  --jobs-metadata /PATH/official-terminal-jobs.json \
  --binding /PATH/terminal-binding.json \
  --frozen /workspace/InkWeft-save-boundary-uuid-run/android/verification/pr20-save-boundary-uuid-20261014 \
  --control-root /workspace/InkWeft-save-boundary-uuid-run \
  --run-id ACTUAL_RUN_ID --job-id ACTUAL_JOB_ID \
  --workflow-sha ac63ce66ed4aaec0372c56b39be97e3335966c31 \
  --source-commit 94379190b9ad0e1034f136ea9887fb0ac12e7ea7 \
  --readiness-git a69c173c16bd8a434cc1eab9ef8ff2ad56070c19 \
  --prep-sha 818493a1e8a4d2121e125d5ba39c4219f1977720 \
  --out /PATH/new-extracted-directory
```

Exit0 requires `COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE`; any failed/incomplete run exits1. `analysis.json` hashes all supplied inputs and records local Git verification, complete/partial method evidence, failure evidence and limitations.

For validated forwarded marker blocks, the existing `method-NN-C1-records.json`, `method-NN-C1-original-payloads.json` and byte-exact `method-NN-C1-markers.log` outputs remain unchanged. For accepted failure envelopes, `failure-method-NN-C1-envelope.json` preserves the entire envelope. Only a FORWARDED failure gets `failure-method-NN-C1-instrumentation.log`, containing exact original UTF8 bytes with ledger length/SHA256. Refusals retain only envelope metadata/reason. Every failure ledger entry has `whole_run_complete=false`.

Only after whole-run completeness, the original marker text for method10 may be passed to `/workspace/scratch/pr20-save-boundary-analysis-20261014/analyze.py` (`--markers .../method-10-C1-markers.log --output ...`). That independent analyzer remains SHA256 `fd1c22004bb91e7e6673a6ac59eb0f2c2413c9f8ef454a00b2950c0581bbd1b7`. A complete numeric trace alone never proves whole-run success.

## Evidence and limits

`check-03/selftest-results.json` records synthetic parser tests, including the prior83 cases and new failure-envelope cases. Synthetic metadata always uses run900001/job900002; real reviewed Git objects/files are used only for local binding tests. No synthetic values are native measurements. `check-03/synthetic-independent-diagnostic.json` crosschecks the unchanged successful marker export against the independent analyzer. `local-git-binding.json` separately checks the actual execution worktree without network or native work.

Supplied official metadata authenticity remains external to this offline tool. Compact PASS receipts do not independently audit APK/native binaries, raw JUnit files, installed bytes, process observations, ACL files or artifact contents. Forwarded failure bytes are checked against their source-bound envelope; the original runner file cannot be independently fetched here. No artifact route or retry is added. Do not publish synthetic test directories as native evidence.
