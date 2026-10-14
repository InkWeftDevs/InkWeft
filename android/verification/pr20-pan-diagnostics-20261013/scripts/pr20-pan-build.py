import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import time

phase, repository, *tasks = sys.argv[1:]
root = pathlib.Path(repository)
out = pathlib.Path('/workspace/scratch/pr20-closure-evidence')

def sources():
    result = {}
    for path in (root / 'android').rglob('*'):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in {'build', '.gradle', 'verification', '.kotlin'} for part in relative.parts):
            continue
        if 'src' in relative.parts or path.name in {'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'libs.versions.toml'}:
            result[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return dict(sorted(result.items()))

started = datetime.datetime.now(datetime.timezone.utc).isoformat()
before_sources = sources()
(out / f'{phase}-source-sha256.json').write_text(json.dumps(dict(utc=started, root=str(root), files=before_sources), indent=2) + '\n')
command = ['bash', '/workspace/scratch/run-inkweft-gradle-pan', *tasks]
print('BUILD ' + phase, flush=True)
before = time.monotonic()
with (out / f'{phase}-build.log').open('w') as log:
    result = subprocess.run(command, cwd=root / 'android', stdout=log, stderr=subprocess.STDOUT)
unchanged = before_sources == sources()
receipt = dict(started_utc=started, seconds=time.monotonic()-before, exit_code=result.returncode, command=command,
               cwd=str(root / 'android'), source_files=len(before_sources), source_unchanged_during_build=unchanged)
(out / f'{phase}-build.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps(receipt), flush=True)
sys.exit(result.returncode if result.returncode else (0 if unchanged else 2))
