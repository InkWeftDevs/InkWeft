# Independent bound-control review

**PASS: no remaining binding or byte-delta finding in this six-file, not-activated output.** This review covers `/workspace/scratch/pr20-save-boundary-ci-20261014/bound-control`, readiness commit `4278f310d6f8a742072725f77fda3fdb92c22431`, and source commit `4f41a501127dbed6dbef3519f93233a105b5d618`. Exact file hashes and checks are recorded in `bound-review.json`. The original reviewed template manifest remains `ee2a145cf9f744e6ad18b6bcfcd1e899a580b946f9c164cacca9463f1dbc1a3d`.

The only control changes from that template are:

- `run.py:23` and `package.py:6`: the single `FROZEN_SOURCE_SPEC_SHA256` literal becomes `de95eee21d1bc3fcbe662d063b99908ec94aeec785068c9c36f698f25d5abe93`.
- `authorization.json`: `approved=true`, `status=FROZEN`, exact `plan_git`, and six SHA-256 bindings for PLAN, source-spec, method-plan, original-protocol, marker-contract and prepare_sources. Every other field and the serialized expected output match. The source manifest is bound through source-spec and independently verified below; this is not a claim that authorization contains eight individual file-hash fields.
- `cleanup.py`, `markers.py`, and `workflow.proposed.yml` are byte-identical. The workflow still requires an all-zero prep-parent SHA and remains inactive. Budgets remain setup 30/data 45/work 55 minutes and job timeout 60 minutes; scope, permission, cache and retry settings are unchanged.

All eight readiness files match exact blobs in the real readiness commit, with no additional files; the seven-entry `SHA256SUMS` matches all payloads. The readiness method-plan and marker-contract differ from the reviewed templates only by their frozen status; PLAN changes only its status phrase, while prepare_sources and original-protocol remain exact. No method, marker schema, threshold or argument changed during binding.

An independent in-memory `git archive` of source `4f41a50…` reproduced all **1009** Android/CI source-file lengths and SHA-256 values, excluding verification records as specified. It matches tree `3cef4bd2d7cab425668f2c8fe85351164b068fc1`, canonical manifest `76bb502e5520b17df393044f58715986feb6a074538ebf2349a9bc38d83ce881`, and all six reviewed hook/test hashes. No overlays, source extraction or source edits were performed by this reviewer.

The supplied `offline-check-bound/preflight.json` reports **13 groups PASS** under `OFFLINE_ONLY_NO_NEW_NATIVE_NO_NETWORK_NO_BUILD`; this reviewer checked all 14 recorded input hashes against the current eight readiness and six bound-control files, plus the checker hash. The checks and this bound review remain offline evidence, not native measurements.

`check_bound.py` performs only local reads and writes these scratch review outputs. It did not execute the binder, runner, packager, build, network, device operations, push or activation. Subsequent prep-parent/active workflow changes need their own exact review. The forthcoming log extractor is outside this review and will receive separate evidence. No performance gate is closed.
