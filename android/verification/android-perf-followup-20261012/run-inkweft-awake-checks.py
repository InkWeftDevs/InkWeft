import datetime
import json
import pathlib
import subprocess
import sys

phase, plan_path = sys.argv[1:]
root = pathlib.Path('/workspace/scratch/android-perf-evidence')
plan = json.loads(pathlib.Path(plan_path).read_text())
adb = '/workspace/.setup/tools/android-sdk/platform-tools/adb'
results = []
for item in plan:
    label = item['label']
    print('START ' + phase + ' ' + label, flush=True)
    commands = []
    if item.get('clear_owned_app_first'):
        commands += [['pm', 'clear', 'org.inkweft.app.a0'], ['settings', 'put', 'system', 'font_scale', '1.0'], ['wm', 'size', '1920x1200'], ['wm', 'density', '240']]
    commands += [['input', 'keyevent', 'KEYCODE_WAKEUP'], ['wm', 'dismiss-keyguard'], ['dumpsys', 'power']]
    setup = []
    for tail in commands:
        command = [adb, '-s', 'emulator-5554', 'shell', *tail]
        run = subprocess.run(command, capture_output=True, text=True, timeout=30)
        content = run.stdout
        if tail[0] == 'dumpsys':
            content = '\n'.join(line for line in content.splitlines() if any(s in line for s in ['mWakefulness=', 'mIsPowered=', 'mStayOn=', 'mScreenOffTimeoutSetting=']))
        setup.append(dict(command=command, utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), exit_code=run.returncode, stdout=content, stderr=run.stderr))
        (root / f'{phase}-{label}-setup.json').write_text(json.dumps(setup, indent=2) + '\n')
        if run.returncode != 0 or (tail[:2] == ['pm', 'clear'] and run.stdout.strip() != 'Success'):
            raise RuntimeError('Owned emulator setup failed; instrumentation not started')
    if 'mWakefulness=Awake' not in setup[-1]['stdout']:
        raise RuntimeError('Owned emulator is not awake; instrumentation not started')
    command = [sys.executable, '/workspace/scratch/run-inkweft-instrumentation.py', phase, label, item['module'], item['selection'], str(item['expected'])]
    subprocess.run(command)
    receipt = json.loads((root / f'{phase}-{label}.json').read_text())
    results.append(dict(label=label, **receipt))
    (root / f'{phase}-results.json').write_text(json.dumps(results, indent=2) + '\n')
    print('FINISH ' + phase + ' ' + label + ' passed=' + str(receipt['passed']), flush=True)
    if receipt['exit_code'] == 124:
        raise RuntimeError('Instrumentation hit outer timeout; inspect process before further cases')
print('COMPLETE ' + phase, flush=True)
sys.exit(0 if all(x['passed'] for x in results) else 1)
