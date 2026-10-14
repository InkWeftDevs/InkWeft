import datetime
import hashlib
import json
import pathlib
import subprocess
import sys

phase, root_arg = sys.argv[1:]
root = pathlib.Path(root_arg)
out = pathlib.Path('/workspace/scratch/android-perf-evidence')
adb = '/workspace/.setup/tools/android-sdk/platform-tools/adb'
paths = ['app/build/outputs/apk/debug/app-debug.apk',
         'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
         'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
receipt = dict(adb_serial='emulator-5554', status='running', artifacts=[],
               started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
for relative in paths:
    path = root / relative
    entry = dict(path=str(path), size_bytes=path.stat().st_size,
                 sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                 command=[adb, '-s', 'emulator-5554', 'install', '-r', '-t', str(path)],
                 started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    run = subprocess.run(entry['command'], capture_output=True, text=True, timeout=300)
    entry.update(exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr,
                 completed_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    receipt['artifacts'].append(entry)
    success = run.returncode == 0 and 'Success' in run.stdout
    print(json.dumps(dict(path=relative, success=success)), flush=True)
    if not success:
        receipt['status'] = 'failed'
        break
else:
    receipt['status'] = 'success'
receipt['completed_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
(out / f'{phase}-apk-installation.json').write_text(json.dumps(receipt, indent=2)+'\n')
sys.exit(0 if receipt['status'] == 'success' else 1)
