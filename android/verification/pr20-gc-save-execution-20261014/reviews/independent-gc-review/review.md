# Independent GC controller contract review

**PASS for the GC contract scope**, with no remaining blocker in that scope. This is a read-only static/synthetic review, not activation or native-performance evidence. Source is `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353`; the GC producer remains `597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055`.

The reproducible `check_controller.py` completed56 checks. It compares23 named AST nodes against the frozen v2 numeric analyzer, then exercises the actual `safe_marker_payloads` entry with original serialized synthetic JSON. Both modes and multi-shard nested/prelude transactions pass. Mode mixing, shard omission/duplication/order/count faults, wrong database/columns/schema, missing trial/summary/stage, invalid worker CPU/thread, malformed JSON, duplicate keys, nonfinite numbers, payload caps and incomplete outcomes are rejected. Original JSON strings survive the parser byte-for-byte.

At `markers.py:511–561`, each method is bound to its own connection mode, exactly30 ordered trials, one closing summary, and original EVENT-before-TRIAL ordering. Nonfinal shards contain48 rows; part numbers and totals close exactly; all rows have10 columns and total rows stay≤512. Same-room collector owns author events; separate-room collector count must remain0. Counts/drops/invalid flags and array scalar types are checked exactly. The copied core at `markers.py:390–509` retains all11 author segments, worker/transaction identities, same-thread CPU checks, complete scan/batch stages, all-save quantiles and the three distinct exposure definitions. Conditional quantiles below20 stay null; pure lock wait stays unknown.

The terminal contract at `markers.py:462–469` correctly permits all1280 deletions followed by a mismatched zero-candidate guard and `firstDeferred=true`. Production `HistoryStorage.kt:87–102` checks the writer guard before the empty-candidate exit. A matched guard with nonempty candidates cannot terminate; defer must exactly reflect guard mismatch. Positive/negative cases cover this legal race without source changes.

One finding was fixed: Python equality accepted malformed same-valued floats/bools. The strict wrapper at `markers.py:551–560` now rejects these cases. All7 independent scalar mutations fail, including boolean interval sequence, float retained revision/covered timestamp, and coerced boundary counts/flags. Standalone numeric analyzer v3 closes the same checks and passes94 synthetic cases; v1/v2 evidence remains intact.

All10 reviewed source blobs match local Git at the pinned source. The8 unique method selections are five existing GC guards, two GC boundary methods and one snapshot-control method, each expecting one JUnit result. GC hashes, mode mappings,30/330 EVENT bounds and30 TRIAL/one SUMMARY bounds agree. `freeze_readiness.py:21–26` and `run.py:240–245` bind source identity and every reviewed hash before fetching. Method fail-stop and the final8-method main gate remain present. The30/45/55/60 budgets, one data install group, no retry, `approved=false`, UNBOUND source-spec and unique zero guard are retained. READY metadata does not activate anything.

Final bindings:

- Author16-file manifest: `6611c43b87a4e01d7d9c34dba890f59a8f554664bdf340f5fd2c5829effd928c`
- `markers.py`: `850707164f4c89ae1920c1f9c99ad9e1a68be7f71b8c456b437671ddada96bda`
- `run.py`: `506561ce1c03013ef2043855f82fd8a659b9601bfb8696c4d76a0b297af62fb1`
- `marker-contract.template.json`: `4d01759b5fa9f5e4aa1b05b75f2b4498f45cc3cdd4cc6a3727da4a7349881eef`

See `checks.json`, `review.json` and `CONTROLLER_REVIEWED_SHA256SUMS`. Full failure forwarding, missing-evidence packaging failure and combined20MiB budgeting are assigned to a separate reviewer; this report does not replace that review. No source edits, CI, Android, build, network or push operations were performed.
