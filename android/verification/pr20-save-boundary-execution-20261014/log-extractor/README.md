# Save-boundary official-log extractor

This is an offline, local-only adaptation of `pr20-causal-20261014/log-analysis/analyze_logs.py`. It reads the official decoded job log and raw run/jobs JSON supplied by root. It does not fetch metadata or artifacts, download ZIPs, run Android, trigger CI, change Git state, or classify performance acceptance. All edits and test objects are in this scratch directory. The frozen controller and readiness are unchanged.

## Fixed scope

- Source `4f41a501127dbed6dbef3519f93233a105b5d618` → readiness `4278f310d6f8a742072725f77fda3fdb92c22431` → prep `da2497844493b82ba2eae22b3dfa291572397649` → active `81fe788cb26413279d4867b92ad3517a60b69c36`.
- Ten ordered JUnit PASS closures; nine correctness guards with no markers and one IDLE/DIRECT/OBSERVE diagnostic with 90 saves. One data APK, one installation group, no raster/app/MuPDF claims.
- Runtime validator is an exact local copy of the frozen controller's `markers.py`, SHA256 `d27e19a7ae5b090436b8e70d5a2d914119e343eeb40e01352359d27f30215857`. This includes all author boundaries, background-task closure, direct-reader closure, positive committed-index readiness and the fixed initial fixture digest.
- CLI run/job IDs must equal binding and supplied official metadata. Official run and job must both be completed/success, attempt 1, the fixed push branch/workflow/head, and exactly one job. Failure preserves only partial evidence and exits nonzero.
- The local Git helper only invokes `rev-parse`, `rev-list`, `merge-base --is-ancestor`, and `show`. It checks active/prep exact single parents, source ancestry/tree, eight readiness blobs, six reviewed source blobs and seven runtime control/workflow blobs. It never fetches, checks out or writes Git objects/refs.

## Minimal delta from the previous extractor

Removed 13-call/two-installation-group/three-APK/raster assumptions. Bound the current source/readiness/prep/active chain and 30/45/55/60 minute deadlines. Retained strict ordered envelopes, payload hashes and shapes, all terminal/cleanup gates and partial-output labeling. Added exact local Git checks, strict integer footer/ordinal metadata, actual job-span checks, UTF-8 BOM support, and separate original-payload output. There is no generalized controller or execution framework.

## Root invocation after official terminal capture

Root owns official fetch and raw retention. `binding.template.json` contains actual run `38540498244` / job `115773755321`, but its four status/conclusion fields intentionally remain pending. Copy it to a new binding file and set those four fields verbatim from the captured terminal run/job JSON; do not infer success from logs. Keep the supplied raw metadata and decoded log unchanged. The extractor crosschecks all CLI identities and those terminal values.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 /workspace/scratch/pr20-save-boundary-log-analysis-20261014/analyze_logs.py \
  --job-log /PATH/official-job.log \
  --run-metadata /PATH/official-run.json \
  --jobs-metadata /PATH/official-jobs.json \
  --binding /PATH/terminal-binding.json \
  --frozen /workspace/InkWeft-save-boundary-run/android/verification/pr20-save-boundary-20261014 \
  --control-root /workspace/InkWeft-save-boundary-run \
  --run-id 38540498244 --job-id 115773755321 \
  --workflow-sha 81fe788cb26413279d4867b92ad3517a60b69c36 \
  --source-commit 4f41a501127dbed6dbef3519f93233a105b5d618 \
  --readiness-git 4278f310d6f8a742072725f77fda3fdb92c22431 \
  --prep-sha da2497844493b82ba2eae22b3dfa291572397649 \
  --out /PATH/new-extracted-directory
```

Exit 0 requires `COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE`. It means complete scoped log evidence only. `analysis.json` records original input file hashes, Git checks, closure/phase evidence and limitations. Output directories must be new.

Every forwarded method has `method-NN-C1-records.json` in the controller's exact compact semantic form, `method-NN-C1-original-payloads.json` containing the exact original JSON strings with their byte lengths/SHA256, and `method-NN-C1-markers.log` containing the same marker/payload pairs as individual lines. The ledger marks block closure, method PASS and whole-run completeness separately. Raw log SHA is linked between CONTEXT and FINAL; the raw instrumentation files themselves are not available through these compact receipts.

Only after whole-run completeness is established, pass `method-10-C1-markers.log` to the independent analyzer:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 /workspace/scratch/pr20-save-boundary-analysis-20261014/analyze.py \
  --markers /PATH/new-extracted-directory/method-10-C1-markers.log \
  --output /PATH/save-boundary-diagnostic.json
```

The independent analyzer SHA256 is `fd1c22004bb91e7e6673a6ac59eb0f2c2413c9f8ef454a00b2950c0581bbd1b7`. No performance conclusion or formal public gate follows merely from successful extraction.

## Offline verification and limits

`check-03/selftest-results.json`: 83 synthetic cases PASS. The positive logs/metadata use deliberately synthetic run 900001/job 900002, with real reviewed Git objects and frozen files. Negative Git commits/refs exist only in the scratch test repository. Coverage includes missing/duplicate/truncated receipts, payload tampering, wrong run/job/source/parent/file bindings, official failures, phase budget/span faults, native-exit claim faults, exact cleanup claim faults, boolean type attacks, original payload preservation, BOM handling and late I/O failure. These are parser tests, not Android or native performance evidence. `local-git-binding.json` is a separate read-only check against the actual execution worktree. `check-03/synthetic-independent-diagnostic.json` exercises the original-marker export against the independent analyzer using synthetic values only.

The extractor cannot authenticate externally supplied metadata online. Compact receipts do not independently audit APK/native binaries, installed bytes, raw JUnit files, process observations, ACL files, or the packaged evidence manifest. They validate the audited controller's bound claims and the forwarded original payloads. Original performance thresholds and acceptance status are unchanged.

Do not upload the synthetic test directory as real run evidence. `derive.py` and `derive_tests.py` are one-off scratch construction notes and are not part of the frozen deliverable. No tool installation, network request or artifact fallback is needed.
