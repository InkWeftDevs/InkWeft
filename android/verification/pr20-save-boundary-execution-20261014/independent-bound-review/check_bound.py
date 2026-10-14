"""Read-only bound identity/delta audit; outputs only this scratch review directory."""
from pathlib import Path
import hashlib, io, json, subprocess, tarfile

BASE = Path('/workspace/scratch/pr20-save-boundary-ci-20261014')
REPO = Path('/workspace/InkWeft')
OUT = Path(__file__).resolve().parent
TEMPLATE = BASE / 'controller'
BOUND = BASE / 'bound-control'
REL = 'android/verification/pr20-save-boundary-20261014'
READY = REPO / REL
PLAN_GIT = '4278f310d6f8a742072725f77fda3fdb92c22431'
SOURCE_GIT = '4f41a501127dbed6dbef3519f93233a105b5d618'

def digest(data): return hashlib.sha256(data).hexdigest()
def sha(path): return digest(path.read_bytes())
def git(*args): return subprocess.check_output(['git', '-C', str(REPO), *args])
def load(path): return json.loads(path.read_text())
def emit(value): return (json.dumps(value, indent=2) + '\n').encode()

template_manifest = {n: h for h, n in (line.split() for line in (BASE / 'CONTROLLER_SHA256SUMS').read_text().splitlines())}
assert len(template_manifest) == 16
assert sha(BASE / 'CONTROLLER_SHA256SUMS') == 'ee2a145cf9f744e6ad18b6bcfcd1e899a580b946f9c164cacca9463f1dbc1a3d'
assert all(sha(TEMPLATE / n) == h for n, h in template_manifest.items())
expected_ready_names = {'C1-source-manifest.json', 'PLAN.md', 'SHA256SUMS', 'marker-contract.json', 'method-plan.json', 'original-protocol.json', 'prepare_sources.py', 'source-spec.json'}
assert {p.name for p in READY.iterdir()} == expected_ready_names
committed = set(git('ls-tree', '-r', '--name-only', PLAN_GIT, '--', REL).decode().splitlines())
assert committed == {REL + '/' + n for n in expected_ready_names}
for n in expected_ready_names:
    assert (READY / n).is_file() and not (READY / n).is_symlink()
    assert git('show', PLAN_GIT + ':' + REL + '/' + n) == (READY / n).read_bytes(), n
listed = {n: h for h, n in (line.split() for line in (READY / 'SHA256SUMS').read_text().splitlines())}
assert set(listed) == expected_ready_names - {'SHA256SUMS'}
assert all(sha(READY / n) == h for n, h in listed.items())

spec = load(READY / 'source-spec.json')
assert spec['status'] == 'FROZEN' and spec['scope'] == 'DIAGNOSTIC_ONLY'
assert spec['common_probes'] == [] and list(spec['identities']) == ['C1']
identity = spec['identities']['C1']
assert identity['git'] == SOURCE_GIT and identity['production_overrides'] == []
assert identity['tree'] == git('rev-parse', SOURCE_GIT + '^{tree}').decode().strip()
files = {}
with tarfile.open(fileobj=io.BytesIO(git('archive', '--format=tar', SOURCE_GIT, 'android', 'ci'))) as archive:
    for member in archive:
        assert not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
        assert member.isfile() or member.isdir()
        if member.isdir() or member.name.startswith('android/verification/'):
            continue
        data = archive.extractfile(member).read()
        files[member.name] = {'bytes': len(data), 'sha256': digest(data)}
manifest = load(READY / 'C1-source-manifest.json')
assert manifest['files'] == files
canonical = digest(json.dumps(files, sort_keys=True, separators=(',', ':')).encode())
assert canonical == manifest['canonical_manifest_sha256'] == identity['canonical_manifest_sha256']
for src, dst, status in [('method-plan.template.json', 'method-plan.json', 'FROZEN_DIAGNOSTIC_NOT_RUN'), ('marker-contract.template.json', 'marker-contract.json', 'FROZEN')]:
    expected = load(TEMPLATE / src); expected['status'] = status
    assert (READY / dst).read_bytes() == emit(expected), dst
assert (READY / 'PLAN.md').read_text() == (TEMPLATE / 'PLAN.template.md').read_text().replace('Status: UNBOUND.', 'Status: FROZEN_SOURCE_NOT_RUN.')
for n in ['prepare_sources.py', 'original-protocol.json']:
    assert (READY / n).read_bytes() == (TEMPLATE / n).read_bytes()
for n, h in load(READY / 'marker-contract.json')['reviewed_source_sha256'].items():
    assert files[n]['sha256'] == h

expected_bound_names = {'run.py', 'markers.py', 'package.py', 'cleanup.py', 'workflow.proposed.yml', 'authorization.json'}
assert {p.name for p in BOUND.iterdir()} == expected_bound_names
spec_hash = sha(READY / 'source-spec.json')
for n in ['run.py', 'package.py']:
    before = (TEMPLATE / n).read_text()
    assert before.count("'UNBOUND'") == 1
    assert (BOUND / n).read_text() == before.replace("'UNBOUND'", repr(spec_hash)), n
for n in ['cleanup.py', 'markers.py', 'workflow.proposed.yml']:
    assert (BOUND / n).read_bytes() == (TEMPLATE / n).read_bytes(), n
auth = load(TEMPLATE / 'authorization.json')
auth.update(approved=True, status='FROZEN', plan_git=PLAN_GIT)
bindings = {'approved_plan_sha256': 'PLAN.md', 'source_spec_sha256': 'source-spec.json', 'method_plan_sha256': 'method-plan.json', 'original_protocol_sha256': 'original-protocol.json', 'marker_contract_sha256': 'marker-contract.json', 'prepare_sources_sha256': 'prepare_sources.py'}
for key, n in bindings.items(): auth[key] = sha(READY / n)
assert (BOUND / 'authorization.json').read_bytes() == emit(auth)
assert "github.event.before == '" + '0' * 40 + "'" in (BOUND / 'workflow.proposed.yml').read_text()
assert auth['phase_cutoff_minutes'] == {'setup': 30, 'data': 45, 'work': 55}
assert auth['job_timeout_minutes'] == 60
preflight_path = BASE / 'offline-check-bound/preflight.json'
preflight = load(preflight_path)
assert preflight['status'] == 'PASS' and len(preflight['groups']) == 13
assert all(group['status'] == 'PASS' for group in preflight['groups'])
for name, h in preflight['inputs'].items(): assert sha(Path(name)) == h, name
assert set(preflight['inputs']) == {str(READY / n) for n in expected_ready_names} | {str(BOUND / n) for n in expected_bound_names}
assert preflight['checker_sha256'] == sha(TEMPLATE / 'check_offline.py')

result = {
    'status': 'PASS_BOUND_NOT_ACTIVATED',
    'scope': 'LOCAL_READ_ONLY_IDENTITY_AND_DELTA_REVIEW_NO_NATIVE',
    'source_git': SOURCE_GIT, 'readiness_git': PLAN_GIT,
    'template_manifest_sha256': sha(BASE / 'CONTROLLER_SHA256SUMS'),
    'source_spec_sha256': spec_hash,
    'canonical_source_manifest_sha256': canonical, 'source_files_checked': len(files),
    'readiness_sha256': {n: sha(READY / n) for n in sorted(expected_ready_names)},
    'bound_control_sha256': {n: sha(BOUND / n) for n in sorted(expected_bound_names)},
    'allowed_control_deltas': {
        'run.py': 'Only FROZEN_SOURCE_SPEC_SHA256 string literal',
        'package.py': 'Only FROZEN_SOURCE_SPEC_SHA256 string literal',
        'authorization.json': {'approved': True, 'status': 'FROZEN', 'plan_git': PLAN_GIT, 'six_readiness_digest_bindings': bindings},
        'byte_identical': ['cleanup.py', 'markers.py', 'workflow.proposed.yml'],
    },
    'workflow_prep_parent_guard': '0' * 40,
    'offline_bound_preflight': {'path': str(preflight_path), 'sha256': sha(preflight_path), 'status': 'PASS', 'groups': 13, 'scope': preflight['scope']},
    'review_script_sha256': sha(Path(__file__)),
    'remaining_findings': [],
    'not_performed': ['source/control/repository edits', 'binder execution', 'push', 'network', 'build', 'native execution', 'workflow activation'],
    'limits': ['This binds the current inactive six-file output only; later prep/active changes require their own exact review.', 'No synthetic or static result is native evidence or performance acceptance.', 'Log extractor is outside this review and will have separate evidence.'],
}
(OUT / 'bound-review.json').write_bytes(emit(result))
print(json.dumps({'status': result['status'], 'source_files_checked': len(files), 'readiness_files': len(expected_ready_names), 'bound_files': len(expected_bound_names), 'preflight_groups': 13, 'result_sha256': sha(OUT / 'bound-review.json')}, indent=2))
