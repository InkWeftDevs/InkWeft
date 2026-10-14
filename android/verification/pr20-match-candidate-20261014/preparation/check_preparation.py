"""Offline checks only: pure AST/parser, synthetic command stubs and temporary text packaging."""
import argparse,ast,copy,datetime,hashlib,importlib.util,json,math,os,re,shutil,subprocess,sys,tempfile,time,traceback,xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
R=Path(__file__).resolve().parent;C=R/'control-template/ci/pr20-kvm-triple';F=R/'readiness-template';RAW=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw');OLD=Path('/workspace/InkWeft-native-candidate3')
P=argparse.ArgumentParser();P.add_argument('--out',required=True,type=Path);P.add_argument('--control',type=Path);P.add_argument('--readiness',type=Path);a=P.parse_args();assert not a.out.exists();a.out.mkdir(parents=True)
if a.control:C=a.control
if a.readiness:F=a.readiness
checks=[];failures=[]
def check(name,fn):
 try:detail=fn();checks.append({'name':name,'status':'PASS','detail':detail})
 except Exception as e:failures.append(name);checks.append({'name':name,'status':'FAIL','error':repr(e),'traceback':traceback.format_exc()})
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def load(p):return json.loads(p.read_text())
def module(p):
 spec=importlib.util.spec_from_file_location('match_markers',p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
M=module(C/'markers.py');contract=load(F/'marker-contract.json');contract['status']='FROZEN';plan=load(F/'method-plan.json');text=(C/'run.py').read_text();tree=ast.parse(text)
def function(name):return next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name)
def pure(name,extra=None):
 scope=dict(re=re,json=json,math=math,hashlib=hashlib,Path=Path,ET=ET,time=time,os=os,copy=copy,datetime=datetime,subprocess=subprocess)
 scope.update(extra or {});exec(compile(ast.Module(body=[function(name)],type_ignores=[]),str(C/'run.py'),'exec'),scope);return scope[name]
def rejects(fn):
 try:fn()
 except (AssertionError,ValueError,TypeError,KeyError,RecursionError):return True
 raise AssertionError('Invalid input accepted')
def invariants():
 old=load(OLD/'android/verification/pr20-candidate3-readiness-20261014/method-plan.json')
 assert plan['steps'][:58]==old['steps'];assert [s['ordinal']for s in plan['steps']]==list(range(1,63))
 assert Counter(s['phase']for s in plan['steps'])=={'correctness':31,'common-comparison':24,'candidate-diagnostics':7}
 assert len({s['install_group']for s in plan['steps']})==14
 cp=load(Path('/workspace/InkWeft/android/verification/pr20-causal-20261014/method-plan.json'))['steps'][7]
 assert plan['steps'][58]['args']==cp['args'];assert plan['steps'][58]['selection']==cp['selection']
 oldtree=ast.parse((OLD/'ci/pr20-kvm-triple/run.py').read_text())
 for name in ['seconds','run','processes_after','common_fixtures','official_dependency_check','validate_clock','start_emulator','host','environment']:
  oldnode=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name)
  assert ast.dump(oldnode)==ast.dump(function(name)),name+' changed'
 assert sha(C/'cleanup.py')==sha(OLD/'ci/pr20-kvm-triple/cleanup.py')
 assert '--no-build-cache' in text and '--max-workers=1' in text
 assert ("FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in text or sha(F/'source-spec.json') in text) and load(C/'authorization.json')['approved'] is False
 assert '0000000000000000000000000000000000000000' in (C/'workflow.proposed.yml').read_text()
 assert sha(F/'original-protocol.json')==sha(OLD/'android/verification/pr20-candidate3-readiness-20261014/original-protocol.json')
 return 'old1..58 exact;62/14groups;unchanged budget/helper/commands/cleanup;UNBOUND guard'
check('scope_and_unchanged_functions',invariants)


def current_scope_metadata():
    auth=load(C/'authorization.json');budget=load(C/'budget-revision.json')
    method_sha=sha(F/'method-plan.json')
    def validate(auth,budget):
        assert budget['phase_budget_amendment']['method_plan_sha256']==method_sha
        scope=auth['phase_budget_override']['scope_reference']
        assert 'original methods1-58' in scope and 'four candidate-only correctness methods59-62' in scope
        source=budget['phase_budget_amendment']['authorization_source']
        assert 'matching and frame-local lazyPencil candidate' in source
        assert 'append59Unicode/60NativeReuse/61Frame/62dense' in source
    validate(auth,budget)
    for variant in range(3):
        a=copy.deepcopy(auth);b=copy.deepcopy(budget)
        if variant==0:b['phase_budget_amendment']['method_plan_sha256']='e1d02f73ed8b417949282a729de8133f16deb2fda47a6ae5ec9f2f3671da9346'
        elif variant==1:a['phase_budget_override']['scope_reference']='changes identities and one correctness method'
        else:b['phase_budget_amendment']['authorization_source']='additional SQL-boundary cancellation candidate'
        rejects(lambda:validate(a,b))
    return {'live_scope_fields':3,'stale_field_negative_cases':3,'source_or_budget_change':False}
check('current_scope_metadata_bindings',current_scope_metadata)
# Historical raw is input only. No synthesis is mislabelled as a new native run.
for entry in plan['steps'][:58]:
 def oldraw(entry=entry):
  raw=(RAW/f"method-{entry['ordinal']:02d}-{entry['identity']}-instrumentation.log").read_text()
  got=M.safe_marker_payloads(entry,raw,contract)
  assert [{'marker':p['marker'],'data':p['data']}for p in got]==load(RAW/f"method-{entry['ordinal']:02d}-{entry['identity']}-records.json")
  e=copy.deepcopy(entry);pure('raw_result')(e,raw,0);assert e['junit_status']=='PASS'
  return len(got)
 check('historical_raw_'+str(entry['ordinal']),oldraw)
check('historical_frame_payload',lambda:len(M.safe_marker_payloads(plan['steps'][60],Path('/workspace/scratch/pr20-closure-evidence/candidate-raster-hex-v1-frame-targeted.log').read_text(),contract)))

def native_actual():
    raw=Path('/workspace/scratch/pr20-closure-evidence/candidate-raster-hex-v1-native-reuse-targeted.log').read_text()
    got=M.safe_marker_payloads(plan['steps'][59],raw,contract)
    assert len(got)==4 and [p['data']['nativeStrokeBuilds']for p in got]==[2,2,1,0]
    # Malformed NATIVE records must not be silently ignored by either marker parser.
    for suffix in ['NATIVE_STROKE_TILE_EVIDENCE []','NATIVE_STROKE_TILE_EVIDENCE {','NATIVE_UNKNOWN {}']:
        rejects(lambda:M.safe_marker_payloads(plan['steps'][59],raw+'\n'+suffix,contract))
    return 'Historical NativeReuse raw shape only; no new native execution'
check('historical_native_oracle_markers',native_actual)
def native_relations():
    raw=Path('/workspace/scratch/pr20-closure-evidence/candidate-raster-hex-v1-native-reuse-targeted.log').read_text()
    values=M.safe_marker_payloads(plan['steps'][59],raw,contract)
    for mode in ['duplicate-phase','wrong-native-count','no-oracle-reuse','false-pixels','unknown-field']:
        bad=copy.deepcopy(values)
        if mode=='duplicate-phase':bad[1]['data']['phase']=0
        elif mode=='wrong-native-count':bad[0]['data']['nativeStrokeBuilds']=1
        elif mode=='no-oracle-reuse':bad[0]['data']['perTileReferenceBuilds']=2
        elif mode=='false-pixels':bad[0]['data']['pixelsEqual']=False
        else:bad[0]['data']['unexpected']='not-publishable'
        badraw='\n'.join(p['marker']+' '+json.dumps(p['data'])for p in bad)
        rejects(lambda:M.safe_marker_payloads(plan['steps'][59],badraw,contract))
    e=copy.deepcopy(plan['steps'][59]);p=e['selection'].split('#')
    raw='\n'.join(x['marker']+' '+json.dumps(x['data'])for x in values)
    synthetic='INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: class='+p[0]+'\nINSTRUMENTATION_STATUS: test='+p[1]+'\nINSTRUMENTATION_STATUS_CODE: 1\n'+raw+'\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: class='+p[0]+'\nINSTRUMENTATION_STATUS: test='+p[1]+'\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
    # Old -w only archived log has no -r status tuples; this wrapper is explicitly synthetic.
    records=pure('raw_result')(e,synthetic,0)
    assert e['junit_status']=='PASS'
    assert len([x for x in records if x['marker']=='NATIVE_STROKE_TILE_EVIDENCE'])==4
    return {'fault_cases':5,'raw_parser_native_records':4,'wrapper':'SYNTHETIC_NOT_NATIVE'}
check('native_oracle_relations_and_raw_prefix',native_relations)

# Successful dense fixture: exact producer fields, synthetic timing values only.
num=lambda rows:{'frameElapsedMs':1.0,'rows':rows,'points':2,'freezeMs':0.1,'decodeMs':0.1}
stats='BuildStats(tiles=1, rebuilt=1, decoded=1201, bitmapBytes=800000, metadataMs=1.0, rasterMs=1.0, publicationMs=1.0, reusedStrokes=0, geometryBytes=0, nativeStrokeBuilds=1201)'
dense=[]
for stage in ['fixture-start','fixture-complete','editor-window-complete','frame-start','frame-complete','verified-and-released']:
 d={'case':'original-dense','stage':stage,'elapsedMs':1.0,'fixtureStrokes':1201,'fixturePoints':76802,'frameDeadlineMs':30000}
 if stage=='editor-window-complete':d.update(windowStrokes=128,windowPoints=8192)
 if stage=='frame-complete':d.update(frameElapsedMs=1.0,snapshotReads=[num(256)]*4+[num(177)],snapshotRows=1201,changeCallbacks=1,publishedStats=stats)
 dense.append('STREAMED_FRAME_STAGE '+json.dumps(d,separators=(',',':')))
check('synthetic_dense_success_shape',lambda:len(M.safe_marker_payloads(plan['steps'][61],'\n'.join(dense),contract)))
# The frozen Unicode schema is the exact producer contract, no replacement implementation.
uc=load(Path('/workspace/InkWeft/android/verification/pr20-causal-20261014/marker-contract.json'))
check('unicode_schema_exact',lambda: contract['schemas']['LIBRARY_CAUSAL_UNICODE']==uc['schemas']['LIBRARY_CAUSAL_UNICODE'] or (_ for _ in()).throw(AssertionError('Unicode schema changed')))
entry=plan['steps'][32];raw=(RAW/'method-33-B-instrumentation.log').read_text()
for suffix in ['INDEX_SAVE_PROGRESS []','LIBRARY_READ_EVIDENCE {"coldMs":','INDEX_SAVE_PROGRESS','KVM_UNKNOWN []','INDEX_SAVE_PROGRESS {"rows":NaN,"elapsedMs":0}','INDEX_SAVE_PROGRESS {"rows":64,"rows":64,"elapsedMs":0}']:
 check('reject_marker_'+str(len(checks)),lambda suffix=suffix:rejects(lambda:M.safe_marker_payloads(entry,raw+'\n'+suffix,contract)))
for label,bad in [('missing',re.sub(r'^.*LIBRARY_READ_EVIDENCE .*\n?','',raw,flags=re.M)),('duplicate',raw+'\n'+next(x for x in raw.splitlines()if 'LIBRARY_READ_EVIDENCE 'in x)),('oversize',raw+'\nINDEX_SAVE_PROGRESS '+(' '*65537)),('unexpected_in_zero','LIBRARY_READ_EVIDENCE {}')]:
 check('reject_'+label,lambda bad=bad,label=label:rejects(lambda:M.safe_marker_payloads(plan['steps'][0]if label=='unexpected_in_zero'else entry,bad,contract)))
# Strict JUnit status/identity and interrupted JSON.
for name,change in [('bad_code',lambda x:x.replace('INSTRUMENTATION_STATUS_CODE: 1','INSTRUMENTATION_STATUS_CODE: 1junk',1)),('extra_terminal',lambda x:x+'\nINSTRUMENTATION_CODE: -1'),('failed',lambda x:x+'\nFAILURES!!!'),('skipped',lambda x:x.replace('OK (1 test)','OK (0 tests)')),('wrong_class',lambda x:x.replace(entry['selection'].split('#')[0],'other.Class')),('bad_final',lambda x:x.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: -1junk'))]:
 def fault(change=change):
  e=copy.deepcopy(entry);pure('raw_result')(e,change(raw),0);assert e['junit_status']=='FAIL';return True
 check('junit_'+name,fault)

def source_bindings():
    if not (F/'C1-source-manifest.json').exists():return 'Source is intentionally unbound'
    manifests={k:load(F/(k+'-source-manifest.json'))for k in ['B','C0','C1']}
    assert load(F/'source-spec.json')['identities']['C1']['git']=='cb7e14218ac83aa8cf6b9d7906051a10da1a6547'
    for e in plan['steps']:
        r=contract['methods'][str(e['ordinal'])]
        assert r['test_source_sha256']==manifests[e['identity']]['files'][r['test_source_path']]['sha256']
    for k,m in manifests.items():assert hashlib.sha256(json.dumps(m['files'],sort_keys=True,separators=(',',':')).encode()).hexdigest()==m['canonical_manifest_sha256']
    assert len(load(F/'candidate-source-delta.json')['production_paths'])==7
    return {'test_bindings':62,'manifest_files':{k:len(m['files'])for k,m in manifests.items()},'production_delta':7}
check('source_bindings',source_bindings)

def unicode_actual():
    values=load(Path('/workspace/scratch/pr20-causal-execution-20261014/extracted/method-08-C1-records.json'))
    raw='\n'.join(x['marker']+' '+json.dumps(x['data'],ensure_ascii=False,separators=(',',':'))for x in values)
    assert len(M.safe_marker_payloads(plan['steps'][58],raw,contract))==1
    return 'Re-serialized prior actual causal8 record for offline schema compatibility only'
check('prior_actual_unicode_shape',unicode_actual)

def sufficient_gc_shape():
    values=copy.deepcopy(load(RAW/'method-56-C1-records.json'));trials=[x['data']for x in values if x['marker']=='HISTORY_DELETION_EXPOSURE_TRIAL'];summary=values[-1]['data']
    for t in trials[:20]:
        t['deletionTransactionExposed']=True;t['coveredBeforeBatchCommitNs']=[t['saveStartNs']];t['phases'].append({'phase':'before-batch-commit','atNs':t['saveStartNs']})
    numbers=[t['saveMs']for t in trials[:20]];ordered=sorted(numbers)
    summary['exposedSaves']={'count':20,'sampleIndices':list(range(20)),'saveSamplesMs':numbers,'maxMs':max(numbers),'p50Ms':ordered[9],'p95Ms':ordered[18]};summary['exposureStatus']='SUFFICIENT_EXPOSURE'
    raw='\n'.join(x['marker']+' '+json.dumps(x['data'],separators=(',',':'))for x in values)
    assert len(M.safe_marker_payloads(plan['steps'][55],raw,contract))==31
    return 'SYNTHETIC20-exposure positive schema test, not observed native evidence'
check('synthetic_gc_sufficient_exposure',sufficient_gc_shape)

def statistics_unchanged():
    old=ast.parse((OLD/'android/verification/pr20-candidate3-readiness-20261014/analyze.py').read_text())
    new=ast.parse((F/('analyze.py'if(F/'analyze.py').exists()else'analyze.template.py')).read_text())
    for name in ['quantiles','classify']:
        get=lambda tree:next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name)
        assert ast.dump(get(old))==ast.dump(get(new))
    for name in ['SEEDS','IDENTITIES','COMPARISONS','METRICS']:
        get=lambda tree:[ast.dump(n)for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id==name for t in n.targets)]
        assert get(old)==get(new),name
    return 'Original quantiles/classification/metric and comparison definitions unchanged'
check('statistics_unchanged',statistics_unchanged)


def install_hash_cases():
    packages=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'];results=[]
    for mode in ['valid','source-mismatch','installed-mismatch','unexpected-location','split-apk','install-failed']:
        root=a.out/('install-'+mode);out=root/'evidence';out.mkdir(parents=True);ident='C1';values=[]
        for i,package in enumerate(packages):
            path=f'fixture-{i}.apk';file=root/'sources'/ident/'android'/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(('SYNTHETIC_NOT_AN_APK_'+str(i)).encode())
            values.append({'package':package,'path':path,'sha256':sha(file)})
        (out/'apk-C1.json').write_text(json.dumps({'apks':values}));(out/'device-before.json').write_text('{}')
        calls=[];report={'install_groups':{}}
        def capture(argv,limit):
            calls.append(argv)
            if argv[3]=='install':return 'Failure'if mode=='install-failed'else'Success'
            if argv[3:6]==['shell','pm','path']:
                if mode=='unexpected-location':return 'package:/private/other/base.apk'
                if mode=='split-apk':return 'package:/data/app/test/base.apk\npackage:/data/app/test/split.apk'
                return 'package:/data/app/'+argv[-1]+'/base.apk'
            assert argv[3:5]==['shell','sha256sum'];package=argv[-1].split('/')[-2];digest=next(x['sha256']for x in values if x['package']==package)
            return ('0'*64 if mode=='installed-mismatch'else digest)+'  '+argv[-1]
        def fake_run(argv,*args,**kwargs):
            assert argv[3:6]==['shell','pm','path'];return subprocess.CompletedProcess(argv,1,'','')
        def digest(path):return '0'*64 if mode=='source-mismatch'else sha(path)
        scope={'ROOT':root,'OUT':out,'REPORT':report,'SPEC':{'identities':{'C1':{'canonical_manifest_sha256':'a'*64}}},'PACKAGES':packages,'ADB':['adb','-s','emulator-5554'],'save':lambda:None,'environment':lambda:{},'host':lambda:{},'run':fake_run,'capture':capture,'digest':digest,'write_json':lambda p,v:p.write_text(json.dumps(v))}
        install=pure('install',scope)
        try:install('C1-diagnostics','C1');assert mode=='valid'
        except AssertionError:assert mode!='valid'
        if mode=='valid':assert [x['sha256']for x in report['install_groups']['C1-diagnostics']['installed_apks']]==[x['sha256']for x in values]
        results.append(mode)
    return {'cases':results,'actual_adb_calls':0}
check('installed_apk_hash_readback',install_hash_cases)

def manifest_cases():
    validate=pure('validate_instrumentation_manifest')
    xml='    E: instrumentation (line=1)\n      A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner"\n      A: android:targetPackage(0x01010021)="org.inkweft.app.a0"\n'
    assert validate(xml,'org.inkweft.app.a0')['targetPackage']=='org.inkweft.app.a0'
    for bad in ['',xml+xml,xml.replace('org.inkweft.app.a0','wrong.package'),xml.replace('AndroidJUnitRunner','WrongRunner')]:rejects(lambda:validate(bad,'org.inkweft.app.a0'))
    return {'valid':1,'rejected':4,'aapt_calls':0}
check('xmltree_manifest_validation',manifest_cases)

from offline_cases import run_checks
check('extended_actual_controller_cases',lambda:run_checks(C,F,a.out/'extended'))
# Always write the receipt, even when a schema case fails.
files=[p for p in C.iterdir()if p.is_file()]+[p for p in F.iterdir()if p.is_file()]+[Path(__file__),R/'offline_cases.py']
report={'status':'PASS'if not failures else'FAIL','scope':'OFFLINE_PREPARATION_ONLY_NO_NEW_NATIVE_EXECUTION','checks':checks,'failures':failures,'inputs':[{'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}for p in files]}
(a.out/'preflight.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'status':report['status'],'checks':len(checks),'failures':failures,'out':str(a.out)}));sys.exit(bool(failures))
