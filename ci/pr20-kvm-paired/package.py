"""Curate only approved text evidence; never publish APKs, databases or emulator keys."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys

here=Path(__file__).resolve().parent;root=Path(os.environ['KVM_ONCE']);source=root/'evidence';out=root/'upload';out.mkdir(exist_ok=True)
summary=source/'summary.json'
if summary.exists():
    report=json.loads(summary.read_text())
    if any(x['status']=='RUNNING' for x in report['methods']):
        report['status']='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION'
        for x in report['methods']:
            if x['status']=='RUNNING':x['status']='INCOMPLETE'
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

fixed=['summary.json','clock.json','analysis-failure.json','host.json','device-before.json','device-after.json','emulator-configuration.json','emulator-selected.log',
       'apk-B.json','apk-C.json','dependencies-B.json','dependencies-C.json','kvm-identity.json','kvm-before.acl','kvm-granted.acl',
       'kvm-after-trap.acl','kvm-after-always.acl','cleanup-trap.json','cleanup-always.json','paired-metrics.json','raw-saves.csv']
logs=['source-fetch','source-reconstruction','sdk','build-B','build-C','acceleration','avd']
fixed += [x+'.log' for x in logs]
for visit in range(1,9):
    fixed += [f'visit-{visit:02d}-environment-before.json',f'visit-{visit:02d}-environment-after.json',f'visit-{visit:02d}-install.log']
    for side in ['B','C']:
        for name in ['local-append','library','history']:
            fixed += [f'visit-{visit:02d}-{side}-{name}'+suffix for suffix in ['-instrumentation.log','-junit.xml','-records.json']]
plans=['PLAN.md','protocol.json','source-spec.json','B-source-manifest.json','C-source-manifest.json','authorization.json','EXECUTION_SHA256SUMS']
items=[(name,source/name) for name in fixed if (source/name).is_file()]
items += [('frozen-'+name,here/name) for name in plans]
# Raw sources are small, public synthetic probes and make the overlay independently reviewable.
items += [('probe-'+p.name,p) for p in (here/'common-overlay').rglob('*.kt')]
items += [('baseline-factory.kt',next((here/'baseline-overlay').rglob('*.kt')))]
secret=re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ssh-rsa|ssh-ed25519) [A-Za-z0-9+/]{40,}')
manifest=[];rejected=[];total=0
for name,path in items:
    assert path.is_file() and not path.is_symlink()
    data=path.read_bytes();data.decode('utf-8')
    digest=hashlib.sha256(data).hexdigest()
    item={'name':name,'original_bytes':len(data),'original_sha256':digest,'uploaded_bytes':0,'uploaded_sha256':None}
    if secret.search(data):
        item['omitted']='credential/key pattern; refuse unsafe content';rejected.append(name)
    else:
        item.update(uploaded_bytes=len(data),uploaded_sha256=digest);total+=len(data)
    manifest.append(item)
if total>19*1024*1024:
    # Leave room for manifest; no log is silently truncated to claim complete evidence.
    (out/'packaging-failure.json').write_text(json.dumps({'status':'EVIDENCE_OVERSIZE_NOT_UPLOADED','bytes':total,'limit':20*1024*1024,'files':manifest},indent=2))
    raise SystemExit(1)
for name,path in items:
    if name not in rejected:shutil.copyfile(path,out/name)
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
(out/'packaging.json').write_text(json.dumps({'files':len(manifest),'bytes_before_manifest':total,'rejected_names':rejected,'truncated_files':0,'retention_days':3,'complete':not rejected},indent=2)+'\n')
assert sum(p.stat().st_size for p in out.iterdir())<=20*1024*1024
print(json.dumps({'files':len(manifest),'bytes':total,'omitted':len(rejected),'truncated':0}))
sys.exit(bool(rejected))
