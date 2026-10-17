"""Reconstruct two exact committed trees; no runtime production or test overrides."""
import argparse,hashlib,io,json,re,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
here=Path(__file__).resolve().parent;spec=json.loads((here/'source-spec.json').read_text());sha=lambda b:hashlib.sha256(b).hexdigest()
assert spec['only_production_delta']=='android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt'
assert spec['status']=='FROZEN' and list(spec['identities'])==['C1','C2'] and spec['common_probes']==[] and not a.out.exists()
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
assert git('diff','--name-only',spec['identities']['C1']['git'],spec['identities']['C2']['git']).decode().splitlines()==[spec['only_production_delta']]
actuals={}
for identity,item in spec['identities'].items():
 ref=item['git'];assert re.fullmatch('[0-9a-f]{40}',ref) and git('rev-parse',ref+'^{tree}').decode().strip()==item['tree']
 assert item['production_overrides']==[]
 destination=a.out/identity;destination.mkdir(parents=True)
 with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',ref,'android','ci'))) as archive:
  for member in archive:
   name=Path(member.name);assert not name.is_absolute() and '..' not in name.parts and (member.isfile() or member.isdir())
   if member.name=='android/verification' or member.name.startswith('android/verification/'):continue
   archive.extract(member,destination,filter='data')
 actual={}
 for prefix in ['android','ci']:
  for path in sorted((destination/prefix).rglob('*')):
   assert not path.is_symlink()
   if path.is_file():data=path.read_bytes();actual[path.relative_to(destination).as_posix()]={'bytes':len(data),'sha256':sha(data)}
 expected=json.loads((here/(identity+'-source-manifest.json')).read_text());assert actual==expected['files']
 canonical=sha(json.dumps(actual,sort_keys=True,separators=(',',':')).encode());assert canonical==item['canonical_manifest_sha256']==expected['canonical_manifest_sha256'];actuals[identity]=actual
 print(identity,ref,canonical,len(actual),'SOURCE_ONLY_NOT_BUILT_NOT_RUN')
assert set(actuals['C1'])==set(actuals['C2'])
assert [p for p in actuals['C1'] if actuals['C1'][p]!=actuals['C2'][p]]==[spec['only_production_delta']]
