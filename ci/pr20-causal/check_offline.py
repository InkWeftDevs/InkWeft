"""Offline faults only. No network, Gradle, ADB, emulator or permission operations."""
import argparse,ast,copy,hashlib,io,json,math,os,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
from pathlib import Path
import yaml
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);p.add_argument('--controller',type=Path,default=Path(__file__).resolve().parent);p.add_argument('--readiness',type=Path);p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));a=p.parse_args();here=a.controller.resolve()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists();a.out.mkdir(parents=True)
sys.path.insert(0,str(here));import markers,size_bounds
checks=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def reject(f):
 try:f()
 except (AssertionError,ValueError,TypeError,KeyError,RecursionError,RuntimeError):return
 raise AssertionError('Fault accepted')
def check(name,f):
 value=f();checks.append({'name':name,'status':'PASS','details':value})
plan=json.loads(((a.readiness/'method-plan.json')if a.readiness else (here/'method-plan.template.json')).read_text());contract=json.loads(((a.readiness/'marker-contract.json')if a.readiness else (here/'marker-contract.template.json')).read_text());contract['status']='FROZEN'
source=(here/'run.py').read_text();tree=ast.parse(source)
ns={'re':re,'json':json,'math':math,'Counter':Counter,'Path':Path,'hashlib':hashlib,'time':time,'os':os,'subprocess':subprocess,'ET':ET,'sys':sys,'shutil':shutil}
for n in tree.body:
 if isinstance(n,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([n],type_ignores=[]),'actual-run-ast','exec'),ns)
ns.update(MARKERS=contract,ADB=['adb','-s','emulator-5554'],PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'])
old=Path('/workspace/InkWeft/ci/pr20-search-screen')
def static():
 for f in here.glob('*.py'):ast.parse(f.read_text())
 assert (here/'cleanup.py').read_bytes()==(old/'cleanup.py').read_bytes()
 assert 'assert re.fullmatch' in source
 if a.readiness:
  auth=json.loads((here/'authorization.json').read_text());assert auth['approved'] is True and auth['status']=='FROZEN'
  for key,file in [('approved_plan_sha256','PLAN.md'),('source_spec_sha256','source-spec.json'),('method_plan_sha256','method-plan.json'),('original_protocol_sha256','original-protocol.json'),('marker_contract_sha256','marker-contract.json'),('prepare_sources_sha256','prepare_sources.py')]:
   assert auth[key]==sha(a.readiness/file)
   assert subprocess.check_output(['git','-C',str(a.repo),'show',auth['plan_git']+':android/verification/pr20-causal-20261014/'+file])==(a.readiness/file).read_bytes()
  assert auth['source_spec_sha256'] in source
 else:
  assert "FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in source
  result=subprocess.run([sys.executable,here/'run.py','setup'],capture_output=True,text=True);assert result.returncode!=0 and 'Finalize source identity' in result.stderr
 assert '--no-build-cache' in source and all(x in source for x in [':app:assembleDebug',':app:assembleDebugAndroidTest',':data-local:assembleDebugAndroidTest'])
 assert [s['ordinal']for s in plan['steps']]==list(range(1,14)) and Counter(s['phase']for s in plan['steps'])=={'data-diagnostics':12,'raster-diagnostics':1}
 assert len({s['install_group']for s in plan['steps']})==2 and {s['identity']for s in plan['steps']}=={'C1'}
 assert [s['ordinal']for s in plan['steps']if s['outer_cap_seconds']==300]==[10]
 assert [s['ordinal']for s in plan['steps']if s['marker_policy']=='none']==list(range(1,8))
 oldtree=ast.parse((old/'run.py').read_text());copied=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','junit','raw_result']
 # raw_result deliberately adds RASTER to marker discovery; strict JUnit unchanged.
 for name in copied[:-1]:
  now=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name);before=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name);assert ast.dump(now)==ast.dump(before),name
 proc=ast.unparse(next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='processes_after'))
 assert 'force-stop' not in re.findall(r"'([^']+)'",proc) and "'kill'" not in proc
 return {'methods':13,'groups':2,'source_identities':1,'apk_count':3,'execution_binding':'REAL_READINESS_MATCH' if a.readiness else 'UNBOUND_REFUSED','copied_functions':copied[:-1],'cleanup_byte_identical':True}
check('unbound isolation and exact scope',static)
def workflow():
 value=yaml.safe_load((here/'workflow.proposed.yml').read_text());job=value['jobs']['causal'];assert job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04' and value['permissions']=={'contents':'read'}
 assert 'github.run_attempt == 1' in job['if'] and 'github.event.created == false' in job['if'] and len(re.findall('[0-9a-f]{40}',job['if']))==1
 count=0
 for i,step in enumerate(job['steps']):
  if 'run'not in step:continue
  f=a.out/f'step-{i:02}.sh';f.write_text(step['run']);subprocess.run(['bash','-n',f],check=True,capture_output=True)
  for _,code in re.findall(r"python3 - <<'([^']+)'\n(.*?)\n\1",step['run'],re.S):ast.parse(code)
  if 'run.py' in step['run']:assert 'timeout --signal=TERM --kill-after=2' in step['run'];count+=1
 assert count==5
 lint=subprocess.run(['/workspace/scratch/actionlint-1.7.12/actionlint','-shellcheck=','-pyflakes=',here/'workflow.proposed.yml'],capture_output=True,text=True);(a.out/'actionlint.log').write_text(lint.stdout+lint.stderr);assert lint.returncode==0,lint.stderr
 root=a.out/'clock';root.mkdir();envfile=root/'env';envfile.touch();env=os.environ.copy();env.update(RUNNER_TEMP=str(root),GITHUB_ENV=str(envfile))
 command=job['steps'][0]['run'];first=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert first.returncode==0,first.stderr
 values=dict(line.split('=',1)for line in envfile.read_text().splitlines());assert len(values)==9 and all('\\n'not in x for x in values.values())
 clock=json.loads((root/'pr20-causal/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,45,50,55,60] and clock['scope']=='DIAGNOSTIC_ONLY'
 before=envfile.read_bytes();again=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert again.returncode!=0 and envfile.read_bytes()==before
 return {'actionlint':0,'phase_wrappers':5,'actual_clock_lines':9,'second_clock_rejected':True}
check('workflow syntax and isolated real first-step clock',workflow)
def budget():
 fake=types.SimpleNamespace(time=lambda:1000.0);ns.update(time=fake,START=1000,PHASE='setup')
 for name in ['CUTOFFS','DEADLINE']:
  n=next(n for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id==name for t in n.targets));exec(compile(ast.Module([n],type_ignores=[]),'actual-cutoffs','exec'),ns)
 assert ns['CUTOFFS']=={'setup':2800,'data':3700,'raster':4000,'work':4300}
 for phase,end in ns['CUTOFFS'].items():
  ns['PHASE']=phase
  for offset in [-.01,0,.01]:
   fake.time=lambda end=end,offset=offset:end+offset
   if offset<0:assert 0<ns['seconds']()<=.011
   else:reject(ns['seconds'])
 ns['time']=time;return {'phase_boundaries':12}
check('phase/work absolute deadline boundaries',budget)
def rawchecks():
 raw=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw');prior=json.loads((raw/'summary.json').read_text())
 for e in prior['methods']:
  text=(raw/f"method-{e['ordinal']:02}-{e['identity']}-instrumentation.log").read_text();test=copy.deepcopy(e);ns['raw_result'](test,text,0);assert test['junit_status']=='PASS'
 e=prior['methods'][0];text=(raw/f"method-{e['ordinal']:02}-{e['identity']}-instrumentation.log").read_text()
 faults=[(text,1),(text+'\nOK (1 test)\n',0),(text.replace('OK (1 test)','OK (2 tests)'),0),(text+'\nINSTRUMENTATION_STATUS_CODE: 0junk\n',0),(text+'\nINSTRUMENTATION_CODE: -1junk\n',0),(text.replace('INSTRUMENTATION_CODE: -1',''),0),(text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3',1),0),(text+'\nFAILURES!!!\n',0),(text.replace('numtests=1','numtests=2'),0),(text[:text.index('OK (1 test)')],None)]
 for value,rc in faults:
  test=copy.deepcopy(e);ns['raw_result'](test,value,rc);assert test['junit_status']!='PASS'
 return {'prior_raw_junit':58,'failure_or_skipped_variants':len(faults),'new_native':0}
check('strict original JUnit parser and skipped/fault cases',rawchecks)

def specimen(s):
 if 'enum'in s:return s['enum'][0]
 if 'const'in s:return s['const']
 kind=s['type']
 if isinstance(kind,list) and 'null'in kind:return None
 if kind=='object':return {k:specimen(s['properties'][k])for k in s['required']}
 if kind=='array':return [specimen(s['items'])for _ in range(s['minItems'])]
 if kind=='string':return '0'*64 if '64' in s['pattern'] else ('SCAN n' if 'MATERIALIZE'in s['pattern'] else '')
 if kind in ['integer','number']:return s.get('minimum',0)
 if kind=='boolean':return False
 return None

def fixtures():
 out={};S=contract['schemas']
 for entry in plan['steps']:
  i=entry['ordinal'];records=[]
  if i==8:records=[('LIBRARY_CAUSAL_UNICODE',specimen(S['LIBRARY_CAUSAL_UNICODE']))]
  if i==9:
   v=specimen(S['LIBRARY_CAUSAL_WARM']);records=[('LIBRARY_CAUSAL_WARM',v)]
  if i==10:
   modes=['IDLE','TRACKER','INDEX_ONLY','INDEX_TRACKER','READS_ONLY','FULL']
   for block in range(2):
    for mode in modes if block==0 else modes[::-1]:
     r=specimen(S['LIBRARY_SAVE_CAUSAL_RESULT']);r.update(mode=mode,block=block,seed=4101+block)
     for n,save in enumerate(r['saves']):save.update(sample=n,startNs=n*100,endNs=n*100+1,elapsedMs=.000001)
     t=specimen(S['LIBRARY_SAVE_CAUSAL_STAGES']);t.update(mode=mode,block=block,part=0)
     e=specimen(S['LIBRARY_SAVE_CAUSAL_END']);e.update(mode=mode,block=block,recordedEvents=len(t['events']))
     records += [('LIBRARY_SAVE_CAUSAL_RESULT',r),('LIBRARY_SAVE_CAUSAL_STAGES',t),('LIBRARY_SAVE_CAUSAL_END',e)]
  if i in [11,12]:
   connection=contract['methods'][str(i)]['connection'];trials=[]
   for n in range(30):
    t=specimen(S['HISTORY_GC_CAUSAL_TRIAL']);t.update(sample=n,connection=connection,status='PASS',committed=True,saveOutcome='COMMITTED',contentVerified=True,authorReadyNs=1,notificationNs=2,saveStartNs=3,saveEndNs=4,saveMs=.000001,deletionTransactionExposed=False)
    trials.append(t);records.append(('HISTORY_GC_CAUSAL_TRIAL',t))
   v=specimen(S['HISTORY_GC_CAUSAL_SUMMARY']);v.update(connection=connection,functionalStatus='PASS',recordedTrials=30,passedTrials=30,committedSaves=30,failedTrials=0,unknownCommitOutcomes=0,notStartedSaves=0)
   v['allSaves'].update(count=30,sampleIndices=list(range(30)),saveSamplesMs=[.000001]*30,maxMs=.000001,p50Ms=.000001,p95Ms=.000001)
   v['exposedSaves'].update(count=0,sampleIndices=[],saveSamplesMs=[],maxMs=None,p50Ms=None,p95Ms=None)
   records.append(('HISTORY_GC_CAUSAL_SUMMARY',v))
  if i==13:
   for n in [1,4,8]:
    v=specimen(S['RASTER_CAUSAL_DIAGNOSTIC']);v['seed']=4101;v['joined']=True;v['result'].update(layers=n,pixelsEqual=True,initialStrokes=320,finalStrokes=321)
    f=specimen(S['RASTER_CAUSAL_DIAGNOSTIC']['properties']['frames']['items']);f['timesNanos'].update(WORKER_FINALLY=1,READY_DRAW_END=2);f['timesNanos'].pop('FAILED',None);f['threadIds']={k:1 for k in f['timesNanos']}
    v['frames']=[copy.deepcopy(f)for _ in range(n*5)];records.append(('RASTER_CAUSAL_DIAGNOSTIC',v))
  out[i]='\n'.join(marker+' '+json.dumps(v,separators=(',',':'))for marker,v in records)
 return out
fixture_raw=fixtures()
def markerchecks():
 for e in plan['steps']:markers.safe_marker_payloads(e,fixture_raw[e['ordinal']],contract)
 e=plan['steps'][7];valid=fixture_raw[8];negative=[valid+'\nLIBRARY_CAUSAL_UNICODE []',valid+'\nLIBRARY_CAUSAL_UNICODE {',valid+'\nLIBRARY_CAUSAL_UNICODE',valid+'\nHISTORY_ARBITRARY []',valid.replace('"schemaVersion":1','"schemaVersion":NaN'),valid.replace('"schemaVersion":1','"schemaVersion":1,"schemaVersion":1'),valid.replace('"queries":8','"queries":9'),valid.replace('"queries":8','"queries":8,"secret":"arbitrary"'),'',valid+'\n'+valid]
 for text in negative:reject(lambda text=text:markers.safe_marker_payloads(e,text,contract))
 reject(lambda:markers.safe_marker_payloads(plan['steps'][0],valid,contract))
 reject(lambda:markers.safe_marker_payloads(plan['steps'][9],fixture_raw[10].replace('"droppedEvents":0','"droppedEvents":1',1),contract))
 for marker,schema in contract['schemas'].items():
  v=specimen(schema);v['unexpected']='bad';reject(lambda:markers.validate_shape(v,schema))
 # Verify query-plan patterns accept real-shaped identifiers, not URLs or arbitrary text.
 schema=contract['schemas']['LIBRARY_CAUSAL_WARM']['properties']['productionBefore']['properties']['queryPlan']['items']
 for v in ['SCAN n','SEARCH n USING INDEX sqlite_autoindex_notes_1 (id=?)']:markers.validate_shape(v,schema)
 for v in ['https://example.invalid/secret','SCAN private_notes']:reject(lambda v=v:markers.validate_shape(v,schema))
 return {'synthetic_complete_blocks':13,'malformed_unknown_duplicate_nonfinite_missing_extra_negatives':12,'closed_object_schemas':len(contract['schemas']),'query_plan_positive':2,'query_plan_negative':2}
check('closed causal marker schemas and whole-block fault refusal',markerchecks)
def fixture_source_contracts():
 counts=contract['schemas']['LIBRARY_SAVE_CAUSAL_RESULT']['properties']['fixture']['properties']['rowCounts']
 # Source construction: 500 * 40 pages plus WorkspaceRepository.create(writer)'s one initial page.
 actual={'notes':501,'note_revisions':501,'notebook_workspace':501,'notebook_pages':500*40+1,'page_search_text':20000,'library_search_pending':20000}
 markers.validate_shape(actual,counts);wrong=dict(actual,notebook_pages=20000);reject(lambda:markers.validate_shape(wrong,counts))
 enum=contract['schemas']['LIBRARY_SAVE_CAUSAL_STAGES']['properties']['events']['items']['properties']['stage']
 source_root=Path('/workspace/InkWeft/android/data-local/src/main/kotlin/org/inkweft/data')
 observer=(source_root/'LibrarySearchObservation.kt').read_text();repository=(source_root/'LibrarySearchRepository.kt').read_text()
 stages=set(re.findall(r'"(observe-[a-z-]+)"',observer)+['index-build-completed'])
 stages.update(re.findall(r'trace\?\.event\("([a-z-]+)"',repository))
 for name in stages:markers.validate_shape(name,enum)
 assert enum['maxLength']==max(map(len,enum['enum']))
 return {'explicit_source_fixture_pages':20001,'pages20000_refused':True,'real_full_observe_stage_literals':sorted(stages)}
check('actual fixture writer page and FULL event constant coverage',fixture_source_contracts)

def processchecks():
 saved={k:ns.get(k)for k in ['run','time','seconds']};cases=[]
 for mode in ['gone','app_alive','test_alive','transport']:
  clock=[0.0];calls=[];ns['time']=types.SimpleNamespace(monotonic=lambda:clock[0],sleep=lambda x:clock.__setitem__(0,clock[0]+max(x,.001)))
  def run(args,*pos,**kw):
   calls.append(args);clock[0]+=.01;package=args[-1];alive=(mode=='app_alive'and package=='org.inkweft.app.a0')or(mode=='test_alive'and package=='org.inkweft.app.a0.test')
   return subprocess.CompletedProcess(args,0 if alive else 1,'2717'if alive else '','bad'if mode=='transport'else'')
  ns.update(run=run,seconds=lambda limit=3600:limit);e={}
  if mode=='gone':ns['processes_after'](e);assert e['process_exit_outcome']=='NATURAL_EXIT'
  else:reject(lambda:ns['processes_after'](e));assert e['process_exit_outcome']=='UNCONFIRMED'
  assert all(args[3:5]==['shell','pidof']for args in calls);cases.append(mode)
 ns.update(saved);return cases
check('all-three package natural isolation and no cleanup fallback',processchecks)


def manifestchecks():
 xml=Path('/workspace/scratch/pr20-search-screen-native-20261014/local-aapt-xmltree.log').read_text()
 assert ns['validate_instrumentation_manifest'](xml,'org.inkweft.data.test')['targetPackage']=='org.inkweft.data.test'
 app=xml.replace('org.inkweft.data.test','org.inkweft.app.a0')
 assert ns['validate_instrumentation_manifest'](app,'org.inkweft.app.a0')['targetPackage']=='org.inkweft.app.a0'
 for value,target in [(xml,'org.inkweft.app.a0'),(app,'org.inkweft.data.test'),(xml.replace('androidx.test.runner.AndroidJUnitRunner','bad.Runner'),'org.inkweft.data.test'),(xml.replace('E: instrumentation','E: other'),'org.inkweft.data.test')]:
  reject(lambda value=value,target=target:ns['validate_instrumentation_manifest'](value,target))
 return {'prior_actual_data_manifest':1,'synthetic_app_target':1,'mismatches':4}
check('instrumentation manifest uses xmltree and exact target package',manifestchecks)

def installchecks():
 cases=[];saved={k:ns.get(k)for k in ['OUT','ROOT','SPEC','REPORT','capture','run','environment','host','digest','save']}
 for group in ['C1-data-diagnostics','C1-raster-diagnostics']:
  for case in ['match','wrong_hash','wrong_path']:
   root=a.out/(group+'-'+case);out=root/'evidence';out.mkdir(parents=True)
   apks=[]
   for i,package in enumerate(['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test']):
    f=root/'sources/C1/android'/f'{i}.apk';f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b'synthetic-not-Android'+str(i).encode());apks.append({'package':package,'path':f'{i}.apk','sha256':sha(f)})
   (out/'apk-C1.json').write_text(json.dumps({'apks':apks}));(out/'device-before.json').write_text('{}')
   ns.update(OUT=out,ROOT=root,SPEC={'identities':{'C1':{'canonical_manifest_sha256':'0'*64}}},REPORT={'install_groups':{}},environment=lambda:{},host=lambda:{},save=lambda:None,digest=sha)
   calls=[];current=[None]
   def capture(args,limit=30):
    calls.append([str(v)for v in args])
    if args[3]=='install':return 'Success'
    if args[3:6]==['shell','pm','path']:
     current[0]=next(v for v in apks if v['package']==args[-1]);return 'package:/data/app/abc/base.apk' if case!='wrong_path' else 'package:/system/not-ours.apk'
    if args[3:5]==['shell','sha256sum']:return (current[0]['sha256']if case!='wrong_hash'else'0'*64)+'  /data/app/abc/base.apk'
    raise AssertionError(args)
   ns['capture']=capture;ns['run']=lambda args,*pos,**kw:subprocess.CompletedProcess(args,1,'','')
   if case=='match':
    ns['install'](group,'C1');r=ns['REPORT']['install_groups'][group];assert r['status']=='INSTALLED' and len(r['installed_apks'])==(1 if group=='C1-data-diagnostics'else 2)
   else:reject(lambda:ns['install'](group,'C1'))
   cases.append({'group':group,'case':case,'commands':len(calls)})
 ns.update(saved);return cases
check('both install groups bind exact installed APK bytes; wrong path/hash stop',installchecks)

def incomplete_marker():
 entry=copy.deepcopy(plan['steps'][7]);entry['status']='NOT_RUN';target=a.out/'malformed-method';target.mkdir()
 cls,meth=entry['selection'].split('#')
 raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'+fixture_raw[8]+'\nLIBRARY_CAUSAL_UNICODE []\n'+f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
 saved={k:ns.get(k)for k in ['OUT','REPORT','run','processes_after','seconds','save','time']};ns.update(OUT=target,REPORT={'methods':[entry]},time=time,seconds=lambda limit=3600:limit,save=lambda:None)
 def run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
 ns['run']=run;ns['processes_after']=lambda e:e.update(process_exit_outcome='NATURAL_EXIT');output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](entry))
 assert entry['status']=='INCOMPLETE'and entry['junit_status']=='PASS'and entry['adb_exit']==0
 assert 'INKWEFT_CAUSAL_CONTEXT' not in output.getvalue()and'INKWEFT_CAUSAL_METHOD'in output.getvalue()
 assert (target/'method-08-C1-instrumentation.log').read_text()==raw
 ns.update(saved);return {'junit':'PASS','host':'INCOMPLETE','unvalidated_payload_forwarded':False,'raw_preserved':True}
check('malformed extra marker preserves raw JUnit PASS but host INCOMPLETE',incomplete_marker)

# Temporary fake identities support package fault tests only; never an execution receipt.
real_readiness=a.readiness
a.controller=here;a.readiness=a.out/'synthetic-readiness';a.readiness.mkdir()
spec={'status':'SYNTHETIC','identities':{'C1':{'git':'0'*40,'canonical_manifest_sha256':'0'*64}},'common_probes':[]}
for name,value in [('source-spec.json',spec),('method-plan.json',plan),('marker-contract.json',contract),('C1-source-manifest.json',{})]:
 (a.readiness/name).write_text(json.dumps(value)+'\n')
for name in ['PLAN.md','original-protocol.json','prepare_sources.py']:(a.readiness/name).write_text('synthetic offline package fixture only\n')
def packagecases():
 repo=a.out/'package-fixture/control';control=repo/'ci/pr20-causal';frozen=repo/'android/verification/pr20-causal-20261014';control.mkdir(parents=True);shutil.copytree(a.readiness,frozen)
 sourcehash=sha(frozen/'source-spec.json')
 for path in a.controller.iterdir():
  if path.is_file():(control/path.name).write_text(re.sub(r"FROZEN_SOURCE_SPEC_SHA256( ?= ?)'[^']+'",lambda m:'FROZEN_SOURCE_SPEC_SHA256'+m[1]+repr(sourcehash),path.read_text()))
 (control/'authorization.json').write_text('{}\n');(control/'EXECUTION_SHA256SUMS').write_text('synthetic offline package fixture; not activation\n')
 for probe in spec['common_probes']:
  dest=repo/probe['from_path'];dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(oldroot/probe['from_path'],dest)
 base={'run_id':'SYNTHETIC','workflow_sha':'0'*40,'input_hashes':{},'status':'MEASURING','actions':{},'phases':[],'install_groups':{},'methods':[dict(s,status='NOT_RUN') for s in plan['steps']]}
 cases=[]
 for name in ['running','installed','between_steps','pass_preserved','fail_preserved','notrun_preserved','cleanup_failure','all13_complete','all13_missing_raw','oversize','secret','excluded_binary','no_summary']:
  root=a.out/'package-fixture'/name;ev=root/'evidence';ev.mkdir(parents=True);report=copy.deepcopy(base)
  if name=='running':report['actions']['build-C1']={'status':'RUNNING','started_epoch':1};report['methods'][0]['status']='RUNNING'
  if name=='installed':report['install_groups']['C1-data-diagnostics']={'identity':'C1','status':'INSTALLED','started_epoch':1}
  if name=='pass_preserved':report['methods'][0]['status']='PASS';report['methods'][0]['junit_status']='PASS'
  if name=='fail_preserved':report['methods'][0]['status']='FAIL';report['methods'][0]['junit_status']='FAIL'
  if name in ['all13_complete','all13_missing_raw']:
   report['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING'
   for e in report['methods']:e.update(status='PASS',junit_status='PASS',adb_exit=0)
   report['install_groups']={e['install_group']:{'identity':e['identity'],'status':'PASS'} for e in report['methods']}
  original=(json.dumps(report,indent=2)+'\n').encode()
  if name!='no_summary':(ev/'summary.json').write_bytes(original)
  for tag in ['trap','always']:
   value={'emulator':'NOT_STARTED','command_groups':'STOPPED_CONFIRMED','acl':'NOT_TOUCHED','errors':['synthetic'] if name=='cleanup_failure' and tag=='always' else []};(ev/f'cleanup-{tag}.json').write_text(json.dumps(value))
  if name in ['all13_complete','all13_missing_raw']:
   package_text=(control/'package.py').read_text();fragment=package_text[package_text.index('fixed='):package_text.index('missing=')]
   fixture_scope={'plan':plan};exec(fragment,fixture_scope)
   for filename in fixture_scope['fixed']:
    target=ev/filename
    if filename=='summary-before-packaging.json' or target.exists():continue
    target.write_text('{}\n' if filename.endswith('.json') else 'synthetic offline presence fixture\n')
   for tag in ['trap','always']:
    (ev/f'cleanup-{tag}.json').write_text(json.dumps({'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'UNCHANGED_EXACT','errors':[]}))
   if name=='all13_missing_raw':(ev/'method-01-C1-instrumentation.log').unlink()
  if name=='oversize':(ev/'build-C1.log').write_bytes(b'a'*(20*1024*1024))
  if name=='secret':(ev/'build-C1.log').write_text('ghp_'+'x'*30)
  if name=='excluded_binary':(ev/'private.apk').write_bytes(b'PK\0');(ev/'private.db').write_bytes(b'SQLite')
  env=os.environ.copy();env['KVM_ONCE']=str(root)
  run=subprocess.run([sys.executable,control/'package.py'],env=env,capture_output=True,text=True);(root/'package.stdout').write_text(run.stdout);(root/'package.stderr').write_text(run.stderr)
  if name in ['oversize','secret']:assert run.returncode!=0,name
  else:assert run.returncode==0,run.stderr
  if name!='no_summary':
   assert (ev/'summary-before-packaging.json').read_bytes()==original
   after=json.loads((ev/'summary.json').read_text())
   if name in ['running','installed','between_steps']:assert after['status'].startswith('INCOMPLETE') and 'ended_epoch' not in after
   if name=='running':assert after['actions']['build-C1']['status']=='INCOMPLETE' and after['methods'][0]['status_before_packaging']=='RUNNING'
   if name=='installed':assert after['install_groups']['C1-data-diagnostics']['status']=='INCOMPLETE'
   if name=='cleanup_failure':assert after['status']=='CLEANUP_UNCONFIRMED' and after['cleanup_verified'] is False
   if name=='all13_complete':assert after['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and after['cleanup_verified'] is True and after['missing_files']==[]
   if name=='all13_missing_raw':
    assert after['status']=='EVIDENCE_INCOMPLETE' and after['missing_files']==['method-01-C1-instrumentation.log']
    assert all(e['status']=='PASS' and e['junit_status']=='PASS' for e in after['methods'])
    assert json.loads((root/'upload/packaging.json').read_text())['complete'] is False
   if name in ['pass_preserved','fail_preserved','notrun_preserved']:assert after['methods']==report['methods']
  assert not (root/'upload/private.apk').exists() and not (root/'upload/private.db').exists()
  cases.append({'case':name,'exit':run.returncode})
 return cases
check('actual new packager: partial, missing raw, failure, limits and privacy',packagecases)
def sizing():
 value=size_bounds.calculate(contract)
 (a.out/'maximum-shape.json').write_text(json.dumps(value,indent=2)+'\n')
 assert value['raw_plus_records_bytes']+3*1024*1024<=20*1024*1024
 return {'raw_plus_records_upper_bytes':value['raw_plus_records_bytes'],'other_text_reserved_bytes':3*1024*1024,'final_real_total_checked_by_packager':True}
check('bounded maximum shapes plus three MiB other text reserve',sizing)
inputs={str(f):sha(f)for f in sorted(list(here.iterdir())+(list(real_readiness.iterdir())if real_readiness else []))if f.is_file()}
receipt={'status':'PASS','scope':'OFFLINE_ONLY_NO_NEW_NATIVE_NO_NETWORK_NO_BUILD','groups':checks,'inputs':inputs,'checker_sha256':sha(Path(__file__))}
(a.out/'preflight.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':'PASS','groups':len(checks),'receipt':str(a.out/'preflight.json'),'sha256':sha(a.out/'preflight.json')}))
