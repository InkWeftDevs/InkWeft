# Prepared 62-method matching candidate control

Status: SOURCE_BOUND / CLOCK_AND_LAUNCH_UNBOUND. This directory contains offline preparation only; no workflow was activated, no network/build/ADB/native call occurred.

Use `frozen-readiness/` for the source/method/schema/statistics review and `bound-control-final/ci/pr20-kvm-triple/` for the controller review. Original execution trees and production were not edited. `control-template/` remains the earlier source-unbound template. `../preparation/` preserves the earlier 61-method preparation and its checks. Here `check-source-bound-01` preserves an offline test-harness failure (record return handling); `check-source-bound-final` is the passing pre-label-fix historical check; `offline-final/preflight.json` is the final offline check (87 top-level checks; 19 helper, 8 POM mocks, 12 actual text-packager cases, 12 phase boundary cases and other cases are nested). Do not count these as native methods or performance results.

Source identities: B a128, C0 d66, C1 cb7e14218ac83aa8cf6b9d7906051a10da1a6547. The prior 61-method preparation used unchanged original `prepare_sources.py` to reconstruct all 947/964/999 input files; every byte count and SHA matched. This 62-method amendment keeps those three source manifests byte-identical and cites the original reconstruction receipt; it does not claim another reconstruction or native run. The complete d66→cb7 delta includes seven production paths, including default-off hooks; see `candidate-source-delta.json`. No claim of an isolated one-helper production delta is made.

Methods1–58 retain every field from the last full run. Added59 is the existing causal Unicode method8 with exactly its original args, no opt-in. Added60 is the existing NativeStrokeTileReuseTest independent old per-tile construction oracle (four phases, native shape counts2/2/1/0). Its finally remains best-effort and is not called strict join. Added61 is the existing14-case continuous-transform/separated-dirty probe. Added62 is the original1201-stroke/76802-point dense30-second frame gate. Original27/28 strict-join guards remain unchanged. All use the existing C1-diagnostics group:62calls,14installgroups,9realAPKs. The phase counts are31/24/7, with the last seven consisting of three original diagnostics and four additional candidate-only correctness methods. JVM matcher tests are not counted as native. No new seeds, public measurements, retries, timeout extensions, cache, permissions or upload types.

The source-bound controller cannot launch: `authorization.approved=false`, `plan_git=UNBOUND`, PR clock placeholders and zero prep guard remain. Root must perform independent review, commit readiness, bind the actual readiness/report Git, and separately freeze a real new immutable PR-server clock/auth/guard. This preparation does not grant launch approval.

After the readiness commit exists, create a NEW bound directory (never overwrite an earlier receipt):

```
python3 bind_control.py --readiness /ABS/frozen-readiness --plan-git ACTUAL_READINESS_GIT --prior-report-git ACTUAL_REPORT_GIT --out /workspace/scratch/NEW-bound-control
```

The binder still leaves approval false and clock placeholders. It is deliberately not a clock finalizer. Root owns the final authorization decision and once-only immutable clock; no real timestamp is generated here. Marker fields to finalize retain existing `__CLOCK_*__` names. `__CLOCK_BUDGET_STATUS__` should describe this continuation of the approved38/41/53/55 budget with a separately frozen62-method scope; it is not a request to extend any deadline. All numeric cutoff offsets remain unchanged. The new README/readiness plan must remain explicit that the old original-protocol26-minute and old source identities are historical and the existing authorized budget amendment supplies the actual offsets.

Strict forwarding uses `INKWEFT_MATCH_CLOCK/CONTEXT/RECORD/END/METHOD/FINAL`. The numeric records preserve the exact original JSON substring, byte length and SHA, plus a canonical ordered footer and compact records hash. CONTEXT binds run/workflow/readiness/source/test/args/seed/group, actual installed APK hashes, source APK signatures, environment/raw/records hashes, and existing process-exit evidence. `process_exit_seconds` is forwarded without new measurement. The original method17 exception and process observations function are AST-identical to the previously audited controller; all other methods need natural package exit. FINAL remains the causal compact receipt shape with new scope and actual62/14 contents. After-END method timeout/group-after/environment/cleanup failures do not become complete merely because marker records were printed.

The independent extraction implementation is owned by integration_review under `/workspace/scratch/pr20-match-log-analysis-20261014/`; it uses this readiness `analyze.py` pure calculation rather than replacing threshold arithmetic. Official compact logs support bounded numerical reanalysis and controller-receipt checks; they are not independent offline APK signature/rawJUnit/ACL-byte rechecks. Actual raw text/records/derivedJUnit/source/dependency/install/environment/dual-cleanup files remain in the ≤20MiB/3day allowlist.

Compatibility issues found offline and fixed before handoff: old B legitimately reports null projection counters; original stage-only LibraryReadPerformanceTest progress samples lack the public KVM probe's extra timestamp/commit fields. Separate closed stage schemas preserve that distinction. No probe or assertion was changed. All58 historical raw logs plus original Frame and NativeReuse marker output validate; the prior actual causal Unicode record also validates. A synthetic successful dense shape and a20-exposure GC shape cover legal variants not supplied by those old logs.

Publication limits total2,834,432 bytes across method payload caps; raw+compact records payloads are roughly at most twice this component. This is not a bound on arbitrary build/runner output. The actual packager still rejects aggregate text>20MiB without truncation; missing required files mark EVIDENCE_INCOMPLETE. GC/stage/Frame payload bounds only bound published evidence, never alter test samples or internal assertions. An unexpectedly large valid diagnostic is incomplete, not trimmed and declared passed.

Offline checks can be repeated into a new destination with:

```
python3 check_preparation.py --control /ABS/bound-control-final/ci/pr20-kvm-triple --readiness /ABS/frozen-readiness --out /workspace/scratch/NEW-offline-check
```

Before actual activation, root's final full preflight must additionally validate real authorization/clock/hash/guard values and the exact committed readiness. The current checks intentionally use false authorization and synthetic clocks; their PASS is not launch readiness.

The stable method-plan SHA is 6660f4f25061440892ad8ff0b7257e09dd311b2df8786e258c873dd21fe15913. NativeReuse is a correctness oracle, not another public timing sample. Its NATIVE prefix is explicitly recognized by both raw_result and safe_marker_payloads; missing, duplicate, malformed, unknown-field, nonmatching phases/counts or unequal pixels fail evidence validation. The historical raw positive and synthetic negative cases are offline checks only.
