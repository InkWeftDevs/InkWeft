# Root-only source/readiness/prep/activation handoff

Copy only the16 text files in controller plus selected text reports/receipts. The offline-check directories also contain explicitly synthetic APK/AAR/oversize/privacy fault fixtures used solely by the checker; do not archive or upload those fixtures as real evidence.

All delivered files are inactive scratch preparation. No GitHub/Gradle/ADB/ACL/network operation was performed. The controller has authorization=false, UNBOUND source and readiness identities, and a zero workflow prep-parent guard. No root ci or .github directory was edited. The ten exact methods remain7 existing guards +2 new boundary guards +1 three-arm boundary diagnostic,1 data APK/1 install group. Source hashes bind the six files from compile2-start.json; this is not a claim that compile2/native execution passed.

`verify_handoff.py` is read-only by default and always; it verifies delivered controller hashes, all six compiled source bytes, inactive guard and absence of this active workflow. It optionally verifies a committed source instead of the current files:

```sh
python3 /workspace/scratch/pr20-save-boundary-ci-20261014/verify_handoff.py --source-git 4f41a501127dbed6dbef3519f93233a105b5d618
```

The following commands are a reviewed plan for root, not executed by this agent. The source commit is fixed below; replace FULL_READINESS_COMMIT with the later real40-hex readiness commit. Root first commits the six reviewed source files after host checks; keep the source commit separate from readiness. No source replacement or overlay occurs during freeze.

```sh
test ! -e /workspace/InkWeft/ci/pr20-save-boundary
cp -R /workspace/scratch/pr20-save-boundary-ci-20261014/controller /workspace/InkWeft/ci/pr20-save-boundary
python3 /workspace/InkWeft/ci/pr20-save-boundary/freeze_readiness.py --repo /workspace/InkWeft --git 4f41a501127dbed6dbef3519f93233a105b5d618 --out /workspace/scratch/pr20-save-boundary-ci-20261014/frozen-readiness
test ! -e /workspace/InkWeft/android/verification/pr20-save-boundary-20261014
cp -R /workspace/scratch/pr20-save-boundary-ci-20261014/frozen-readiness /workspace/InkWeft/android/verification/pr20-save-boundary-20261014
```

`freeze_readiness.py` verifies the full Git source manifest, all ten selected test hashes and all six compile2-reviewed source hashes. It creates `source-spec.json`, `C1-source-manifest.json`, exact `method-plan.json`, `marker-contract.json`, `PLAN.md`, `prepare_sources.py`, byte-exact public `original-protocol.json` and `SHA256SUMS`. It reads committed Android/CI bytes through Git archive and excludes only prior verification archives. It builds nothing.

Root commits that readiness directory and the inactive ci/pr20-save-boundary controller as FULL_READINESS_COMMIT, then binds the committed readiness bytes:

```sh
python3 /workspace/InkWeft/ci/pr20-save-boundary/bind_control.py --repo /workspace/InkWeft --plan-git FULL_READINESS_COMMIT --readiness /workspace/InkWeft/android/verification/pr20-save-boundary-20261014 --out /workspace/scratch/pr20-save-boundary-ci-20261014/bound-control
python3 /workspace/InkWeft/ci/pr20-save-boundary/check_offline.py --controller /workspace/scratch/pr20-save-boundary-ci-20261014/bound-control --readiness /workspace/InkWeft/android/verification/pr20-save-boundary-20261014 --repo /workspace/InkWeft --out /workspace/scratch/pr20-save-boundary-ci-20261014/offline-check-bound
```

The binder checks every readiness file against FULL_READINESS_COMMIT, binds hashes into the runtime/packager, and sets authorization approved only in its new scratch output. It does not write a live workflow, commit, push, fetch or launch a run. The proposed workflow remains zero-guard. Independent source/controller/bound checks remain required.

After those checks, root alone creates an isolated execution checkout at FULL_READINESS_COMMIT on `codex/kvm-pr20-save-boundary-20261014`, overlays the six bound runtime/controller files, creates and verifies `ci/pr20-save-boundary/EXECUTION_SHA256SUMS`, and commits/pushes one prep commit while `.github/workflows/pr20-save-boundary.yml` is absent. Before activation, source/readiness/control hashes and the prep parent must agree with the independent review.

Root then fills only that exact prep commit into workflow.proposed.yml's single zero `github.event.before` guard, copies its exact bytes to `.github/workflows/pr20-save-boundary.yml`, updates the execution hash manifest for those two copies and commits one activation whose parent equals prep. The trigger permits only that exact branch, non-created push and attempt1. Do not replace run38520196523 or reuse a prior run clock. No repeated run, fast mode, source/base/main force push, Docker modification, merge or deployment is part of this plan.

Observe one standard ubuntu24/API35 ATD KVM run, setup30/data45/work55/job60 deadlines; actual GitHub job start is checked from terminal metadata. Keep both exact cleanup receipts and original failure/raw markers. Retrieve only official decoded logs/metadata through the allowed route. No artifact ZIP download, storage-route workaround, MuPDF/app build claim or native performance claim arises from this offline preparation.
