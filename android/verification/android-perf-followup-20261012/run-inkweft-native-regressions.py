import datetime
import json
import pathlib
import subprocess
import sys

root = pathlib.Path('/workspace/scratch/android-perf-evidence')
plan = json.loads((root / 'native-regression-plan.json').read_text())
results = []
for item in plan:
    label = item['label']
    print('START ' + label, flush=True)
    if item.get('clear_owned_app_first'):
        command = ['/workspace/.setup/tools/android-sdk/platform-tools/adb', '-s', 'emulator-5554', 'shell', 'pm', 'clear', 'org.inkweft.app.a0']
        cleared = subprocess.run(command, capture_output=True, text=True, timeout=60)
        receipt = dict(command=command, utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), exit_code=cleared.returncode, stdout=cleared.stdout, stderr=cleared.stderr, scope='Disposable cloud emulator and synthetic test application only')
        (root / ('candidate-' + label + '-clear.json')).write_text(json.dumps(receipt, indent=2) + '\n')
        if cleared.returncode != 0 or cleared.stdout.strip() != 'Success':
            print('STOP: owned test application clear failed', flush=True)
            sys.exit(2)
    command = [sys.executable, '/workspace/scratch/run-inkweft-instrumentation.py', 'candidate', label, item['module'], item['selection'], str(item['expected'])]
    completed = subprocess.run(command)
    receipt_path = root / ('candidate-' + label + '.json')
    receipt = json.loads(receipt_path.read_text())
    results.append(dict(label=label, **receipt))
    (root / 'native-regression-results.json').write_text(json.dumps(results, indent=2) + '\n')
    print('FINISH ' + label + ' passed=' + str(receipt['passed']), flush=True)
    if receipt['exit_code'] == 124:
        print('STOP: inspect timed out instrumentation before continuing', flush=True)
        sys.exit(2)
print('COMPLETE', flush=True)
sys.exit(0 if all(x['passed'] for x in results) else 1)
