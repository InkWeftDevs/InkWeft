"""Reconstruct source snapshots only; never builds/runs/tests or changes Git refs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile

parser=argparse.ArgumentParser()
parser.add_argument('--repo',required=True,type=Path)
parser.add_argument('--out',required=True,type=Path)
args=parser.parse_args()
here=Path(__file__).resolve().parent
spec=json.loads((here/'source-spec.json').read_text())
assert not args.out.exists(), 'Use a new destination; never overwrite an existing tree'
args.out.mkdir(parents=True)
for label,ref in [('B',spec['baseline_git']),('C',spec['candidate_git'])]:
    destination=args.out/label;destination.mkdir()
    archive=subprocess.check_output(['git','-C',str(args.repo),'archive','--format=tar',ref])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            assert not member.name.startswith('/') and '..' not in Path(member.name).parts
            assert member.isfile() or member.isdir(), 'No links/devices in prepared source'
        tar.extractall(destination,filter='data')
    overlays=[here/'common-overlay']
    if label=='B':overlays.append(here/'baseline-overlay')
    for overlay in overlays:
        for source in overlay.rglob('*'):
            if not source.is_file():continue
            target=destination/source.relative_to(overlay)
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    manifest=json.loads((here/f'{label}-source-manifest.json').read_text())
    actual={}
    for prefix in ['android','ci']:
        for file in (destination/prefix).rglob('*'):
            if not file.is_file():continue
            relative=file.relative_to(destination).as_posix()
            if relative.startswith('android/verification/'):continue
            b=file.read_bytes();actual[relative]={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
    assert actual==manifest['files'], f'{label} source differs from frozen manifest'
    canonical=json.dumps(actual,sort_keys=True,separators=(',',':')).encode()
    assert hashlib.sha256(canonical).hexdigest()==manifest['canonical_manifest_sha256']
    print(label,ref,manifest['canonical_manifest_sha256'],len(actual),'SOURCE_ONLY_NOT_BUILT')
