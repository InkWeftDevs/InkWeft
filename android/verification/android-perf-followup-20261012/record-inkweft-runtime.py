import datetime
import json
import pathlib
import subprocess
import sys

phase = sys.argv[1]
adb = ['/workspace/.setup/tools/android-sdk/platform-tools/adb', '-s', 'emulator-5554', 'shell']
commands = [['getprop', 'sys.boot_completed'], ['getprop', 'ro.build.fingerprint'],
            ['getprop', 'ro.hw_timeout_multiplier'], ['id'], ['getenforce'],
            ['wm', 'size'], ['wm', 'density'], ['settings', 'get', 'system', 'font_scale'],
            ['settings', 'get', 'global', 'window_animation_scale'],
            ['settings', 'get', 'global', 'transition_animation_scale'],
            ['settings', 'get', 'global', 'animator_duration_scale']]
receipt = dict(phase=phase, started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               device='emulator-5554', hardware_acceleration=False, observations=[])
for command in commands:
    run = subprocess.run(adb + command, text=True, capture_output=True, timeout=60)
    receipt['observations'].append(dict(command=command, exit_code=run.returncode,
                                         stdout=run.stdout.strip(), stderr=run.stderr.strip()))
    assert run.returncode == 0
receipt['completed_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
receipt['host_processes'] = subprocess.check_output(['ps', '-eo', 'pid,stat,comm'], text=True)
receipt['host_processes'] = [line for line in receipt['host_processes'].splitlines()
                            if 'java' in line or 'qemu' in line]
assert all(' Z' in line for line in receipt['host_processes'] if 'java' in line), 'A JVM build is still running'
pathlib.Path(f'/workspace/scratch/android-perf-evidence/{phase}-runtime.json').write_text(json.dumps(receipt, indent=2)+'\n')
print(json.dumps(receipt))
