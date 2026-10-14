from pathlib import Path
import json,hashlib,re,subprocess,collections,xml.etree.ElementTree as ET,datetime
ROOT=Path('/workspace/scratch/pr20-candidate3-results-20261014');E=ROOT/'raw'
REPO=Path('/workspace/InkWeft-native-candidate3');ACTIVE='d20b37a00d084ddea8a59698b926d94ce2f1a633';READINESS='445654c90a4c78893f158fdc4eca4313f3320a7f'
OUT=[ROOT/'audit/audit-runtime.json',ROOT/'audit/audit-runtime.md'];assert not any(p.exists() for p in OUT)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(name):return json.loads((E/name).read_text())
def git(path,commit=ACTIVE):return subprocess.check_output(['git','-C',str(REPO),'show',commit+':'+path],timeout=15)
issues=[];checks=[]
def check(value,label):
 checks.append({'check':label,'pass':bool(value)})
 if not value:issues.append(label)
inputs_before={p.name:sha(p) for p in E.iterdir() if p.is_file()}
s=read('summary.json');original=read('summary-before-packaging.json');plan=read('frozen-method-plan.json');spec=read('frozen-source-spec.json');auth=read('authorization.json');receipt=json.loads((ROOT/'raw-archive-verification.json').read_text())
manifest=read('manifest.json');check(len(manifest)==292 and len(inputs_before)==294,'292 payload files plus manifest/packaging')
check(all(sha(E/x['name'])==x['original_sha256']==x['uploaded_sha256'] and (E/x['name']).stat().st_size==x['original_bytes']==x['uploaded_bytes'] for x in manifest),'All original manifest hashes and sizes rechecked')
archive=Path('/workspace/scratch/pr20-candidate3-original-20261014/pr20-kvm-triple-38498392929.zip')
check(sha(archive)==receipt['archive_sha256'] and archive.stat().st_size==receipt['archive_bytes'],'Library original ZIP digest/size matches fixed verifier receipt')
check(receipt['runtime_audit']['issues']==[] and receipt['runtime_audit']['pending']==[],'Fixed verifier receipt has no runtime issues/pending')
check(s['status']==original['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and s['cleanup_verified'] is True,'Original and packaged execution completion retained')
copy=json.loads(json.dumps(s));copy.pop('cleanup_verified');check(copy==original,'Packaging added only cleanup_verified on completed run')
check(str(s['run_id'])=='38498392929' and s['run_attempt']=='1' and s['workflow_sha']==ACTIVE,'Exact run/attempt/active binding')
check(s['source_spec']==spec and len(plan['steps'])==58 and [x['ordinal'] for x in plan['steps']]==list(range(1,59)),'58 exact frozen plan entries/source spec')
frozen=[]
for n in ['PLAN.md','original-protocol.json','source-spec.json','method-plan.json','B-source-manifest.json','C0-source-manifest.json','C1-source-manifest.json','prepare_sources.py']:
 path='android/verification/pr20-candidate3-readiness-20261014/'+n
 check((E/('frozen-'+n)).read_bytes()==git(path,READINESS)==git(path),'Frozen '+n+' equals readiness and active objects');frozen.append(n)
controller_hashes={}
for line in (E/'EXECUTION_SHA256SUMS').read_text().splitlines():
 h,n=line.split('  ',1);blob=git(n);check(hashlib.sha256(blob).hexdigest()==h,'Committed controller hash '+n);controller_hashes[n]=h
 if (E/Path(n).name).exists():check((E/Path(n).name).read_bytes()==blob,'Archived controller bytes '+n)
code=git('ci/pr20-kvm-triple/run.py').decode();check(controller_hashes['ci/pr20-kvm-triple/run.py']=='173bc638d75a12d78d4aeb594cc44f44f2b8670fd8d2d04cb099b1fe74ce97e3','Reviewed exact controller')
probe_checks=[]
for p in spec['common_probes']:
 name=Path(p['from_path']).name;ok=sha(E/name)==p['sha256']==hashlib.sha256(git(p['from_path'])).hexdigest();check(ok,'Frozen common probe '+name);probe_checks.append({'name':name,'sha256':p['sha256'],'matched':ok})
check(auth['phase_cutoff_minutes']=={'setup':38,'correctness':41,'common':53,'diagnostics':55},'Budget3 exact phase offsets')
check(s['budget_anchor_epoch']==1791942801 and s['actual_job_started_epoch'] is None,'Conservative anchor distinct from actual start')
check(s['phase_cutoff_epochs']=={k:1791942801+v*60 for k,v in auth['phase_cutoff_minutes'].items()},'Exact cumulative cutoffs')
for layer in ['actions','phases']:
 vals=s[layer].values() if isinstance(s[layer],dict) else s[layer]
 for item in vals:
  check(item['status']=='PASS' and item['started_epoch']+item['seconds']<=s['phase_cutoff_epochs'][item['budget_phase']],'Completed before cutoff '+layer+':'+str(item.get('name',item['budget_phase'])))
packages=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test'];absent={'exit':1,'stdout':'','stderr':''};gone={p:absent for p in packages}
methods=[];markers=collections.Counter();fixtures=collections.defaultdict(list);phase_counts=collections.Counter();natural=[];last_end=0
for step,e in zip(plan['steps'],s['methods']):
 o=step['ordinal'];prefix=f"method-{o:02d}-{step['identity']}";label=f'method{o:02d}'
 check(all(e[k]==v for k,v in step.items()),label+' exact plan identity/args/seed/phase/group')
 check(e['status']==e['junit_status']=='PASS' and e['adb_exit']==0,label+' host PASS/raw PASS/adb0')
 raw=(E/(prefix+'-instrumentation.log')).read_text();cls,method=step['selection'].split('#')
 values={k:re.findall(pattern,raw,re.M) for k,pattern in {'ok':r'^OK \((\d+) tests?\)\s*$','code':r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$','class':r'^INSTRUMENTATION_STATUS: class=(.*)$','test':r'^INSTRUMENTATION_STATUS: test=(.*)$','count':r'^INSTRUMENTATION_STATUS: numtests=(.*)$','terminal':r'^INSTRUMENTATION_CODE: (-?\d+)\s*$'}.items()}
 codes=list(map(int,values['code']));check(values['ok']==['1'] and codes.count(1)==1 and 0 in codes and set(codes)<={0,1} and values['terminal']==['-1'] and [v.strip() for v in values['class']]==[cls,cls] and [v.strip() for v in values['test']]==[method,method] and [v.strip() for v in values['count']]==['1','1'] and 'FAILURES!!!' not in raw and 'INSTRUMENTATION_FAILED' not in raw,label+' independently parsed exact single raw JUnit')
 check(e['log_sha256']==sha(E/(prefix+'-instrumentation.log')) and e['status_codes']==codes and e['junit_ok_counts']==values['ok'] and e['reported_classes']==values['class'] and e['reported_methods']==values['test'] and e['reported_test_counts']==values['count'],label+' original raw fields match ledger')
 records=[]
 for line in raw.splitlines():
  match=re.search(r'((?:KVM|LIBRARY|HISTORY|TILE|INDEX)_[A-Z0-9_]+) (\{.*\}|\[.*\])\s*$',line)
  if match:records.append({'marker':match[1],'data':json.loads(match[2])})
 check(records==read(prefix+'-records.json'),label+' every raw JSON marker equals recorded payload')
 markers.update(x['marker'] for x in records)
 xml=ET.fromstring((E/(prefix+'-junit.xml')).read_bytes());cases=xml.findall('testcase')
 check(xml.get('tests')=='1' and xml.get('failures')==xml.get('errors')=='0' and len(cases)==1 and cases[0].get('classname')==cls and cases[0].get('name')==method and not list(xml.iter('failure')) and not list(xml.iter('error')),label+' derived XML exactly one matching passing testcase')
 check(float(xml.get('time'))==e['seconds'] and float(cases[0].get('time'))==e['seconds'],label+' XML duration matches host ledger')
 observations=e['process_exit_observations'];times=[x['elapsed_seconds'] for x in observations]
 check(times==sorted(times) and len(observations)>0 and all(set(x['packages'])==set(packages) for x in observations),label+' ordered complete three-package observations')
 check(all(not v['stderr'] and ((v['exit']==1 and v['stdout']=='') or (v['exit']==0 and re.fullmatch(r'[1-9][0-9]*(?: [1-9][0-9]*)*',v['stdout']))) for x in observations for v in x['packages'].values()),label+' valid pidof results throughout')
 check(e['processes_after']==observations[-1]['packages']==gone,label+' all three packages absent at method completion')
 check(0<=times[0]<=times[-1]<=e['process_exit_seconds']<=e['seconds'],label+' process observations fit host method duration')
 if o!=17:
  check(e['process_exit_outcome']=='NATURAL_EXIT' and 'auxiliary_cleanup' not in e and all(x['stage']=='natural-exit' for x in observations) and e['process_exit_seconds']<=3,label+' natural exit without cleanup request')
  natural.append({'ordinal':o,'observations':len(observations),'seconds':e['process_exit_seconds']})
 check(e['started_epoch']>=last_end,label+' sequential nonoverlapping host method');last_end=e['started_epoch']+e['seconds']
 phase={'correctness':'correctness','common-comparison':'common','candidate-diagnostics':'diagnostics'}[e['phase']]
 check(last_end<=s['phase_cutoff_epochs'][phase],label+' ends before phase cutoff')
 group=s['install_groups'][e['install_group']];check(group['started_epoch']<=e['started_epoch'] and last_end<=group['started_epoch']+group['seconds'],label+' enclosed by correct install group')
 if e['phase']=='common-comparison':
  f=[x['data'] for x in records if x['marker']=='KVM_PAIR_FIXTURE'];check(f==e['fixtures'] and len(f)==(3 if 'KvmLocalAppendPairProbe#' in e['selection'] else 1),label+' complete fixture evidence');fixtures[(e['seed'],e['selection'])].append({'identity':e['identity'],'fixtures':f})
 phase_counts[e['phase']]+=1
 methods.append({'ordinal':o,'phase':e['phase'],'identity':e['identity'],'install_group':e['install_group'],'selection':e['selection'],'seed':e['seed'],'raw_junit':'PASS','host':'PASS','adb_exit':0,'process_exit_outcome':e['process_exit_outcome'],'observations':len(observations),'seconds':e['seconds'],'files_sha256':{suffix:sha(E/(prefix+suffix)) for suffix in ['-instrumentation.log','-records.json','-junit.xml']}})
check(len(methods)==58 and len({e['selection'] for e in s['methods']})==36,'58 calls correspond to36 distinct selections')
check(dict(phase_counts)=={'correctness':31,'common-comparison':24,'candidate-diagnostics':3},'31correctness/24common/3diagnostics retained')
check(len(fixtures)==8 and len({seed for seed,selection in fixtures})==4 and all(sorted(x['identity'] for x in vals)==['B','C0','C1'] and vals[0]['fixtures']==vals[1]['fixtures']==vals[2]['fixtures'] for vals in fixtures.values()),'Four seeds/two common selections exact three-way fixture equality')
h=s['methods'][16];obs=h['process_exit_observations'];pre=[x for x in obs if x['stage']=='natural-exit'];post=[x for x in obs if x['stage']=='after-background-cleanup'];aux=h['auxiliary_cleanup']
check(h['ordinal']==17 and h['selection']=='org.inkweft.app.NotebookShelfUiTest#pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges' and h['phase']=='correctness' and h['identity']=='C1' and h['args'][-1]=='org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner','Only authorized helper17 identity')
check(obs==pre+post and len(pre)==21 and len(post)==1 and all(x['packages'][packages[0]]==x['packages'][packages[2]]==absent and x['packages'][packages[1]]=={'exit':0,'stdout':'2717','stderr':''} for x in pre),'Helper17 app/data absent throughout21 natural observations; only helper2717 remains')
check(h['process_exit_outcome']=='AUXILIARY_AM_KILL_CONFIRMED' and aux['command']==['adb','-s','emulator-5554','shell','am','kill','--user','0','org.inkweft.app.a0.test'] and aux['status']=='ABSENCE_CONFIRMED' and aux['exit']==0 and aux['stdout']==aux['stderr']=='','Helper17 single exact background-safe am kill receipt')
check(pre[-1]['elapsed_seconds']<aux['started_seconds']<post[0]['elapsed_seconds']<=h['process_exit_seconds']<=min(5,aux['started_seconds']+2) and 2.75<=aux['started_seconds']<=3,'Helper17 request after natural window guard and absence confirmed within2s/5s bounds')
check(post[0]['packages']==gone and len(natural)==57,'Helper post-request all absent plus57 natural methods')
# Order is corroborated by exact committed controller method(), not an invented per-command wall timestamp.
check(code.index('records = raw_result(entry, raw, returncode)')<code.index('processes_after(entry)\n        assert') and "entry.get('junit_status') == 'PASS' and entry.get('adb_exit') == 0" in code and "until = min(started + 5, time.monotonic() + 2)" in code and 'while until - time.monotonic() > 0.25:' in code,'Frozen controller establishes raw completion eligibility and0.25s observation guard')
helper={'ordinal':17,'raw_junit':'PASS','adb_exit':0,'natural_observations':len(pre),'first_natural_seconds':pre[0]['elapsed_seconds'],'last_natural_seconds':pre[-1]['elapsed_seconds'],'helper_pid':'2717','app_and_data_absent_every_natural_observation':True,'request':aux,'post_observation_seconds':post[0]['elapsed_seconds'],'total_process_exit_seconds':h['process_exit_seconds'],'request_to_post_observation_seconds':post[0]['elapsed_seconds']-aux['started_seconds'],'request_to_completed_observation_seconds':h['process_exit_seconds']-aux['started_seconds'],'window_note':'Nominal3s natural window reserves0.25s to avoid beginning3 queries at the edge; actual request at2.754429444s. No claim of a full3s wait or externally proven cached state.','ordering_evidence':'Original monotonic observations/receipt plus hash-bound controller ordering: raw_result before processes_after; PASS+adb0+onlyhelper alive gate before one am kill; no force-stop fallback.'}
apks=[];native=[];signers=[];deps={}
expectedpaths=['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
for identity in ['B','C0','C1']:
 a=read('apk-'+identity+'.json');source=spec['identities'][identity]
 check(a['identity']==identity and a['base_commit']==source['git'] and a['source_manifest_sha256']==source['canonical_manifest_sha256'] and a['jvm_tests']==a['lint']=='NOT_RUN','APK source/base/overlay scope '+identity)
 check([x['path'] for x in a['apks']]==expectedpaths,'Exact3 APK paths '+identity)
 for apk in a['apks']:
  signer=re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\S+)$',apk['signature'],re.M)
  check(signer==apk['signer_sha256'] and len(signer)==1 and bool(re.fullmatch('[0-9a-f]{64}',signer[0])) and apk['bytes']>0 and bool(re.fullmatch('[0-9a-f]{64}',apk['sha256'])),'APK signer/size/hash receipt '+identity+':'+apk['path'])
  signers.append(signer[0]);apks.append({'identity':identity,'path':apk['path'],'bytes':apk['bytes'],'sha256':apk['sha256'],'signer_sha256':signer[0]})
 embedded=a['apks'][0]['build_identity'];check(embedded['build_commit']==ACTIVE and embedded['source_commit']==source['git'] and embedded['formal'] is False and embedded['application_id']=='org.inkweft.app.a0' and set(embedded['target_abis'])=={'arm64-v8a','armeabi-v7a','x86','x86_64'},'Embedded source/active/fourABI binding '+identity)
 n=a['apks'][0]['mupdf_native'];native.append(n);check(len(n)==4 and {x['path'] for x in n}=={'lib/'+abi+'/libmupdf_java.so' for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']} and all(x['bytes']>100000 and re.fullmatch('[0-9a-f]{64}',x['sha256']) for x in n),'Four MuPDF ABI receipts '+identity)
 d=read('dependencies-'+identity+'.json');check(len(d)==879 and len({x['path'] for x in d})==879 and all(x['bytes']>0 and re.fullmatch('[0-9a-f]{64}',x['sha256']) for x in d),'879 unique valid dependency records '+identity);deps[identity]=d
 check(re.search(r'^BUILD SUCCESSFUL in ',(E/('build-'+identity+'.log')).read_text(),re.M) is not None,'Build raw success '+identity)
check(len(apks)==9 and len(set(signers))==1,'Nine APKs same recorded signer')
check(native[0]==native[1]==native[2],'All12 MuPDF records exact4 common ABI bytes/hash')
check(deps['B']==deps['C0']==deps['C1'],'All879 dependency path/size/SHA records identical three-way')
mupdf=[x for x in deps['B'] if x['path'].startswith('com.artifex.mupdf/fitz/1.28.5/')]
check(len(mupdf)==2 and {Path(x['path']).suffix for x in mupdf}=={'.aar','.pom'},'Official sameversion MuPDF fullAAR andPOM provenance')
check("assert data.startswith(b'\\x7fELF') and len(data) > 100000" in code and "'build-tools/36.0.0/apksigner', 'verify', '--print-certs'" in code and "assert digest(apk) == apk_item['sha256']" in code,'Runner exactcode signature/ELF checks and digest recheck before every install')
groups=[];device=(E/'device-before.json').read_bytes();group_order=list(dict.fromkeys(x['install_group'] for x in plan['steps']));uninstall=0;installcount=0
check(list(s['install_groups'])==group_order and len(group_order)==14,'14 installation groups exact plan order with no retries')
for i,g in enumerate(group_order):
 group=s['install_groups'][g];steps=[x for x in plan['steps'] if x['install_group']==g];identity=steps[0]['identity'];check(group['status']=='PASS' and group['identity']==identity and all(x['identity']==identity for x in steps),'Group identity/PASS '+g)
 text=(E/('install-'+g+'.log')).read_text();presence=[json.loads(line) for line in text.splitlines() if line.startswith('{')];check([x['package'] for x in presence]==packages,'Three package-presence checks '+g)
 check(all(x['stderr']=='' and x['presence_exit']==(1 if i==0 else 0) and (x['stdout']=='' if i==0 else x['stdout'].startswith('package:')) for x in presence),'Clean absent-first/uninstall-prior package state '+g)
 for p in packages:
  cnt=text.count(p+': Success\n');check(cnt==(0 if i==0 else 1),'Exact uninstall success '+g+':'+p);uninstall+=cnt
 for path in expectedpaths:
  cnt=text.count(path+': Performing Streamed Install\nSuccess');check(cnt==1,'Exact install success '+g+':'+path);installcount+=cnt
 envfiles=['install-'+g+'-environment-'+boundary+'.json' for boundary in ['before','after']]
 check(all((E/n).read_bytes()==device for n in envfiles),'Both environment bytes identical '+g)
 hostfiles=['install-'+g+'-host-'+boundary+'.json' for boundary in ['before','after']]
 hostdata=[read(n) for n in hostfiles];check(all(hh['uid']==1001 and hh['cpu_count']==4 and all(k in hh for k in ['load_1_5_15','memory','disk_bytes']) for hh in hostdata),'Host resource before/after recorded '+g)
 groups.append({'group':g,'identity':identity,'methods':[x['ordinal'] for x in steps],'status':group['status'],'install_log_sha256':sha(E/('install-'+g+'.log')),'environment_files':{n:sha(E/n) for n in envfiles},'host_files':{n:sha(E/n) for n in hostfiles}})
check(installcount==42 and uninstall==39,'42 installs and39 uninstalls succeeded exactly once')
check((E/'device-after.json').read_bytes()==device,'Overall device after exactly matches before')
flags=read('emulator-configuration.json')['command_flags'];check(flags.count('-accel')==1 and flags[flags.index('-accel')+1]=='on' and flags.count('-port')==1 and flags[flags.index('-port')+1]=='5554' and 'KVM (version 12) is installed and usable.' in (E/'acceleration.log').read_text(),'Same emulator5554 explicitly accelerated by KVM')
before=(E/'kvm-before.acl').read_bytes();grant=(E/'kvm-granted.acl').read_bytes();cleanups={}
check(before!=grant and b'user:1001:rw-\n' not in before and b'user:1001:rw-\n' in grant,'Temporary UID1001 ACL grant only expected recorded user')
check(set(grant.decode().splitlines())-set(before.decode().splitlines())=={'user:1001:rw-','mask::rw-'} and set(before.decode().splitlines())<=set(grant.decode().splitlines()),'ACL grant preserves all original lines, adds onlyuid andrequiredmask')
for tag in ['trap','always']:
 c=read('cleanup-'+tag+'.json');check(c=={'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'RESTORED_EXACT','errors':[]},'Exact successful cleanup '+tag)
 check((E/('kvm-after-'+tag+'.acl')).read_bytes()==before,'ACL restoration byte-identical '+tag)
 cleanups[tag]={'receipt':c,'receipt_sha256':sha(E/('cleanup-'+tag+'.json')),'after_acl_sha256':sha(E/('kvm-after-'+tag+'.acl'))}
import zipfile
with zipfile.ZipFile(archive) as z:
 names=z.namelist()
 check(len(names)==len(set(names))==294 and set(names)==set(inputs_before),'Original ZIP has294 unique exact text payload names')
 check(all(z.read(name)==(E/name).read_bytes() for name in names),'Every extracted evidence file equals original ZIP bytes')
check(archive.stat().st_size==772054 and sha(archive)=='79c53a73e262a22205519f5a994e10e6f2c75ca396a39bbd251d5bbd6b17993c','Exact current Library ZIP size and SHA')
packaging=read('packaging.json')
check(packaging=={'files':292,'bytes_before_manifest':3579522,'rejected_names':[],'truncated_files':0,'retention_days':3,'complete':True},'No omitted/truncated payload and original3day retention')
check(sum((E/n).stat().st_size for n in names)==3663735<20*1024*1024,'Original uncompressed text within20MiB')
meta=Path('/workspace/scratch/pr20-candidate3-execution')
job,=json.loads((meta/'jobs-terminal.json').read_text())['jobs']
run=json.loads((meta/'run-terminal.json').read_text())
art,=json.loads((meta/'artifacts.json').read_text())['artifacts']
def epoch(value):return datetime.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
check(job['id']==115639302352 and job['run_id']==run['id']==38498392929 and job['head_sha']==run['head_sha']==ACTIVE and run['run_attempt']==1,'Independent terminal metadata exact job/run/active/attempt')
check(run['head_branch']=='codex/kvm-pr20-candidate3-20261014' and run['status']==job['status']=='completed' and run['conclusion']==job['conclusion']=='success','Independent terminal job/run success branch')
check(art['id']==11866466104 and art['workflow_run']['id']==run['id'] and art['workflow_run']['head_sha']==ACTIVE and art['size_in_bytes']==archive.stat().st_size and art['digest']=='sha256:'+sha(archive),'Independent official artifact metadata binds this ZIP')
job_start=epoch(job['started_at']);job_end=epoch(job['completed_at']);anchor=s['budget_anchor_epoch']
clock={'anchor_epoch':anchor,'anchor_utc':read('clock-contract.json')['updated_at'],'actual_job_started_at':job['started_at'],'actual_job_started_epoch':job_start,'job_completed_at':job['completed_at'],'job_wall_seconds':job_end-job_start,'anchor_lead_seconds':job_start-anchor,'phase_cutoffs':s['phase_cutoff_epochs']}
check(job_start-anchor==187 and job_end-job_start==2342 and job_end-job_start<=3600,'Earlier clock by187s and actual39m02job within60m')
check(all(job_start<=v['started_epoch']<=v['started_epoch']+v['seconds']<=job_end for v in s['actions'].values()),'All measured actions enclosed by actual job')
check(read('clock-contract.json')['actual_job_started_epoch'] is None and read('clock.json')['actual_job_started_epoch'] is None,'Frozen clock does not invent actual job start')
check({k:v['git'] for k,v in spec['identities'].items()}=={'B':'a128bb07e34cb82f00eeafc8e62a47bf7b95b128','C0':'45b4861ae23ac19eda3246fcd34cf3d2132dc1ee','C1':'d66b60d6cf9587f646acb76dd8a00910b73a6d70'},'Actual baseline/previous/new candidate aliases')
check(len([x for x in s['methods'] if x.get('auxiliary_cleanup')])==1 and s['methods'][16].get('auxiliary_cleanup')==aux,'Only method17 has any helper cleanup request')
check(sum(x['selection']==h['selection'] for x in plan['steps'])==1,'Unique helper selection remains ordinal17')
check('force-stop' not in code[code.index('def processes_after'):code.index('def common_fixtures')].replace('never force-stop the application','').replace('never force-stop an active application',''),'No force-stop command/fallback in helper logic')
check(h['seconds']>h['process_exit_seconds'] and h['log_sha256']==sha(E/'method-17-C1-instrumentation.log'),'Raw helper JUnit independently bound to enclosing host operation')
check(all(x['elapsed_seconds']<3 for x in pre) and all(x['elapsed_seconds']-aux['started_seconds']<2 for x in post),'Every actual observation inside frozen3s/2s windows')
check(all((E/'dependencies-B.json').read_bytes()==(E/('dependencies-'+i+'.json')).read_bytes() for i in ['C0','C1']),'Dependency lists byte-identical across3identities')
pom=next(x for x in mupdf if x['path'].endswith('.pom'));aar=next(x for x in mupdf if x['path'].endswith('.aar'));official=read('official-dependency-check.json')
check(official['status']=='PASS' and official['http_code']=='200' and official['exit_code']==0 and official['sha256']==pom['sha256']=='d66b443446cb2bb992c0de60115ae4988584bd44d16c877d0ba5e2d400b416ff','Official POM availability and recorded dependency bytes agree')
check(aar['bytes']==21636412 and aar['sha256']=='e6887a242882cdd2dce062430bb46b6727513e3eb94e222255cc1a37db7a7d8e','Same complete native MuPDF AAR recorded source')
device_value=read('device-before.json')
check(device_value['sdk']=='35' and device_value['abi']=='x86_64' and device_value['qemu']=='1' and device_value['font_scale']=='1.0' and 'Override size: 1920x1200' in device_value['size'] and 'Override density: 240' in device_value['density'],'Recorded fixed API35 emulator display settings')
check(all(k in read(n) for n in ['host.json','host-after.json'] for k in ['load_1_5_15','memory','disk_bytes']),'Overall host resource boundaries recorded')
secret_patterns={'private_key':r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----','github_token':r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b','aws_access_key':r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'}
secret_hits=[]
for name in inputs_before:
 text=(E/name).read_text()
 for kind,pattern in secret_patterns.items():
  if re.search(pattern,text):secret_hits.append({'file':name,'kind':kind})
check(secret_hits==[],'Selected literal credential/private-key pattern scan has no hits')
check(not any(n.lower().endswith(('.apk','.aar','.so','.db','.sqlite','.sqlite3','.jks','.keystore','.key')) or 'logcat' in n.lower() for n in inputs_before),'No binary APK/dependency/database/key/full-logcat filename in published text payload')
inputs_after={p.name:sha(p) for p in E.iterdir() if p.is_file()}
check(inputs_before==inputs_after,'All294 raw files unchanged throughout this independent audit')
report={'status':'INDEPENDENT_RUNTIME_RECORD_AUDIT_PASS' if not issues else 'INDEPENDENT_RUNTIME_RECORD_AUDIT_WITH_ISSUES','scope':'Independent read-only raw text/ZIP/Git/metadata audit. No build, native, adb, network, mutation or binary APK/AAR/ELF reverification. Performance acceptance remains separate.',
 'run_id':38498392929,'job_id':115639302352,'active_git':ACTIVE,'readiness_git':READINESS,'preparation_git':'b3bcbcc9ca4e4134f0d2e4806bcd7bcf55ab0897',
 'identities':{k:{'git':v['git'],'canonical_manifest_sha256':v['canonical_manifest_sha256']} for k,v in spec['identities'].items()},
 'archive':{k:receipt[k] for k in ['artifact_id','archive_sha256','archive_bytes','payload_files','uncompressed_bytes']},'manifest_sha256':sha(E/'manifest.json'),'controller_hashes':controller_hashes,
 'checks':{'total':len(checks),'passed':sum(x['pass'] for x in checks),'issues':issues},'check_details':checks,'common_probes':probe_checks,
 'method_totals':{'calls':58,'distinct_selections':36,'phase_calls':dict(phase_counts),'raw_junit_pass':sum(x['raw_junit']=='PASS' for x in methods),'host_pass':sum(x['host']=='PASS' for x in methods),'natural_exit':len(natural),'helper_exit':1,'raw_marker_counts':dict(markers)},'methods':methods,'helper17':helper,
 'natural_exit_summary':{'count':len(natural),'maximum_process_exit_seconds':max(x['seconds'] for x in natural),'maximum_observations':max(x['observations'] for x in natural)},
 'common_fixtures':{'seeds':sorted({seed for seed,selection in fixtures}),'seed_selection_groups':len(fixtures),'all_three_way_equal':True,'limitation':'Equal recorded fixture identity/hash/scale/settings does not assert byte-identical databases.'},
 'apk_receipts':{'scope':'Runner-recorded hashes, signatures, embeddedidentity, 4ABI MuPDF and dependency receipts; binaries absent, not independently re-signed or reverified.','apks':apks,'common_mupdf_native':native[0],'dependency_counts':{k:len(v) for k,v in deps.items()},'dependencies_byte_identical':True,'mupdf_dependencies':mupdf,'jvm_tests':'NOT_RUN','lint':'NOT_RUN'},
 'installations':{'groups':groups,'group_count':14,'successful_installs':42,'successful_uninstalls':39,'group_environment_files':28,'including_overall_environment_files':30,'environment_sha256':sha(E/'device-before.json'),'device':device_value,'host_boundary_files':30},
 'cleanup':{'before_acl_sha256':sha(E/'kvm-before.acl'),'granted_acl_sha256':sha(E/'kvm-granted.acl'),'grant_added_lines':['user:1001:rw-','mask::rw-'],'both_after_exact_original_bytes':True,'receipts':cleanups},
 'clock':clock,'phase_action_seconds':{k:v['seconds'] for k,v in s['actions'].items()},'terminal_metadata_sha256':{n:sha(meta/n) for n in ['jobs-terminal.json','run-terminal.json','artifacts.json']},
 'privacy':{'scanned_literal_files':len(inputs_before),'patterns':list(secret_patterns),'hits':secret_hits,'scope':'Narrow exact-pattern scan, not a general guarantee. Manifest contains synthetic instrumentation and public dependency/signature receipts. Do not add APKs, DBs, private signing material, task-cache directories or environment dumps. Preserve unmodified raw hashes.'},
 'limitations':['58 executions comprise36 unique selections; stale workflow label30 does not override31 correctness methods.','No claim of physical Android device performance; this is one KVM API35 AOSP ATD x86_64/SwiftShader emulator.','Natural window is3s with0.25s edge guard; helper request happened at2.754429444s, not after a literal full3s sleep. Postcheck ends upon absence, not after a mandatory2s wait.','PID presence alone does not prove that helper was cached; eligibility is rawPASS+adb0+unique helper+application/data absent, followed by safe am kill and strict disappearance.','Runtime PASS does not close statistical regression/inconclusive or GC exposure gates; unchanged analyzer conclusions remain authoritative.','Text receipts cannot independently revalidate APK signatures or native ELF binaries. JVM/lint were not run by this native job.'],
 'evidence_unchanged':{'files':len(inputs_before),'all_hashes_match':inputs_before==inputs_after},'audit_procedure_sha256':sha(Path(__file__))}
OUT[0].write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
md=f'''本轮独立原始运行记录审计：**{'PASS' if not issues else '存在问题'}**。run38498392929 / job115639302352，active `{ACTIVE}`，readiness `{READINESS}`。58次原JUnit与host均PASS，36个不同selection；31正确性/24公共/3诊断。B=a128、C0=45b、C1=d66。运行通过不等于性能门槛关闭，保留冻结分析器全部回退/不确定结果。

原ZIP772054字节，SHA256 `{sha(archive)}`；292 payload加manifest/packaging共294文件、3663735解压字节，逐个与原ZIP/manifest原字节核对。审计前后294文件hash不变，未修改raw、源码或控制器。所有readiness和执行hash绑定固定Git对象；没有依赖自动verifier的PASS代替逐项检查。

**方法17人工前置复核**：先读原instrumentation确认唯一OK(1)、准确class/method、终态-1、adb0，再读完整21次natural观察、单次auxiliary_cleanup及后置观察，并与固定method()/processes_after()代码顺序核对。21次natural观察中app和data.test始终exit1/空，只有helper app.a0.test PID2717持续存在。最后natural观察从{pre[-1]['elapsed_seconds']:.9f}s开始；单次 `am kill --user 0 org.inkweft.app.a0.test` 请求于{aux['started_seconds']:.9f}s，退出0/空。后置观察从{post[0]['elapsed_seconds']:.9f}s开始三包已全gone；完整确认结束于{h['process_exit_seconds']:.9f}s。请求后完整确认仅{h['process_exit_seconds']-aux['started_seconds']:.9f}s，符合≤2s且总≤5s。

原自然窗口为3s，控制器预留0.25s避免在末端启动三条查询；实际请求为2.754429444s，**不能说足等3秒**。后置≤2s是上限，全gone即可结束，不需等满2秒。仅此唯一选择/phase/C1/runner允许helper清理；其余57方法都是NATURAL_EXIT、无辅助清理字段，最长退出确认{max(x['seconds'] for x in natural):.9f}s。没有force-stop/fallback，也没有推断PID当时必为cached。顺序证据是原raw结果、单调观察记录及哈希绑定代码共同支持，不是假造每条adb的独立墙钟时间戳。

58份raw逐一解析OK(1)、状态code一次1其余0、两组准确class/method/numtests、终态-1；58份records逐项匹配原JSON marker，58份JUnit XML各一测试且0failure/0error；所有args/ordinal/seed/identity/installgroup精确匹配冻结计划。四种子×两公共selection×三端夹具记录相等，不据此说DB字节相同。方法31新增六取消门控用例原JUnit及host均PASS；过时的workflow“30项”显示名没有改变31条实际正确性执行。

9APK回执按三端各三件核对，全部同一记录签名证书 `{signers[0]}`；3个app的source_commit分别为实际B/C0/C1、build_commit为d20、formal=false。三端各4个MuPDF ABI记录对应字节/hash一致，879项依赖清单三端逐字节相同。完整fitz1.28.5 AAR21636412字节、SHA `{aar['sha256']}`；官方POM200回执与依赖POM hash一致。冻结runner确实调用apksigner、检查ELF魔数/尺寸并在安装前重hash；**这里只复核回执和代码，ZIP没有APK/AAR/.so，未独立对二进制重新验签或验ELF。** 本job JVM/lint均NOT_RUN。

14安装组顺序/身份均匹配：42次安装Success，第一组三包原本缺席，后13组39次卸载Success。28份分组前后环境加整体前后共30份字节一致，SHA `{sha(E/'device-before.json')}`。API35 AOSP ATD x86_64、KVM强制on、emulator5554、SwiftShader；1920×1200/density240/font_scale1.0。动画值保持原记录（1.0/1.0/null），hw_timeout_multiplier为空；不将该环境算真机。30份host边界有load/memory/disk记录，不作无依据的性能因果归因。

KVM before ACL SHA `{sha(E/'kvm-before.acl')}`。临时grant只增加uid1001 rw与mask，保留原行。trap和always均STOPPED_CONFIRMED/RESTORED_EXACT/errors[]，两份after ACL分别与before逐字节一致；未只相信恢复标签。

独立终态metadata核对：冻结anchor01:53:21Z、实际job01:56:28Z，前置187s；02:35:30Z结束，2342s（39m02），小于60min。所有actions/phases及方法都在38/41/53/55累计截止前完成。原summary保留EXECUTION_COMPLETE_ANALYSIS_PENDING，包装只增加cleanup_verified，不改写原状态和时刻。

共{len(checks)}项一致性核对，问题{len(issues)}；详见[audit-runtime.json](audit-runtime.json)及只读复核过程[audit-runtime-procedure.py](audit-runtime-procedure.py)。

发布建议：可以按原清单保留这些合成原instrumentation、方法XML/records、来源/依赖/签名回执、环境/清理字节与本报告，明确它们是记录审计。294个文本文件的私钥/GitHub-token/AWS-key字面模式扫描无命中；这不是一般隐私保证。不要附加APK/AAR/数据库、签名私钥、Gradle/task cache、全量logcat或环境dump；不要删旧失败/回退样本。用户授权回执含内部conversation标识，可在新增说明中省略；不要直接改动已哈希的原始回执。性能结果引用冻结分析器，不以本审计PASS替代性能验收。
'''
OUT[1].write_text(md)
print(json.dumps({'status':report['status'],'checks':len(checks),'issues':issues,'json_sha256':sha(OUT[0]),'md_sha256':sha(OUT[1])},ensure_ascii=False,indent=2))
