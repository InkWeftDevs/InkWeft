"""Bind the completed scoped review to the author's frozen16-file manifest."""
from pathlib import Path
import hashlib,json
p=Path(__file__).resolve().parent;c=p.parent/'controller'
sha=lambda q:hashlib.sha256(q.read_bytes()).hexdigest()
checks=json.loads((p/'checks.json').read_text());expected=checks['controllerInputSha256']
assert expected=={q.name:sha(q) for q in c.iterdir() if q.is_file()}
manifest=p.parent/'controller-SHA256SUMS'
assert sha(manifest)=='6611c43b87a4e01d7d9c34dba890f59a8f554664bdf340f5fd2c5829effd928c'
assert {line.split('  ',1)[1]:line.split('  ',1)[0] for line in manifest.read_text().splitlines()}==expected
(p/'CONTROLLER_REVIEWED_SHA256SUMS').write_bytes(manifest.read_bytes())
v3=Path('/workspace/scratch/pr20-gc-author-only-analysis-v3-20261014')
tests=json.loads((v3/'selftest-results.json').read_text());assert tests['status']=='PASS' and tests['cases']==94
report={
 'status':'PASS_SCOPED_GC_CONTRACT','scope':'GC schema/payload/terminal guard/shards/mode/source identity; static and synthetic only',
 'sourceCommit':'fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353',
 'gcProducerSha256':'597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055',
 'controllerDirectory':str(c),'controllerManifestSha256':sha(manifest),
 'markersSha256':expected['markers.py'],'runSha256':expected['run.py'],'contractSha256':expected['marker-contract.template.json'],
 'checksSha256':sha(p/'checks.json'),'independentChecks':56,'coreAstIdentityNamedNodes':23,
 'closedFindings':[{'finding':'Python equality accepted malformed bool/int or int/float coercions in DELETE intervals, retained revisions and covered commit timestamps.',
                    'fix':'Strict controller wrapper fields/types at markers.py:552-560, plus exact boundary JSON comparison at551.',
                    'evidence':'All7 independent scalar-negative cases reject; valid traces continue to pass.'}],
 'numericAnalyzerV3':{'path':str(v3),'analyzerSha256':sha(v3/'analyze.py'),'manifestSha256':sha(v3/'SHA256SUMS'),'syntheticCases':94,
                     'note':'V3 independently validates scalar types. Controller keeps v2 numeric AST plus equivalent outer type checks.'},
 'activation':'review_status READY metadata only; execution remains UNBOUND/approved=false/source-spec UNBOUND/unique zero guard.',
 'remainingBlockersInScope':[],
 'excluded':['No Android execution or performance acceptance.','Failure forwarding, strict missing-evidence packaging exit and joint20MiB sizing belong to save_boundary review.','No source/readiness/prep/active binding or remote identity verification.'],
 'networkCalls':0,'nativeExecutions':0,'sourceEdits':0}
(p/'review.json').write_text(json.dumps(report,indent=2)+'\n')
md=f'''# Independent GC controller contract review

**PASS for the GC contract scope**, with no remaining blocker in that scope. This is a read-only static/synthetic review, not activation or native-performance evidence. Source is `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353`; the GC producer remains `597bed443289598870d063af99731a60db48ca0e38d8e3576b24fcd995cf4055`.

The reproducible `check_controller.py` completed56 checks. It compares23 named AST nodes against the frozen v2 numeric analyzer, then exercises the actual `safe_marker_payloads` entry with original serialized synthetic JSON. Both modes and multi-shard nested/prelude transactions pass. Mode mixing, shard omission/duplication/order/count faults, wrong database/columns/schema, missing trial/summary/stage, invalid worker CPU/thread, malformed JSON, duplicate keys, nonfinite numbers, payload caps and incomplete outcomes are rejected. Original JSON strings survive the parser byte-for-byte.

At `markers.py:511–561`, each method is bound to its own connection mode, exactly30 ordered trials, one closing summary, and original EVENT-before-TRIAL ordering. Nonfinal shards contain48 rows; part numbers and totals close exactly; all rows have10 columns and total rows stay≤512. Same-room collector owns author events; separate-room collector count must remain0. Counts/drops/invalid flags and array scalar types are checked exactly. The copied core at `markers.py:390–509` retains all11 author segments, worker/transaction identities, same-thread CPU checks, complete scan/batch stages, all-save quantiles and the three distinct exposure definitions. Conditional quantiles below20 stay null; pure lock wait stays unknown.

The terminal contract at `markers.py:462–469` correctly permits all1280 deletions followed by a mismatched zero-candidate guard and `firstDeferred=true`. Production `HistoryStorage.kt:87–102` checks the writer guard before the empty-candidate exit. A matched guard with nonempty candidates cannot terminate; defer must exactly reflect guard mismatch. Positive/negative cases cover this legal race without source changes.

One finding was fixed: Python equality accepted malformed same-valued floats/bools. The strict wrapper at `markers.py:551–560` now rejects these cases. All7 independent scalar mutations fail, including boolean interval sequence, float retained revision/covered timestamp, and coerced boundary counts/flags. Standalone numeric analyzer v3 closes the same checks and passes94 synthetic cases; v1/v2 evidence remains intact.

All10 reviewed source blobs match local Git at the pinned source. The8 unique method selections are five existing GC guards, two GC boundary methods and one snapshot-control method, each expecting one JUnit result. GC hashes, mode mappings,30/330 EVENT bounds and30 TRIAL/one SUMMARY bounds agree. `freeze_readiness.py:21–26` and `run.py:240–245` bind source identity and every reviewed hash before fetching. Method fail-stop and the final8-method main gate remain present. The30/45/55/60 budgets, one data install group, no retry, `approved=false`, UNBOUND source-spec and unique zero guard are retained. READY metadata does not activate anything.

Final bindings:

- Author16-file manifest: `{sha(manifest)}`
- `markers.py`: `{expected['markers.py']}`
- `run.py`: `{expected['run.py']}`
- `marker-contract.template.json`: `{expected['marker-contract.template.json']}`

See `checks.json`, `review.json` and `CONTROLLER_REVIEWED_SHA256SUMS`. Full failure forwarding, missing-evidence packaging failure and combined20MiB budgeting are assigned to a separate reviewer; this report does not replace that review. No source edits, CI, Android, build, network or push operations were performed.
'''
(p/'review.md').write_text(md)
(p/'SHA256SUMS').write_text(''.join(sha(q)+'  '+q.name+'\n' for q in sorted(p.iterdir()) if q.is_file() and q.name!='SHA256SUMS'))
print(json.dumps({'status':report['status'],'reviewSha256':sha(p/'review.md'),'jsonSha256':sha(p/'review.json'),'manifestSha256':sha(p/'SHA256SUMS')},indent=2))
