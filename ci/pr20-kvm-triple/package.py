"""Publish only fixed text evidence within the approved 20 MiB total budget."""
import hashlib,json,os,re,shutil,sys
from pathlib import Path
here=Path(__file__).resolve().parent
frozen=here.parents[1]/'android/verification/pr20-native-readiness-20261013'
root=Path(os.environ['KVM_ONCE']);source=root/'evidence';out=root/'upload';out.mkdir(exist_ok=True)
plan=json.loads((frozen/'method-plan.json').read_text());spec=json.loads((frozen/'source-spec.json').read_text())
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
                r.get('command_groups') in ['NOT_STARTED','STOPPED_CONFIRMED'] and
                r.get('acl') in ['NOT_TOUCHED','UNCHANGED_EXACT','RESTORED_EXACT'])
    report['cleanup_verified']=all(cleaned(tag) for tag in ['trap','always'])
    if not report['cleanup_verified']:report['status']='CLEANUP_UNCONFIRMED'
    summary.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
fixed=['official-dependency-check.json','official-dependency-check.log','summary.json','summary-before-packaging.json','clock.json','host.json','host-after.json','device-before.json','device-after.json',
       'emulator-configuration.json','emulator-selected.log','kvm-identity.json','kvm-before.acl','kvm-granted.acl',
       'kvm-after-trap.acl','kvm-after-always.acl','cleanup-trap.json','cleanup-always.json']
for side in ['B','C0','C1']:fixed += [f'apk-{side}.json',f'dependencies-{side}.json',f'build-{side}.log']
fixed += [name+'.log' for name in ['source-fetch','source-reconstruction','sdk','acceleration','avd']]
for group in dict.fromkeys(x['install_group'] for x in plan['steps']):
    fixed += [f'install-{group}.log']
    for boundary in ['before','after']:
        fixed += [f'install-{group}-environment-{boundary}.json',f'install-{group}-host-{boundary}.json']
for step in plan['steps']:
    prefix=f"method-{step['ordinal']:02d}-{step['identity']}"
    fixed += [prefix+suffix for suffix in ['-instrumentation.log','-junit.xml','-records.json']]
items=[(name,source/name) for name in fixed if (source/name).is_file()]
for name in ['PLAN.md','original-protocol.json','source-spec.json','method-plan.json','B-source-manifest.json',
             'C0-source-manifest.json','C1-source-manifest.json','prepare_sources.py']:
    items.append(('frozen-'+name,frozen/name))
for name in ['authorization.json','budget-revision.json','EXECUTION_SHA256SUMS']:
    items.append((name,here/name))
for probe in spec['common_probes']:
    path=here.parents[1]/probe['from_path']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==probe['sha256']
    items.append((Path(probe['from_path']).name,path))
factory=spec['identities']['B']['production_overrides'][0]
p=root/'sources/B'/factory['path']
if p.exists():
    assert hashlib.sha256(p.read_bytes()).hexdigest()==factory['sha256']
    items.append(('baseline-factory.kt',p))
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
packaging_bytes=(json.dumps({'files':len(manifest),'bytes_before_manifest':total,'rejected_names':rejected,'truncated_files':0,'retention_days':3,'complete':not rejected},indent=2)+'\n').encode()
if total+len(manifest_bytes)+len(packaging_bytes)>20*1024*1024:
    (out/'packaging-failure.json').write_text(json.dumps({'status':'EVIDENCE_OVERSIZE_NOT_UPLOADED','bytes':total,'limit':20*1024*1024,'files':manifest},indent=2))
    raise SystemExit(1)
for name,path in items:
    if name not in rejected:shutil.copyfile(path,out/name)
(out/'manifest.json').write_bytes(manifest_bytes);(out/'packaging.json').write_bytes(packaging_bytes)
assert sum(p.stat().st_size for p in out.iterdir())<=20*1024*1024
print(json.dumps({'files':len(manifest),'bytes':total,'omitted':len(rejected),'truncated':0}))
sys.exit(bool(rejected))
