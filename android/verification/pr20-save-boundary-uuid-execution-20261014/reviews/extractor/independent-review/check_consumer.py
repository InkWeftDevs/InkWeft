"""Local read-only Git binding and pure synthetic producer/consumer checks; no CI/native/network."""
from pathlib import Path
import ast, copy, contextlib, datetime, hashlib, io, json, re, sys
sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
import analyze_logs as A

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p): return json.loads(p.read_text())
manifest = {n: h for h, n in (line.split() for line in (BASE / 'EXTRACTOR_SHA256SUMS').read_text().splitlines())}
assert sha(BASE / 'EXTRACTOR_SHA256SUMS') == 'f4036ab0b5907a043d42f31ffdcc824ce2eddeb725a47443c12060830cf939b0'
assert all(sha(BASE / n) == h for n, h in manifest.items())
check = BASE / 'check-03'; control = Path('/workspace/InkWeft-save-boundary-uuid-run')
ready = control / 'android/verification/pr20-save-boundary-uuid-20261014'
binding = load(check / 'binding.json'); run = load(check / 'run.json'); jobs = load(check / 'jobs.json')
expected = {k: binding[k] for k in ['run_id', 'job_id', 'source_commit', 'readiness_git', 'prep_sha', 'workflow_sha']}
frozen = A.frozen_inputs(ready, expected['source_commit'], expected['readiness_git'], control, binding)
git_result = A.verify_local_git(control, ready, expected, frozen, binding)
events = []
for line in (check / 'synthetic-job.log').read_text().splitlines():
    stamp, tail = line.split(' ', 1); kind, encoded = tail[len(A.PREFIX):].split(' ', 1)
    events.append((stamp, kind, json.loads(encoded)))
def render(values): return ''.join(stamp + ' ' + A.PREFIX + kind + ' ' + json.dumps(value, ensure_ascii=True, separators=(',', ':')) + '\n' for stamp, kind, value in values)
START = events[0][2]['budget_anchor_epoch']
iso = lambda t: datetime.datetime.fromtimestamp(t, datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
producer_path = control / 'ci/pr20-save-boundary-uuid/run.py'
producer_tree = ast.parse(producer_path.read_text())
helper = next(n for n in producer_tree.body if isinstance(n, ast.FunctionDef) and n.name == 'forward_failure_log')
consumer = next(n for n in ast.parse((BASE / 'analyze_logs.py').read_text()).body if isinstance(n, ast.FunctionDef) and n.name == 'failure_payload')
def secret_ast(node): return ast.dump(next(n.value for n in ast.walk(node) if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'secret' for t in n.targets)), include_attributes=False)
assert secret_ast(helper) == secret_ast(consumer) == secret_ast(ast.parse((control / 'ci/pr20-save-boundary-uuid/package.py').read_text()))
target = OUT / 'synthetic-inputs'; target.mkdir()
step = frozen['plan']['steps'][7]
entry = dict(copy.deepcopy(step), status='FAIL', junit_status='FAIL', adb_exit=0, process_exit_outcome='NATURAL_EXIT')
raw = 'synthetic failure\r\n栈\u2028\nINKWEFT_CAUSAL_METHOD fake\0\n'.encode()
log = target / 'method-08-C1-instrumentation.log'; log.write_bytes(raw); entry['log_sha256'] = sha(log)
ns = {'Path': Path, 're': re, 'hashlib': hashlib, 'json': json, 'OUT': target, 'PLAN': frozen['plan'], 'MARKERS': frozen['contract'], 'validate_shape': A.frozen_markers.validate_shape,
      'REPORT': {'run_id': expected['run_id'], 'run_attempt': '1', 'workflow_sha': expected['workflow_sha']}, 'SPEC': frozen['spec'], 'INPUT_HASHES': frozen['hashes']}
exec(compile(ast.Module([helper], type_ignores=[]), '<reviewed-production-forward-helper>', 'exec'), ns)
buf = io.StringIO()
with contextlib.redirect_stdout(buf): ns['forward_failure_log'](entry, log)
line = buf.getvalue(); assert len(line.splitlines()) == 1
payload = json.loads(line.partition(' ')[2])
assert payload['forwarding_status'] == 'FORWARDED' and payload['original_utf8'].encode() == raw
method = {k: step[k] for k in ['ordinal', 'phase', 'identity', 'selection']}
method.update(status='FAIL', junit_status='FAIL', adb_exit=0, process_exit_outcome='NATURAL_EXIT', error='synthetic original assertion')
prefix = [x for x in events if x[1] == 'CLOCK' or (x[1] != 'FINAL' and x[2].get('ordinal', 99) < 8)]
failed_events = prefix + [(iso(START + 80), 'FAILURE_LOG', payload), (iso(START + 80.3), 'METHOD', method)]
failed_binding = copy.deepcopy(binding); failed_binding.update(run_conclusion='failure', job_conclusion='failure')
failed_run = copy.deepcopy(run); failed_run['conclusion'] = 'failure'
failed_jobs = copy.deepcopy(jobs); failed_jobs['jobs'][0]['conclusion'] = 'failure'; failed_jobs['jobs'][0]['steps'][-1]['conclusion'] = 'failure'
cases = []
def test(name, values=failed_events, b=failed_binding, r=failed_run, j=failed_jobs, *, retained=1, matched=True, complete=False):
    result = A.analyze(render(values), frozen, expected, copy.deepcopy(b), copy.deepcopy(r), copy.deepcopy(j))
    assert result['analysis_status'] == ('COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE' if complete else 'INCOMPLETE'), (name, result['issues'])
    assert len(result['state']['failures']) == retained, (name, result['issues'])
    if retained: assert result['state']['failures'][0]['matched_method_receipt'] is matched
    cases.append({'name': name, 'analysis_status': result['analysis_status'], 'retained_failure_count': retained, 'matched_method_receipt': matched if retained else None, 'issues': result['issues']})
    return result

success = test('Unchanged complete synthetic success', events, binding, run, jobs, retained=0, complete=True)
good = test('Production helper output accepted only as matched failure')
A.write_outputs(OUT / 'synthetic-forwarded-output', good)
item = good['failure_evidence'][0]
assert (OUT / 'synthetic-forwarded-output' / item['raw_path']).read_bytes() == raw
assert not item['whole_run_complete'] and len(good['extracted_records']) == 7
test('Missing METHOD retains only explicitly unmatched partial', failed_events[:-1], matched=False)
test('Duplicate failure is incomplete', failed_events[:-1] + [failed_events[-2], failed_events[-1]], matched=False)
wrong_method = copy.deepcopy(failed_events); wrong_method[-1][2]['junit_status'] = 'INCOMPLETE'
test('Mismatched following METHOD never matches', wrong_method, matched=False)
test('Official success cannot rescue failure', b=binding, r=run, j=jobs)
pass_method = copy.deepcopy(failed_events); pass_method[-1][2].update(status='PASS', junit_status='PASS', error=None)
test('Following PASS cannot rescue failure', pass_method, binding, run, jobs, matched=False)
for field, value in [('run_id', '900999'), ('source_commit', '0' * 40), ('raw_log_sha256', '0' * 64), ('status', 'PASS'), ('original_utf8', '\ud800')]:
    changed = copy.deepcopy(failed_events); changed[-2][2][field] = value
    test('Reject failure field ' + field, changed, retained=0)
changed = copy.deepcopy(failed_events); v = changed[-2][2]; text = 'github_pat_' + 'x' * 30; v.update(original_utf8=text, raw_bytes=len(text.encode()), raw_log_sha256=A.sha(text.encode()))
test('Reject rehashed secret content', changed, retained=0)
refused = copy.deepcopy(failed_events); refused[-2][2].update(forwarding_status='REFUSED', original_utf8=None, raw_log_sha256=None, raw_bytes=None, refusal_reason='MISSING_LOG')
refusal = test('Valid refusal has no original', refused); A.write_outputs(OUT / 'synthetic-refused-output', refusal)
assert 'raw_path' not in refusal['failure_evidence'][0]
assert not list((OUT / 'synthetic-refused-output').glob('failure-*-instrumentation.log'))
bad_refusal = copy.deepcopy(refused); bad_refusal[-2][2]['original_utf8'] = 'must not export'
test('Refusal containing text is rejected', bad_refusal, retained=0)
late = copy.deepcopy(failed_events); late[-2] = (iso(START + 2800), 'FAILURE_LOG', late[-2][2]); late[-1] = (iso(START + 2800.3), 'METHOD', late[-1][2])
long_jobs = copy.deepcopy(failed_jobs); long_jobs['jobs'][0]['completed_at'] = iso(START + 2801)
test('Post-phase failure retained inside real job span', late, j=long_jobs)
outside = copy.deepcopy(failed_events); outside[-2] = (iso(START + 202), 'FAILURE_LOG', outside[-2][2]); outside[-1] = (iso(START + 202.3), 'METHOD', outside[-1][2])
outside_result = test('Ordered failure beyond end plus one second rejected', outside, retained=0)
assert any('outside official job span' in issue for issue in outside_result['issues'])
late_success = copy.deepcopy(events)
for i, (stamp, kind, value) in enumerate(late_success):
    if kind != 'CLOCK': late_success[i] = (iso(A.epoch(stamp) + 2800), kind, value)
late_jobs = copy.deepcopy(jobs); late_jobs['jobs'][0]['completed_at'] = iso(START + 3100)
late_result = test('Late success remains rejected by original phase cutoff', late_success, binding, run, late_jobs, retained=0)
assert any('phase/work deadline' in issue for issue in late_result['issues'])
tail = copy.deepcopy([x for x in events if x[1] != 'FINAL' and not (x[1] == 'METHOD' and x[2]['ordinal'] == 10)])
tail_payload = copy.deepcopy(payload); tail_step = frozen['plan']['steps'][9]
tail_payload.update(ordinal=10, selection=tail_step['selection'], status='INCOMPLETE', junit_status='PASS')
for _, kind, value in tail:
    if kind == 'CONTEXT' and value['ordinal'] == 10: value['raw_log_sha256'] = tail_payload['raw_log_sha256']
tail_method = {k: tail_step[k] for k in ['ordinal', 'phase', 'identity', 'selection']}
tail_method.update(status='INCOMPLETE', junit_status='PASS', adb_exit=0, process_exit_outcome='NATURAL_EXIT', error='synthetic failure after completed marker block')
tail += [(iso(START + 100.25), 'FAILURE_LOG', tail_payload), (iso(START + 100.3), 'METHOD', tail_method)]
tail_result = test('Completed success marker block followed by failure stays incomplete', tail)
assert len(tail_result['state']['methods']) == 9 and tail_result['state']['blocks'][-1]['ended'] and not tail_result['state']['blocks'][-1]['method_pass']
A.write_outputs(OUT / 'synthetic-late-failure-output', tail_result)
assert all(not x['whole_run_complete'] for x in tail_result['extracted_records'])
A.write_outputs(OUT / 'synthetic-success-output', success)
result = {'status': 'PASS_LOCAL_BINDING_AND_SYNTHETIC_PRODUCER_CONSUMER', 'scope': 'SYNTHETIC_NO_NEW_NATIVE_NO_NETWORK', 'manifest_sha256': sha(BASE / 'EXTRACTOR_SHA256SUMS'), 'source_chain': A.GIT_BINDING, 'read_only_local_git': git_result, 'secret_regex_ast_matches_producer_and_packager': True, 'synthetic_case_count': len(cases), 'cases': cases, 'producer_run_sha256': sha(producer_path), 'consumer_sha256': sha(BASE / 'analyze_logs.py'), 'checker_sha256': sha(Path(__file__)), 'actual_run_data_read': False}
(OUT / 'independent-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': result['status'], 'synthetic_cases': len(cases), 'actual_run_data_read': False}))
