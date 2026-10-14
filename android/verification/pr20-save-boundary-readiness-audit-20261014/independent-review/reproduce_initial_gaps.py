"""Pure synthetic validation checks. No device, network, build, source edits or CI."""
import ast
import copy
import hashlib
import json
import types
from pathlib import Path

HERE = Path('/workspace/scratch/pr20-save-boundary-ci-20261014/controller')
OUT = Path(__file__).parent
contract = json.loads((HERE / 'marker-contract.template.json').read_text())
contract['status'] = 'FROZEN'
plan = json.loads((HERE / 'method-plan.template.json').read_text())
markers = types.ModuleType('audited_markers')
exec(compile((HERE / 'markers.py').read_bytes(), str(HERE / 'markers.py'), 'exec'), markers.__dict__)
namespace = {'contract': contract, 'plan': plan, 'json': json}
tree = ast.parse((HERE / 'check_offline.py').read_text())
for name in ('specimen', 'fixtures'):
    node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == name)
    exec(compile(ast.Module([node], type_ignores=[]), 'synthetic_fixture', 'exec'), namespace)
raw = namespace['fixtures']()[10]
entry = plan['steps'][9]
def accepted(value):
    try:
        markers.safe_marker_payloads(entry, value, contract)
        return True
    except (AssertionError, ValueError, KeyError, TypeError, RuntimeError):
        return False
payloads = [(line.split(' ', 1)[0], json.loads(line.split(' ', 1)[1])) for line in raw.splitlines()]
author = [event for name, block in payloads if name == 'LIBRARY_SAVE_BOUNDARY_EVENTS' and block['mode'] == 'DIRECT' for event in block['events'] if event['sample'] == 0]
result = {
    'scope': 'SYNTHETIC_REJECTION_AUDIT_NOT_NATIVE',
    'controller_files': {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in ('markers.py', 'check_offline.py', 'marker-contract.template.json')},
    'synthetic_fixture_accepted': accepted(raw),
    'synthetic_author_stages': [event['stage'] for event in author],
    'synthetic_missing_real_author_stages': sorted(set(('room-begin-enter', 'room-success-enter', 'room-success-exit', 'room-end-enter', 'sqlite-begin-enter', 'sqlite-success-enter', 'sqlite-success-exit', 'sqlite-end-enter', 'save-head-read', 'save-head-written', 'save-revision-written')) - {event['stage'] for event in author}),
    'synthetic_direct_control_stages': [event['stage'] for name, block in payloads if name == 'LIBRARY_SAVE_BOUNDARY_READS' and block['mode'] == 'DIRECT' for event in block['events'] if event['owner'] == 'control'],
    'native_executions': 0,
}
(OUT / 'initial-gap-reproduction.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
