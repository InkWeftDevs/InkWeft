"""Independent pure-synthetic controller/analysis compatibility audit. NOT NATIVE."""
import ast
import copy
import hashlib
import json
import sys
import types
from pathlib import Path
sys.dont_write_bytecode = True
ROOT = Path('/workspace/scratch/pr20-save-boundary-ci-20261014')
CONTROL = ROOT / 'controller'
ANALYSIS = Path('/workspace/scratch/pr20-save-boundary-analysis-20261014')
OUT = ROOT / 'independent-review'

def load_module(path, name):
    module = types.ModuleType(name)
    exec(compile(path.read_bytes(), str(path), 'exec'), module.__dict__)
    return module

validator = load_module(CONTROL / 'markers.py', 'independent_controller')
analysis = load_module(ANALYSIS / 'analyze.py', 'independent_analysis')
contract = json.loads((CONTROL / 'marker-contract.template.json').read_text())
contract['status'] = 'FROZEN'
plan = json.loads((CONTROL / 'method-plan.template.json').read_text())
entry = plan['steps'][9]

def functions(path, names, namespace):
    tree = ast.parse(path.read_text())
    for name in names:
        node = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == name)
        exec(compile(ast.Module([node], type_ignores=[]), str(path) + ':synthetic', 'exec'), namespace)
    return namespace

controller_functions = functions(CONTROL / 'check_offline.py', ('specimen', 'fixtures'),
    {'contract': contract, 'plan': plan, 'json': json, 'copy': copy})
analysis_functions = functions(ANALYSIS / 'test_analyze.py', ('fixture', 'events', 'repartition'),
    {'analysis': analysis, 'json': json, 'copy': copy})

def raw(markers):
    return '\n'.join('LIBRARY_SAVE_BOUNDARY_' + marker['kind'] + ' ' + json.dumps(marker['value'], separators=(',', ':')) for marker in markers)

def outcomes(value):
    result = {}
    for name, action in [('controller', lambda: validator.safe_marker_payloads(entry, value, contract)),
                         ('analysis', lambda: analysis.analyze(analysis.load_markers(value)))]:
        try:
            output = action()
            result[name] = {'accepted': True}
            if name == 'analysis':
                assert output['saveCount'] == 90
                assert all(len(sample['segments']) == 12 for mode in analysis.MODES for sample in output['modes'][mode]['samples'])
                result[name]['save_count'] = 90
                result[name]['segments_per_sample'] = 12
        except (ValueError, AssertionError, KeyError, TypeError, RuntimeError) as error:
            result[name] = {'accepted': False, 'error_type': type(error).__name__, 'error': str(error)}
    return result

cases = []
def check(name, value, expected):
    result = outcomes(value)
    cases.append({'name': name, 'expected_acceptance': expected, 'outcomes': result})
    assert all(v['accepted'] == expected for v in result.values()), (name, result)

fixture = analysis_functions['fixture']
all_events = analysis_functions['events']
repartition = analysis_functions['repartition']
check('controller complete synthetic fixture accepted by both', controller_functions['fixtures']()[10], True)
check('analysis trigger-sync and nested synthetic fixture accepted by both', raw(fixture()), True)

required = [event for event in all_events(fixture()) if event['sample'] == 0 and event['transaction'] != 3000 and event['depth'] <= 1][:22]
assert len(required) == 22
for required_event in required:
    values = fixture()
    stream = [event for marker in values if marker['kind'] == 'EVENTS' and marker['value']['mode'] == 'IDLE' for event in marker['value']['events']]
    remove = next(index for index, event in enumerate(stream) if event['sample'] == 0 and all(event[key] == required_event[key] for key in ('stage', 'depth', 'transaction')))
    stream.pop(remove)
    check('delete ' + required_event['stage'] + ' and repair counts', raw(repartition(values, 'IDLE', 'EVENTS', stream)), False)

for key, value in [('threadId', 99), ('task', 999999), ('threadCpuNs', 0), ('wallNs', 0), ('depth', 1), ('transaction', 123), ('outcome', 'threw')]:
    values = fixture()
    next(event for event in all_events(values) if event['stage'] == 'save-head-written')[key] = value
    check('author body corrupted ' + key, raw(values), False)
values = fixture()
stream = [event for marker in values if marker['kind'] == 'EVENTS' and marker['value']['mode'] == 'IDLE' for event in marker['value']['events']]
index = next(index for index, event in enumerate(stream) if event['sample'] == 0 and event['stage'] == 'writer-run-exit')
failure = dict(stream[index], stage='save-failed', outcome='returned')
stream.insert(index + 1, failure)
check('extra save-failed with repaired counts', raw(repartition(values, 'IDLE', 'EVENTS', stream)), False)

values = fixture()
reads = [event for marker in values if marker['kind'] == 'READS' and marker['value']['mode'] == 'DIRECT' for event in marker['value']['events'] if event['stage'] != 'direct-end']
check('DIRECT end removed with repaired counts', raw(repartition(values, 'DIRECT', 'READS', reads)), False)
for count in [-1, 0]:
    values = fixture()
    next(marker['value']['events'][0] for marker in values if marker['kind'] == 'READS' and marker['value']['mode'] == 'OBSERVE')['count'] = count
    check('OBSERVE nonpositive commit witness ' + str(count), raw(values), False)

values = fixture()
end = values.pop(next(index for index, marker in enumerate(values) if marker['kind'] == 'END'))
values.append(end)
check('END moved after other arms', raw(values), False)
values = fixture(); values[0]['value']['unexpected'] = 1
check('unknown result field', raw(values), False)
values = fixture(); values[0]['value']['fixture']['rowCounts']['note_revisions'] = 500
check('unmeasured table count wrong', raw(values), False)

values = fixture()
stream = [event for marker in values if marker['kind'] == 'EVENTS' and marker['value']['mode'] == 'IDLE' for event in marker['value']['events']]
fragment = dict(stream[-1], sample=-1, task=999999, transaction=0, depth=0, threadId=1001, wallNs=99_000_000, threadCpuNs=99_000_000)
stream.append(fragment)
check('background terminal fragment without recorded submit allowed', raw(repartition(values, 'IDLE', 'EVENTS', stream)), True)
values = fixture()
stream = [event for marker in values if marker['kind'] == 'EVENTS' and marker['value']['mode'] == 'IDLE' for event in marker['value']['events']]
stream.append(dict(fragment, stage='writer-submit', outcome='entered'))
check('recorded background submit without terminal rejected', raw(repartition(values, 'IDLE', 'EVENTS', stream)), False)

values = fixture()
part = next(marker['value']['events'] for marker in values if marker['kind'] == 'EVENTS')
index = next(index for index, event in enumerate(part) if event['stage'] == 'save-returned')
part[index], part[index + 1] = part[index + 1], part[index]
check('different-thread enqueue interleave accepted', raw(values), True)

values = fixture()
reads = next(marker['value']['events'] for marker in values if marker['kind'] == 'READS' and marker['value']['mode'] == 'OBSERVE')
reads.extend([dict(reads[0], owner='read', stage='read-start', read=2, wallNs=2000, threadCpuNs=2000),
              dict(reads[0], owner='read', stage='read-cancel', read=2, wallNs=3000, threadCpuNs=3000)])
check('cancelled background read fragment kept without invented page completion', raw(repartition(values, 'OBSERVE', 'READS', reads)), True)

assert set(contract['schemas']['LIBRARY_SAVE_BOUNDARY_EVENTS']['properties']['events']['items']['properties']['stage']['enum']) == analysis.STAGES
assert set(contract['schemas']['LIBRARY_SAVE_BOUNDARY_READS']['properties']['events']['items']['properties']['stage']['enum']) == analysis.READ_STAGES
source_paths = [CONTROL / name for name in ('markers.py', 'check_offline.py', 'marker-contract.template.json', 'method-plan.template.json')]
source_paths += [ANALYSIS / name for name in ('analyze.py', 'test_analyze.py')]
output = {'scope': 'PURE_SYNTHETIC_CONTRACT_REVIEW_NOT_NATIVE', 'native_executions': 0, 'network_calls': 0, 'builds': 0,
          'status': 'PASS', 'case_count': len(cases), 'positive_cases': sum(case['expected_acceptance'] for case in cases),
          'negative_cases': sum(not case['expected_acceptance'] for case in cases),
          'source_sha256': {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in source_paths}, 'cases': cases}
(OUT / 'cross-validation.json').write_text(json.dumps(output, indent=2) + '\n')
print(json.dumps({key: output[key] for key in ('scope', 'status', 'case_count', 'positive_cases', 'negative_cases')}))
