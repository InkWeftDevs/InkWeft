# PR20 opt-in causal controller

This directory is an **UNBOUND**, non-active controller template. It does not replace any frozen performance protocol or close a performance gate. Only the root integrator binds/commits/pushes an execution. `workflow.proposed.yml` is not an active workflow and its prep-parent guard remains zero.

There is one source C1, three real APKs, two installation groups and 13 exact method calls. Methods 1–7 are existing correctness guards; 8 is the new Unicode guard; 9–13 are five opt-in diagnostics. Data methods share the first group; raster alone uses the second group. Every call requires exactly one original JUnit PASS, exact class/method and natural exit of all three owned packages. There is no auxiliary-process exception or kill fallback. No cache, parallel build, retry, altered watchdog, formal probe or added sample.

Absolute first-step clock cutoffs are setup30/data45/raster50/work55/job60 minutes. Only method10 (12 save-mode cases, each 30 saves) has a new 300-second outer command cap. The per-test production watchdogs and public tests are unchanged. `cleanup.py` remains byte-identical to the previously reviewed implementation.

`run.py` refuses `UNBOUND` before reading execution environment or running subprocesses. `package.py` also refuses an unbound source identity. Source readiness and authorization hashes must agree before the first official dependency/network operation. The proposed workflow uses `contents: read`, a standard runner, a single exact-prep-parent/attempt1 push guard, 20 MiB text upload and three-day retention. It never uploads APKs/AARs, databases, keys or full logcat.

Only root may perform these steps after source/diagnostic review. All commands below are offline preparation, **not activation**:

```sh
python3 ci/pr20-causal/freeze_readiness.py --repo /workspace/InkWeft \
  --git FULL_FINAL_SOURCE_COMMIT \
  --out /workspace/scratch/pr20-causal-20261014/frozen-readiness
```

The source commit must contain the exact diagnostic test bytes already recorded in `marker-contract.template.json`; any mismatch stops the freeze. The freezer uses Git archive, no working-tree source overlays. Root copies the generated readiness files verbatim to `android/verification/pr20-causal-20261014/` and commits them. That real commit is then bound:

```sh
python3 ci/pr20-causal/bind_control.py --repo /workspace/InkWeft \
  --plan-git FULL_READINESS_COMMIT \
  --readiness /workspace/InkWeft/android/verification/pr20-causal-20261014 \
  --out /workspace/scratch/pr20-causal-20261014/bound-control
python3 ci/pr20-causal/check_offline.py \
  --controller /workspace/scratch/pr20-causal-20261014/bound-control \
  --readiness /workspace/InkWeft/android/verification/pr20-causal-20261014 \
  --repo /workspace/InkWeft \
  --out /workspace/scratch/pr20-causal-20261014/offline-check-bound
```

The binder verifies every readiness file against that exact Git commit. It does not write an active workflow, commit or push. Root stages the bound execution controller in a separate clean branch/clone, records `EXECUTION_SHA256SUMS`, obtains independent review, pushes one prep commit without an active workflow, then fills only the exact prep-parent guard and adds the proposed workflow for one attempt. The first runner-step clock is newly created once inside that run and cannot be reused. There is no PR-server timestamp or `/jobs` clock dependency.

The checker uses pure AST extraction, prior original JUnit logs, synthetic fault fixtures, local `bash -n`/actionlint and one actual local first-step shell execution in its new scratch directory. It never executes SDK/Gradle/ADB/emulator/ACL commands. Unbound mode needs no readiness arguments. Bound mode verifies real authorization/readiness Git hashes. Synthetic identities are confined to its package fixtures and never represent Android output.

`markers.py` validates a closed, bounded JSON Schema subset, rejects unknown or malformed prefixes/duplicates/non-finite values and validates whole-method cardinality before forwarding any original JSON. Methods 1–7 require zero known diagnostic markers. Method8 requires Unicode evidence. Missing or malformed required markers yield host `INCOMPLETE` while preserving original JUnit and raw log. A failure never becomes PASS because some earlier records are present.

Official log prefixes are `INKWEFT_CAUSAL_CLOCK`, `CONTEXT`, `RECORD`, `END`, `METHOD` and `FINAL`. CONTEXT includes exact source/test/method/arguments/APK/signature/installed hashes and environment/raw-log/records hashes. RECORD includes the unmodified JSON substring, byte length and SHA. END closes the ordered list, but appears before final method/group checks; consumers must also require the corresponding METHOD PASS, all 13 methods/two groups, successful official run/job identity, both zero-error exact cleanups and FINAL with `missing_files=[]` and `packaging_complete=true`. Compact log receipts support numerical analysis; they do not independently re-verify the actual APK/native binaries or raw JUnit/ACL files.

`size_bounds.py` conservatively materializes maximum schema shapes. Save events are capped at2048 per case and128 per part; overflow is a real failure, not an invitation to truncate. Parsed records retain every record in compact JSON; raw instrumentation remains unchanged. Current conservative diagnostic raw+records shape fits with a separate3MiB text reserve under20MiB. Build/dependency/source text is not assumed bounded by that estimate: the actual packager always totals every selected byte and refuses oversize. Missing expected files are enumerated and an otherwise complete result becomes `EVIDENCE_INCOMPLETE`; existing safe text can still be packaged, so a green upload step is insufficient evidence of a complete run.

No native result is recorded by this directory's offline checks. Diagnostic CPU/wall observations remain instrumented observations, not formal acceptance or a claim of exclusive causal attribution.
