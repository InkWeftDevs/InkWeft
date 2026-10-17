"""Publish only fixed text evidence within the approved 20 MiB total budget."""
import hashlib,json,os,re,shutil,sys
from pathlib import Path
from size_bounds import SHARED_BUCKETS,BUDGET_RECEIPT_ALLOWANCE,fixed_bucket,method_wire_bound
here=Path(__file__).resolve().parent
FROZEN_RELATIVE='android/verification/pr20-raster-recovery-20261017/frozen'
FROZEN_SOURCE_SPEC_SHA256='e83af6bd7ab19c2251fd4a81c853b37de7a2071f75651bf3aca1391b7cb5f0f5'
assert re.fullmatch(r'[0-9a-f]{64}', FROZEN_SOURCE_SPEC_SHA256), 'Finalize source-spec hash before execution'
frozen=here.parents[1]/FROZEN_RELATIVE
root=Path(os.environ['KVM_ONCE']);source=root/'evidence';out=root/'upload';out.mkdir(exist_ok=False)  # Never upload unaccounted stale output.
plan=json.loads((frozen/'method-plan.json').read_text());spec=json.loads((frozen/'source-spec.json').read_text())
contract=json.loads((frozen/'marker-contract.json').read_text())
assert contract['failure_text']['raw_max_bytes']==1048576 and contract['failure_text']['wire_max_bytes']==1439104
assert hashlib.sha256((frozen/'source-spec.json').read_bytes()).hexdigest()==FROZEN_SOURCE_SPEC_SHA256, 'Frozen source-spec hash mismatch'
summary=source/'summary.json'
if summary.exists():
    original=source/'summary-before-packaging.json'
    if not original.exists():shutil.copyfile(summary,original)
    report=json.loads(summary.read_text())
    interrupted=False
    for entries in [report.get('actions',{}).values(),report.get('phases',[]),
                    report.get('install_groups',{}).values(),report.get('methods',[])]:
        for entry in entries:
            if entry.get('status') in ['RUNNING','INSTALLED']:
                entry['status_before_packaging']=entry['status']
                entry['status']='INCOMPLETE'
                interrupted=True
    if interrupted:
        report['status_before_packaging']=report['status']
        report['status']='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION'
    elif report['status'] in ['PREPARING','SOURCES_FROZEN','MEASURING']:
        report['status_before_packaging']=report['status']
        report['status']='INCOMPLETE_EXECUTION_NOT_FINISHED'
    def cleaned(tag):
        p=source/f'cleanup-{tag}.json'
        if not p.exists():return False
        r=json.loads(p.read_text())
        return (r.get('errors')==[] and r.get('emulator') in ['NOT_STARTED','STOPPED_CONFIRMED'] and
                r.get('adb_server') in ['NOT_STARTED','STOPPED_CONFIRMED'] and
                r.get('command_groups') in ['NOT_STARTED','STOPPED_CONFIRMED'] and
                r.get('acl') in ['NOT_TOUCHED','UNCHANGED_EXACT','RESTORED_EXACT'])
    report['cleanup_verified']=all(cleaned(tag) for tag in ['trap','always'])
    if not report['cleanup_verified']:report['status']='CLEANUP_UNCONFIRMED'
    summary.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
fixed=['summary.json','summary-before-packaging.json','clock.json','host.json','host-after.json','device-before.json','device-after.json','adb-server.json',
       'emulator-configuration.json','emulator-selected.log','kvm-identity.json','kvm-before.acl','kvm-granted.acl',
       'kvm-after-trap.acl','kvm-after-always.acl','cleanup-trap.json','cleanup-always.json']
for side in ['C1','C2']:fixed += [f'apk-{side}.json',f'dependencies-{side}.json',f'build-{side}.log']
fixed += ['official-dependency-check.json','official-dependency-check.log']
fixed += [name+'.log' for name in ['source-fetch','source-reconstruction','sdk','acceleration','avd']]
for group in dict.fromkeys(x['install_group'] for x in plan['steps']):
    fixed += [f'install-{group}.log']
    for boundary in ['before','after']:
        fixed += [f'install-{group}-environment-{boundary}.json',f'install-{group}-host-{boundary}.json']
for step in plan['steps']:
    prefix=f"method-{step['ordinal']:02d}-{step['identity']}"
    fixed += [prefix+suffix for suffix in ['-instrumentation.log','-junit.xml','-records.json']]
    fixed.append(prefix+'-log-forwarding.json')
optional=['summary.tmp'] if (source/'summary.tmp').exists() else []
assert not {p.name for p in source.iterdir()}-set(fixed)-set(optional), 'Unexpected evidence file; no silent residual upload'
missing=[name for name in fixed if not (source/name).is_file()]
if summary.exists():
    report=json.loads(summary.read_text())
    report['missing_files']=missing
    if missing and report['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING':
        report['status_before_evidence_check']=report['status']
        report['status']='EVIDENCE_INCOMPLETE'
    summary.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
items=[(name,source/name) for name in fixed+optional if (source/name).is_file()]
for name in ['PLAN.md','original-protocol.json','source-spec.json','method-plan.json','marker-contract.json',
             'C1-source-manifest.json','C2-source-manifest.json','prepare_sources.py','prior-correctness-reuse.json']:
    items.append(('frozen-'+name,frozen/name))
for name in ['authorization.json','EXECUTION_SHA256SUMS','run.py','markers.py','package.py','cleanup.py','failure_text.py','size_bounds.py','workflow.proposed.yml']:
    items.append((name,here/name))
for probe in spec['common_probes']:
    path=here.parents[1]/probe['from_path']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==probe['sha256']
    items.append((Path(probe['from_path']).name,path))
secret=re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ssh-rsa|ssh-ed25519) [A-Za-z0-9+/]{40,}')
manifest=[];rejected=[];total=0
assert len(set(name for name,_ in items))==len(items)
for name,path in items:
    assert path.is_file() and not path.is_symlink()
    data=path.read_bytes();data.decode('utf-8');digest=hashlib.sha256(data).hexdigest()
    item={'name':name,'original_bytes':len(data),'original_sha256':digest,'uploaded_bytes':0,'uploaded_sha256':None}
    if secret.search(data):item['omitted']='credential/key pattern; refuse unsafe content';rejected.append(name)
    else:item.update(uploaded_bytes=len(data),uploaded_sha256=digest);total+=len(data)
    manifest.append(item)
manifest_bytes=(json.dumps(manifest,indent=2)+'\n').encode()
packaging_bytes=(json.dumps({'files':len(manifest),'bytes_before_manifest':total,'rejected_names':rejected,'truncated_files':0,'retention_days':3,'missing_files':missing,'complete':not rejected and not missing},indent=2)+'\n').encode()
# The mutually exclusive shared buckets include both cleanup invocations, both
# summaries and any interrupted atomic summary.tmp. No stale upload is permitted.
buckets={name:0 for name in SHARED_BUCKETS}
for item in manifest:
    bucket=fixed_bucket(item['name'])
    if bucket is not None:buckets[bucket]+=item['uploaded_bytes']
buckets['packaging']=len(manifest_bytes)+len(packaging_bytes)+BUDGET_RECEIPT_ALLOWANCE
buckets['official_fixed']=SHARED_BUCKETS['official_fixed']
entries={entry['ordinal']:entry for entry in report.get('methods',[])} if summary.exists() else {}
assert len(entries)==len(report.get('methods',[])) if summary.exists() else True
assert set(entries)<=set(step['ordinal'] for step in plan['steps'])
wire_rows=[]
for step in plan['steps']:
    entry=entries.get(step['ordinal'],{})
    if entry:assert entry['identity']==step['identity'] and entry['selection']==step['selection']
    log=source/f"method-{step['ordinal']:02d}-{step['identity']}-instrumentation.log"
    amount,kind=method_wire_bound(entry,contract['methods'][str(step['ordinal'])],log.exists())
    wire_rows.append({'ordinal':step['ordinal'],'bytes_upper':amount,'kind':kind})
wire_upper=buckets['official_fixed']+sum(row['bytes_upper'] for row in wire_rows)
artifact_bytes=total+len(manifest_bytes)+len(packaging_bytes)+BUDGET_RECEIPT_ALLOWANCE
shared_bytes=sum(buckets.values());joint_bytes=artifact_bytes+wire_upper
violations=[name for name,value in buckets.items() if value>SHARED_BUCKETS[name]]
budget={'status':'PASS' if not violations and joint_bytes<=20*1024*1024 else 'EVIDENCE_OVERSIZE_NOT_UPLOADED',
        'artifact_bytes_upper':artifact_bytes,'budget_receipt_allowance':BUDGET_RECEIPT_ALLOWANCE,
        'official_evidence_wire_upper':wire_upper,'joint_bytes_upper':joint_bytes,'shared_bytes':shared_bytes,
        'shared_buckets':{name:{'bytes_upper':value,'limit':SHARED_BUCKETS[name]}for name,value in buckets.items()},
        'bucket_violations':violations,'wire_rows':wire_rows,'limit':20*1024*1024}
if budget['status']!='PASS':
    refusal=(json.dumps(dict(budget,files=manifest),indent=2)+'\n').encode()
    assert len(refusal)<=SHARED_BUCKETS['packaging']
    assert len(refusal)+wire_upper<=20*1024*1024, 'Refusal artifact plus emitted evidence also exceeds cap'
    (out/'packaging-failure.json').write_bytes(refusal)
    raise SystemExit(1)
budget_bytes=(json.dumps(budget,indent=2)+'\n').encode();assert len(budget_bytes)<=BUDGET_RECEIPT_ALLOWANCE
for name,path in items:
    if name not in rejected:shutil.copyfile(path,out/name)
(out/'manifest.json').write_bytes(manifest_bytes);(out/'packaging.json').write_bytes(packaging_bytes);(out/'publication-budget.json').write_bytes(budget_bytes)
assert sum(p.stat().st_size for p in out.iterdir())<=20*1024*1024
print(json.dumps({'files':len(manifest),'bytes':total,'omitted':len(rejected),'truncated':0,'joint_bytes_upper':joint_bytes,'shared_bytes':shared_bytes}))
# Fixed, limited terminal receipt for official job-log consumers. Artifact bytes stay authoritative.
if summary.exists():
    final=json.loads(summary.read_text())
    acl={}
    before=source/'kvm-before.acl'
    for tag in ['trap','always']:
        receipt_path=source/('cleanup-'+tag+'.json')
        value=json.loads(receipt_path.read_text()) if receipt_path.exists() else {}
        after=source/('kvm-after-'+tag+'.acl')
        acl[tag]={'emulator':value.get('emulator'),'command_groups':value.get('command_groups'),
                  'acl':value.get('acl'),'error_count':len(value.get('errors',[])) if 'errors' in value else None,
                  'before_sha256':hashlib.sha256(before.read_bytes()).hexdigest() if before.exists() else None,
                  'after_sha256':hashlib.sha256(after.read_bytes()).hexdigest() if after.exists() else None,
                  'exact_match':before.read_bytes()==after.read_bytes() if before.exists() and after.exists() else None}
    safe={'scope':'DIAGNOSTIC_ONLY','run_id':final['run_id'],'workflow_sha':final['workflow_sha'],
          'input_hashes':final['input_hashes'],'status':final['status'],'cleanup_verified':final['cleanup_verified'],
          'cleanup':acl,'methods':[dict((k,e.get(k)) for k in ['ordinal','identity','phase','selection','seed','status','junit_status','adb_exit','process_exit_outcome','log_sha256']) for e in final['methods']],
          'install_groups':{k:{'identity':v['identity'],'status':v['status']} for k,v in final['install_groups'].items()},
          'device_before_sha256':hashlib.sha256((source/'device-before.json').read_bytes()).hexdigest() if (source/'device-before.json').exists() else None,
          'device_after_sha256':hashlib.sha256((source/'device-after.json').read_bytes()).hexdigest() if (source/'device-after.json').exists() else None,
          'missing_files':missing,'packaging_complete':not rejected and not missing,'manifest_sha256':hashlib.sha256(manifest_bytes).hexdigest(),
          'summary_sha256':hashlib.sha256(summary.read_bytes()).hexdigest()}
    terminal='INKWEFT_CAUSAL_FINAL '+json.dumps(safe,separators=(',',':'))
    assert len((terminal+'\n').encode())<=32768, 'FINAL exceeds its fixed official budget; not printed'
    print(terminal,flush=True)
sys.exit(bool(rejected or missing))
