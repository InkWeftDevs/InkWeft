"""Offline faults only. No network, Gradle, ADB, emulator or permission operations."""
import base64,argparse,ast,copy,zipfile,hashlib,io,json,math,os,re,shutil,subprocess,sys,time,types,xml.etree.ElementTree as ET
from collections import Counter
from contextlib import redirect_stdout
from pathlib import Path
import yaml
p=argparse.ArgumentParser();p.add_argument('--out',required=True,type=Path);p.add_argument('--controller',type=Path,default=Path(__file__).resolve().parent);p.add_argument('--readiness',type=Path);p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft'));a=p.parse_args();here=a.controller.resolve()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists();a.out.mkdir(parents=True)
sys.dont_write_bytecode=True
sys.path.insert(0,str(here));import markers,size_bounds
checks=[]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def reject(f):
 try:f()
 except (AssertionError,ValueError,TypeError,KeyError,RecursionError,RuntimeError,OSError):return
 raise AssertionError('Fault accepted')
def check(name,f):
 value=f();checks.append({'name':name,'status':'PASS','details':value})
plan=json.loads(((a.readiness/'method-plan.json')if a.readiness else (here/'method-plan.template.json')).read_text());contract=json.loads(((a.readiness/'marker-contract.json')if a.readiness else (here/'marker-contract.template.json')).read_text());contract['status']='FROZEN'
source=(here/'run.py').read_text();tree=ast.parse(source)
ns={'base64':base64,'re':re,'json':json,'math':math,'Counter':Counter,'Path':Path,'hashlib':hashlib,'time':time,'os':os,'subprocess':subprocess,'ET':ET,'sys':sys,'shutil':shutil,'zipfile':zipfile}
for n in tree.body:
 if isinstance(n,(ast.FunctionDef,ast.ClassDef)):exec(compile(ast.Module([n],type_ignores=[]),'actual-run-ast','exec'),ns)
for node in tree.body:
 if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id in ['CHUNK_BYTES','SECRET','PREFIX','BINDING_KEYS'] for t in node.targets):exec(compile(ast.Module([node],type_ignores=[]),'helper-constants','exec'),ns)
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
   assert subprocess.check_output(['git','-C',str(a.repo),'show',auth['plan_git']+':android/verification/pr20-gc-save-20261014/'+file])==(a.readiness/file).read_bytes()
  assert auth['source_spec_sha256'] in source
 else:
  assert "FROZEN_SOURCE_SPEC_SHA256 = 'UNBOUND'" in source
  result=subprocess.run([sys.executable,here/'run.py','setup'],capture_output=True,text=True);assert result.returncode!=0 and 'Finalize source identity' in result.stderr
  auth=json.loads((here/'authorization.json').read_text());assert auth['approved'] is False and auth['status']=='UNBOUND' and auth['plan_git']=='UNBOUND'
  result=subprocess.run([sys.executable,here/'package.py'],capture_output=True,text=True);assert result.returncode!=0 and 'Finalize source-spec hash' in result.stderr
 assert '--no-build-cache' in source and ':data-local:assembleDebugAndroidTest' in source and ':app:assemble' not in source
 assert 'maven.ghostscript.com' not in source and 'official_dependency_check' not in source
 assert [s['ordinal']for s in plan['steps']]==list(range(1,9)) and Counter(s['phase']for s in plan['steps'])=={'data-diagnostics':8}
 assert len({s['install_group']for s in plan['steps']})==1 and {s['identity']for s in plan['steps']}=={'C1'}
 assert [s['ordinal']for s in plan['steps']if s['outer_cap_seconds']==300]==[8]
 assert [s['ordinal']for s in plan['steps']if s['marker_policy']=='none']==list(range(1,6))
 oldtree=ast.parse((old/'run.py').read_text());copied=['write_json','save','seconds','digest','owned','run','capture','shell','phase','host','environment','source_manifest','junit','raw_result']
 # raw_result deliberately adds RASTER to marker discovery; strict JUnit unchanged.
 for name in copied[:-1]:
  now=next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name);before=next(n for n in oldtree.body if isinstance(n,ast.FunctionDef)and n.name==name);assert ast.dump(now)==ast.dump(before),name
 proc=ast.unparse(next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name=='processes_after'))
 assert 'force-stop' not in re.findall(r"'([^']+)'",proc) and "'kill'" not in proc
 return {'methods':8,'groups':1,'source_identities':1,'apk_count':1,'execution_binding':'REAL_READINESS_MATCH' if a.readiness else 'UNBOUND_REFUSED','copied_functions':copied[:-1],'cleanup_byte_identical':True}
check('unbound isolation and exact scope',static)
def workflow():
 value=yaml.safe_load((here/'workflow.proposed.yml').read_text());job=value['jobs']['causal'];assert job['timeout-minutes']==60 and job['runs-on']=='ubuntu-24.04' and value['permissions']=={'contents':'read'}
 assert 'github.run_attempt == 1' in job['if'] and 'github.event.created == false' in job['if'] and len(re.findall('[0-9a-f]{40}',job['if']))==1
 if not a.readiness:assert '0'*40 in job['if'], 'Inactive draft prep-parent guard must stay zero'
 count=0
 for i,step in enumerate(job['steps']):
  if 'run'not in step:continue
  f=a.out/f'step-{i:02}.sh';f.write_text(step['run']);subprocess.run(['bash','-n',f],check=True,capture_output=True)
  for _,code in re.findall(r"python3 - <<'([^']+)'\n(.*?)\n\1",step['run'],re.S):ast.parse(code)
  if 'run.py' in step['run']:assert 'timeout --signal=TERM --kill-after=2' in step['run'];count+=1
 assert count==4
 lint=subprocess.run(['/workspace/scratch/actionlint-1.7.12/actionlint','-shellcheck=','-pyflakes=',here/'workflow.proposed.yml'],capture_output=True,text=True);(a.out/'actionlint.log').write_text(lint.stdout+lint.stderr);assert lint.returncode==0,lint.stderr
 root=a.out/'clock';root.mkdir();envfile=root/'env';envfile.touch();env=os.environ.copy();env.update(RUNNER_TEMP=str(root),GITHUB_ENV=str(envfile))
 command=job['steps'][0]['run'];first=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert first.returncode==0,first.stderr
 values=dict(line.split('=',1)for line in envfile.read_text().splitlines());assert len(values)==8 and all('\\n'not in x for x in values.values())
 clock=json.loads((root/'pr20-gc-save/evidence/clock.json').read_text());assert clock['deadlines_minutes']==[30,45,55,60] and clock['scope']=='DIAGNOSTIC_ONLY'
 before=envfile.read_bytes();again=subprocess.run(['bash','-c',command],env=env,capture_output=True,text=True);assert again.returncode!=0 and envfile.read_bytes()==before
 return {'actionlint':0,'phase_wrappers':4,'actual_clock_lines':8,'second_clock_rejected':True}
check('workflow syntax and isolated real first-step clock',workflow)
def budget():
 fake=types.SimpleNamespace(time=lambda:1000.0);ns.update(time=fake,START=1000,PHASE='setup')
 for name in ['CUTOFFS','DEADLINE']:
  n=next(n for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id==name for t in n.targets));exec(compile(ast.Module([n],type_ignores=[]),'actual-cutoffs','exec'),ns)
 assert ns['CUTOFFS']=={'setup':2800,'data':3700,'work':4300}
 for phase,end in ns['CUTOFFS'].items():
  ns['PHASE']=phase
  for offset in [-.01,0,.01]:
   fake.time=lambda end=end,offset=offset:end+offset
   if offset<0:assert 0<ns['seconds']()<=.011
   else:reject(ns['seconds'])
 ns['time']=time;return {'phase_boundaries':9}
check('phase/work absolute deadline boundaries',budget)
def main_scope():
 saved=dict(ns);env=dict(os.environ);results=[]
 try:
  for count in [8,7,9,10]:
   report={'input_hashes':{},'source_spec':{},'actual_job_started_epoch':None,'budget_anchor_epoch':1000,'workflow_sha':'a'*40,'phase_cutoff_epochs':{'setup':2800,'data':3700,'work':4300},'work_deadline_epoch':4300,'run_id':'900001','run_attempt':'1','methods':[dict(step)for step in plan['steps']],'actions':{},'status':'PREPARING'}
   if count<8:report['methods']=report['methods'][:count]
   if count>8:report['methods'] += [dict(plan['steps'][-1]) for _ in range(count-8)]
   os.environ.update(GITHUB_SHA='a'*40,GITHUB_RUN_ID='900001',GITHUB_RUN_ATTEMPT='1')
   called=[];ns.update(sys=types.SimpleNamespace(argv=['run.py','setup']),REPORT=report,INPUT_HASHES={},SPEC={},START=1000,CUTOFFS=report['phase_cutoff_epochs'],DEADLINE=4300,PLAN=plan,time=time,save=lambda:None,seconds=lambda:1,setup=lambda:called.append('setup'))
   if count==8:ns['main']();assert called==['setup'] and report['actions']['setup']['status']=='PASS'
   else:reject(ns['main']);assert not called
   results.append({'methods':count,'accepted':count==8})
 finally:ns.clear();ns.update(saved);os.environ.clear();os.environ.update(env)
 return results
check('actual main exact eight-call precondition and obsolete ten-call rejection',main_scope)

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

# Synthetic only: integers deliberately do not represent Android timings.
M=1_000_000
gc_fixture_spec=types.SimpleNamespace(MODES=markers.gc_MODES,SEGMENTS=markers.gc_SEGMENTS,COLUMNS=markers.gc_COLUMNS,MARKERS=markers.gc_MARKERS)
snapshot_fixture_spec=types.SimpleNamespace(MODES=contract['snapshot_control']['order'],STAGES=contract['snapshot_control']['boundaryStages'],READ_STAGES=contract['snapshot_control']['readStages'],prior=types.SimpleNamespace(EXPECTED_FIXTURE_SHA256=contract['snapshot_control']['expected']['fixtureSha256'],ROW_COUNTS=contract['snapshot_control']['expected']['rowCounts']))
def synthetic_distribution(trials, field=None):
    selected = [t for t in trials if field is None or t[field]]
    v = [t['saveMs'] for t in selected]
    sv = sorted(v)
    n = len(v)
    enabled = field is None or n >= 20
    return {'count': n, 'sampleIndices': [t['sample'] for t in selected], 'saveSamplesMs': v, 'maxMs': sv[-1] if n else None, 'p50Ms': sv[math.ceil(n * 0.5) - 1] if n and enabled else None, 'p95Ms': sv[math.ceil(n * 0.95) - 1] if n and enabled else None}

def synthetic_fixture(exposures=(20, 19)):
    records = []
    for mode_index, mode in enumerate(gc_fixture_spec.MODES):
        trials = []
        for sample in range(30):
            origin = (mode_index + 1) * 10 ** 12 + sample * 10 ** 9
            ns = lambda t: origin + int(t * M)
            selected = sample < exposures[mode_index]
            task = sample + 1
            room = sample * 3 + 1
            sql = sample * 3 + 2
            owner = sample * 3 + 1
            raw = [('writer-submit', 100, 0, 0, False, 'entered', 11), ('writer-run-enter', 110, 0, 0, False, 'entered', 22), ('room-begin-enter', 115, room, 1, False, 'entered', 22), ('sqlite-begin-enter', 120, sql, 1, False, 'entered', 22), ('sqlite-begin-exit', 125, sql, 1, False, 'returned', 22), ('room-begin-exit', 130, room, 1, False, 'returned', 22), ('room-success-enter', 160, room, 1, False, 'entered', 22), ('sqlite-success-enter', 161, sql, 1, False, 'entered', 22), ('sqlite-success-exit', 162, sql, 1, True, 'returned', 22), ('room-success-exit', 163, room, 1, True, 'returned', 22), ('room-end-enter', 164, room, 1, True, 'entered', 22), ('sqlite-end-enter', 165, sql, 1, True, 'entered', 22), ('sqlite-end-exit', 170, sql, 1, True, 'returned', 22), ('room-end-exit', 175, room, 1, True, 'returned', 22), ('writer-run-exit', 180, 0, 0, False, 'returned', 22)]
            events = [[stage, owner, task, tx, depth, success, outcome, ns(t), (i + 1) * 1000, thread] for i, (stage, t, tx, depth, success, outcome, thread) in enumerate(raw)]
            points = [90, 100, 110, 120, 125, 130, 150, 165, 170, 175, 190 + sample, 200 + sample]
            segments = [{'name': name, 'startNs': ns(x), 'endNs': ns(y), 'elapsedNs': int((y - x) * M)} for name, x, y in zip(gc_fixture_spec.SEGMENTS, points, points[1:])]
            phases = [{'phase': 'after-roots', 'atNs': ns(50)}, {'phase': 'after-stats', 'atNs': ns(55)}, {'phase': 'after-scan', 'atNs': ns(60)}]
            batches = []
            intervals = []

            def batch(phase, seq, count, t, changed=False):
                batches.append({'phase': phase, 'afterSequence': seq, 'candidates': count, 'expectedDataVersion': 10, 'expectedChanges': 20, 'observedDataVersion': 11 if changed else 10, 'observedChanges': 20, 'atNs': ns(t), 'cpuNs': int(t * 1000), 'thread': 33, 'callbackNs': 100})
            if selected:
                typ = sample % 3
                begin, end = (80, 100) if typ == 0 else (80, 89) if typ == 1 else (91, 100)
                for phase, t in [('guard-checked', begin - 1), ('before-delete', begin), ('after-delete', end), ('transaction-returned', 102)]:
                    batch(phase, 0, 256, t)
                phases.extend([{'phase': 'before-batch-commit', 'atNs': ns(101)}, {'phase': 'after-batch', 'atNs': ns(103)}])
                intervals.append({'afterSequence': 0, 'candidates': 256, 'deleteStartNs': ns(begin), 'deleteEndNs': ns(end), 'transactionReturnedNs': ns(102), 'saveIntersected': end > 90, 'saveStartedDuringDelete': begin <= 90 <= end})
                batch('guard-checked', 256, 256, 201, True)
                batch('transaction-returned', 256, 256, 202, True)
                first_deleted = 256
                collector_end = 205
            else:
                for i in range(5):
                    begin = 65 + i * 4
                    end = begin + 1
                    for phase, t in [('guard-checked', begin), ('before-delete', begin), ('after-delete', end), ('transaction-returned', end + 1)]:
                        batch(phase, i * 256, 256, t)
                    phases.extend([{'phase': 'before-batch-commit', 'atNs': ns(end + 1)}, {'phase': 'after-batch', 'atNs': ns(end + 2)}])
                    intervals.append({'afterSequence': i * 256, 'candidates': 256, 'deleteStartNs': ns(begin), 'deleteEndNs': ns(end), 'transactionReturnedNs': ns(end + 1), 'saveIntersected': False, 'saveStartedDuringDelete': False})
                batch('guard-checked', 1280, 0, 85)
                batch('transaction-returned', 1280, 0, 86)
                first_deleted = 1280
                collector_end = 87
            end = 200 + sample
            finish = max(end, collector_end) + 10
            databases = ['collector'] if mode == 'same-room' else ['collector', 'author']
            db = 'collector' if mode == 'same-room' else 'author'
            settings = {'page_size': 1024, 'auto_vacuum': 1, 'journal_mode': 'wal', 'synchronous': 2}
            trial = {'sample': sample, 'connection': mode, 'scenario': 'AFTER_SCAN_ARRIVAL_BOUNDARIES', 'replacesFrozenProbe': False, 'fixtureSourceVersions': 1281, 'fixtureDetachedSources': 1280, 'fixtureExplicitRoots': 0, 'fixtureLatestRetained': 1, 'artificialGates': False, 'committed': True, 'saveOutcome': 'COMMITTED', 'saveWatchdogMs': 5000, 'batchLimit': 256, 'boundaryCapacityPerDatabase': 512, 'batchCapacity': 64, 'phaseCapacity': 32, 'boundaryScope': 'AUTHOR_ONLY', 'pureSqliteLockWaitMs': None, 'gcOwnerToken': sample * 3, 'authorOwnerToken': owner, 'finishOwnerToken': sample * 3 + 2, 'initialRevision': 2, 'writerSqlite': settings, 'authorWriterSqlite': settings, 'savedRevision': 3, 'finalRevision': 3, 'contentVerified': True, 'retainedRevisions': [1281], 'status': 'PASS', 'stage': 'complete', 'failureClass': None, 'suppressedFailureClasses': [], 'collectorFailureClass': None, 'collectorStartNs': ns(0), 'collectorEndNs': ns(collector_end), 'saveStartNs': ns(90), 'saveEndNs': ns(end), 'saveMs': float(end - 90), 'beforeRenameReceiptNs': ns(150), 'afterRenameTransactionNs': ns(190 + sample), 'firstDeleted': first_deleted, 'firstDeferred': selected, 'firstRootScans': 1, 'firstCommittedBatches': first_deleted // 256, 'finishStartNs': ns(finish), 'finishEndNs': ns(finish + 50), 'finishDeleted': 1280 - first_deleted, 'finishRootScans': 1, 'finishDeferred': False, 'deletionTransactionExposed': selected, 'deleteWindowIntersected': any((i['saveIntersected'] for i in intervals)), 'saveStartedDuringDelete': any((i['saveStartedDuringDelete'] for i in intervals)), 'deleteIntervals': intervals, 'batchEvents': batches, 'batchDropped': 0, 'phaseDropped': 0, 'workersExited': True, 'helpersClosed': True, 'fixtureDeleted': True, 'boundaries': [{'database': d, 'count': len(events) if d == db else 0, 'dropped': 0, 'invalid': False} for d in databases], 'coveredBeforeBatchCommitNs': [ns(101)] if selected else [], 'phases': phases, 'authorBoundaries': {'databaseTask': task, 'sqliteTransaction': sql, 'roomTransaction': room, 'writerThread': 22, 'segments': segments, 'sqliteBeginThreadCpuNs': 1000, 'sqliteEndThreadCpuNs': 1000, 'pureSqliteLockWaitMs': None}}
            event = {'sample': sample, 'connection': mode, 'database': db, 'part': 0, 'parts': 1, 'eventSchema': 'author-boundary-v1', 'eventColumns': gc_fixture_spec.COLUMNS.copy(), 'events': events}
            records.extend([{'marker': gc_fixture_spec.MARKERS[0], 'data': event}, {'marker': gc_fixture_spec.MARKERS[1], 'data': trial}])
            trials.append(trial)
        summary = {'scenario': 'AFTER_SCAN_ARRIVAL_BOUNDARIES', 'replacesFrozenProbe': False, 'connection': mode, 'plannedTrials': 30, 'recordedTrials': 30, 'passedTrials': 30, 'committedSaves': 30, 'failedTrials': 0, 'unknownCommitOutcomes': 0, 'notStartedSaves': 0, 'functionalStatus': 'PASS', 'exposureStatus': 'SUFFICIENT_EXPOSURE' if exposures[mode_index] >= 20 else 'INSUFFICIENT_EXPOSURE', 'minimumExposedSamplesForQuantiles': 20, 'latencyAcceptance': 'NOT_EVALUATED', 'saveWatchdogMs': 5000, 'watchdogIsLatencyAcceptance': False, 'quantileDefinition': 'nearest rank: ceil(p*n)-1, zero based; n=30 p50=14 p95=28', 'pureSqliteLockWaitMs': None}
        for key, field in [('allSaves', None), ('exposedSaves', 'deletionTransactionExposed'), ('deleteWindowIntersectedSaves', 'deleteWindowIntersected'), ('saveStartedDuringDeleteSaves', 'saveStartedDuringDelete')]:
            summary[key] = synthetic_distribution(trials, field)
        records.append({'marker': gc_fixture_spec.MARKERS[2], 'data': summary})
    return records
def synthetic_synthetic():
    markers = []
    order = 'save-request writer-submit writer-run-enter room-begin-enter sqlite-begin-enter sqlite-begin-exit room-begin-exit save-writer-acquired save-head-read save-head-written save-revision-written save-receipt-written room-success-enter sqlite-success-enter sqlite-success-exit room-success-exit room-end-enter sqlite-end-enter sqlite-end-exit room-end-exit writer-run-exit save-returned'.split()
    for mode in snapshot_fixture_spec.MODES:
        events, saves = ([], [])
        for sample in range(30):
            start = 1000000 + sample * 1000
            for n, stage in enumerate(order, 1):
                tx = 100 + sample if stage.startswith('room-') else 200 + sample if stage.startswith('sqlite-') else 0
                success = stage.startswith(('room-', 'sqlite-')) and stage.split('-', 1)[1] in ('success-exit', 'end-enter', 'end-exit')
                outcome = 0 if stage.endswith('-enter') or stage == 'writer-submit' else 1
                task = 0 if stage in ('save-request', 'save-returned') else sample + 1
                thread = 1 if stage in ('save-request', 'save-returned', 'writer-submit') else 2
                events.append([snapshot_fixture_spec.STAGES.index(stage), sample, task, tx, int(tx > 0), int(success), outcome, start + n, (start + n) // 2, thread])
            saves.append([sample, start, start + 23, sample + 2, 1])
        stages = {'IDLE': ['connection-open', 'snapshot-open', 'pin-read', 'snapshot-closed', 'connection-closed', 'idle-ready'], 'HELD_SNAPSHOT': ['connection-open', 'snapshot-open', 'pin-read', 'hold-enter', 'hold-exit', 'pin-recheck', 'snapshot-closed', 'connection-closed'], 'ACTIVE_SEARCH': snapshot_fixture_spec.READ_STAGES[:9] + ['active-ready'] + snapshot_fixture_spec.READ_STAGES[9:21]}[mode]
        reads = []
        for n, stage in enumerate(stages):
            owner = int(mode == 'ACTIVE_SEARCH' and stage != 'active-ready')
            after = mode == 'HELD_SNAPSHOT' and n >= 4 or (mode == 'ACTIVE_SEARCH' and n >= 10)
            wall = (2000000 if after else 100) + n * 10
            reads.append([owner, snapshot_fixture_spec.READ_STAGES.index(stage), owner, 1 if stage in ('pin-read', 'pin-recheck') else -1, wall, n + 10, 3])
        result = dict(schema=1, diagnosticOnly=True, seed=4101, mode=mode, readiness=dict(zip(snapshot_fixture_spec.MODES, ('reader-closed', 'main-snapshot-pinned', 'first-source-check')))[mode], fixture={'logicalSha256': snapshot_fixture_spec.prior.EXPECTED_FIXTURE_SHA256, 'rowCounts': snapshot_fixture_spec.prior.ROW_COUNTS, 'writerSqlite': {'page_size': 1024, 'auto_vacuum': 1, 'journal_mode': 'wal', 'synchronous': 2}}, startRevision=1, finalRevision=31, readerBeforeRevision=-1 if mode == 'ACTIVE_SEARCH' else 1, readerAfterRevision=1 if mode == 'HELD_SNAPSHOT' else -1, saves=saves)
        values = [('RESULT', result)]
        for kind, rows in (('EVENTS', events), ('READS', reads)):
            for offset in range(0, len(rows), 96):
                values.append((kind, dict(schema=1, mode=mode, part=offset // 96, events=rows[offset:offset + 96])))
        values.append(('END', dict(schema=1, mode=mode, completed=True, coverageValid=True, committedSaves=30, recordedEvents=len(events), recordedReadEvents=len(reads), droppedEvents=0, droppedReadEvents=0, invalid=False, readerExited=True, writersExited=True, databaseClosed=True, databaseDeleted=True, cleanupErrors=0)))
        markers += [{'kind': kind, 'line': len(markers) + n + 1, 'value': value} for n, (kind, value) in enumerate(values)]
    return markers
def fixtures():
 result={i:'' for i in range(1,9)}
 records=synthetic_fixture()
 for ordinal,mode in [(6,'same-room'),(7,'separate-room')]:result[ordinal]='\n'.join(x['marker']+' '+json.dumps(x['data'],separators=(',',':')) for x in records if x['data']['connection']==mode)
 result[8]='\n'.join('LIBRARY_SNAPSHOT_CONTROL_'+x['kind']+' '+json.dumps(x['value'],separators=(',',':')) for x in synthetic_synthetic())
 return result
fixture_raw=fixtures()
def markerchecks():
 for e in plan['steps']:markers.safe_marker_payloads(e,fixture_raw[e['ordinal']],contract)
 negative=0
 for ordinal in [6,7,8]:
  e=plan['steps'][ordinal-1];valid=fixture_raw[ordinal];lines=valid.splitlines()
  for raw in ['',valid+'\n'+valid,'\n'.join(lines[:-1]),'\n'.join(reversed(lines)),valid+'\nLIBRARY_UNKNOWN {}',(valid.replace('"sample":0','"sample":true',1) if ordinal<8 else valid.replace('[[0,0,','[[0,true,',1)),valid.replace('"part":0','"part":1',1),valid+'\n'+lines[0],valid.replace('{','{"unknown":true,',1)]:
   reject(lambda raw=raw,e=e:markers.safe_marker_payloads(e,raw,contract));negative+=1
 # Every mandatory author stage deleted and counts recomputed must still fail.
 for ordinal in [6,7]:
  records=[(line.split(' ',1)[0],json.loads(line.split(' ',1)[1])) for line in fixture_raw[ordinal].splitlines()]
  for index in range(15):
   changed=copy.deepcopy(records);changed[0][1]['events'].pop(index);changed[1][1]['boundaries'][-1]['count']-=1
   text='\n'.join(k+' '+json.dumps(v,separators=(',',':')) for k,v in changed)
   reject(lambda text=text,ordinal=ordinal:markers.safe_marker_payloads(plan['steps'][ordinal-1],text,contract));negative+=1
  for field in ['contentVerified','workersExited','helpersClosed','fixtureDeleted']:
   changed=copy.deepcopy(records);changed[1][1][field]=False
   reject(lambda changed=changed,ordinal=ordinal:markers.safe_marker_payloads(plan['steps'][ordinal-1],'\n'.join(k+' '+json.dumps(v,separators=(',',':')) for k,v in changed),contract));negative+=1
  for field in ['interval_bool','retained_float','covered_float']:
   changed=copy.deepcopy(records);t=changed[1][1]
   if field=='interval_bool':t['deleteIntervals'][0]['afterSequence']=False
   elif field=='retained_float':t['retainedRevisions']=[1281.0]
   else:t['coveredBeforeBatchCommitNs'][0]=float(t['coveredBeforeBatchCommitNs'][0])
   reject(lambda changed=changed,ordinal=ordinal:markers.safe_marker_payloads(plan['steps'][ordinal-1],'\n'.join(k+' '+json.dumps(v,separators=(',',':')) for k,v in changed),contract));negative+=1
  changed=copy.deepcopy(records);changed[1][1]['phases'].pop(1)
  reject(lambda:markers.safe_marker_payloads(plan['steps'][ordinal-1],'\n'.join(k+' '+json.dumps(v,separators=(',',':')) for k,v in changed),contract));negative+=1
 records=synthetic_synthetic()
 for index in range(22):
  changed=copy.deepcopy(records);rows=[row for x in changed if x['kind']=='EVENTS' and x['value']['mode']=='IDLE' for row in x['value']['events']];rows.pop(index)
  parts=[x for x in changed if x['kind']=='EVENTS' and x['value']['mode']=='IDLE']
  for part,x in enumerate(parts):x['value']['events']=rows[part*96:(part+1)*96]
  next(x['value'] for x in changed if x['kind']=='END' and x['value']['mode']=='IDLE')['recordedEvents']-=1
  text='\n'.join('LIBRARY_SNAPSHOT_CONTROL_'+x['kind']+' '+json.dumps(x['value'],separators=(',',':')) for x in changed)
  reject(lambda:markers.safe_marker_payloads(plan['steps'][7],text,contract));negative+=1
 # Same wall-clock read-end on another thread is legal after connection closure.
 changed=copy.deepcopy(records);reads=next(x['value']['events'] for x in changed if x['kind']=='READS' and x['value']['mode']=='ACTIVE_SEARCH');reads[-1][4]=reads[-2][4];reads[-1][6]=99
 text='\n'.join('LIBRARY_SNAPSHOT_CONTROL_'+x['kind']+' '+json.dumps(x['value'],separators=(',',':')) for x in changed)
 markers.safe_marker_payloads(plan['steps'][7],text,contract)
 return {'synthetic_methods':8,'negative_mutations':negative,'same_wall_cross_thread_after_close':True,'native':False}
check('closed GC and snapshot whole-method stage/count/cleanup faults',markerchecks)
def fixture_source_contracts():
 assert contract['source_commit']=='fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353'
 for rule in contract['methods'].values():assert hashlib.sha256(subprocess.check_output(['git','-C',str(a.repo),'show',contract['source_commit']+':'+rule['test_source_path']])).hexdigest()==rule['test_source_sha256']
 for path,expected in contract['reviewed_source_sha256'].items():assert hashlib.sha256(subprocess.check_output(['git','-C',str(a.repo),'show',contract['source_commit']+':'+path])).hexdigest()==expected
 assert contract['snapshot_control']['expected']['fixtureSha256']=='1c69bba77781ba0c0b642309b8e869ff21c9a440fd7714894e67c572b95c9dcf'
 return {'git_source':contract['source_commit'],'all_reviewed_source_blobs_match':True,'save_samples':90,'gc_samples':60,'native':False}
check('exact Git source test/production binding and original fixture hash',fixture_source_contracts)

def processchecks():
 saved={k:ns.get(k)for k in ['run','time','seconds']};cases=[]
 for mode in ['gone','app_alive','test_alive','data_alive','transport']:
  clock=[0.0];calls=[];ns['time']=types.SimpleNamespace(monotonic=lambda:clock[0],sleep=lambda x:clock.__setitem__(0,clock[0]+max(x,.001)))
  def run(args,*pos,**kw):
   calls.append(args);clock[0]+=.01;package=args[-1];alive=(mode=='app_alive'and package=='org.inkweft.app.a0')or(mode=='test_alive'and package=='org.inkweft.app.a0.test')or(mode=='data_alive'and package=='org.inkweft.data.test')
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


def buildchecks():
 saved={k:ns.get(k)for k in ['ROOT','OUT','SDK','SPEC','phase','capture','source_manifest','digest','seconds']}
 original_gradle=os.environ.get('GRADLE_USER_HOME');cases=[]
 try:
  for case in ['match','missing_apk_abi','different_aar_native','wrong_aar_version']:
   root=a.out/('build-'+case);out=root/'evidence';out.mkdir(parents=True)
   android=root/'sources/C1/android';apk=android/'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk';apk.parent.mkdir(parents=True)
   cache=root/'gradle/caches/modules-2/files-2.1';version='3.48.0'if case=='wrong_aar_version'else '3.49.0'
   aar=cache/('com.github.requery/sqlite-android/'+version+'/synthetic/sqlite-android-'+version+'.aar');aar.parent.mkdir(parents=True)
   abis=['arm64-v8a','armeabi-v7a','x86','x86_64']
   with zipfile.ZipFile(apk,'w')as target,zipfile.ZipFile(aar,'w')as resolved:
    for i,abi in enumerate(abis):
     data=b'\x7fELF'+bytes([i])*100001
     if not(case=='missing_apk_abi'and abi=='x86_64'):target.writestr('lib/'+abi+'/libsqlite3x.so',data)
     resolved.writestr('jni/'+abi+'/libsqlite3x.so',data+b'different'if case=='different_aar_native'and abi=='x86_64'else data)
   os.environ['GRADLE_USER_HOME']=str(root/'gradle');commands=[]
   def phase(name,argv,*args,**kwargs):commands.append(argv)
   def capture(argv,*args):
    if 'apksigner'in str(argv[0]):return 'Signer #1 certificate SHA-256 digest: '+'1'*64
    if argv[2]=='badging':return "package: name='org.inkweft.data.test' versionCode='1'"
    if argv[2]=='xmltree':return Path('/workspace/scratch/pr20-search-screen-native-20261014/local-aapt-xmltree.log').read_text()
    raise AssertionError(argv)
   ns.update(ROOT=root,OUT=out,SDK=Path('/synthetic-sdk'),SPEC={'identities':{'C1':{'git':'0'*40}}},phase=phase,capture=capture,source_manifest=lambda identity:'0'*64,digest=sha,seconds=lambda limit=3600:limit)
   if case=='match':
    ns['build']('C1');r=json.loads((out/'apk-C1.json').read_text());assert len(r['apks'])==1
    item=r['apks'][0];assert item['package']=='org.inkweft.data.test'
    assert len(item['sqlite_native'])==len(item['sqlite_aar']['native'])==4
    assert item['sqlite_aar']['sha256']==sha(aar)and item['sqlite_aar']['coordinate']=='com.github.requery:sqlite-android:3.49.0'
    assert [v['sha256']for v in item['sqlite_native']]==[v['sha256']for v in item['sqlite_aar']['native']]
    assert '--no-build-cache'in commands[0]and [v for v in commands[0]if str(v).startswith(':')]==[':data-local:assembleDebugAndroidTest']
   else:reject(lambda:ns['build']('C1'))
   cases.append({'case':case,'native_executions':0,'gradle_executions':0})
 finally:
  if original_gradle is None:os.environ.pop('GRADLE_USER_HOME',None)
  else:os.environ['GRADLE_USER_HOME']=original_gradle
  ns.update(saved)
 return cases
check('data-only build receipt: four ABI APK/AAR match and negative source cases',buildchecks)

def installchecks():
 cases=[];saved={k:ns.get(k)for k in ['OUT','ROOT','SPEC','REPORT','capture','run','environment','host','digest','save']}
 for group in ['C1-data-diagnostics']:
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
    ns['install'](group,'C1');r=ns['REPORT']['install_groups'][group];assert r['status']=='INSTALLED' and len(r['installed_apks'])==1
   else:reject(lambda:ns['install'](group,'C1'))
   cases.append({'group':group,'case':case,'commands':len(calls)})
 ns.update(saved);return cases
check('data install group binds exact installed APK bytes; wrong path/hash stop',installchecks)

def incomplete_marker():
 entry=copy.deepcopy(plan['steps'][7]);entry['status']='NOT_RUN';target=a.out/'malformed-method';target.mkdir()
 cls,meth=entry['selection'].split('#')
 raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'+fixture_raw[8]+'\nLIBRARY_SNAPSHOT_CONTROL_RESULT []\n'+f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
 saved={k:ns.get(k)for k in ['OUT','REPORT','run','processes_after','seconds','save','time']};ns.update(OUT=target,REPORT={'methods':[entry]},time=time,seconds=lambda limit=3600:limit,save=lambda:None)
 def run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
 ns['run']=run;ns['processes_after']=lambda e:e.update(process_exit_outcome='NATURAL_EXIT');output=io.StringIO()
 with redirect_stdout(output):reject(lambda:ns['method'](entry))
 assert entry['status']=='INCOMPLETE'and entry['junit_status']=='PASS'and entry['adb_exit']==0
 assert 'INKWEFT_CAUSAL_CONTEXT' not in output.getvalue()and'INKWEFT_CAUSAL_METHOD'in output.getvalue()
 assert (target/'method-08-C1-instrumentation.log').read_text()==raw
 ns.update(saved);return {'junit':'PASS','host':'INCOMPLETE','unvalidated_payload_forwarded':False,'raw_preserved':True}
check('malformed extra marker preserves raw JUnit PASS but host INCOMPLETE',incomplete_marker)

def failure_forwarding():
 saved=dict(ns);root=a.out/'failure-runtime';target=root/'evidence';target.mkdir(parents=True)
 entry=dict(copy.deepcopy(plan['steps'][7]),status='FAIL',junit_status='FAIL',adb_exit=0,process_exit_outcome='NATURAL_EXIT')
 report={'run_id':'900001','run_attempt':'1','workflow_sha':'a'*40,'methods':[entry]}
 ns.update(save=lambda:None,ROOT=root,OUT=target,PLAN=plan,REPORT=report,SPEC={'identities':{'C1':{'git':contract['source_commit']}}},INPUT_HASHES={'source_spec_sha256':'c'*64,'method_plan_sha256':'d'*64})
 path=target/'method-08-C1-instrumentation.log';cases=[]
 for label,data,reason in [('unicode','full αβ\nstack'.encode(),None),('invalid_utf8',b'\xff','INVALID_UTF8'),('secret',b'ghp_'+b'x'*30,'SENSITIVE'),('oversize',b'x'*858515,'RAW_OVERSIZE'),('normal_started',b'full original','NORMAL_FORWARD_ALREADY_STARTED'),('hash',b'wrong','HASH_MISMATCH')]:
  e=copy.deepcopy(entry);path.write_bytes(data);e['log_sha256']=hashlib.sha256(data).hexdigest()
  if label=='hash':e['log_sha256']='0'*64
  if label=='normal_started':e['forwarding_path']='NORMAL_STARTED'
  out=io.StringIO()
  with redirect_stdout(out):ns['forward_failure_log'](e,path)
  values=[json.loads(line.split(' ',1)[1]) for line in out.getvalue().splitlines()]
  assert values[0]['kind']==('BEGIN' if reason is None else 'REFUSED')
  if reason:assert values[0]['reason']==reason
  else:assert b''.join(base64.b64decode(v['base64']) for v in values if v['kind']=='PART')==data
  assert path.read_bytes()==data and e['status']=='FAIL';cases.append(label)
 path.unlink();path.symlink_to(target/'other');reject(lambda:ns['forward_failure_log'](entry,path));path.unlink()
 wrong=copy.deepcopy(entry);wrong['selection']='org.inkweft.data.Wrong#method';reject(lambda:ns['forward_failure_log'](wrong,path))
 # Inject native method JUnit failure and forwarding failure: exact primary exception remains.
 for forwarding_fault in [False,True]:
  runroot=a.out/('method-failure-'+str(forwarding_fault));outdir=runroot/'evidence';outdir.mkdir(parents=True)
  e=copy.deepcopy(plan['steps'][0]);e['status']='NOT_RUN';cls,meth=e['selection'].split('#')
  raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n'
  def fake_run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
  ns.update(ROOT=runroot,OUT=outdir,REPORT={**report,'methods':[e]},run=fake_run,processes_after=lambda e:e.update(process_exit_outcome='NATURAL_EXIT'),seconds=lambda limit=3600:limit,save=lambda:None,time=time)
  original=ns['forward_failure_log']
  if forwarding_fault:ns['forward_failure_log']=lambda *args:(_ for _ in ()).throw(OSError('synthetic forward error'))
  with redirect_stdout(io.StringIO()):
   try:ns['method'](e)
   except AssertionError as error:assert str(error)=='Original method/JUnit failed; stop without retry'
   else:raise AssertionError('Original failure swallowed')
  assert e['status']=='FAIL' and e['junit_status']=='FAIL' and (outdir/'method-01-C1-instrumentation.log').read_text()==raw
  ns['forward_failure_log']=original
 ns.clear();ns.update(saved)
 return {'cases':cases,'exact_path_and_symlink_guards':True,'first_failure_preserved_in_two_finally_paths':True}
check('full failure bytes, path/hash/identity and original exception preservation',failure_forwarding)

def forwarding_interruptions():
 saved=dict(ns);cases=[]
 for fault in ['context','record','end','after-normal-seconds','none']:
  root=a.out/('normal-interrupt-'+fault);out=root/'evidence';out.mkdir(parents=True)
  e=dict(copy.deepcopy(plan['steps'][0]),status='NOT_RUN');cls,meth=e['selection'].split('#')
  raw=f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={meth}\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'
  apk={'package':'org.inkweft.data.test','sha256':'0'*64,'signer_sha256':'1'*64,'source_apk_sha256':'0'*64}
  (out/'apk-C1.json').write_text(json.dumps({'source_manifest_sha256':'2'*64,'apks':[apk]}))
  report={'run_id':'900001','run_attempt':'1','workflow_sha':'a'*40,'methods':[e],'install_groups':{e['install_group']:{'installed_apks':[apk]}}}
  captured=[];saved_states=[];ticks=[0]
  def fake_run(args,*pos,**kw):kw['stdout'].write(raw);return subprocess.CompletedProcess(args,0)
  def emit(text,**kw):
   if text.startswith('INKWEFT_CAUSAL_'+fault.upper()+' '):raise OSError('synthetic print '+fault)
   captured.append(text)
  def seconds(limit=3600):
   ticks[0]+=1
   if fault=='after-normal-seconds' and ticks[0]==2:raise ns['BudgetAbort']('synthetic late cutoff')
   return limit
  ns.update(ROOT=root,OUT=out,PLAN=plan,REPORT=report,SPEC={'identities':{'C1':{'git':contract['source_commit']}}},INPUT_HASHES={'source_spec_sha256':'c'*64,'method_plan_sha256':'d'*64},digest=lambda p:'e'*64,save=lambda:saved_states.append(copy.deepcopy(e)),run=fake_run,processes_after=lambda e:e.update(process_exit_outcome='NATURAL_EXIT'),safe_marker_payloads=lambda *args:[{'marker':'SYNTHETIC','original_json':'{}','data':{}}],common_fixtures=lambda *args:None,seconds=seconds,print=emit,time=time)
  if fault=='none':ns['method'](e);assert e['status']=='PASS'
  else:
   reject(lambda:ns['method'](e));assert e['status'] in ['FAIL','INCOMPLETE']
   values=[json.loads(line.split(' ',1)[1]) for line in captured if line.startswith('INKWEFT_CAUSAL_FAILURE_TEXT ')]
   assert len(values)==1 and values[0]['kind']=='REFUSED' and values[0]['reason']=='NORMAL_FORWARD_ALREADY_STARTED'
   assert not any('"kind":"PART"' in line or '"kind":"BEGIN"' in line for line in captured)
  assert any(v.get('forwarding_path')=='NORMAL_STARTED' and v.get('normal_record_end_wire_bytes',0)>0 for v in saved_states), 'Full bounded output charge must persist before first print'
  assert (out/'method-01-C1-instrumentation.log').read_text()==raw
  cases.append({'fault':fault,'status':e['status'],'normal_and_full_failure_duplicated':False})
 ns.clear();ns.update(saved);return cases
check('actual normal forward print interruptions and late deadline remain exclusive',forwarding_interruptions)

def bucketchecks():
 treep=ast.parse((here/'package.py').read_text());func=next(n for n in treep.body if isinstance(n,ast.FunctionDef) and n.name=='account_text');space={'re':re};exec(compile(ast.Module([func],type_ignores=[]),'package-account','exec'),space);f=space['account_text']
 report={'methods':[dict(s,status='NOT_RUN') for s in plan['steps']]}
 baseline=f([],report,contract,0);assert baseline['combined_reserved_bytes']==65536
 samples={'source_frozen_protocol_controller_and_manifest_text':'run.py','build_and_sdk_source_setup_logs':'build-C1.log','dependencies_apk_install_host_device_receipts':'apk-C1.json','forwarding_inventory_context_method_final_and_summary_copies':'summary.json','guard_raw_junit_cleanup_acl_packaging_and_manifest':'cleanup-trap.json'}
 for bucket,filename in samples.items():
  cap=contract['transport']['fixed_text_buckets_bytes'][bucket]
  reject(lambda filename=filename,cap=cap:f([(filename,b'x'*(cap+1))],report,contract,0))
 e=report['methods'][0];e.update(status='FAIL',failure_wire_bytes=contract['transport']['methods']['1']['failure_wire_max_bytes']+1)
 reject(lambda:f([],report,contract,0));e['failure_wire_bytes']=100;e['normal_record_end_wire_bytes']=100;e['failure_log_forwarding']={'status':'FORWARDED'}
 reject(lambda:f([],report,contract,0));e['normal_record_end_wire_bytes']=0;report['methods'][1]['status']='PASS';reject(lambda:f([],report,contract,0))
 low=copy.deepcopy(contract);low['transport']['total_text_limit_bytes']=65535;reject(lambda:f([],{'methods':[]},low,0))
 return {'fixed_bucket_overflows':5,'wire_overflow':1,'duplicate_full_forward':1,'successor_after_failure':1,'combined_total':1,'artifact_records_copies':0}
check('real package fixed buckets, exclusive wire ledger and combined total fail closed',bucketchecks)

# Temporary fake identities support package fault tests only; never an execution receipt.
real_readiness=a.readiness
a.controller=here;a.readiness=a.out/'synthetic-readiness';a.readiness.mkdir()
spec={'status':'SYNTHETIC','identities':{'C1':{'git':'0'*40,'canonical_manifest_sha256':'0'*64}},'common_probes':[]}
for name,value in [('source-spec.json',spec),('method-plan.json',plan),('marker-contract.json',contract),('C1-source-manifest.json',{})]:
 (a.readiness/name).write_text(json.dumps(value)+'\n')
for name in ['PLAN.md','original-protocol.json','prepare_sources.py']:(a.readiness/name).write_text('synthetic offline package fixture only\n')
def packagecases():
 repo=a.out/'package-fixture/control';control=repo/'ci/pr20-gc-save';frozen=repo/'android/verification/pr20-gc-save-20261014';control.mkdir(parents=True);shutil.copytree(a.readiness,frozen)
 sourcehash=sha(frozen/'source-spec.json')
 for path in a.controller.iterdir():
  if path.is_file():(control/path.name).write_text(re.sub(r"FROZEN_SOURCE_SPEC_SHA256( ?= ?)'[^']+'",lambda m:'FROZEN_SOURCE_SPEC_SHA256'+m[1]+repr(sourcehash),path.read_text()))
 (control/'authorization.json').write_text('{}\n');(control/'EXECUTION_SHA256SUMS').write_text('synthetic offline package fixture; not activation\n')
 for probe in spec['common_probes']:
  dest=repo/probe['from_path'];dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(oldroot/probe['from_path'],dest)
 base={'run_id':'SYNTHETIC','workflow_sha':'0'*40,'input_hashes':{},'status':'MEASURING','actions':{},'phases':[],'install_groups':{},'methods':[dict(s,status='NOT_RUN') for s in plan['steps']]}
 cases=[]
 for name in ['running','installed','between_steps','pass_preserved','fail_preserved','notrun_preserved','cleanup_failure','all8_complete','all8_missing_raw','oversize','secret','excluded_binary','no_summary']:
  root=a.out/'package-fixture'/name;ev=root/'evidence';ev.mkdir(parents=True);report=copy.deepcopy(base)
  if name=='running':report['actions']['build-C1']={'status':'RUNNING','started_epoch':1};report['methods'][0]['status']='RUNNING'
  if name=='installed':report['install_groups']['C1-data-diagnostics']={'identity':'C1','status':'INSTALLED','started_epoch':1}
  if name=='pass_preserved':report['methods'][0]['status']='PASS';report['methods'][0]['junit_status']='PASS';report['methods'][0]['forwarding_path']='NORMAL_COMPLETE'
  if name=='fail_preserved':report['methods'][0]['status']='FAIL';report['methods'][0]['junit_status']='FAIL'
  if name in ['all8_complete','all8_missing_raw']:
   report['status']='EXECUTION_COMPLETE_ANALYSIS_PENDING'
   for e in report['methods']:e.update(status='PASS',junit_status='PASS',adb_exit=0,forwarding_path='NORMAL_COMPLETE')
   report['install_groups']={e['install_group']:{'identity':e['identity'],'status':'PASS'} for e in report['methods']}
  original=(json.dumps(report,indent=2)+'\n').encode()
  if name!='no_summary':(ev/'summary.json').write_bytes(original)
  for tag in ['trap','always']:
   value={'emulator':'NOT_STARTED','command_groups':'STOPPED_CONFIRMED','acl':'NOT_TOUCHED','errors':['synthetic'] if name=='cleanup_failure' and tag=='always' else []};(ev/f'cleanup-{tag}.json').write_text(json.dumps(value))
  if name in ['all8_complete','all8_missing_raw']:
   package_text=(control/'package.py').read_text();fragment=package_text[package_text.index('fixed='):package_text.index('missing=')]
   fixture_scope={'plan':plan};exec(fragment,fixture_scope)
   for filename in fixture_scope['fixed']:
    target=ev/filename
    if filename=='summary-before-packaging.json' or target.exists():continue
    target.write_text('{}\n' if filename.endswith('.json') else 'synthetic offline presence fixture\n')
   for tag in ['trap','always']:
    (ev/f'cleanup-{tag}.json').write_text(json.dumps({'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'UNCHANGED_EXACT','errors':[]}))
   if name=='all8_missing_raw':(ev/'method-01-C1-instrumentation.log').unlink()
  if name=='oversize':(ev/'build-C1.log').write_bytes(b'a'*(20*1024*1024))
  if name=='secret':(ev/'build-C1.log').write_text('ghp_'+'x'*30)
  if name=='excluded_binary':(ev/'private.apk').write_bytes(b'PK\0');(ev/'private.db').write_bytes(b'SQLite')
  env=os.environ.copy();env['KVM_ONCE']=str(root)
  run=subprocess.run([sys.executable,control/'package.py'],env=env,capture_output=True,text=True);(root/'package.stdout').write_text(run.stdout);(root/'package.stderr').write_text(run.stderr)
  if name!='all8_complete':assert run.returncode!=0,name
  else:assert run.returncode==0,run.stderr
  if name!='no_summary':
   assert (ev/'summary-before-packaging.json').read_bytes()==original
   after=json.loads((ev/'summary.json').read_text())
   if name in ['running','installed','between_steps']:assert after['status'].startswith('INCOMPLETE') and 'ended_epoch' not in after
   if name=='running':assert after['actions']['build-C1']['status']=='INCOMPLETE' and after['methods'][0]['status_before_packaging']=='RUNNING'
   if name=='installed':assert after['install_groups']['C1-data-diagnostics']['status']=='INCOMPLETE'
   if name=='cleanup_failure':assert after['status']=='CLEANUP_UNCONFIRMED' and after['cleanup_verified'] is False
   if name=='all8_complete':assert after['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and after['cleanup_verified'] is True and after['missing_files']==[]
   if name=='all8_missing_raw':
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
 assert value['maximum_case_bytes']==19675060 and value['remaining_bytes']==1296460
 return value
check('frozen exact shared budget and all first-failure positions',sizing)
inputs={str(f):sha(f)for f in sorted(list(here.iterdir())+(list(real_readiness.iterdir())if real_readiness else []))if f.is_file()}
receipt={'status':'PASS','scope':'OFFLINE_ONLY_NO_NEW_NATIVE_NO_NETWORK_NO_BUILD','groups':checks,'inputs':inputs,'checker_sha256':sha(Path(__file__))}
(a.out/'preflight.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':'PASS','groups':len(checks),'receipt':str(a.out/'preflight.json'),'sha256':sha(a.out/'preflight.json')}))
