"""Verify and safely extract the single approved text artifact; never execute its contents."""
from pathlib import Path
import argparse,hashlib,json,zipfile,stat,subprocess
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--metadata',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
meta=json.loads(a.metadata.read_text());assert meta['total_count']==1 and len(meta['artifacts'])==1
artifact=meta['artifacts'][0];assert artifact['name']=='pr20-kvm-triple-38419007471' and not artifact['expired']
assert a.archive.stat().st_size==artifact['size_in_bytes']
actual=hashlib.file_digest(a.archive.open('rb'),'sha256').hexdigest();assert artifact['digest']=='sha256:'+actual
assert not a.out.exists();a.out.mkdir()
with zipfile.ZipFile(a.archive) as z:
    names=z.namelist();assert len(names)==len(set(names))
    assert sum(item.file_size for item in z.infolist())<=20*1024*1024
    for item in z.infolist():
        name=Path(item.filename);mode=item.external_attr>>16
        assert len(name.parts)==1 and not name.is_absolute() and name.name not in ['.','..']
        assert not stat.S_ISLNK(mode) and not item.is_dir()
        assert name.suffix not in ['.apk','.aar','.db','.wal','.keystore']
        data=z.read(item);data.decode('utf-8');(a.out/name).write_bytes(data)
manifest=json.loads((a.out/'manifest.json').read_text());packaging=json.loads((a.out/'packaging.json').read_text())
assert packaging['complete'] and packaging['truncated_files']==0 and not packaging['rejected_names'] and packaging['retention_days']==3
expected={'manifest.json','packaging.json'}|{item['name'] for item in manifest};assert expected=={item.name for item in a.out.iterdir()}
for item in manifest:
    b=(a.out/item['name']).read_bytes();h=hashlib.sha256(b).hexdigest()
    assert len(b)==item['original_bytes']==item['uploaded_bytes'] and h==item['original_sha256']==item['uploaded_sha256']
fixed='6e6391c7de228b69453499ea106ffed7615afb29';root='android/verification/pr20-native-readiness-20261013/'
for name in ['PLAN.md','original-protocol.json','source-spec.json','method-plan.json','B-source-manifest.json','C0-source-manifest.json','C1-source-manifest.json','prepare_sources.py']:
    expected=subprocess.check_output(['git','show',fixed+':'+root+name]);assert (a.out/('frozen-'+name)).read_bytes()==expected
workflow='30acef2e082eb1cd2d70471737824da32fbe9c24'
for name in ['authorization.json','budget-revision.json','EXECUTION_SHA256SUMS']:
    expected=subprocess.check_output(['git','show',workflow+':ci/pr20-kvm-triple/'+name]);assert (a.out/name).read_bytes()==expected
for line in (a.out/'EXECUTION_SHA256SUMS').read_text().splitlines():
    sha,name=line.split('  ',1);b=subprocess.check_output(['git','show',workflow+':ci/pr20-kvm-triple/'+name]);assert hashlib.sha256(b).hexdigest()==sha
receipt={'status':'ARCHIVE_AND_MANIFEST_VERIFIED','artifact_id':artifact['id'],'archive_sha256':actual,'archive_bytes':a.archive.stat().st_size,'payload_files':len(manifest),'uncompressed_bytes':sum(item.stat().st_size for item in a.out.iterdir()),'fixed_plan_git':fixed,'workflow_git':workflow,'note':'Integrity is separate from execution correctness/performance; raw logs still require review.'}
(a.out.parent/'archive-verification.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
