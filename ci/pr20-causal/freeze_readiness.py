"""Offline source/readiness freeze only. Does not build, access the network or activate CI."""
import argparse,hashlib,io,json,re,subprocess,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--git',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
assert re.fullmatch('[0-9a-f]{40}',a.git)
assert a.out.resolve().is_relative_to('/workspace/scratch') and not a.out.exists()
here=Path(__file__).resolve().parent
assert 'UNBOUND' in (here/'run.py').read_text(), 'Use the unbound source template'
def git(*args):return subprocess.check_output(['git','-C',str(a.repo),*args])
files={}
with tarfile.open(fileobj=io.BytesIO(git('archive','--format=tar',a.git,'android','ci')))as archive:
    for member in archive:
        name=member.name
        assert not Path(name).is_absolute() and '..' not in Path(name).parts
        assert member.isfile() or member.isdir()
        if member.isdir() or name.startswith('android/verification/'):continue
        data=archive.extractfile(member).read();files[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
canonical=hashlib.sha256(json.dumps(files,sort_keys=True,separators=(',',':')).encode()).hexdigest()
contract=json.loads((here/'marker-contract.template.json').read_text());contract['status']='FROZEN'
for rule in contract['methods'].values():
    assert files[rule['test_source_path']]['sha256']==rule['test_source_sha256'], 'Diagnostic source changed; refresh schemas/review first'
plan=json.loads((here/'method-plan.template.json').read_text());plan['status']='FROZEN_DIAGNOSTIC_NOT_RUN'
spec={'status':'FROZEN','scope':'DIAGNOSTIC_ONLY','identities':{'C1':{'alias':'causal_diagnostic_candidate','git':a.git,'tree':git('rev-parse',a.git+'^{tree}').decode().strip(),'canonical_manifest_sha256':canonical,'production_overrides':[]}},'common_probes':[]}
a.out.mkdir(parents=True)
def write(name,value): (a.out/name).write_text(json.dumps(value,indent=2)+'\n')
write('source-spec.json',spec);write('C1-source-manifest.json',{'canonical_manifest_sha256':canonical,'files':files});write('method-plan.json',plan);write('marker-contract.json',contract)
(a.out/'PLAN.md').write_text((here/'PLAN.template.md').read_text().replace('Status: UNBOUND.','Status: FROZEN_SOURCE_NOT_RUN.'))
for source,dest in [('prepare_sources.py','prepare_sources.py'),('original-protocol.json','original-protocol.json')]: (a.out/dest).write_bytes((here/source).read_bytes())
(a.out/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n'for f in sorted(a.out.iterdir())))
print(json.dumps({'status':'SOURCE_READINESS_FROZEN_NOT_EXECUTED','git':a.git,'source_manifest_sha256':canonical,'files':len(files),'out':str(a.out)}))
