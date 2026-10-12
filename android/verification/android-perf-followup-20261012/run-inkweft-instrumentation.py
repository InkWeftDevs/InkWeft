import datetime
import json
import pathlib
import re
import subprocess
import sys
import time

phase, label, module, selection, expected = sys.argv[1:]
out = pathlib.Path('/workspace/scratch/android-perf-evidence')
command = ['/workspace/.setup/tools/android-sdk/platform-tools/adb', '-s', 'emulator-5554',
           'shell', 'am', 'instrument', '-w', '-e', 'class', selection]
if phase == 'baseline':
    command += ['-e', 'historyScanBaseline', 'true']
runner = 'org.inkweft.data.test' if module == 'data' else 'org.inkweft.app.a0.test'
command += [runner + '/androidx.test.runner.AndroidJUnitRunner']
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
before = time.monotonic()
log = out / f'{phase}-{label}.log'
with log.open('w') as stream:
    try:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=1800)
        code = result.returncode
    except subprocess.TimeoutExpired:
        stream.write('\nRUNNER_TIMEOUT\n')
        code = 124
raw = log.read_text(errors='replace')
matches = re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M)
failure_counts = re.findall(r'Tests run: (\d+),\s+Failures: (\d+)', raw)
passed = code == 0 and len(matches) == 1 and int(matches[0]) == int(expected) and 'FAILURES!!!' not in raw and 'RUNNER_TIMEOUT' not in raw
receipt = dict(phase=phase, selection=selection, command=command, started_utc=started,
               seconds=time.monotonic()-before, exit_code=code, expected=int(expected),
               tests=int(matches[0]) if matches else None,
               executed=int(matches[0]) if matches else (int(failure_counts[-1][0]) if failure_counts else None),
               failures=int(failure_counts[-1][1]) if failure_counts else (0 if passed else None), passed=passed,
               environment='API35 x86_64 software Android emulator; no KVM; not physical-device performance')
(out / f'{phase}-{label}.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps(receipt))
sys.exit(0 if passed else 1)
