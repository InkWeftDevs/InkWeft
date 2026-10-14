"""Independent local/synthetic checks; no network, Git writes, native calls or source changes."""
from pathlib import Path
import copy, hashlib, importlib.util, json, sys
sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
import analyze_logs as A

check = BASE / 'check-03'
control = Path('/workspace/InkWeft-save-boundary-run')
ready = control / 'android/verification/pr20-save-boundary-20261014'
binding = json.loads((check / 'binding.json').read_text())
expected = {key: binding[key] for key in ['run_id', 'job_id', 'workflow_sha', 'source_commit', 'readiness_git', 'prep_sha']}
frozen = A.frozen_inputs(ready, expected['source_commit'], expected['readiness_git'], control, binding)
run = json.loads((check / 'run.json').read_text())
jobs = json.loads((check / 'jobs.json').read_text())
original_text = (check / 'synthetic-job.log').read_text()
lines = original_text.splitlines()
cases = []

def test(name, text=original_text, changed_run=None, changed_jobs=None, changed_binding=None, complete=False):
    result = A.analyze(text, frozen, expected, changed_binding or binding, changed_run or run, changed_jobs or jobs)
    wanted = 'COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE' if complete else 'INCOMPLETE'
    assert result['analysis_status'] == wanted, (name, result['issues'])
    cases.append({'name': name, 'expected': wanted, 'actual': result['analysis_status'], 'issues': result['issues']})
    return result

valid = test('Full synthetic 10-method closure', complete=True)
for kind, ordinal in [('CLOCK', None), ('CONTEXT', 10), ('RECORD', 10), ('END', 10), ('METHOD', 10), ('FINAL', None)]:
    selected = lines.copy()
    index = next(i for i, line in enumerate(selected) if ' INKWEFT_CAUSAL_' + kind + ' ' in line and (ordinal is None or json.loads(line.split(' INKWEFT_CAUSAL_' + kind + ' ', 1)[1]).get('ordinal') == ordinal))
    selected.pop(index)
    test('Remove ' + kind + ' receipt', '\n'.join(selected) + '\n')
test('Duplicate FINAL', original_text + lines[-1] + '\n')
for field, value in [('status', 'BLOCKED_OR_FAILED'), ('packaging_complete', False), ('missing_files', ['method-10-C1-instrumentation.log']), ('cleanup_verified', False)]:
    stamp, payload = lines[-1].split(' INKWEFT_CAUSAL_FINAL ', 1)
    final = json.loads(payload); final[field] = value
    test('FINAL ' + field + ' cannot establish complete', '\n'.join(lines[:-1] + [stamp + ' INKWEFT_CAUSAL_FINAL ' + json.dumps(final)]) + '\n')
failed_run = copy.deepcopy(run); failed_run['conclusion'] = 'failure'
failed_binding = copy.deepcopy(binding); failed_binding['run_conclusion'] = 'failure'
failed = test('Complete synthetic receipts cannot override official failure', changed_run=failed_run, changed_binding=failed_binding)
A.write_outputs(OUT / 'synthetic-failed-official-extracted', failed)
assert len(failed['extracted_records']) == 10 and all(not r['whole_run_complete'] for r in failed['extracted_records'])
A.write_outputs(OUT / 'synthetic-complete-extracted', valid)
assert len(valid['extracted_records']) == 10 and all(r['whole_run_complete'] for r in valid['extracted_records'])
original_payloads = []
for line in lines:
    if ' INKWEFT_CAUSAL_RECORD ' in line:
        record = json.loads(line.split(' INKWEFT_CAUSAL_RECORD ', 1)[1])
        original_payloads.append(record['marker'] + ' ' + record['original_json'] + '\n')
assert (OUT / 'synthetic-complete-extracted/method-10-C1-markers.log').read_bytes() == ''.join(original_payloads).encode()

actual = json.loads((OUT / 'actual-extracted/analysis.json').read_text())
assert actual['analysis_status'] == 'INCOMPLETE'
assert len(actual['state']['methods']) == len(actual['extracted_records']) == 7
assert [m['ordinal'] for m in actual['state']['methods']] == list(range(1, 8))
assert len(actual['state']['observed_method_receipts']) == 8
assert actual['state']['observed_method_receipts'][-1]['ordinal'] == 8
assert actual['state']['observed_method_receipts'][-1]['status'] == 'FAIL'
assert actual['state']['final'] is None and actual['state']['final_observed'] is None
assert all(r['block_closed'] and r['method_pass'] and not r['whole_run_complete'] for r in actual['extracted_records'])
assert not (OUT / 'actual-extracted/method-10-C1-markers.log').exists()
result = {'status': 'PASS', 'scope': 'LOCAL_SYNTHETIC_CHECKS_PLUS_EXISTING_OFFICIAL_FAILURE_REPARSE_NO_NEW_NATIVE', 'synthetic_case_count': len(cases), 'synthetic_cases': cases, 'original_marker_export_byte_exact': True, 'existing_official_input': {'run': 38540498244, 'job': 115773755321, 'extractor_exit_code': 1, 'status': 'INCOMPLETE', 'closed_guard_methods': 7, 'observed_failed_method': 8, 'diagnostic_samples_available': 0}, 'analyzer_sha256': A.sha(Path(A.__file__).read_bytes()), 'review_checker_sha256': A.sha(Path(__file__).read_bytes())}
(OUT / 'independent-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': 'PASS', 'synthetic_cases': len(cases), 'existing_official_result': 'INCOMPLETE_7_PASS_8_FAIL_NO_DIAGNOSTICS'}))
