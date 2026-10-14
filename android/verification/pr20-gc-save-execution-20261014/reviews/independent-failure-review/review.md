# Independent GC/save failure transport review

Status: PASS for the reviewed inactive candidate. No unresolved findings in this review scope. No Android edits, Gradle, network access, native execution, CI activation, commits, or push were performed.

44 independent helper/budget cases and 27 independent selected runtime/packager cases passed. These are synthetic/offline checks, not Android performance results. The author's final full offline preflight separately reports 17 groups PASS.

## Complete failure bytes and identity

A separately written consumer reconstructs the original immutable bytes exactly, including UTF-8 code points crossing 32 KiB boundaries and the full 3,790,465-byte GC failure cap. Chunk SHA, overall SHA, context binding, ordering, count, canonical base64, strict UTF-8 and the listed sensitive patterns were checked. Missing, duplicate, reordered, corrupted and wrong-source streams are rejected. Sensitive-pattern checking is limited to the explicit regex patterns.

The runtime's embedded producer functions are AST-identical to the reviewed pure helper. The caller checks the selected method fields against the plan and requires the exact non-symlink method log under its owned evidence directory before reading. Its envelope binds run, attempt, workflow/source commits, source-spec, method-plan, test source, ordinal, selection and existing non-PASS status. Oversize, hash mismatch, malformed UTF-8 or sensitive bytes produce metadata-only refusal; raw log bytes remain intact.

## Failure state, exclusivity and interruption

The normal path validates the complete marker stream and persists NORMAL_STARTED plus the complete bounded wire charge before printing CONTEXT. A later failure may emit only a small REFUSED record, never another full failure stream. The failure path likewise persists FAILURE_STARTED and its full wire charge before any BEGIN/PART/END print. Independent fault injection verifies zero output when this save fails and a retained full charge when printing fails at BEGIN, an intermediate part or a later part. No fallback double charge was added.

The original JUnit failure survives a failure-forwarding exception. A method cannot be retried; the actual ordered methods loop propagates the first failure without invoking a successor. Package accounting rejects a successor after a failed method and rejects simultaneous normal and full-failure wire streams. Final artifact selection excludes the derived records copy; full original instrumentation remains the authoritative raw source.

## Budget

The independent capacity implementation rebuilt all 60 normal/last-arm-failed snapshot payloads from the frozen source contract, including escaped JSON forwarding and full signed Long widths on failure. Per-part base64 padding is counted separately. The fixed 3 MiB is shared once, and the 256 KiB residual raw failure reserve is included before base64 expansion.

| Case | Upper bytes |
| --- | ---: |
| All methods successful | 18,348,639 |
| First failure in guards | 3,813,048 |
| First failure in GC, last trial | 19,360,002 |
| First failure in save, last arm | 19,675,060 |

The largest modeled case leaves 1,296,460 bytes below 20 MiB. The actual package account_text function independently reproduces 19,675,060 with every fixed bucket filled and the largest exclusive failure case. Five individual bucket overflows and the combined total overflow are rejected. This remains a bounded transport policy: arbitrary oversized output is refused, never truncated into success.

## Findings closed

1. The first reviewed runtime retained an obsolete 10-method assertion although the plan has eight methods. The author corrected it to len(REPORT methods) == len(PLAN steps) == 8. Independent calls to the real main function with inert side effects accept eight and reject seven, nine and ten before setup.
2. Parent review found that missing required files could leave the packager exit code zero. The final package differs from the reviewed version by only sys.exit(bool(rejected or missing)). All four combinations of missing/rejected were checked against the actual terminal expression. The author's full packager fixtures now have only all8_complete returning zero.

## Binding and remaining work

Candidate source fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353 matches the source files and joint-budget bindings exactly. Reviewed run.py: 506561ce1c03013ef2043855f82fd8a659b9601bfb8696c4d76a0b297af62fb1. Reviewed final package.py: ed6e966941c3cbdbba620d2a85ec7ac4b5c77da5642bbcf6478ff3c9cd4c3f46. Reviewed helper: 4080e090365ae8abdbe85cc6eb8d0c1a097970533659ba5fe9417846a5f83edf. Final author preflight: dab4912723c2d9a51b8466b4dafce7307f7fad58ac630d57e1252b03edbaa538.

No review blocker remains for these bytes. The controller remains inactive/UNBOUND; readiness freeze, binding and any permitted new run remain parent-owned. This review does not grant activation, establish a native result or change historical 90-save evidence.

Evidence: helper-budget-results.json, runtime-results.json, package-exit-review.json, source-binding.json; reproducible scripts check_independent.py and check_runtime.py; SHA256SUMS binds this package.
