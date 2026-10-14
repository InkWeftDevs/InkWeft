"""Independent local delta and synthetic forwarding checks. Never executes runner setup."""
from pathlib import Path
import ast, copy, contextlib, hashlib, importlib.util, io, json, re, subprocess, sys, time
sys.dont_write_bytecode = True
BASE = Path(__file__).resolve().parents[1]
OUT = Path(__file__).resolve().parent
NEW = BASE / 'controller'
OLD = Path('/workspace/scratch/pr20-save-boundary-ci-20261014/controller')
REPO = Path('/workspace/InkWeft')
SOURCE = '94379190b9ad0e1034f136ea9887fb0ac12e7ea7'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p): return json.loads(p.read_text())
manifest = {n: h for h, n in (line.split() for line in (BASE / 'CONTROLLER_SHA256SUMS').read_text().splitlines())}
assert sha(BASE / 'CONTROLLER_SHA256SUMS') == 'a09787d105551a10cea5627d8de377cfc0ab00c60e19da0dab5e21029d631e09'
assert len(manifest) == 16 and all(sha(NEW / n) == h for n, h in manifest.items())
preflight = load(BASE / 'offline-check-01/preflight.json')
assert len(preflight['groups']) == 14 and all(g['status'] == 'PASS' for g in preflight['groups'])
assert preflight['inputs'] == {str(NEW / n): h for n, h in manifest.items()}
unchanged = ['authorization.json', 'cleanup.py', 'freeze_readiness.py', 'markers.py', 'method-plan.template.json', 'original-protocol.json', 'prepare_sources.py']
for n in unchanged: assert (NEW / n).read_bytes() == (OLD / n).read_bytes(), n
for n in ['bind_control.py', 'package.py', 'workflow.proposed.yml']:
    assert (NEW / n).read_text() == (OLD / n).read_text().replace('pr20-save-boundary', 'pr20-save-boundary-uuid'), n
auth = load(NEW / 'authorization.json')
assert auth['approved'] is False and auth['status'] == auth['plan_git'] == 'UNBOUND'
assert "github.event.before == '" + '0' * 40 + "'" in (NEW / 'workflow.proposed.yml').read_text()
assert "FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in (NEW / 'run.py').read_text()
contract = load(NEW / 'marker-contract.template.json'); normalized = copy.deepcopy(contract)
failure = normalized.pop('failure_log_forwarding')
path = 'android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveBoundaryDiagnosticsTest.kt'
prior = load(OLD / 'marker-contract.template.json')
for ordinal in ['8', '9', '10']:
    assert normalized['methods'][ordinal]['test_source_sha256'] == 'cd1b0ba9e112a19e16fdea36b1b867183c890a4addb8c6ebfdfe7986f4a73038'
    normalized['methods'][ordinal]['test_source_sha256'] = prior['methods'][ordinal]['test_source_sha256']
normalized['reviewed_source_sha256'][path] = prior['reviewed_source_sha256'][path]
assert normalized == prior
for n, h in contract['reviewed_source_sha256'].items():
    data = subprocess.check_output(['git', '-C', str(REPO), 'show', SOURCE + ':' + n])
    assert hashlib.sha256(data).hexdigest() == h

tree = ast.parse((NEW / 'run.py').read_text())
original = ast.parse((OLD / 'run.py').read_text().replace('pr20-save-boundary', 'pr20-save-boundary-uuid'))
functions = {n.name: n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.ClassDef))}
helper = functions['forward_failure_log']
reverted = copy.deepcopy(tree)
reverted.body = [n for n in reverted.body if not isinstance(n, ast.FunctionDef) or n.name != 'forward_failure_log']
method = next(n for n in reverted.body if isinstance(n, ast.FunctionDef) and n.name == 'method')
handler = next(n for n in method.body if isinstance(n, ast.Try))
assert isinstance(handler.finalbody[1], ast.If)
assert ast.unparse(handler.finalbody[1].test) == "entry['status'] != 'PASS'"
del handler.finalbody[1]
assert ast.dump(reverted, include_attributes=False) == ast.dump(original, include_attributes=False)
secret = next(n.value for n in helper.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'secret' for t in n.targets))
pack = ast.parse((NEW / 'package.py').read_text())
pack_secret = next(n.value for n in pack.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'secret' for t in n.targets))
assert ast.dump(secret) == ast.dump(pack_secret)

spec = importlib.util.spec_from_file_location('reviewed_markers', NEW / 'markers.py')
markers = importlib.util.module_from_spec(spec); spec.loader.exec_module(markers)
plan = load(NEW / 'method-plan.template.json')
target = OUT / 'synthetic-inputs'; target.mkdir(exist_ok=True)
entry = dict(copy.deepcopy(plan['steps'][7]), status='FAIL', junit_status='FAIL', adb_exit=0, process_exit_outcome='NATURAL_EXIT')
ns = {'Path': Path, 're': re, 'hashlib': hashlib, 'json': json, 'time': time, 'subprocess': subprocess, 'OUT': target, 'PLAN': plan, 'MARKERS': contract, 'validate_shape': markers.validate_shape,
      'REPORT': {'run_id': '900001', 'run_attempt': '1', 'workflow_sha': 'a' * 40, 'methods': [entry]},
      'SPEC': {'identities': {'C1': {'git': SOURCE}}}, 'INPUT_HASHES': {'source_spec_sha256': 'b' * 64, 'method_plan_sha256': 'c' * 64}}
exec(compile(ast.Module([helper], type_ignores=[]), '<reviewed-forward-helper>', 'exec'), ns)
forward = ns['forward_failure_log']; log = target / 'method-08-C1-instrumentation.log'; cases = []

def check(name, data, reason=None, wrong_hash=False, selected=None):
    log.write_bytes(data); entry['log_sha256'] = '0' * 64 if wrong_hash else sha(log)
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf): forward(entry, selected or log)
    lines = buf.getvalue().splitlines(); assert len(lines) == 1
    assert lines[0].startswith('INKWEFT_CAUSAL_FAILURE_LOG ')
    encoded = lines[0].partition(' ')[2]; value = json.loads(encoded)
    assert encoded.isascii() and len(encoded.encode()) <= 6 * 262144 + 8192
    assert value['refusal_reason'] == reason
    if reason is None:
        assert value['forwarding_status'] == 'FORWARDED' and value['original_utf8'].encode() == data
    else: assert value['forwarding_status'] == 'REFUSED' and value['original_utf8'] is None
    assert value['source_commit'] == SOURCE and value['test_source_sha256'] == contract['methods']['8']['test_source_sha256']
    assert entry['status'] == value['status'] == 'FAIL'
    cases.append({'name': name, 'forwarding_status': value['forwarding_status'], 'reason': reason, 'envelope_bytes': len(encoded.encode())})

check('UTF8 CRLF NUL and marker-like line preserve bytes', '栈\r\n\0\u2028\nINKWEFT_CAUSAL_METHOD fake'.encode())
check('Exact 256KiB sixfold escaping', b'\0' * 262144)
check('Oversize refusal with no partial content', b'x' * 262145, 'OVERSIZE')
check('Invalid UTF8 refusal', b'\xff', 'INVALID_UTF8')
check('Hash mismatch refusal', b'original', 'HASH_MISMATCH', wrong_hash=True)
for i, data in enumerate([b'ghp_' + b'x' * 30, b'github_pat_' + b'x' * 30, b'AKIA' + b'A' * 16, b'-----BEGIN PRIVATE KEY-----', b'ssh-ed25519 ' + b'A' * 40]):
    check('Synthetic secret pattern ' + str(i), data, 'SENSITIVE')
check('Wrong path refusal', b'original', 'UNSAFE_PATH_OR_METHOD', selected=target / 'not-allowed.log')
entry['status'] = 'PASS'; buf = io.StringIO()
with contextlib.redirect_stdout(buf): forward(entry, log)
assert buf.getvalue() == ''; cases.append({'name': 'PASS helper is silent', 'status': 'PASS'})
entry['status'] = 'NOT_RUN'
class BudgetAbort(RuntimeError): pass
class EvidenceIncomplete(RuntimeError): pass
ns.update(BudgetAbort=BudgetAbort, EvidenceIncomplete=EvidenceIncomplete, seconds=lambda *a: 100, save=lambda: None, junit=lambda *a: None)
def raw_result(current, raw, code):
    current.update(junit_status='INCOMPLETE', adb_exit=code)
    return []
ns['raw_result'] = raw_result
original_error = RuntimeError('independent synthetic original error')
def fail_run(*args, **kwargs):
    kwargs['stdout'].write('synthetic original method stack\n')
    raise original_error
ns['run'] = fail_run
exec(compile(ast.Module([functions['method']], type_ignores=[]), '<reviewed-method-with-stubs>', 'exec'), ns)
for broken in [False, True]:
    entry['status'] = 'NOT_RUN'; log.unlink(missing_ok=True); buf = io.StringIO()
    def fail_forward(*args): raise OSError('synthetic forwarding fault')
    ns['forward_failure_log'] = fail_forward if broken else forward
    with contextlib.redirect_stdout(buf):
        try: ns['method'](entry)
        except RuntimeError as error: assert error is original_error
        else: raise AssertionError('Original method exception was swallowed')
    assert entry['status'] == 'FAIL'
    assert 'INKWEFT_CAUSAL_CONTEXT' not in buf.getvalue() and 'INKWEFT_CAUSAL_END' not in buf.getvalue()
    receipt = json.loads(next(l.partition(' ')[2] for l in buf.getvalue().splitlines() if l.startswith('INKWEFT_CAUSAL_METHOD ')))
    assert receipt['status'] == 'FAIL' and receipt['error'] == str(original_error)
    if broken: assert entry['failure_log_forwarding'] == {'status': 'UNAVAILABLE', 'reason': 'FORWARD_EXCEPTION'}
    cases.append({'name': 'Original method exception identity preserved' + (' with forwarding fault' if broken else ''), 'status': 'PASS'})
total = 9115485 + 4931709 + 262144 + 1581056 + 3145728
assert total == 19036122 < 20 * 1024 * 1024
result = {'status': 'PASS_LOCAL_DELTA_AND_SYNTHETIC_FORWARDING', 'scope': 'NO_NETWORK_NO_NATIVE_NO_SOURCE_MUTATION', 'manifest_sha256': sha(BASE / 'CONTROLLER_SHA256SUMS'), 'source_git': SOURCE, 'source_reviewed_blobs_verified': 6, 'unchanged_files': unchanged, 'run_ast_only_delta': 'scope strings plus forward_failure_log and finally non-PASS call', 'packager_secret_regex_ast_identical': True, 'offline_14_groups_hash_bound': True, 'independent_synthetic_case_count': len(cases), 'cases': cases, 'conservative_combined_bytes': total, 'actual_package_limit_bytes': 20 * 1024 * 1024, 'script_sha256': sha(Path(__file__))}
(OUT / 'independent-checks.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'status': result['status'], 'synthetic_cases': len(cases), 'source_blobs': 6, 'offline_groups': 14}))
