# Inactive save-boundary UUID correction controller

This scratch-derived controller remains UNBOUND with authorization=false and a zero prep-parent guard. It has not built, installed, fetched, pushed or activated anything. Only root may stage it in a reviewed isolated execution branch and bind one final source/readiness/prep identity.

The plan is10 exact methods/1 data APK/1 installation group. See PLAN.template.md and method-plan.template.json. It reuses the reviewed ci/pr20-causal controller; cleanup.py and original-protocol.json remain byte-exact. Four phases replace five: setup, build C1, emulator, methods data. The unrelated MuPDF POM gate and app/raster builds are removed; real requery SQLite natives, dependency/signature/install evidence remain. No full-app build success is implied.

Offline freeze and bind tools are retained; they do not activate a workflow or push:

```sh
python3 ci/pr20-save-boundary-uuid/freeze_readiness.py --repo /workspace/InkWeft --git FINAL_SOURCE_SHA --out /workspace/scratch/pr20-save-boundary-uuid-ci-20261014/frozen-readiness
python3 ci/pr20-save-boundary-uuid/bind_control.py --repo /workspace/InkWeft --plan-git FINAL_READINESS_SHA --readiness /workspace/InkWeft/android/verification/pr20-save-boundary-uuid-20261014 --out /workspace/scratch/pr20-save-boundary-uuid-ci-20261014/bound-control
```

Final source hashes and marker schema/capacity must be reviewed before the freezer can accept them. It uses only Git archive, never mutable source overlays. Root copies the resulting readiness files verbatim, commits them, verifies their bytes against that commit, then runs bound offline checks. Activation still requires the existing exact prep-parent guard plus attempt1 on the isolated branch. No live workflow is placed by these preparation tools.

This separate scope preserves the prior failed run. The intended source delta is valid fixed UUIDs in two correctness guards only; production hooks and the seed4101 diagnostic are unchanged. The new failure-only log envelope is defined in marker-contract.template.json and implemented directly in run.py. It accepts only the method's exact original allowlisted UTF8 log, <=256KiB, with matching SHA256 and no existing secret-pattern match. Refusals contain no original text. No failure payload is counted as a PASS, no original exception is swallowed, and no retry is added. The static bound reserves one raw log plus worst sixfold JSON escaping in addition to the previous estimates and three MiB other text reserve.
