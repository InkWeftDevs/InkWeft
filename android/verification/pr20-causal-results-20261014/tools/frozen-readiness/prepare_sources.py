"""Reconstruct one exact committed Android/CI source identity; no builds or overlays."""
import argparse,hashlib,io,json,re,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',required=True,type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args()
here=Path(__file__).resolve().parent;spec=json.loads((here/'source-spec.json').read_text())
assert spec['status']=='FROZEN' and list(spec['identities'])==['C1'] and spec['common_probes']==[]
identity=spec['identities']['C1'];assert identity['production_overrides']==[]
ref=identity['git'];assert re.fullmatch('[0-9a-f]{40}',ref) and not a.out.exists()
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
assert git('rev-parse',ref+'^{tree}').decode().strip()==identity['tree']
destination=a.out/'C1';destination.mkdir(parents=True)
with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',ref,'android','ci'))) as archive:
    for member in archive:
        name=Path(member.name);assert not name.is_absolute() and '..' not in name.parts
        assert member.isfile() or member.isdir(), 'No symlink/device source members'
        if member.name=='android/verification' or member.name.startswith('android/verification/'):continue
        archive.extract(member,destination,filter='data')
actual={}
for prefix in ['android','ci']:
    for path in sorted((destination/prefix).rglob('*')):
        if path.is_file():
            content=path.read_bytes();actual[path.relative_to(destination).as_posix()]={'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()}
expected=json.loads((here/'C1-source-manifest.json').read_text())
assert actual==expected['files']
canonical=hashlib.sha256(json.dumps(actual,sort_keys=True,separators=(',',':')).encode()).hexdigest()
assert canonical==identity['canonical_manifest_sha256']==expected['canonical_manifest_sha256']
print('C1',ref,canonical,len(actual),'SOURCE_ONLY_NOT_BUILT_NOT_RUN')
