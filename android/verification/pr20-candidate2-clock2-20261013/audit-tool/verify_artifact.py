"""Offline integrity/source/clock audit of run38433834125's user-supplied original ZIP.
Never fetch an artifact or execute its contents. Failed/unfinished methods remain failed/unfinished.
"""
from pathlib import Path
import argparse, ast, collections, datetime, hashlib, json, re, stat, subprocess, zipfile
import xml.etree.ElementTree as ET

RUN_ID=38433834125
JOB_ID=115422592925
ACTIVE='9907a8f96fe0d20061f0a7deba6a69d4416ced50'
PREPARATION='14b4f48ea3a683b3cff1b4201bd1e2b4cd740d4a'
READINESS='6c72fd8b20228cc324fd835efa3f505f1f179997'
FROZEN='android/verification/pr20-candidate2-readiness-20261013/'
CONTROL='ci/pr20-kvm-triple/'
SOURCE_SHA='6e9c422d8be60728967226de674b750cb7b3633485956d537dd0c2dac21a6dfb'
PLAN_SHA='808dd0cf1fece09185c59ae94ce41f51fdbc26dcde5ed196df7e803860f49765'
ANCHOR=1791910150
MAX_BYTES=20*1024*1024
PACKAGES=['org.inkweft.app.a0','org.inkweft.app.a0.test','org.inkweft.data.test']
FROZEN_NAMES=['PLAN.md','original-protocol.json','source-spec.json','method-plan.json','B-source-manifest.json','C0-source-manifest.json','C1-source-manifest.json','prepare_sources.py']
CONTROL_NAMES=['authorization.json','budget-revision.json','clock-contract.json','clock-server-receipt.json','EXECUTION_SHA256SUMS']
SECRET=re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ssh-rsa|ssh-ed25519) [A-Za-z0-9+/]{40,}')


def require(value,message):
    if not value: raise ValueError(message)


def sha(data): return hashlib.sha256(data).hexdigest()
def load(data): return json.loads(data.decode('utf-8'))
def epoch(text): return datetime.datetime.fromisoformat(text.replace('Z','+00:00')).timestamp()


def git_bytes(repo,commit,path):
    # Only local object reads; no fetch, checkout, credentials or network.
    return subprocess.check_output(['git','-C',str(repo),'show',commit+':'+path],timeout=15)


def trusted_inputs(repo):
    inputs={name:git_bytes(repo,ACTIVE,CONTROL+name) for name in CONTROL_NAMES}
    frozen={name:git_bytes(repo,READINESS,FROZEN+name) for name in FROZEN_NAMES}
    for name,data in frozen.items():
        require(git_bytes(repo,ACTIVE,FROZEN+name)==data,'Active/readiness source mismatch: '+name)
    require(sha(frozen['source-spec.json'])==SOURCE_SHA,'Wrong frozen source spec')
    require(sha(frozen['method-plan.json'])==PLAN_SHA,'Wrong frozen method plan')
    plan=load(frozen['method-plan.json']);spec=load(frozen['source-spec.json'])
    require(len(plan['steps'])==57 and [s['ordinal'] for s in plan['steps']]==list(range(1,58)),'57 exact ordered methods required')
    require([sum(s['phase']==p for s in plan['steps']) for p in ['correctness','common-comparison','candidate-diagnostics']]==[30,24,3],'Wrong phase counts')
    require(len(set(s['install_group'] for s in plan['steps']))==14,'Wrong installation count')
    require(spec['identities']['C0']['git']=='ebb48481e7e995288c1844d43011398c513b4f4e','Wrong previous candidate')
    require(spec['identities']['C1']['git']=='45b4861ae23ac19eda3246fcd34cf3d2132dc1ee','Wrong new candidate')
    execution={}
    for line in inputs['EXECUTION_SHA256SUMS'].decode().splitlines():
        digest,name=line.split('  ',1)
        require(re.fullmatch('[0-9a-f]{64}',digest) and name not in execution,'Invalid execution manifest')
        require(sha(git_bytes(repo,ACTIVE,CONTROL+name))==digest,'Committed controller hash mismatch: '+name)
        execution[name]=digest
    require(set(execution)=={'README.md','authorization.json','budget-revision.json','cleanup.py','clock-contract.json','clock-server-receipt.json','package.py','preflight.json','run.py','workflow.proposed.yml'},'Unexpected control execution manifest scope')
    for name in execution:
        require(git_bytes(repo,PREPARATION,CONTROL+name)==git_bytes(repo,ACTIVE,CONTROL+name),'Preparation/active controller changed: '+name)
    auth=load(inputs['authorization.json']);contract=load(inputs['clock-contract.json']);receipt=load(inputs['clock-server-receipt.json']);budget=load(inputs['budget-revision.json'])
    require(auth['clock_contract_sha256']==sha(inputs['clock-contract.json']),'Contract authorization mismatch')
    require(auth['budget_revision_sha256']==sha(inputs['budget-revision.json']),'Budget authorization mismatch')
    require(auth['server_receipt_sha256']==contract['server_receipt_sha256']==sha(inputs['clock-server-receipt.json']),'Receipt hash mismatch')
    require(auth['budget_anchor_epoch']==contract['budget_anchor_epoch']==budget['clock_amendment']['budget_anchor_epoch']==ANCHOR,'Anchor changed')
    require(receipt['updated_at']==contract['updated_at']=='2026-10-13T16:49:10Z' and epoch(receipt['updated_at'])==ANCHOR,'Server timestamp changed')
    require(receipt=={'http_status':200,'id':4835003832,'number':20,'node_id':'PR_kwDOUnRr3M8AAAABIDBNuA','draft':True,'updated_at':'2026-10-13T16:49:10Z','head_sha':'d30fc6c805b58951d657f2505381f4f503aba1ef'},'PR source receipt changed')
    required={**inputs,**{'frozen-'+name:data for name,data in frozen.items()}}
    for probe in spec['common_probes']:
        data=git_bytes(repo,ACTIVE,probe['from_path'])
        require(sha(data)==probe['sha256'],'Common probe hash mismatch')
        required[Path(probe['from_path']).name]=data
    factory=spec['identities']['B']['production_overrides'][0]
    factory_data=git_bytes(repo,factory['from_git'],factory['path'])
    require(sha(factory_data)==factory['sha256'],'Baseline common factory hash mismatch')
    # Evaluate only the fixed package whitelist declarations before any file access.
    tree=ast.parse(git_bytes(repo,ACTIVE,CONTROL+'package.py'))
    begin=next(i for i,n in enumerate(tree.body) if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id=='fixed')
    end=next(i for i,n in enumerate(tree.body[begin:],begin) if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id=='items')
    scope={'plan':plan};exec(compile(ast.Module(body=tree.body[begin:end],type_ignores=[]),'<trusted-local-package-whitelist>','exec'),scope)
    whitelist=set(scope['fixed'])|set(required)|{'baseline-factory.kt','manifest.json','packaging.json'}
    return {'plan':plan,'spec':spec,'required':required,'factory':factory_data,'whitelist':whitelist,'auth':auth,'contract':contract,'execution':execution,
            'run_source':git_bytes(repo,ACTIVE,CONTROL+'run.py'),'analyzer_sha256':sha(git_bytes(repo,READINESS,FROZEN+'analyze.py'))}


def read_archive(archive,metadata,trusted):
    require(metadata['total_count']==1 and len(metadata['artifacts'])==1,'Expected one original artifact')
    artifact=metadata['artifacts'][0]
    require(artifact['name']==f'pr20-kvm-triple-{RUN_ID}' and artifact['expired'] is False,'Wrong artifact name or expired receipt')
    require(artifact['workflow_run']['id']==RUN_ID and artifact['workflow_run']['head_sha']==ACTIVE,'Artifact run identity mismatch')
    require(artifact['workflow_run']['head_branch']=='codex/kvm-pr20-candidate2-clock2-20261013','Wrong run branch')
    require(archive.stat().st_size==artifact['size_in_bytes'] and archive.stat().st_size<=MAX_BYTES,'Wrong or excessive ZIP size')
    with archive.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    require(artifact['digest']=='sha256:'+digest,'Original ZIP SHA256 mismatch')
    payload={}
    with zipfile.ZipFile(archive) as z:
        entries=z.infolist();names=[i.filename for i in entries]
        require(len(names)==len(set(names)) and len(names)<=len(trusted['whitelist']),'Duplicate/excess ZIP entries')
        require(sum(i.file_size for i in entries)<=MAX_BYTES,'Uncompressed payload exceeds20MiB')
        for item in entries:
            name=item.filename;mode=item.external_attr>>16
            require(name in trusted['whitelist'] and '/' not in name and '\\' not in name and name not in ['.','..'],'Not whitelisted flat file: '+name)
            require(not item.is_dir() and not stat.S_ISLNK(mode) and not item.flag_bits&1,'Directory/symlink/encrypted payload rejected')
            require(stat.S_IFMT(mode) in [0,stat.S_IFREG],'Special ZIP entry rejected')
            data=z.read(item);data.decode('utf-8')
            require(not SECRET.search(data),'Credential pattern rejected in '+name)
            payload[name]=data
    manifest=load(payload['manifest.json']);packaging=load(payload['packaging.json'])
    require(isinstance(manifest,list) and len({x['name'] for x in manifest})==len(manifest),'Invalid/duplicate manifest')
    require(packaging['complete'] is True and packaging['truncated_files']==0 and packaging['rejected_names']==[] and packaging['retention_days']==3,'Incomplete or altered package policy')
    require({'manifest.json','packaging.json'}|{x['name'] for x in manifest}==set(payload),'Manifest/file set mismatch')
    for item in manifest:
        data=payload[item['name']]
        require(len(data)==item['original_bytes']==item['uploaded_bytes'] and sha(data)==item['original_sha256']==item['uploaded_sha256'],'Payload manifest hash mismatch: '+item['name'])
    require(packaging['files']==len(manifest) and packaging['bytes_before_manifest']==sum(len(payload[x['name']]) for x in manifest),'Packaging totals mismatch')
    for name,data in trusted['required'].items():require(payload.get(name)==data,'Frozen source/control/probe mismatch or missing: '+name)
    if 'baseline-factory.kt' in payload:require(payload['baseline-factory.kt']==trusted['factory'],'Factory overlay changed')
    return payload,{'artifact_id':artifact['id'],'archive_sha256':digest,'archive_bytes':archive.stat().st_size,
                    'payload_files':len(manifest),'uncompressed_bytes':sum(map(len,payload.values()))}


def audit_execution(payload,trusted,jobs=None):
    issues=[];pending=[];result={}
    def note(condition,message):
        if not condition:issues.append(message)
    def record(name):return load(payload[name])
    summary=record('summary.json');original=record('summary-before-packaging.json');clock=record('clock.json')
    plan=trusted['plan'];spec=trusted['spec'];auth=trusted['auth'];contract=trusted['contract']
    note(str(summary.get('run_id'))==str(RUN_ID) and str(summary.get('run_attempt'))=='1' and summary.get('workflow_sha')==ACTIVE,'summary run/workflow identity mismatch')
    note(summary.get('source_spec')==spec,'summary source spec mismatch')
    for name in ['approved_plan_sha256','source_spec_sha256','method_plan_sha256','original_protocol_sha256','clock_contract_sha256','server_receipt_sha256']:
        note(summary.get('input_hashes',{}).get(name)==auth[name],'summary input hash mismatch: '+name)
    aliases={key:{'alias':alias,'source_commit':spec['identities'][key]['git']} for key,alias in [('B','controlled_baseline'),('C0','previous_candidate'),('C1','new_candidate')]}
    note(summary.get('version_aliases')==aliases,'version alias/actual commit mismatch')
    cutoffs={key:ANCHOR+minutes*60 for key,minutes in contract['phase_cutoff_minutes'].items()}
    exports={'KVM_BUDGET_ANCHOR':ANCHOR,'KVM_SETUP_CUTOFF':cutoffs['setup'],'KVM_CORRECTNESS_CUTOFF':cutoffs['correctness'],'KVM_COMMON_CUTOFF':cutoffs['common'],'KVM_WORK_DEADLINE':cutoffs['diagnostics']}
    note(summary.get('budget_anchor_epoch')==ANCHOR and summary.get('actual_job_started_epoch') is None and 'job_started_epoch' not in summary,'summary anchor confused with actual job start')
    note(summary.get('phase_cutoff_epochs')==cutoffs and summary.get('work_deadline_epoch')==cutoffs['diagnostics'],'summary cutoff mismatch')
    note(clock.get('status')=='PASS' and clock.get('budget_anchor_epoch')==ANCHOR and clock.get('actual_job_started_epoch') is None,'clock anchor/status mismatch')
    note(clock.get('deadline_epochs')==exports and clock.get('deadlines_minutes')==[32,38,52,55,60],'clock deadlines mismatch')
    for key in ['updated_at','anchor_kind','server_receipt_sha256']:note(clock.get(key)==contract[key],'clock/contract mismatch: '+key)
    note(clock.get('clock_contract_sha256')==auth['clock_contract_sha256'],'clock contract digest mismatch')
    observed=clock.get('first_step_observed_epoch')
    note(isinstance(observed,(int,float)) and ANCHOR<=observed<cutoffs['setup'],'invalid/expired clock first observation')
    result['clock']={'anchor_epoch':ANCHOR,'anchor_utc':contract['updated_at'],'phase_cutoff_epochs':cutoffs,'actual_job_start_verification':'PENDING_LOCAL_TERMINAL_METADATA'}
    if jobs is not None:
        matching=[job for job in jobs['jobs'] if job['id']==JOB_ID]
        require(len(matching)==1,'Expected fixed job metadata')
        job=matching[0]
        require(job['run_id']==RUN_ID and job['head_sha']==ACTIVE and job.get('run_attempt',1)==1,'Job metadata binding mismatch')
        actual=epoch(job['started_at']);note(ANCHOR<=actual,'Conservative anchor is later than actual job start')
        note(job['status']=='completed' and bool(job.get('completed_at')),'Job metadata is not terminal')
        result['clock'].update(actual_job_start_verification='BOUND_LOCAL_METADATA',actual_job_started_epoch=actual,actual_job_started_at=job['started_at'],anchor_lead_seconds=actual-ANCHOR,job_conclusion=job.get('conclusion'))
        if job.get('completed_at'):
            result['clock']['job_wall_seconds']=epoch(job['completed_at'])-actual
            note(result['clock']['job_wall_seconds']<=3600,'Externally recorded job duration exceeds60 minutes')
    else:pending.append('Externally sourced terminal job115422592925 metadata is not supplied;105s parent-reported lead is not independently verified here.')
    for layer in ['actions','phases']:
        values=summary.get(layer,{}) if layer=='actions' else summary.get(layer,[])
        for item in values.values() if isinstance(values,dict) else values:
            if item.get('status')=='PASS':
                phase=item.get('budget_phase');note(phase in cutoffs,'Missing phase budget on PASS '+layer)
                if phase in cutoffs:note(item['started_epoch']+item['seconds']<=cutoffs[phase],'Recorded PASS completion after phase deadline: '+layer+' '+str(item.get('name',phase)))
    # Reproduce only package status normalization, never invent elapsed/end times or rewrite raw JUnit.
    normalized=json.loads(json.dumps(original));interrupted=False
    for entries in [normalized.get('actions',{}).values(),normalized.get('phases',[]),normalized.get('install_groups',{}).values(),normalized.get('methods',[])]:
        for entry in entries:
            if entry.get('status') in ['RUNNING','INSTALLED']:
                entry['status_before_packaging']=entry['status'];entry['status']='INCOMPLETE';interrupted=True
    if interrupted:normalized.update(status_before_packaging=normalized['status'],status='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION')
    elif normalized['status'] in ['PREPARING','SOURCES_FROZEN','MEASURING']:normalized.update(status_before_packaging=normalized['status'],status='INCOMPLETE_EXECUTION_NOT_FINISHED')
    clean=[];cleanup={};acl_verified=True
    before_acl=payload.get('kvm-before.acl');granted_acl=payload.get('kvm-granted.acl')
    if granted_acl is not None and before_acl is None:
        note(False,'KVM granted ACL exists without original ACL');acl_verified=False
    for tag in ['trap','always']:
        name=f'cleanup-{tag}.json';c=record(name) if name in payload else None
        okay=c is not None and c.get('errors')==[] and c.get('emulator') in ['NOT_STARTED','STOPPED_CONFIRMED'] and c.get('command_groups') in ['NOT_STARTED','STOPPED_CONFIRMED'] and c.get('acl') in ['NOT_TOUCHED','UNCHANGED_EXACT','RESTORED_EXACT']
        clean.append(okay);cleanup[tag]=c
        if before_acl is not None or (c and c.get('acl') in ['UNCHANGED_EXACT','RESTORED_EXACT']):
            exact=(c is not None and c.get('acl') in ['UNCHANGED_EXACT','RESTORED_EXACT']
                   and before_acl is not None and payload.get(f'kvm-after-{tag}.acl')==before_acl)
            if granted_acl is not None and granted_acl!=before_acl:
                exact=exact and c is not None and c.get('acl')=='RESTORED_EXACT'
            note(exact,'ACL exact restoration/state missing or mismatched: '+tag)
            acl_verified=acl_verified and exact
    normalized['cleanup_verified']=all(clean)
    if not all(clean):normalized['status']='CLEANUP_UNCONFIRMED'
    note(normalized==summary,'Packaged summary differs beyond allowed status normalization')
    emulator_verified=True
    if 'device-before.json' in payload:
        for tag,c in cleanup.items():
            stopped=c is not None and c.get('emulator')=='STOPPED_CONFIRMED'
            note(stopped,'Started emulator cleanup not confirmed: '+tag);emulator_verified=emulator_verified and stopped
    result['cleanup']={'receipts':cleanup,'receipt_claims_verified':all(clean),'acl_original_bytes_verified':acl_verified,'both_verified':all(clean) and acl_verified and emulator_verified}
    if not result['cleanup']['both_verified']:pending.append('Cleanup is unconfirmed or failed; do not claim process/ACL closure.')
    # Isolated extraction of one pure parser from the trusted committed controller (not the ZIP).
    tree=ast.parse(trusted['run_source']);parser=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='raw_result')
    scope={'re':re,'json':json};exec(compile(ast.Module(body=[parser],type_ignores=[]),'<trusted-raw-parser>','exec'),scope)
    entries=summary.get('methods',[]);note(len(entries)==57,'Expected57 method ledger entries')
    methods=[];common={}
    for step,entry in zip(plan['steps'],entries):
        ordinal=step['ordinal'];note(all(entry.get(k)==v for k,v in step.items()),f'Method{ordinal} differs from frozen plan')
        status=entry.get('status');prefix=f"method-{ordinal:02d}-{step['identity']}";rawname=prefix+'-instrumentation.log'
        item={'ordinal':ordinal,'identity':step['identity'],'phase':step['phase'],'selection':step['selection'],'status':status,'junit_status':entry.get('junit_status'),'adb_exit':entry.get('adb_exit')}
        if status=='NOT_RUN':note(not any(n.startswith(prefix+'-') for n in payload),f'NOT_RUN method{ordinal} has execution files')
        interrupted_entry=(status=='INCOMPLETE' and entry.get('status_before_packaging')=='RUNNING'
                           and ordinal<=len(original.get('methods',[])) and original['methods'][ordinal-1].get('status')=='RUNNING')
        if rawname in payload:
            raw=payload[rawname].decode('utf-8');parsed={'selection':entry['selection']};records=scope['raw_result'](parsed,raw,entry.get('adb_exit'))
            item['observed_raw_parser_status']=parsed['junit_status']
            item['observed_marker_parse_errors']=sum('parse_error' in r for r in records)
            if interrupted_entry and 'log_sha256' not in entry:pending.append(f'Method{ordinal}: outer interruption left raw digest unpersisted')
            else:note(entry.get('log_sha256')==sha(payload[rawname]),f'Method{ordinal} raw digest mismatch')
            for key,value in parsed.items():
                if interrupted_entry and key not in entry:pending.append(f'Method{ordinal}: outer interruption left derived {key} unpersisted')
                else:note(entry.get(key)==value,f'Method{ordinal} raw/summary {key} mismatch')
            recname=prefix+'-records.json'
            if interrupted_entry and recname not in payload:pending.append(f'Method{ordinal}: outer interruption left records unpersisted; raw retained')
            else:note(recname in payload and record(recname)==records,f'Method{ordinal} raw records mismatch/missing')
            xmlname=prefix+'-junit.xml'
            if xmlname in payload:
                xml=ET.fromstring(payload[xmlname]);cases=xml.findall('testcase');cls,method=step['selection'].split('#')
                failed=entry.get('junit_status')=='FAIL';incomplete=status!='PASS' and not failed
                note(xml.get('tests')=='1' and xml.get('failures')==str(int(failed)) and xml.get('errors')==str(int(incomplete)),f'Method{ordinal} XML result mismatch')
                note(len(cases)==1 and cases[0].get('classname')==cls and cases[0].get('name')==method,f'Method{ordinal} XML identity mismatch')
            elif status in ['PASS','FAIL']:note(False,f'Method{ordinal} completed status without derived XML')
            elif interrupted_entry:pending.append(f'Method{ordinal}: outer interruption left derived XML unpersisted')
            if status=='PASS':
                note(parsed['junit_status']=='PASS' and not any('parse_error' in r for r in records),f'Method{ordinal} false PASS')
                gone={pkg:{'exit':1,'stdout':'','stderr':''} for pkg in PACKAGES}
                note(entry.get('processes_after')==gone and entry.get('process_exit_outcome') in ['NATURAL_EXIT','AUXILIARY_AM_KILL_CONFIRMED'],f'Method{ordinal} processes not confirmed absent')
                outcome=entry.get('process_exit_outcome')
                note((outcome=='AUXILIARY_AM_KILL_CONFIRMED')==('auxiliary_cleanup' in entry),f'Method{ordinal} auxiliary outcome/receipt mismatch')
                if 'auxiliary_cleanup' in entry:
                    helper=entry['auxiliary_cleanup'];note(ordinal==17 and helper.get('command')==['adb','-s','emulator-5554','shell','am','kill','--user','0','org.inkweft.app.a0.test'] and helper.get('status')=='ABSENCE_CONFIRMED' and helper.get('exit')==0 and helper.get('stdout')==helper.get('stderr')=='',f'Method{ordinal} auxiliary cleanup exception mismatch')
                if step['phase']=='common-comparison':
                    fixture=[r['data'] for r in records if r['marker']=='KVM_PAIR_FIXTURE'];local='.KvmLocalAppendPairProbe#' in step['selection']
                    note(len(fixture)==(3 if local else 1) and entry.get('fixtures')==fixture,f'Method{ordinal} fixture evidence mismatch')
                    key=(step['seed'],step['selection']);prior=common.setdefault(key,fixture);note(prior==fixture,f'Method{ordinal} three-way fixture mismatch')
                    if not local:
                        final=[r['data'] for r in records if r['marker']=='LIBRARY_READ_EVIDENCE']
                        note(len(final)==1 and len(fixture)==1 and final[0].get('writerSqlite')==fixture[0].get('writerSqlite'),f'Method{ordinal} writer SQLite settings changed')
        elif status in ['PASS','FAIL']:note(False,f'Method{ordinal} status without raw instrumentation')
        if status!='NOT_RUN':note(all(e.get('status')=='PASS' for e in entries[:ordinal-1]),f'Method{ordinal} ran after earlier nonPASS')
        if status=='PASS':
            phase={'correctness':'correctness','common-comparison':'common','candidate-diagnostics':'diagnostics'}[step['phase']]
            note(entry['started_epoch']<cutoffs[phase],f'Method{ordinal} began after cutoff')
            note(entry['started_epoch']+entry['seconds']<=cutoffs[phase],f'Method{ordinal} recorded PASS completion after cutoff')
        methods.append(item)
    result['methods']=methods;result['method_status_counts']=dict(collections.Counter(e.get('status') for e in entries));result['raw_junit_status_counts']=dict(collections.Counter(e.get('junit_status','NOT_RECORDED') for e in entries))
    # APKs remain private; this verifies recorded identities/signatures/native hashes, not absent binary bytes.
    apk_receipts={};signer=None;native=None;dependencies={}
    paths=['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
    for identity in ['B','C0','C1']:
        name=f'apk-{identity}.json';action=summary.get('actions',{}).get('build-'+identity,{})
        if name not in payload:
            note(action.get('status')!='PASS','Build PASS missing APK receipt: '+identity);continue
        apk=record(name);items=apk['apks'];source=spec['identities'][identity]
        note(apk.get('identity')==identity and apk.get('base_commit')==source['git'] and apk.get('source_manifest_sha256')==source['canonical_manifest_sha256'],'APK source identity mismatch: '+identity)
        note([i['path'] for i in items]==paths and apk.get('jvm_tests')==apk.get('lint')=='NOT_RUN','APK receipt scope mismatch: '+identity)
        for item in items:
            note(re.fullmatch('[0-9a-f]{64}',item['sha256']) and item['bytes']>0,'Invalid APK digest/size')
            recorded=re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\S+)$',item['signature'],re.M)
            note(recorded==item['signer_sha256'] and bool(recorded),'APK signature receipt mismatch')
            if signer is None:signer=recorded
            note(recorded==signer,'Three-source APK signer changed')
        build=items[0]['build_identity'];note(build['source_commit']==source['git'] and build['build_commit']==ACTIVE and build['formal'] is False,'Embedded build identity mismatch: '+identity)
        mupdf=items[0]['mupdf_native'];note({x['path'].split('/')[1] for x in mupdf}=={'arm64-v8a','armeabi-v7a','x86','x86_64'} and all(x['bytes']>100000 and re.fullmatch('[0-9a-f]{64}',x['sha256']) for x in mupdf),'MuPDF four ABI receipt invalid')
        if native is None:native=mupdf
        note(mupdf==native,'MuPDF bytes differ by recorded hashes')
        depname=f'dependencies-{identity}.json';note(depname in payload,'Dependencies provenance absent: '+identity)
        if depname in payload:
            current={x['path']:x for x in record(depname)};note(bool(current) and all(current.get(k)==v for k,v in dependencies.items()),'Dependency cache changed/disappeared');dependencies=current
        apk_receipts[identity]={'source_commit':source['git'],'source_manifest_sha256':source['canonical_manifest_sha256'],'apks':[{'path':i['path'],'sha256':i['sha256'],'bytes':i['bytes']} for i in items]}
    result['apk_receipts']=apk_receipts;result['apk_binary_reverification']='NOT_PERFORMED_APKS_NOT_IN_APPROVED_ARTIFACT'
    groups=summary.get('install_groups',{})
    for group in dict.fromkeys(s['install_group'] for s in plan['steps']):
        item=groups.get(group)
        if item and item.get('status')=='PASS':
            for boundary in ['before','after']:
                name=f'install-{group}-environment-{boundary}.json';note(name in payload and payload.get(name)==payload.get('device-before.json'),'Device settings missing/changed: '+name)
    complete=all(e.get('status')=='PASS' for e in entries) and len(entries)==57
    claims_complete=(summary.get('status')=='EXECUTION_COMPLETE_ANALYSIS_PENDING' or original.get('status')=='EXECUTION_COMPLETE_ANALYSIS_PENDING')
    if claims_complete:
        note(complete,'Completion claimed without57 PASS methods')
        note(len(apk_receipts)==3,'Completion claimed without all9 APK receipts')
        note(len(groups)==14 and all(g.get('status')=='PASS' for g in groups.values()),'Completion claimed without14 PASS installation groups')
        note(payload.get('device-before.json')==payload.get('device-after.json') and 'device-after.json' in payload,'Final device settings missing/changed')
        note(summary.get('status')==('EXECUTION_COMPLETE_ANALYSIS_PENDING' if all(clean) else 'CLEANUP_UNCONFIRMED'),'Completion promoted or mislabeled')
    if complete:
        note(len(apk_receipts)==3,'57PASS without all9 recorded APK identities')
        if not claims_complete:pending.append('All57 methods recorded PASS, but final group/environment acceptance did not complete; overall completion is not claimed.')
    result.update(summary_status=summary.get('status'),all57_methods_pass=complete,issues=issues,pending=pending,
                  assessment='RECORDED_EXECUTION_REVIEW_WITH_ISSUES' if issues else 'RECORDED_EXECUTION_CONSISTENT',
                  performance='NOT_ANALYZED_USE_UNCHANGED_FROZEN_ANALYZER_ON_VERIFIED_EVIDENCE',analyzer_sha256=trusted['analyzer_sha256'])
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--archive',type=Path,required=True);p.add_argument('--metadata',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--repo',type=Path,default=Path('/workspace/InkWeft-native-candidate2-clock2'));p.add_argument('--jobs',type=Path)
    args=p.parse_args();require(not args.out.exists(),'Refuse to overwrite evidence output')
    receipt_path=args.out.parent/(args.out.name+'-archive-verification.json');require(not receipt_path.exists(),'Refuse to overwrite audit receipt')
    trusted=trusted_inputs(args.repo);payload,receipt=read_archive(args.archive,load(args.metadata.read_bytes()),trusted)
    audit=audit_execution(payload,trusted,load(args.jobs.read_bytes()) if args.jobs else None)
    args.out.mkdir(parents=True)
    for name,data in payload.items():(args.out/name).write_bytes(data)
    receipt.update(status='ARCHIVE_AND_MANIFEST_VERIFIED',run_id=RUN_ID,job_id=JOB_ID,workflow_git=ACTIVE,preparation_git=PREPARATION,readiness_git=READINESS,
                   source_spec_sha256=SOURCE_SHA,method_plan_sha256=PLAN_SHA,controller_execution_hashes=trusted['execution'],runtime_audit=audit,
                   verifier_sha256=sha(Path(__file__).read_bytes()),input_metadata_sha256=sha(args.metadata.read_bytes()),jobs_metadata_sha256=sha(args.jobs.read_bytes()) if args.jobs else None,
                   note='Integrity is separate from native success and performance; original failures and unfinished methods are retained.')
    receipt_path.write_text(json.dumps(receipt,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({'status':receipt['status'],'receipt':str(receipt_path),'runtime_issues':audit['issues'],'counts':audit['method_status_counts'],'pending':audit['pending']},ensure_ascii=False))
    return 2 if audit['issues'] else 0

if __name__=='__main__':raise SystemExit(main())
