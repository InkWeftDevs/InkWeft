"""Direct independent receipt/hash/count audit of supplied actual local log; no new native/network."""
from pathlib import Path
from collections import Counter
import hashlib, json, re
BASE = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent
EXEC = Path('/workspace/scratch/pr20-save-boundary-uuid-execution-20261014')
EXTRACTED = OUT / 'actual-reviewed-output'
CONTROL = Path('/workspace/InkWeft-save-boundary-uuid-run')
FROZEN = CONTROL / 'android/verification/pr20-save-boundary-uuid-20261014'
def digest(b): return hashlib.sha256(b).hexdigest()
def sha(p): return digest(p.read_bytes())
def compact(x): return json.dumps(x, ensure_ascii=False, separators=(',', ':')).encode()
def load(p): return json.loads(p.read_bytes())
raw = (EXEC / 'official-job.log').read_bytes()
assert len(raw) == 1037054 and digest(raw) == '45d6579ec88cdff12dbd94393121a04684dce7595713947e6663af0e6b45d68c'
run = load(EXEC / 'official-terminal-run.json'); jobs = load(EXEC / 'official-terminal-jobs.json')
assert jobs['total_count'] == len(jobs['jobs']) == 1; job = jobs['jobs'][0]
assert run['id'] == job['run_id'] == 38543698114 and job['id'] == 115784421756
assert run['run_attempt'] == job['run_attempt'] == 1
assert run['status'] == job['status'] == 'completed' and run['conclusion'] == job['conclusion'] == 'success'
assert run['head_sha'] == job['head_sha'] == 'ac63ce66ed4aaec0372c56b39be97e3335966c31'
plan = load(FROZEN / 'method-plan.json'); contract = load(FROZEN / 'marker-contract.json'); binding = load(OUT / 'actual-terminal-binding.json')
counts = Counter(); context = None; ended = False; blocks = []; methods = []; records = []; ordered = []; payloads = []; final = None
for line_number, line in enumerate(raw.decode('utf-8-sig').splitlines(), 1):
    match = re.match(r'^(\S+) (INKWEFT_CAUSAL_[A-Z_]+) (.*)$', line)
    if not match: continue
    tag = match[2][len('INKWEFT_CAUSAL_'):]; value = json.loads(match[3]); counts[tag] += 1
    assert final is None, 'Receipt after FINAL'
    if tag == 'CLOCK':
        assert counts[tag] == 1 and not blocks
        assert value['deadlines_minutes'] == [30, 45, 55, 60]
    elif tag == 'CONTEXT':
        assert context is None and value['ordinal'] == len(methods) + 1
        step = plan['steps'][len(methods)]; assert all(value[k] == step[k] for k in ['ordinal', 'selection', 'identity', 'args', 'seed', 'install_group'])
        assert value['run_id'] == '38543698114' and value['workflow_sha'] == binding['workflow_sha']
        assert value['source_commit'] == binding['source_commit']
        assert value['test_source_sha256'] == contract['methods'][str(value['ordinal'])]['test_source_sha256']
        assert value['junit_status'] == 'PASS' and value['adb_exit'] == 0 and value['process_exit_outcome'] == 'NATURAL_EXIT'
        context = value; records = []; payloads = []; ordered = []; ended = False
    elif tag == 'RECORD':
        assert context is not None and not ended
        assert value['ordinal'] == context['ordinal'] and value['sequence'] == len(records)
        data = value['original_json'].encode(); assert len(data) == value['payload_bytes'] and digest(data) == value['payload_sha256']
        records.append({'marker': value['marker'], 'data': json.loads(value['original_json'])})
        payloads.append(value['marker'] + ' ' + value['original_json'] + '\n')
        ordered.append({k: value[k] for k in ['sequence', 'marker', 'payload_bytes', 'payload_sha256']})
    elif tag == 'END':
        assert context is not None and not ended and value['ordinal'] == context['ordinal']
        assert value['count'] == len(records) and value['ordered_payloads'] == ordered
        assert digest(compact(ordered)) == value['ordered_payloads_sha256']
        actual_records = compact(records) + b'\n'; assert digest(actual_records) == context['records_sha256']
        name = 'method-%02d-C1' % context['ordinal']
        assert (EXTRACTED / (name + '-records.json')).read_bytes() == actual_records
        assert (EXTRACTED / (name + '-markers.log')).read_bytes() == ''.join(payloads).encode()
        ended = True
    elif tag == 'METHOD':
        assert context is not None and ended
        assert value['ordinal'] == context['ordinal'] == len(methods) + 1
        assert value['status'] == value['junit_status'] == 'PASS' and value['adb_exit'] == 0
        assert value['process_exit_outcome'] == 'NATURAL_EXIT' and value['error'] is None
        methods.append(value); blocks.append({'context': context, 'records': records}); context = None
    elif tag == 'FINAL':
        assert context is None and len(methods) == 10
        final = value; final_line = line_number
    else: raise AssertionError('Unexpected actual receipt: ' + tag)
assert dict(counts) == {'CLOCK': 1, 'CONTEXT': 10, 'END': 10, 'METHOD': 10, 'RECORD': 57, 'FINAL': 1}
assert final['status'] == 'EXECUTION_COMPLETE_ANALYSIS_PENDING' and final['packaging_complete'] and final['cleanup_verified']
assert final['missing_files'] == [] and final['run_id'] == '38543698114' and final['workflow_sha'] == binding['workflow_sha']
assert final['input_hashes'] == binding['input_hashes']
assert final['device_before_sha256'] == final['device_after_sha256']
assert final['install_groups'] == {'C1-data-diagnostics': {'identity': 'C1', 'status': 'PASS'}}
for method, footer, block in zip(methods, final['methods'], blocks):
    assert all(method[k] == footer[k] for k in method if k != 'error')
    assert footer['log_sha256'] == block['context']['raw_log_sha256']
assert len({json.dumps(b['context']['selected_apks'], sort_keys=True) for b in blocks}) == 1
assert len({json.dumps(b['context']['installed_apks'], sort_keys=True) for b in blocks}) == 1
for cleanup in final['cleanup'].values():
    assert cleanup['emulator'] == cleanup['command_groups'] == 'STOPPED_CONFIRMED'
    assert cleanup['acl'] == 'RESTORED_EXACT' and cleanup['error_count'] == 0 and cleanup['exact_match'] is True
    assert cleanup['before_sha256'] == cleanup['after_sha256']
assert final['cleanup']['trap']['before_sha256'] == final['cleanup']['always']['before_sha256']
assert all(not block['records'] for block in blocks[:9])
diag = blocks[9]['records']; arms = {}
for mode, revision in [('IDLE', 1), ('DIRECT', 1), ('OBSERVE', 32)]:
    selected = [r for r in diag if r['data']['mode'] == mode]
    results = [r['data'] for r in selected if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_RESULT']; ends = [r['data'] for r in selected if r['marker'] == 'LIBRARY_SAVE_BOUNDARY_END']
    assert len(results) == len(ends) == 1; result, end = results[0], ends[0]
    assert result['seed'] == 4101 and result['startRevision'] == revision
    assert result['fixture']['logicalSha256'] == '1c69bba77781ba0c0b642309b8e869ff21c9a440fd7714894e67c572b95c9dcf'
    assert [s['sample'] for s in result['saves']] == list(range(30))
    assert all(s['committed'] and s['revision'] == revision + s['sample'] + 1 for s in result['saves'])
    event_counts = {}
    for marker, key in [('LIBRARY_SAVE_BOUNDARY_EVENTS', 'recordedEvents'), ('LIBRARY_SAVE_BOUNDARY_READS', 'recordedReadEvents')]:
        parts = [r['data'] for r in selected if r['marker'] == marker]
        assert [p['part'] for p in parts] == list(range(len(parts)))
        assert all(len(p['events']) <= 96 for p in parts)
        count = sum(len(p['events']) for p in parts); assert count == end[key]; event_counts[key] = count
    assert end['completed'] and end['committedSaves'] == 30 and not end['invalid']
    assert end['droppedEvents'] == end['droppedReadEvents'] == 0
    arms[mode] = {'committed_saves': 30, 'start_revision': revision, 'end_revision': result['saves'][-1]['revision'], 'readiness': result['readiness'], **event_counts, 'dropped_events': 0, 'dropped_read_events': 0}
analysis = load(EXTRACTED / 'analysis.json')
assert analysis['analysis_status'] == 'COMPLETE_SCOPED_SAVE_BOUNDARY_LOG_EVIDENCE' and analysis['issues'] == []
assert analysis['official_job_span_seconds'] == 372 and not analysis['failure_evidence']
assert all(x['block_closed'] and x['method_pass'] and x['whole_run_complete'] for x in analysis['extracted_records'])
owner = BASE / 'actual-output'
assert (owner / 'method-10-C1-records.json').read_bytes() == (EXTRACTED / 'method-10-C1-records.json').read_bytes()
assert (owner / 'method-10-C1-markers.log').read_bytes() == (EXTRACTED / 'method-10-C1-markers.log').read_bytes()
result = {'status': 'PASS_ACTUAL_LOCAL_LOG_CLOSURE', 'scope': 'EXISTING_OFFICIAL_RUN_REVIEW_NO_NEW_NATIVE_EXECUTION', 'run_id': 38543698114, 'job_id': 115784421756, 'attempt': 1, 'official_job_seconds': 372, 'source_chain': {k: binding[k] for k in ['source_commit', 'readiness_git', 'prep_sha', 'workflow_sha']}, 'official_log_sha256': digest(raw), 'official_log_bytes': len(raw), 'receipt_counts': dict(counts), 'final_line': final_line, 'methods_pass': 10, 'failure_envelopes': 0, 'committed_save_count': 90, 'arms': arms, 'cleanup_bound_claims': final['cleanup'], 'phase_times': analysis['state']['phase_times'], 'final_missing_files': final['missing_files'], 'packaging_complete_claim': final['packaging_complete'], 'payload_hashes_and_original_marker_export_independently_rebuilt': True, 'owner_method10_records_and_markers_byte_identical': True, 'method10_records_sha256': sha(EXTRACTED / 'method-10-C1-records.json'), 'method10_markers_sha256': sha(EXTRACTED / 'method-10-C1-markers.log'), 'extractor_receipt_sha256': sha(EXTRACTED / 'analysis.json'), 'review_checker_sha256': sha(Path(__file__)), 'performance_acceptance': 'NOT_EVALUATED', 'limits': ['Compact receipts do not independently inspect APK/native/ACL/artifact or original raw JUnit files.', 'Official metadata/log authenticity supplied by root; no online lookup performed.', 'No new run, retry, build, network or artifact route used.', 'Numerical interpretation and public performance gate remain separate.']}
(OUT / 'independent-actual-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': result['status'], 'methods': 10, 'saves': 90, 'failure_envelopes': 0, 'method10_markers_sha256': result['method10_markers_sha256']}))
