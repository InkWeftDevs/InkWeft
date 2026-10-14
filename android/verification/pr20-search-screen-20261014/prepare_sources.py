"""Materialize and verify two frozen Android source trees; never build or run them."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', required=True, type=Path)
parser.add_argument('--out', required=True, type=Path)
args = parser.parse_args()
here = Path(__file__).resolve().parent
spec = json.loads((here / 'source-spec.json').read_text())
assert not args.out.exists(), 'Use a new destination; existing trees are never overwritten'

def git(*arguments):
    return subprocess.check_output(['git', '-C', str(args.repo), *arguments])

def overlay(entry, destination):
    content = git('show', entry['from_git'] + ':' + entry['from_path'])
    assert hashlib.sha256(content).hexdigest() == entry['sha256'], entry['from_path']
    assert len(content) == entry['bytes'], entry['from_path']
    relative = Path(entry['path'])
    assert not relative.is_absolute() and '..' not in relative.parts
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)

args.out.mkdir(parents=True)
for label in ['C0', 'C1']:
    identity = spec['identities'][label]
    ref = identity['git']
    assert len(ref) == 40 and all(c in '0123456789abcdef' for c in ref)
    assert git('rev-parse', ref + '^{tree}').decode().strip() == identity['tree']
    destination = args.out / label
    destination.mkdir()
    archive = git('archive', '--format=tar', ref, 'android', 'ci')
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for member in source:
            relative = Path(member.name)
            assert not relative.is_absolute() and '..' not in relative.parts
            assert member.isfile() or member.isdir(), 'No links or devices in source snapshots'
            if member.name == 'android/verification' or member.name.startswith('android/verification/'):
                continue
            source.extract(member, destination, filter='data')
    for entry in spec['common_probes'] + identity['production_overrides']:
        overlay(entry, destination)
    actual = {}
    for prefix in ['android', 'ci']:
        for path in sorted((destination / prefix).rglob('*')):
            if path.is_file():
                content = path.read_bytes()
                actual[path.relative_to(destination).as_posix()] = {
                    'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
    expected = json.loads((here / (label + '-source-manifest.json')).read_text())
    assert actual == expected['files'], label + ' does not match the frozen build inputs'
    canonical = json.dumps(actual, sort_keys=True, separators=(',', ':')).encode()
    digest = hashlib.sha256(canonical).hexdigest()
    assert digest == identity['canonical_manifest_sha256']
    print(label, ref, digest, len(actual), 'SOURCE_ONLY_NOT_BUILT_NOT_RUN')
