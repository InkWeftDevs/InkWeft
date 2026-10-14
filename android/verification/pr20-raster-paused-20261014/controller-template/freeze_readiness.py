"""Offline exact two-commit source freeze. No build, network, activation or push."""
import argparse,hashlib,io,json,re,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
here=Path(__file__).resolve().parent;scope=json.loads((here/'source-scope.template.json').read_text());sha=lambda b:hashlib.sha256(b).hexdigest()
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
path=scope['control_path'];assert path=='android/data-local/src/main/kotlin/org/inkweft/data/InkRepository.kt'
assert list(scope['identities'])==['C1','C2']
assert git('diff','--name-only',scope['identities']['C1'],scope['identities']['C2']).decode().splitlines()==[path], 'Whole Git trees must differ only at the approved production file'
contract=json.loads((here/'marker-contract.template.json').read_text());contract['status']='FROZEN'
plan=json.loads((here/'method-plan.template.json').read_text());plan['status']='FROZEN_DIAGNOSTIC_NOT_RUN'
spec={'status':'FROZEN','scope':'DIAGNOSTIC_ONLY','identities':{},'common_probes':[],'only_production_delta':path}
manifests={}
for identity,ref in scope['identities'].items():
 assert re.fullmatch('[0-9a-f]{40}',ref)
 contents={}
 with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',ref,'android','ci'))) as archive:
  for member in archive:
   assert not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
   assert member.isfile() or member.isdir()
   if member.isfile() and not member.name.startswith('android/verification/'):contents[member.name]=archive.extractfile(member).read()
 assert sha(contents[path])==scope['control_sha256'if identity=='C1'else'candidate_sha256']
 for name,expected in scope['common_tests'].items():assert sha(contents[name])==expected, 'Common test changed; review required: '+name
 for rule in contract['methods'].values():assert sha(contents[rule['test_source_path']])==rule['test_source_sha256']
 files={name:{'bytes':len(data),'sha256':sha(data)}for name,data in contents.items()};canonical=sha(json.dumps(files,sort_keys=True,separators=(',',':')).encode())
 spec['identities'][identity]={'alias':'all_miss_control'if identity=='C1'else'all_miss_candidate','git':ref,'tree':git('rev-parse',ref+'^{tree}').decode().strip(),'canonical_manifest_sha256':canonical,'production_overrides':[]}
 manifests[identity]={'canonical_manifest_sha256':canonical,'files':files}
left=manifests['C1']['files'];right=manifests['C2']['files'];assert set(left)==set(right) and [n for n in left if left[n]!=right[n]]==[path]
a.out.mkdir(parents=True)
def write(name,v):(a.out/name).write_text(json.dumps(v,indent=2)+'\n')
for identity,manifest in manifests.items():write(identity+'-source-manifest.json',manifest)
write('source-spec.json',spec);write('method-plan.json',plan);write('marker-contract.json',contract)
(a.out/'PLAN.md').write_text((here/'PLAN.template.md').read_text().replace('Status: UNBOUND.','Status: FROZEN_SOURCE_NOT_RUN.'))
for name in ['prepare_sources.py','original-protocol.json']:(a.out/name).write_bytes((here/name).read_bytes())
(a.out/'SHA256SUMS').write_text(''.join(sha(f.read_bytes())+'  '+f.name+'\n'for f in sorted(a.out.iterdir())))
print(json.dumps({'status':'FROZEN_SOURCE_NOT_RUN','source_gits':scope['identities'],'only_production_delta':path,'out':str(a.out)}))
