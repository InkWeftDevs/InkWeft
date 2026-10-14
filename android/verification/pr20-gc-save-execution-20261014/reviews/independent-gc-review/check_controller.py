"""Read-only controller/source review; synthetic marker calls only, no run.py execution."""
from pathlib import Path
import ast, copy, hashlib, importlib.util, json, math, re, subprocess

HERE=Path(__file__).resolve().parent
C=HERE.parent/'controller'
A=Path('/workspace/scratch/pr20-gc-author-only-analysis-v2-20261014')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs={p.name:sha(p) for p in C.iterdir() if p.is_file()}
def module(path,name):
    s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
m=module(C/'markers.py','reviewed_markers');a=module(A/'analyze.py','independent_analyzer')
contract=json.loads((C/'marker-contract.template.json').read_text())
plan=json.loads((C/'method-plan.template.json').read_text())
auth=json.loads((C/'authorization.json').read_text())
results=[]
def record(name,detail=None):results.append({'check':name,'status':'PASS','detail':detail})

# Compare all copied pure functions/constants by AST, not by misleading comments.
tree=ast.parse((A/'analyze.py').read_text());nodes=[]
for node in tree.body:
    if isinstance(node,(ast.Import,ast.ImportFrom)):continue
    if isinstance(node,ast.FunctionDef) and node.name=='analyze':break
    nodes.append(node)
names=set()
for node in nodes:
    if isinstance(node,(ast.ClassDef,ast.FunctionDef)):names.add(node.name)
    if isinstance(node,ast.Assign):names.update(t.id for t in node.targets if isinstance(t,ast.Name))
class Prefix(ast.NodeTransformer):
    def visit_Name(self,node):
        if node.id in names:node.id='gc_'+node.id
        return node
    def visit_FunctionDef(self,node):node.name='gc_'+node.name;return self.generic_visit(node)
    def visit_ClassDef(self,node):node.name='gc_'+node.name;return self.generic_visit(node)
expected=Prefix().visit(ast.Module(nodes,type_ignores=[])).body
def name(node):
    if isinstance(node,(ast.FunctionDef,ast.ClassDef)):return node.name
    if isinstance(node,ast.Assign) and isinstance(node.targets[0],ast.Name):return node.targets[0].id
actual={name(n):n for n in ast.parse((C/'markers.py').read_text()).body if name(n)}
count=0
for node in expected:
    if name(node):
        assert ast.dump(node,include_attributes=False)==ast.dump(actual[name(node)],include_attributes=False),name(node)
        count+=1
record('GC v2 pure AST identity',{'nodes':count,'analyzerSha256':sha(A/'analyze.py')})
assert contract['gc_semantic_contract']['analyzer_sha256']==sha(A/'analyze.py')
assert contract['gc_semantic_contract']['schema_notes_sha256']==sha(A/'schema-notes.json')
assert contract['gc_semantic_contract']['schema_notes']==json.loads((A/'schema-notes.json').read_text())
record('GC source contract and schema notes hashes exact')

# Load only fixture-building function ASTs, without running the owner's selftest/writes.
test_tree=ast.parse((A/'test_analyze.py').read_text())
selected=[n for n in test_tree.body if isinstance(n,ast.FunctionDef) and n.name in ['distribution','fixture','extended_author']]
namespace={'a':a,'copy':copy,'math':math,'M':1_000_000}
exec(compile(ast.Module(selected,type_ignores=[]),str(A/'test_analyze.py'),'exec'),namespace)
baseline=namespace['fixture']();namespace['baseline']=baseline
def mode_records(records,mode):return [copy.deepcopy(r) for r in records if r['data']['connection']==mode]
def raw(records):return ''.join(r['marker']+' '+json.dumps(r['data'],separators=(',',':'))+'\n' for r in records)
frozen=copy.deepcopy(contract);frozen['status']='FROZEN' # In-memory only; never write or activate.
def check(name,records,valid=False,mode='same-room',change_raw=None,change_contract=None,change_entry=None):
    entry=copy.deepcopy(plan['steps'][5 if mode=='same-room' else 6]);c=copy.deepcopy(frozen)
    if change_entry:change_entry(entry)
    if change_contract:change_contract(c)
    data=raw(records)
    if change_raw:data=change_raw(data)
    try:
        parsed=m.safe_marker_payloads(entry,data,c)
        assert [p['original_json'] for p in parsed]==[line.split(' ',1)[1] for line in data.splitlines()]
        accepted=True;why=None
    except (AssertionError,ValueError,KeyError,TypeError,IndexError) as error:
        accepted=False;why=f'{type(error).__name__}: {error}'
    assert accepted==valid,(name,why)
    record(name,{'accepted':accepted,'syntheticOnly':True,'reason':why})

for mode in a.MODES:check('legal '+mode,mode_records(baseline,mode),True,mode)
check('prelude and nested transactions with multiple shards',mode_records(namespace['extended_author'](prelude=True,nested=3),'same-room'),True)
empty=mode_records(namespace['fixture']((0,0)),'same-room');t=empty[1]['data'];origin=10**12;M=1_000_000
for b,at in zip(t['batchEvents'][-2:],[210,211]):b.update(atNs=origin+at*M,cpuNs=at*1000,observedChanges=21)
t.update(firstDeferred=True,collectorEndNs=origin+220*M,finishStartNs=origin+230*M,finishEndNs=origin+280*M)
check('all1280 deleted then empty guard mismatched valid',empty,True)
x=copy.deepcopy(empty);x[1]['data']['firstDeferred']=False;check('empty guard mismatch cannot declare nondeferred',x)
x=copy.deepcopy(empty)
for b in x[1]['data']['batchEvents'][-2:]:b['observedChanges']=20
check('empty guard match cannot declare deferred',x)
same=mode_records(baseline,'same-room')
faults={
 'wrong outer mode':lambda x:x[0]['data'].__setitem__('connection','separate-room'),
 'wrong event database':lambda x:x[0]['data'].__setitem__('database','author'),
 'wrong columns':lambda x:x[0]['data']['eventColumns'].reverse(),
 'wrong schema':lambda x:x[0]['data'].__setitem__('eventSchema','author-boundary-v2'),
 'missing event shard':lambda x:x.pop(0),
 'duplicated event shard':lambda x:x.insert(1,copy.deepcopy(x[0])),
 'missing trial':lambda x:x.pop(1),
 'duplicate trial':lambda x:x.insert(2,copy.deepcopy(x[1])),
 'missing summary':lambda x:x.pop(),
 'duplicate summary':lambda x:x.append(copy.deepcopy(x[-1])),
 'trial before events':lambda x:x.__setitem__(slice(0,2),[x[1],x[0]]),
 'sample skipped':lambda x:x[1]['data'].__setitem__('sample',1),
 'part out of order':lambda x:x[0]['data'].__setitem__('part',1),
 'part total not closed':lambda x:x[0]['data'].__setitem__('parts',2),
 'part boolean':lambda x:x[0]['data'].__setitem__('part',False),
 'unknown event key':lambda x:x[0]['data'].__setitem__('unexpected',1),
 'row column missing':lambda x:x[0]['data']['events'][0].pop(),
 'non-author owner':lambda x:x[0]['data']['events'][0].__setitem__(1,999),
 'boundary count mismatch':lambda x:x[1]['data']['boundaries'][0].__setitem__('count',14),
 'boundary float count':lambda x:x[1]['data']['boundaries'][0].__setitem__('count',15.0),
 'boundary boolean drop':lambda x:x[1]['data']['boundaries'][0].__setitem__('dropped',False),
 'boundary integer invalid':lambda x:x[1]['data']['boundaries'][0].__setitem__('invalid',0),
 'boundary extra key':lambda x:x[1]['data']['boundaries'][0].__setitem__('extra',0),
 'interval sequence boolean':lambda x:x[1]['data']['deleteIntervals'][0].__setitem__('afterSequence',False),
 'interval candidate float':lambda x:x[1]['data']['deleteIntervals'][0].__setitem__('candidates',256.0),
 'retained revision float':lambda x:x[1]['data'].__setitem__('retainedRevisions',[1281.0]),
 'covered timestamp float':lambda x:x[1]['data']['coveredBeforeBatchCommitNs'].__setitem__(0,float(x[1]['data']['coveredBeforeBatchCommitNs'][0])),
 'missing after-stats':lambda x:x[1]['data']['phases'].pop(1),
 'missing terminal guard':lambda x:x[1]['data']['batchEvents'].__delitem__(slice(4,6)),
 'first failure retained not success':lambda x:x[1]['data'].__setitem__('status','FAIL'),
 'writer CPU backwards':lambda x:x[0]['data']['events'][4].__setitem__(8,0),
 'cross-thread writer identity':lambda x:x[0]['data']['events'][4].__setitem__(9,77),
 'missing author stage recounted':lambda x:(x[0]['data']['events'].pop(4),x[1]['data']['boundaries'][0].__setitem__('count',14)),
 'conditional quantiles below20':lambda x:x[-1]['data']['saveStartedDuringDeleteSaves'].__setitem__('p95Ms',1),
 'fabricated pure lock wait':lambda x:x[1]['data'].__setitem__('pureSqliteLockWaitMs',0),
}
for label,mutate in faults.items():x=copy.deepcopy(same);mutate(x);check(label,x)
separate=mode_records(baseline,'separate-room');separate[1]['data']['boundaries'][0]['count']=1
check('separate collector must remain zero author events',separate,mode='separate-room')
check('method6 cannot accept method7 outer stream',mode_records(baseline,'separate-room'))
check('UNBOUND cannot parse for execution',same,change_contract=lambda c:c.__setitem__('status','UNBOUND'))
check('wrong selection rejected',same,change_entry=lambda e:e.__setitem__('selection','wrong'))
check('wrong marker policy rejected',same,change_entry=lambda e:e.__setitem__('marker_policy','none'))
check('unknown marker refused',same,change_raw=lambda s:s+'HISTORY_UNEXPECTED {}\n')
check('truncated marker refused',same,change_raw=lambda s:s+'HISTORY_GC_BOUNDARY_EVENTS {\n')
check('duplicate JSON keys refused',same,change_raw=lambda s:s.replace('"sample":0','"sample":0,"sample":0',1))
check('nonfinite JSON refused',same,change_raw=lambda s:s.replace('"sample":0','"sample":NaN',1))
check('per-payload byte cap enforced',same,change_contract=lambda c:c['methods']['6']['markers']['HISTORY_GC_BOUNDARY_EVENTS'].__setitem__('payload_max_bytes',10))
check('whole-method payload byte cap enforced',same,change_contract=lambda c:c['methods']['6'].__setitem__('method_payload_max_bytes',10))

# Pure local Git reads prove source/test identity. No remote query or working-tree edits.
source='fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353';assert contract['source_commit']==source
source_checks={}
for path,wanted in contract['reviewed_source_sha256'].items():
    blob=subprocess.check_output(['git','show',source+':'+path],cwd='/workspace/InkWeft')
    actual_hash=hashlib.sha256(blob).hexdigest();assert actual_hash==wanted,(path,actual_hash,wanted)
    source_checks[path]=actual_hash
assert len(plan['steps'])==plan['instrumentation_calls']==plan['expected_junit_methods']==8
assert [e['ordinal'] for e in plan['steps']]==list(range(1,9))
assert len({e['selection'] for e in plan['steps']})==8
assert plan['install_groups']==1 and plan['retries']==0 and plan['scope']=='DIAGNOSTIC_ONLY'
assert plan['phase_cutoff_minutes']=={'setup':30,'data':45,'work':55,'job':60}
for e in plan['steps']:
    rule=contract['methods'][str(e['ordinal'])]
    assert rule['selection']==e['selection'] and rule['marker_policy']==e['marker_policy']
    assert rule['test_source_sha256']==source_checks[rule['test_source_path']]
    method=e['selection'].split('#')[1]
    blob=subprocess.check_output(['git','show',source+':'+rule['test_source_path']],cwd='/workspace/InkWeft').decode()
    assert re.search(r'fun\s+'+re.escape(method)+r'\s*\(',blob)
    assert e['args'].count(e['selection'])==1 and e['expected_junit_tests']==1
    assert e['identity']=='C1' and e['install_group']=='C1-data-diagnostics'
for n,mode in [(6,'same-room'),(7,'separate-room')]:
    rule=contract['methods'][str(n)];assert rule['connection']==mode and rule['kind']=='gc-boundary'
    assert rule['test_source_sha256']==a.PRODUCER_SHA256
    assert rule['markers'][a.MARKERS[0]]['min_count']==30 and rule['markers'][a.MARKERS[0]]['max_count']==330
    assert rule['markers'][a.MARKERS[1]]['min_count']==rule['markers'][a.MARKERS[1]]['max_count']==30
    assert rule['markers'][a.MARKERS[2]]['min_count']==rule['markers'][a.MARKERS[2]]['max_count']==1
record('source Git blobs /8 exact method selections /GC mode and counts',{'source':source,'blobs':source_checks})
assert auth['approved'] is False and auth['status']=='UNBOUND' and auth['plan_git']=='UNBOUND'
assert contract['status']=='UNBOUND'
workflow=(C/'workflow.proposed.yml').read_text()
assert workflow.count('0'*40)==1
assert "FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in (C/'run.py').read_text()
record('inactive authorization/zero guard/source-spec remain UNBOUND')
assert inputs=={p.name:sha(p) for p in C.iterdir() if p.is_file()},'controller changed during checks'
result={'status':'PASS','scope':'READ_ONLY_GC_CONTRACT_REVIEW_SYNTHETIC_ONLY','checks':results,'checkCount':len(results),'controllerInputSha256':inputs,'analyzerSha256':sha(A/'analyze.py'),'nativeExecutions':0,'networkCalls':0,'sourceEdits':0,'limitations':['No runtime activation, native performance or combined artifact budget acceptance.','Controller GC core copies v2 semantics; added strict wrapper rejects scalar type coercions.','Full failure transport and joint budget are assigned to a separate reviewer.']}
(HERE/'checks.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'status':'PASS','checks':len(results),'markersSha256':inputs['markers.py']}))
