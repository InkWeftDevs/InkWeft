"""Offline-only checks: no network, build, adb, emulator, privilege or workflow execution."""
import argparse,ast,copy,hashlib,io,json,math,os,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
from pathlib import Path
import yaml
p=argparse.ArgumentParser();home=Path(__file__).resolve().parent
p.add_argument('--controller',type=Path,default=home/'controller-template');p.add_argument('--readiness',type=Path,default=home/'frozen-readiness');p.add_argument('--out',type=Path,required=True);a=p.parse_args()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists();a.out.mkdir(parents=True)
checks=[]
def check(name,func):
 try:value=func();checks.append({'name':name,'status':'PASS','details':value})
 except Exception as e:checks.append({'name':name,'status':'FAIL','error':type(e).__name__+': '+str(e)});raise

def reject(func):
 try:func()
 except (AssertionError,ValueError,TypeError,RecursionError,RuntimeError):return True
 raise AssertionError('Invalid input was accepted')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
source=(a.controller/'run.py').read_text();tree=ast.parse(source)
ns={'re':re,'json':json,'math':math,'Counter':Counter,'Path':Path,'hashlib':hashlib,'time':time,'os':os,'subprocess':subprocess,'ET':ET,'sys':sys,'shutil':shutil}
for node in tree.body:
 if isinstance(node,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([node],type_ignores=[]),'controller-ast','exec'),ns)
plan=json.loads((a.readiness/'method-plan.json').read_text());spec=json.loads((a.readiness/'source-spec.json').read_text());contract=json.loads((a.readiness/'marker-contract.json').read_text())
ns['MARKERS']=contract;ns['ADB']=['adb','-s','emulator-5554'];ns['PACKAGES']=['org.inkweft.data.test'];ns['PHASE_NAMES']={'correctness':'correctness','common':'common-comparison'}
oldroot=Path('/workspace/InkWeft-native-candidate3');oldcontrol=oldroot/'ci/pr20-kvm-triple';raw=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw');summary=json.loads((raw/'summary.json').read_text())
def static():
 for path in a.controller.glob('*.py'):ast.parse(path.read_text())
 assert (a.controller/'cleanup.py').read_bytes()==(oldcontrol/'cleanup.py').read_bytes()
 oldtree=ast.parse((oldcontrol/'run.py').read_text())
 copied=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','junit']
 for name in copied:
  one=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name==name);prior=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef) and n.name==name)
  assert ast.dump(one)==ast.dump(prior),name+' changed'
 assert '--no-build-cache' in source and "':app:assemble" not in source
 assert 'official_dependency_check' not in source and 'mupdf' not in source.lower()
 proc=ast.unparse(next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='processes_after'))
 assert 'am' not in re.findall(r"'([^']+)'",proc) and 'helper =' not in proc
 assert len(plan['steps'])==18 and [x['ordinal'] for x in plan['steps']]==list(range(1,19))
 assert Counter(x['phase'] for x in plan['steps'])=={'correctness':10,'common-comparison':8}
 assert len({x['install_group'] for x in plan['steps']})==9
 assert [(x['seed'],x['identity']) for x in plan['steps'][10:]]==[(4101,'C0'),(4101,'C1'),(4102,'C1'),(4102,'C0'),(4103,'C0'),(4103,'C1'),(4104,'C1'),(4104,'C0')]
 assert spec['identities']['C0']['git']=='d66b60d6cf9587f646acb76dd8a00910b73a6d70' and spec['identities']['C1']['git']=='a9ccf2cc727a7957e8fb00115f0060d80b287de1'
 if (a.controller/'authorization.json').exists():
  auth=json.loads((a.controller/'authorization.json').read_text())
  assert auth['approved'] is True and auth['scope']=='SCREENING_ONLY' and auth['phase_cutoff_minutes']=={'setup':30,'correctness':35,'common':50,'work':55}
  assert auth['plan_git']=='03a335f3d92e3682080f3ce7e4c661434956c075'
  for key,name in [('approved_plan_sha256','PLAN.md'),('source_spec_sha256','source-spec.json'),('method_plan_sha256','method-plan.json'),('original_protocol_sha256','original-protocol.json'),('marker_contract_sha256','marker-contract.json'),('prepare_sources_sha256','prepare_sources.py')]:
   assert auth[key]==sha(a.readiness/name)
   committed=subprocess.check_output(['git','-C','/workspace/InkWeft','show',auth['plan_git']+':android/verification/pr20-search-screen-20261014/'+name])
   assert committed==(a.readiness/name).read_bytes(),name+' readiness commit mismatch'
 assert (a.readiness/'original-protocol.json').read_bytes()==(oldroot/'android/verification/pr20-candidate3-readiness-20261014/original-protocol.json').read_bytes()
 return {'copied_functions':copied,'methods':18,'groups':9,'cleanup_byte_identical':True}
check('static scope and inherited functions',static)
def workflow():
 value=yaml.safe_load((a.controller/'workflow.proposed.yml').read_text());job=value['jobs']['screen']
 assert value['permissions']=={'contents':'read'} and job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04'
 assert 'github.run_attempt == 1' in job['if'] and 'github.event.before ==' in job['if'] and 'github.event.created == false' in job['if']
 assert len(re.findall("[0-9a-f]{40}",job['if']))==1
 assert value['concurrency']['cancel-in-progress'] is False
 count=0
 for index,step in enumerate(job['steps']):
  if 'run' not in step:continue
  text=step['run'];script=a.out/f'step-{index:02}.sh';script.write_text(text)
  subprocess.run(['bash','-n',script],check=True,capture_output=True)
  for code in re.findall(r"python3 - <<'([^']+)'\n(.*?)\n\1",text,re.S):ast.parse(code[1])
  if 'run.py' in text:assert 'timeout --signal=TERM --kill-after=2' in text and 'remaining' in text;count+=1
 assert count==6
 lint=Path('/workspace/scratch/actionlint-1.7.12/actionlint')
 result=subprocess.run([lint,'-shellcheck=','-pyflakes=',a.controller/'workflow.proposed.yml'],capture_output=True,text=True)
 (a.out/'actionlint.log').write_text(result.stdout+result.stderr);assert result.returncode==0,result.stderr
 first=job['steps'][0]['run'];clockroot=a.out/'clock-exec';clockroot.mkdir();envfile=clockroot/'github-env';envfile.touch();runtemp=clockroot/'runner-temp';runtemp.mkdir()
 env=os.environ.copy();env.update(RUNNER_TEMP=str(runtemp),GITHUB_ENV=str(envfile))
 result=subprocess.run(['bash','-c',first],env=env,capture_output=True,text=True);(a.out/'clock-first-step.log').write_text(result.stdout+result.stderr);assert result.returncode==0
 values=dict(line.split('=',1) for line in envfile.read_text().splitlines());assert len(values)==9 and all('\\n' not in x for x in values.values())
 clock=json.loads((runtemp/'pr20-search-screen/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,35,50,55,60]
 previous=envfile.read_bytes();again=subprocess.run(['bash','-c',first],env=env,capture_output=True,text=True);assert again.returncode!=0 and envfile.read_bytes()==previous
 return {'actionlint_exit':result.returncode,'phase_wrappers':count,'clock_export_lines':len(values),'second_clock_refused':True}
check('workflow syntax and actual isolated first-step clock',workflow)
def budget():
 fake=types.SimpleNamespace(time=lambda:1000.0);ns.update(time=fake,START=1000,PHASE='setup')
 for name in ['CUTOFFS','DEADLINE']:
  node=next(n for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id==name for t in n.targets))
  exec(compile(ast.Module([node],type_ignores=[]),'actual-cutoff-ast','exec'),ns)
 assert ns['CUTOFFS']=={'setup':2800,'correctness':3100,'common':4000,'work':4300} and ns['DEADLINE']==4300
 for phase,end in ns['CUTOFFS'].items():
  ns['PHASE']=phase
  for offset in [-.01,0,.01]:
   fake.time=lambda end=end,offset=offset:end+offset
   if offset<0:assert 0<ns['seconds']()<=.011
   else:reject(lambda:ns['seconds']())
 ns['time']=time
 return {'phase_boundaries':12}
check('phase/work budget exact boundaries',budget)
def rawchecks():
 for entry in summary['methods']:
  text=(raw/f"method-{entry['ordinal']:02}-{entry['identity']}-instrumentation.log").read_text()
  candidate=copy.deepcopy(entry);ns['raw_result'](candidate,text,0);assert candidate['junit_status']=='PASS'
 entry=next(x for x in summary['methods'] if x['ordinal']==37);text=(raw/'method-37-C1-instrumentation.log').read_text()
 variants=[('exit',text,1),('wrong class',text.replace('class=org.inkweft.data.KvmLibraryPairProbe','class=org.wrong.Bad'),0),('wrong method',text.replace('test=coldSearchConcurrentSavesAndShelfReadScale','test=wrong'),0),('double OK',text+'\nOK (1 test)\n',0),('two tests',text.replace('OK (1 test)','OK (2 tests)'),0),('status failure',text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -2',1),0),('missing terminal',text.replace('INSTRUMENTATION_CODE: -1',''),0),('extra start',text+'\nINSTRUMENTATION_STATUS_CODE: 1\n',0),('status junk',text+'\nINSTRUMENTATION_STATUS_CODE: 0junk\n',0),('terminal junk',text+'\nINSTRUMENTATION_CODE: -1junk\n',0),('failures',text+'\nFAILURES!!!\n',0),('wrong count',text.replace('numtests=1','numtests=2'),0),('partial',text[:text.index('OK (1 test)')],None)]
 for name,value,rc in variants:
  test=copy.deepcopy(entry);ns['raw_result'](test,value,rc);assert test['junit_status']!='PASS',name
 return {'prior_actual_raw_pass':58,'negative_variants':len(variants)}
check('strict original raw JUnit and invalid variants',rawchecks)
def markers():
 count=0
 for entry in summary['methods']:
  if entry['identity'] not in ['C0','C1'] or 'KvmLibraryPairProbe' not in entry['selection']:continue
  text=(raw/f"method-{entry['ordinal']:02}-{entry['identity']}-instrumentation.log").read_text();values=ns['safe_marker_payloads'](entry,text);assert len(values)==12;count+=1
 entry=next(x for x in summary['methods'] if x['ordinal']==37);text=(raw/'method-37-C1-instrumentation.log').read_text()
 values=ns['safe_marker_payloads'](entry,text);fixture=next(x['original_json'] for x in values if x['marker']=='KVM_PAIR_FIXTURE')
 negatives={'truncated_known':text+'\nLIBRARY_READ_EVIDENCE {"books":', 'array_known':text+'\nLIBRARY_READ_EVIDENCE []', 'extra_valid':text+'\nKVM_PAIR_FIXTURE '+fixture,'missing_payload':text+'\nKVM_PAIR_FIXTURE','unknown_marker':text+'\nKVM_ARBITRARY {"url":"https://bad.invalid"}', 'duplicate_key':text.replace(fixture,fixture[:-1]+',"seed":4101}'),'nonfinite':text.replace('"seed":4101','"seed":NaN',1),'wrong_seed':text.replace('"seed":4101','"seed":4102',1),'unknown_key':text.replace(fixture,fixture[:-1]+',"url":"https://bad.invalid"}'),'oversized':text+'\nLIBRARY_READ_EVIDENCE '+(' '*65537)+'{}'}
 for name,value in negatives.items():reject(lambda value=value:ns['safe_marker_payloads'](entry,value))
 return {'prior_actual_complete_marker_blocks':count,'negative_variants':list(negatives)}
check('marker closed schemas, exact cardinalities and malformed-prefix negatives',markers)
def manifest():
 xml=(home/'local-aapt-xmltree.log').read_text();assert ns['validate_instrumentation_manifest'](xml)=={'name':'androidx.test.runner.AndroidJUnitRunner','targetPackage':'org.inkweft.data.test'}
 for value in [xml.replace('androidx.test.runner.AndroidJUnitRunner','bad.Runner'),xml.replace('android:targetPackage(0x01010021)="org.inkweft.data.test"','android:targetPackage(0x01010021)="bad.package"'),xml.replace('E: instrumentation','E: other'),xml+'    E: instrumentation (line=9)\n']:
  reject(lambda value=value:ns['validate_instrumentation_manifest'](value))
 return {'local_actual_manifest':1,'negative_cases':4}
check('local APK instrumentation element and target binding',manifest)
def processes():
 outcomes=[]
 for kind in ['absent','lingering','transport']:
  current=[0.0];calls=[];fake=types.SimpleNamespace(monotonic=lambda:current[0],sleep=lambda x:current.__setitem__(0,current[0]+max(x,.001)))
  def run(args,*pos,**kwargs):
   calls.append(args);current[0]+=.01
   assert args==['adb','-s','emulator-5554','shell','pidof','org.inkweft.data.test']
   return subprocess.CompletedProcess(args,1 if kind=='absent' else 0,'' if kind=='absent' else '2717\n','transport error' if kind=='transport' else '')
  ns.update(time=fake,run=run,seconds=lambda limit=3600:limit)
  entry={'junit_status':'PASS','adb_exit':0}
  if kind=='absent':ns['processes_after'](entry);assert entry['process_exit_outcome']=='NATURAL_EXIT'
  else:reject(lambda:ns['processes_after'](entry));assert entry['process_exit_outcome']=='UNCONFIRMED'
  assert all('kill' not in c and 'force-stop' not in c for c in calls);outcomes.append({'case':kind,'calls':len(calls)})
 ns['time']=time
 return outcomes
check('data-only natural exit; no helper/force-stop fallback',processes)
def marker_failure_status():
 entry=copy.deepcopy(plan['steps'][10]);entry['status']='NOT_RUN'
 target=a.out/'method-incomplete';target.mkdir();text=(raw/'method-37-C1-instrumentation.log').read_text()+'\nINDEX_SAVE_PROGRESS []\n'
 saved={k:ns.get(k) for k in ['OUT','REPORT','run','processes_after','seconds','save','time']}
 ns.update(OUT=target,REPORT={'methods':[entry]},time=time,seconds=lambda limit=3600:limit,save=lambda:None)
 def run(args,*pos,**kwargs):kwargs['stdout'].write(text);return subprocess.CompletedProcess(args,0)
 def ended(e):e['process_exit_outcome']='NATURAL_EXIT'
 ns.update(run=run,processes_after=ended)
 output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](entry))
 assert entry['status']=='INCOMPLETE' and entry['junit_status']=='PASS' and entry['adb_exit']==0
 assert 'INKWEFT_SEARCH_SCREEN_CONTEXT' not in output.getvalue()
 assert 'INKWEFT_SEARCH_SCREEN_METHOD' in output.getvalue()
 ns.update(saved)
 return {'host':'INCOMPLETE','raw_junit':'PASS','arbitrary_payload_forwarded':False}
check('malformed marker preserves raw PASS but host INCOMPLETE',marker_failure_status)
def installed_hash():
 results=[]
 for case in ['match','wrong_hash','wrong_path']:
  root=a.out/('install-'+case);out=root/'evidence';out.mkdir(parents=True);apk=root/'sources/C0/android/test.apk';apk.parent.mkdir(parents=True);apk.write_bytes(b'synthetic-apk-not-Android')
  value=hashlib.sha256(apk.read_bytes()).hexdigest();(out/'device-before.json').write_text('{}');(out/'apk-C0.json').write_text(json.dumps({'apks':[{'path':'test.apk','sha256':value}]}))
  saved={k:ns.get(k) for k in ['OUT','ROOT','SPEC','REPORT','capture','run','environment','host','digest','save']}
  ns.update(OUT=out,ROOT=root,SPEC=spec,REPORT={'install_groups':{}},save=lambda:None,environment=lambda:{},host=lambda:{},digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest())
  calls=[]
  def capture(args,limit=30):
   calls.append([str(x) for x in args])
   if args[3]=='install':return 'Success'
   if args[3:6]==['shell','pm','path']:return 'package:/data/app/abc/base.apk' if case!='wrong_path' else 'package:/system/not-ours.apk'
   if args[3:5]==['shell','sha256sum']:return (value if case!='wrong_hash' else '0'*64)+'  /data/app/abc/base.apk'
   raise AssertionError(args)
  ns['capture']=capture;ns['run']=lambda args,*pos,**kwargs:subprocess.CompletedProcess(args,1,'','')
  if case=='match':
   ns['install']('visit-01-C0','C0');receipt=ns['REPORT']['install_groups']['visit-01-C0'];assert receipt['status']=='INSTALLED' and receipt['installed_apks'][0]['sha256']==value
  else:reject(lambda:ns['install']('visit-01-C0','C0'));assert ns['REPORT']['install_groups']['visit-01-C0']['status']=='RUNNING'
  results.append({'case':case,'captured_commands':calls});ns.update(saved)
 return results
check('post-install real byte hash contract with mismatch refusal',installed_hash)
def packagecases():
 repo=a.out/'package-fixture/control';control=repo/'ci/pr20-search-screen';frozen=repo/'android/verification/pr20-search-screen-20261014';control.mkdir(parents=True);shutil.copytree(a.readiness,frozen)
 sourcehash=sha(frozen/'source-spec.json')
 for path in a.controller.iterdir():
  if path.is_file():(control/path.name).write_text(path.read_text().replace('__SCREEN_SOURCE_SPEC_SHA256__',sourcehash))
 (control/'authorization.json').write_text('{}\n');(control/'EXECUTION_SHA256SUMS').write_text('synthetic offline package fixture; not activation\n')
 for probe in spec['common_probes']:
  dest=repo/probe['from_path'];dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(oldroot/probe['from_path'],dest)
 base={'run_id':'SYNTHETIC','workflow_sha':'0'*40,'input_hashes':{},'status':'MEASURING','actions':{},'phases':[],'install_groups':{},'methods':[dict(s,status='NOT_RUN') for s in plan['steps']]}
 cases=[]
 for name in ['running','installed','between_steps','pass_preserved','fail_preserved','notrun_preserved','cleanup_failure','all18_complete','all18_missing_raw','oversize','secret','excluded_binary','no_summary']:
  root=a.out/'package-fixture'/name;ev=root/'evidence';ev.mkdir(parents=True);report=copy.deepcopy(base)
  if name=='running':report['actions']['build-C1']={'status':'RUNNING','started_epoch':1};report['methods'][0]['status']='RUNNING'
  if name=='installed':report['install_groups']['C1-correctness']={'identity':'C1','status':'INSTALLED','started_epoch':1}
  if name=='pass_preserved':report['methods'][0]['status']='PASS';report['methods'][0]['junit_status']='PASS'
  if name=='fail_preserved':report['methods'][0]['status']='FAIL';report['methods'][0]['junit_status']='FAIL'
  if name in ['all18_complete','all18_missing_raw']:
   report['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING'
   for e in report['methods']:e.update(status='PASS',junit_status='PASS',adb_exit=0)
   report['install_groups']={e['install_group']:{'identity':e['identity'],'status':'PASS'} for e in report['methods']}
  original=(json.dumps(report,indent=2)+'\n').encode()
  if name!='no_summary':(ev/'summary.json').write_bytes(original)
  for tag in ['trap','always']:
   value={'emulator':'NOT_STARTED','command_groups':'STOPPED_CONFIRMED','acl':'NOT_TOUCHED','errors':['synthetic'] if name=='cleanup_failure' and tag=='always' else []};(ev/f'cleanup-{tag}.json').write_text(json.dumps(value))
  if name in ['all18_complete','all18_missing_raw']:
   package_text=(control/'package.py').read_text();fragment=package_text[package_text.index('fixed='):package_text.index('missing=')]
   fixture_scope={'plan':plan};exec(fragment,fixture_scope)
   for filename in fixture_scope['fixed']:
    target=ev/filename
    if filename=='summary-before-packaging.json' or target.exists():continue
    target.write_text('{}\n' if filename.endswith('.json') else 'synthetic offline presence fixture\n')
   for tag in ['trap','always']:
    (ev/f'cleanup-{tag}.json').write_text(json.dumps({'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'UNCHANGED_EXACT','errors':[]}))
   if name=='all18_missing_raw':(ev/'method-01-C1-instrumentation.log').unlink()
  if name=='oversize':(ev/'build-C0.log').write_bytes(b'a'*(20*1024*1024))
  if name=='secret':(ev/'build-C0.log').write_text('ghp_'+'x'*30)
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
   if name=='installed':assert after['install_groups']['C1-correctness']['status']=='INCOMPLETE'
   if name=='cleanup_failure':assert after['status']=='CLEANUP_UNCONFIRMED' and after['cleanup_verified'] is False
   if name=='all18_complete':assert after['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and after['cleanup_verified'] is True and after['missing_files']==[]
   if name=='all18_missing_raw':
    assert after['status']=='EVIDENCE_INCOMPLETE' and after['missing_files']==['method-01-C1-instrumentation.log']
    assert all(e['status']=='PASS' and e['junit_status']=='PASS' for e in after['methods'])
    assert json.loads((root/'upload/packaging.json').read_text())['complete'] is False
   if name in ['pass_preserved','fail_preserved','notrun_preserved']:assert after['methods']==report['methods']
  assert not (root/'upload/private.apk').exists() and not (root/'upload/private.db').exists()
  cases.append({'case':name,'exit':run.returncode})
 return cases
check('actual new packager: interruption, preservation, cleanup, bounded whitelist',packagecases)
inputs={str(path):sha(path) for path in sorted(list(a.controller.glob('*'))+list(a.readiness.glob('*'))) if path.is_file()}
receipt={'status':'PASS','scope':'OFFLINE_SYNTHETIC_AND_PRIOR_RAW_ONLY_NO_NEW_NATIVE','checks':checks,'inputs':inputs,'checker_sha256':sha(Path(__file__))}
(a.out/'preflight.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':'PASS','groups':len(checks),'receipt':str(a.out/'preflight.json'),'sha256':sha(a.out/'preflight.json')},indent=2))
