"""One approved paired job. Invoked once per visible workflow stage; no retries."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=Path(os.environ['KVM_ONCE']); OUT=ROOT/'evidence'; OUT.mkdir(parents=True,exist_ok=True)
CONTROL=HERE.parents[1]
SDK=Path(os.environ['ANDROID_HOME'])
START=int(os.environ['KVM_JOB_STARTED']); SETUP=START+26*60; DEADLINE=START+55*60
os.environ['PATH']=str(SDK/'platform-tools')+':'+str(SDK/'cmdline-tools/latest/bin')+':'+os.environ['PATH']
ADB=['adb','-s','emulator-5554']
PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test']
PROTOCOL=json.loads((HERE/'protocol.json').read_text())
SPEC=json.loads((HERE/'source-spec.json').read_text())
METHODS=[
 ('local-append','org.inkweft.app.KvmLocalAppendPairProbe#oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions',PACKAGES[1],180),
 ('library','org.inkweft.data.KvmLibraryPairProbe#coldSearchConcurrentSavesAndShelfReadScale',PACKAGES[2],240),
 ('history','org.inkweft.data.KvmHistoryPairProbe#collectLargeGarbageWithRootsAndConcurrentAuthorSaves',PACKAGES[2],180)]
SEQUENCE=[(p['pair'],p['seed'],side) for p in PROTOCOL['pairs'] for side in p['order']]
SUMMARY=OUT/'summary.json'
REPORT=json.loads(SUMMARY.read_text()) if SUMMARY.exists() else {
 'run_id':os.environ['GITHUB_RUN_ID'],'run_attempt':os.environ['GITHUB_RUN_ATTEMPT'],
 'workflow_sha':os.environ['GITHUB_SHA'],'source_spec':SPEC,'protocol_sha256':hashlib.sha256((HERE/'protocol.json').read_bytes()).hexdigest(),
 'job_started_epoch':START,'setup_cutoff_epoch':SETUP,'work_deadline_epoch':DEADLINE,
 'status':'PREPARING','phases':[],
 'methods':[{'visit':i,'pair':p,'seed':seed,'side':side,'name':n,'selection':s,'status':'NOT_RUN'}
            for i,(p,seed,side) in enumerate(SEQUENCE,1) for n,s,_,_ in METHODS],
 'scope':'Four pairs on one KVM emulator; debug synthetic workload only. Raw observations do not prove absence of all performance regressions.'}

class BudgetAbort(RuntimeError): pass
def save():
    tmp=SUMMARY.with_suffix('.tmp');tmp.write_text(json.dumps(REPORT,ensure_ascii=False,indent=2)+'\n');tmp.replace(SUMMARY)
def seconds(limit,setup=False):
    left=(SETUP if setup else DEADLINE)-time.time()
    if left<=0:raise BudgetAbort('Setup cutoff' if setup else '55-minute work deadline')
    return min(limit,left)
def owned(pid):
    f=Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
    return {'pid':pid,'uid':os.getuid(),'start':f[19]}
def run(argv,limit=60,setup=False,check=True,**kwargs):
    argv=[str(x) for x in argv];timeout=seconds(limit,setup)
    data=kwargs.pop('input',None)
    if data is not None:kwargs['stdin']=subprocess.PIPE
    with subprocess.Popen(argv,start_new_session=True,**kwargs) as p:
        groups=ROOT/'command-groups.json'; values=json.loads(groups.read_text()) if groups.exists() else []
        try:values.append(owned(p.pid))
        except FileNotFoundError:
            # A completed short read command has no live group leader to record.
            if p.poll() is None:raise
        temporary=groups.with_suffix('.tmp');temporary.write_text(json.dumps(values));temporary.replace(groups)
        try:stdout,stderr=p.communicate(input=data,timeout=timeout)
        except subprocess.TimeoutExpired:
            try:os.killpg(p.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            p.wait();raise BudgetAbort('Command management/deadline limit: '+str(argv[0]))
        result=subprocess.CompletedProcess(argv,p.returncode,stdout,stderr)
        if check:result.check_returncode()
        return result
def capture(argv,limit=30,setup=False):
    return run(argv,limit,setup=setup,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True).stdout.strip()
def shell(*args):return capture(ADB+['shell',*args])
def phase(name,argv,limit=600,setup=False,**kwargs):
    item={'name':name,'status':'RUNNING','started_epoch':time.time()};REPORT['phases'].append(item);save()
    try:
        with (OUT/(name+'.log')).open('w') as f:
            result=run(argv,limit,setup=setup,stdout=f,stderr=subprocess.STDOUT,text=True,check=False,**kwargs)
        item['exit_code']=result.returncode
        if result.returncode:raise RuntimeError(name+' failed; see raw stage log; no retry')
        item['status']='PASS'
    except Exception:
        item['status']='INCOMPLETE' if time.time()>=DEADLINE else 'FAIL';raise
    finally:item['seconds']=time.time()-item['started_epoch'];save()
def environment():
    return {'sdk':shell('getprop','ro.build.version.sdk'),'abi':shell('getprop','ro.product.cpu.abi'),
            'fingerprint':shell('getprop','ro.build.fingerprint'),'qemu':shell('getprop','ro.kernel.qemu'),
            'hw_timeout_multiplier':shell('getprop','ro.hw_timeout_multiplier'),'size':shell('wm','size'),'density':shell('wm','density'),
            'font_scale':shell('settings','get','system','font_scale'),
            'animations':{k:shell('settings','get','global',k) for k in ['window_animation_scale','transition_animation_scale','animator_duration_scale']}}
def source_manifest(side):
    root=ROOT/'sources'/side
    expected=json.loads((HERE/f'{side}-source-manifest.json').read_text())
    # Ignore generated build/.gradle outputs, but verify every frozen input byte.
    for p,item in expected['files'].items():
        b=(root/p).read_bytes()
        assert len(b)==item['bytes'] and hashlib.sha256(b).hexdigest()==item['sha256'], 'Source changed: '+p
    return expected['canonical_manifest_sha256']

def setup():
    assert REPORT['run_attempt']=='1'
    auth=json.loads((HERE/'authorization.json').read_text())
    assert auth['approved'] and auth['approved_protocol_sha256']==REPORT['protocol_sha256']
    assert auth['approved_plan_sha256']==hashlib.sha256((HERE/'PLAN.md').read_bytes()).hexdigest()
    assert auth['source_spec_sha256']==hashlib.sha256((HERE/'source-spec.json').read_bytes()).hexdigest()
    assert SEQUENCE==[(1,4101,'C'),(1,4101,'B'),(2,4102,'B'),(2,4102,'C'),(3,4103,'B'),(3,4103,'C'),(4,4104,'C'),(4,4104,'B')]
    assert not capture(['git','-C',CONTROL,'status','--porcelain'],setup=True)
    phase('source-fetch',['git','-C',CONTROL,'fetch','--no-tags','--depth=1','origin',SPEC['baseline_git'],SPEC['candidate_git']],180,setup=True)
    phase('source-reconstruction',['python3',HERE/'reconstruct_sources.py','--repo',CONTROL,'--out',ROOT/'sources'],180,setup=True)
    host={'uid':os.getuid(),'cpu_count':os.cpu_count(),'kernel':os.uname().release,
          'cpu_model':next((s.split(':',1)[1].strip() for s in Path('/proc/cpuinfo').read_text().splitlines() if s.startswith('model name')),None),
          'memory_kib':Path('/proc/meminfo').read_text().splitlines()[0],'java':capture(['java','-version'],setup=True)}
    (OUT/'host.json').write_text(json.dumps(host,indent=2))
    phase('sdk',['sdkmanager','platforms;android-36','build-tools;36.0.0','platform-tools','emulator','system-images;android-35;aosp_atd;x86_64'],900,setup=True)
    # No KVM mutation before both builds have succeeded.
    REPORT['status']='SOURCES_FROZEN';save()

def build(side):
    root=ROOT/'sources'/side; before=source_manifest(side)
    env=os.environ.copy();env['INKWEFT_HEAD_SHA']=SPEC['baseline_git' if side=='B' else 'candidate_git']
    phase('build-'+side,['./gradlew','--no-daemon','--max-workers=1','--no-build-cache',
        '-Pkotlin.compiler.execution.strategy=in-process','-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8',
        ':app:assembleDebug',':app:assembleDebugAndroidTest',':data-local:assembleDebugAndroidTest'],1440,setup=True,cwd=root/'android',env=env)
    assert source_manifest(side)==before
    paths=['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
           'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
    receipts=[]
    for p in paths:
        apk=root/'android'/p
        item={'path':p,'bytes':apk.stat().st_size,'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()}
        item['signature']=capture([SDK/'build-tools/36.0.0/apksigner','verify','--print-certs',apk],60,setup=True)
        receipts.append(item)
    with zipfile.ZipFile(root/'android'/paths[0]) as z:
        identity=json.loads(z.read('assets/inkweft-build-identity.json'))
        assert identity['source_commit']==env['INKWEFT_HEAD_SHA'] and identity['build_commit']==os.environ['GITHUB_SHA'] and identity['formal'] is False
        receipts[0]['build_identity']=identity; native=[]
        for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
            names=[n for n in z.namelist() if n.startswith('lib/'+abi+'/') and 'mupdf' in n and n.endswith('.so')]
            assert names,'Missing MuPDF ABI '+abi
            for name in names:
                b=z.read(name);assert b.startswith(b'\x7fELF') and len(b)>100000
                native.append({'path':name,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()})
        receipts[0]['mupdf_native']=native
    deps=[];cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
    if cache.exists():
        for p in sorted(cache.rglob('*')):
            if p.is_file():deps.append({'path':p.relative_to(cache).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.file_digest(p.open('rb'),'sha256').hexdigest()})
    (OUT/('dependencies-'+side+'.json')).write_text(json.dumps(deps,indent=2))
    (OUT/('apk-'+side+'.json')).write_text(json.dumps({'side':side,'base_commit':env['INKWEFT_HEAD_SHA'],'source_manifest_sha256':before,'overlay_identity':'See source-spec: embedded source commit is the Git base, not the complete overlaid source','apks':receipts},indent=2))
    if side=='C':
        b=json.loads((OUT/'apk-B.json').read_text())['apks'][0]['mupdf_native'];assert b==native,'MuPDF bytes differ between builds'
        prior={v['path']:v for v in json.loads((OUT/'dependencies-B.json').read_text())}
        now={v['path']:v for v in deps}
        assert all(now.get(p)==v for p,v in prior.items()),'Downloaded dependency changed or disappeared'

def start_emulator():
    seconds(1,setup=True)
    for side in ['B','C']:assert (OUT/('apk-'+side+'.json')).exists()
    node=os.lstat('/dev/kvm');assert stat.S_ISCHR(node.st_mode)
    (OUT/'kvm-identity.json').write_text(json.dumps([node.st_dev,node.st_ino,node.st_rdev]))
    (OUT/'kvm-before.acl').write_bytes(run(['getfacl','-pn','/dev/kvm'],15,setup=True,stdout=subprocess.PIPE).stdout)
    if not (os.access('/dev/kvm',os.R_OK) and os.access('/dev/kvm',os.W_OK)):
        (ROOT/'acl-may-have-changed').touch()
        run(['sudo','-n','setfacl','--no-mask','-m',f'u:{os.getuid()}:rw','/dev/kvm'],15,setup=True)
    assert os.access('/dev/kvm',os.R_OK) and os.access('/dev/kvm',os.W_OK),'Current mask prevents access; stop without widening'
    (OUT/'kvm-granted.acl').write_bytes(run(['getfacl','-pn','/dev/kvm'],15,setup=True,stdout=subprocess.PIPE).stdout)
    phase('acceleration',[SDK/'emulator/emulator','-accel-check'],30,setup=True)
    phase('avd',['avdmanager','create','avd','--name','pr20-kvm-paired','--package','system-images;android-35;aosp_atd;x86_64','--device','pixel_2'],120,setup=True,input='no\n')
    command=[str(SDK/'emulator/emulator'),'-avd','pr20-kvm-paired','-port','5554','-no-window','-no-snapshot','-noaudio','-no-boot-anim','-gpu','swiftshader','-accel','on','-cores','2','-memory','2048']
    with (ROOT/'emulator-private.log').open('w') as log:p=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    (ROOT/'emulator.json').write_text(json.dumps(owned(p.pid)))
    until=min(SETUP,time.time()+240)
    while time.time()<until:
        if p.poll() is not None:raise RuntimeError('Emulator exited before boot')
        r=run(ADB+['shell','getprop','sys.boot_completed'],10,setup=True,check=False,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
        if r.stdout.strip()=='1':break
        time.sleep(2)
    else:raise BudgetAbort('Emulator boot/setup cutoff')
    assert shell('getprop','ro.build.version.sdk')=='35' and shell('getprop','ro.kernel.qemu')=='1'
    for args in [('wm','size','1920x1200'),('wm','density','240'),('settings','put','system','font_scale','1.0'),
                 ('settings','put','global','window_animation_scale','1.0'),('settings','put','global','transition_animation_scale','1.0'),
                 ('settings','delete','global','animator_duration_scale'),('svc','power','stayon','true'),('input','keyevent','KEYCODE_WAKEUP'),('wm','dismiss-keyguard')]:shell(*args)
    seconds(1,setup=True)
    initial=environment();(OUT/'device-before.json').write_text(json.dumps(initial,indent=2))
    log=(ROOT/'emulator-private.log').read_text(errors='replace')
    selected=[line for line in log.splitlines() if 'Android emulator version ' in line or 'Boot completed in ' in line]
    (OUT/'emulator-selected.log').write_text('\n'.join(selected)+'\n')
    (OUT/'emulator-configuration.json').write_text(json.dumps({'command_flags':command[1:],'image_source_properties':(SDK/'system-images/android-35/aosp_atd/x86_64/source.properties').read_text()},indent=2))
    REPORT['setup_seconds']=time.time()-START;REPORT['status']='MEASURING';save()

def raw_result(entry,raw,result):
    ok=re.findall(r'^OK \((\d+) tests?\)\s*$',raw,re.M)
    codes=[int(x) for x in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)',raw,re.M)]
    classes=re.findall(r'^INSTRUMENTATION_STATUS: class=(.*)$',raw,re.M)
    methods=re.findall(r'^INSTRUMENTATION_STATUS: test=(.*)$',raw,re.M)
    counts=re.findall(r'^INSTRUMENTATION_STATUS: numtests=(.*)$',raw,re.M)
    cls,method=entry['selection'].split('#')
    valid=(result.returncode==0 and ok==['1'] and codes.count(1)==1 and 0 in codes and not any(x<0 for x in codes)
           and len(classes)==2 and all(x.strip()==cls for x in classes) and len(methods)==2 and all(x.strip()==method for x in methods)
           and len(counts)==2 and all(x.strip()=='1' for x in counts)
           and re.search(r'^INSTRUMENTATION_CODE: -1\s*$',raw,re.M) and 'FAILURES!!!' not in raw and 'INSTRUMENTATION_FAILED' not in raw)
    entry.update(junit_status='PASS' if valid else 'FAIL',adb_exit=result.returncode,junit_ok_counts=ok,status_codes=codes)
    records=[]
    for line in raw.splitlines():
        m=re.search(r'(KVM_[A-Z_]+|LIBRARY_[A-Z_]+|HISTORY_COLLECTION_EVIDENCE|TILE_LOCAL_EVIDENCE|INDEX_SAVE_PROGRESS) (\{.*\})\s*$',line)
        if m:records.append({'marker':m[1],'data':json.loads(m[2])})
    return records

def visit(number):
    pair,seed,side=SEQUENCE[number-1]
    assert all(x['status']=='PASS' for x in REPORT['methods'] if x['visit']<number)
    initial=json.loads((OUT/'device-before.json').read_text());before=environment();assert before==initial,'Device settings changed'
    (OUT/f'visit-{number:02d}-environment-before.json').write_text(json.dumps(before,indent=2))
    apkdata=json.loads((OUT/('apk-'+side+'.json')).read_text())
    install_log=[]
    for package in PACKAGES:
        probe=run(ADB+['shell','pm','path',package],30,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        assert probe.returncode in [0,1] and not probe.stderr.strip(),'Package presence command/transport failed'
        locations=probe.stdout.strip()
        assert not locations or (probe.returncode==0 and all(line.startswith('package:') for line in locations.splitlines())),'Unexpected package presence result'
        if locations:
            result=capture(ADB+['uninstall',package],60);assert result=='Success';install_log.append(package+': uninstalled')
        else:install_log.append(package+': not previously installed')
    for item in apkdata['apks']:
        apk=ROOT/'sources'/side/'android'/item['path']
        assert hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()==item['sha256']
        result=capture(ADB+['install','-t',apk],120);assert result.endswith('Success');install_log.append(item['path']+': '+result)
    (OUT/f'visit-{number:02d}-install.log').write_text('\n'.join(install_log)+'\n')
    for name,selection,package,limit in METHODS:
        seconds(1)
        entry=next(x for x in REPORT['methods'] if x['visit']==number and x['name']==name)
        entry.update(status='RUNNING',started_epoch=time.time());save()
        prefix=f'visit-{number:02d}-{side}-{name}'
        logpath=OUT/(prefix+'-instrumentation.log')
        argv=ADB+['shell','am','instrument','-w','-r','-e','pairSeed',str(seed),'-e','historyScanBaseline','true' if side=='B' else 'false','-e','class',selection,package+'/androidx.test.runner.AndroidJUnitRunner']
        with logpath.open('w') as log:result=run(argv,limit,check=False,stdout=log,stderr=subprocess.STDOUT,text=True)
        entry['seconds']=time.time()-entry['started_epoch'];entry['log_sha256']=hashlib.sha256(logpath.read_bytes()).hexdigest()
        raw=logpath.read_text(errors='replace');records=raw_result(entry,raw,result)
        (OUT/(prefix+'-records.json')).write_text(json.dumps(records,ensure_ascii=False,indent=2))
        suite=ET.Element('testsuite',name=selection,tests='1',failures=str(int(entry['junit_status']!='PASS')),time=str(entry['seconds']))
        case=ET.SubElement(suite,'testcase',classname=selection.split('#')[0],name=selection.split('#')[1],time=str(entry['seconds']))
        if entry['junit_status']!='PASS':ET.SubElement(case,'failure',message='See raw instrumentation').text=raw[-8192:]
        ET.ElementTree(suite).write(OUT/(prefix+'-junit.xml'),encoding='utf-8',xml_declaration=True)
        assert entry['junit_status']=='PASS','Original method/JUnit failed; stop without retry'
        entry['fixtures']=[x['data'] for x in records if x['marker']=='KVM_PAIR_FIXTURE']
        assert len(entry['fixtures'])==(3 if name=='local-append' else 1),'Missing fixture evidence'
        other=next((x for x in REPORT['methods'] if x['pair']==pair and x['side']!=side and x['name']==name and x['status']=='PASS'),None)
        if other is not None:assert entry['fixtures']==other['fixtures'],'B/C input hash, rows or writer settings mismatch'
        if name!='local-append':
            marker='LIBRARY_READ_EVIDENCE' if name=='library' else 'HISTORY_COLLECTION_EVIDENCE'
            final=[x['data'] for x in records if x['marker']==marker]
            assert len(final)==1 and final[0]['writerSqlite']==entry['fixtures'][0]['writerSqlite'],'Writer settings changed during method'
        entry['processes_after']={}
        for pkg in PACKAGES:
            p=run(ADB+['shell','pidof',pkg],10,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
            entry['processes_after'][pkg]={'exit':p.returncode,'stdout':p.stdout.strip(),'stderr':p.stderr.strip()}
        assert all(p['exit']==1 and not p['stdout'] and not p['stderr'] for p in entry['processes_after'].values()),'Process cleanup unconfirmed'
        entry['status']='PASS';save();print(json.dumps(entry,ensure_ascii=False),flush=True)
    after=environment();(OUT/f'visit-{number:02d}-environment-after.json').write_text(json.dumps(after,indent=2));assert after==initial
    if number==8:
        (OUT/'device-after.json').write_text(json.dumps(after,indent=2));REPORT['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING';save()

save()
try:
    action=sys.argv[1]
    if action=='setup':setup()
    elif action=='build':build(sys.argv[2])
    elif action=='emulator':start_emulator()
    elif action=='visit':visit(int(sys.argv[2]))
    else:raise ValueError('Unknown stage')
except Exception as error:
    REPORT['status']='BUDGET_ABORTED' if isinstance(error,BudgetAbort) else 'BLOCKED_OR_FAILED'
    REPORT['error']=type(error).__name__+': '+str(error)
    if isinstance(error,subprocess.CalledProcessError):REPORT['failed_command_output']=str(error.stdout or '')[-8192:]
    for entry in REPORT['methods']:
        if entry['status']=='RUNNING':entry.update(status='INCOMPLETE',seconds=time.time()-entry['started_epoch'])
    save();print(json.dumps({'status':REPORT['status'],'error':REPORT['error']},ensure_ascii=False),flush=True)
    raise SystemExit(1)
save()
