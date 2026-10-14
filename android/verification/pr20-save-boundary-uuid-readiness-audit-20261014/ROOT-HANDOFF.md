# Inactive UUID guard correction and failure-log forwarding

The sixteen text files under `controller/` are a distinct UNBOUND controller. They have `approved=false`, unbound source/readiness hashes and the zero prep-parent guard. No readiness freeze, build, push, activation, network access or native execution was performed by this agent. The prior controller, readiness and failed run remain unchanged.

Root supplied source `94379190b9ad0e1034f136ea9887fb0ac12e7ea7`. All six reviewed source hashes were checked against that actual Git commit. The changed Android test hash is `cd1b0ba9e112a19e16fdea36b1b867183c890a4addb8c6ebfdfe7986f4a73038`; methods 8/9/10 bind that file. This source corrects two guards' fixed IDs; production hooks and the seed4101 diagnostic remain unchanged. Other uncommitted local tests were not included in the Git binding.

The new scope is `ci/pr20-save-boundary-uuid`, readiness `android/verification/pr20-save-boundary-uuid-20261014`, branch `codex/kvm-pr20-save-boundary-uuid-20261014`, and workflow `pr20-save-boundary-uuid.yml`. The runtime file set remains unchanged. Paths/identities, test hashes, a failure-only helper in run.py, its contract, offline tests, size estimate and explanatory text comprise the changes. `markers.py`, `cleanup.py`, `original-protocol.json` and the ten-call method plan remain byte-identical. `controller-review-ready.json` records each file's exact classification and hashes.

`INKWEFT_CAUSAL_FAILURE_LOG` is a separate closed schema, capped once because the first non-PASS method still stops execution. It forwards only the exact allowlisted method instrumentation log after strict UTF8, <=256KiB, SHA256 and existing secret-pattern checks. Invalid/missing/changed/sensitive/oversized/unlisted/symlink input emits a refusal with no original content. There is no truncation. ASCII JSON prevents line injection and bounds escaping to six times the input bytes. A forwarding exception cannot replace the original method exception. This envelope supplies no marker block or PASS closure and does not enable retries.

The full offline checker passed 14 groups in `offline-check-01/preflight.json`, including the previous thirteen groups and the new failure-forwarding group. It exercises original JUnit failure with adb exit0, preserved original exception identity, forwarding output failure, UTF8 and byte-exact Unicode/CRLF/control text, exact cap, sixfold escaping, oversize, invalid UTF8, hash mismatch, sensitive patterns, symlink/allowlist/missing refusal and PASS silence. The helper's secret-regex AST equals the packager's exact expression. These are synthetic host checks, not native results.

The conservative combined bound is 19,036,122 bytes, including previous raw/compact records, official marker envelopes, one256KiB raw failure log, one1,581,056-byte failure envelope and three MiB other text reserve. It remains below20,971,520 bytes. The actual packager continues to reject over-budget totals without truncation; retention remains three days.

Root should copy only the sixteen listed text files after independent review, then perform the normal source/readiness/prep/active chain. Do not copy synthetic test outputs, fake APK/AAR files or test logs as native evidence. The source freezer reads the committed archive, not the mutable worktree. For the source freeze, root can use the copied tool:

```sh
python3 ci/pr20-save-boundary-uuid/freeze_readiness.py \
  --repo /workspace/InkWeft \
  --git 94379190b9ad0e1034f136ea9887fb0ac12e7ea7 \
  --out /workspace/scratch/pr20-save-boundary-uuid-ci-20261014/frozen-readiness
```

This handoff does not authorize or perform a new run. Root retains the final activation decision. The old failure's actual stack is unavailable in its decoded log; the static guard-ID defect must not be described as an observed old stack.
