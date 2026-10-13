#!/usr/bin/env python3
"""One app instrumentation selection on the owned emulator; no builds or data reset."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import time

OUT = pathlib.Path('/workspace/scratch/pr20-closure-evidence')
ADB = '/workspace/.setup/tools/android-sdk/platform-tools/adb'
SERIAL = 'emulator-5554'
RUNNER = 'org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner'


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def parse_junit(raw, code, expected):
    matches = re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M)
    failures = re.findall(r'Tests run: (\d+),\s+Failures: (\d+)', raw)
    passed = (code == 0 and len(matches) == 1 and int(matches[0]) == expected
              and 'FAILURES!!!' not in raw and 'RUNNER_TIMEOUT' not in raw
              and not any(int(count) > 0 for _, count in failures))
    return dict(tests=int(matches[0]) if matches else None,
                executed=int(matches[0]) if matches else (int(failures[-1][0]) if failures else None),
                failures=int(failures[-1][1]) if failures else (0 if passed else None), passed=passed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', help='Unique output prefix; existing evidence is never overwritten')
    parser.add_argument('selection', help='Fully qualified app test class or Class#method')
    parser.add_argument('expected', type=int)
    parser.add_argument('--diagnostics', action='store_true', help='Only adds -e rasterMetadataDiagnostics true')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,100}', args.phase) or args.expected < 1:
        parser.error('phase must be a safe unique name and expected must be positive')
    prefix = OUT / (args.phase + '-targeted')
    paths = [pathlib.Path(str(prefix) + suffix) for suffix in ['-setup.json', '.log', '.json']]
    if any(path.exists() for path in paths):
        parser.error('Refusing to overwrite existing phase evidence')
    OUT.mkdir(exist_ok=True)
    command = [ADB, '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-e', 'class', args.selection]
    if args.diagnostics:
        command += ['-e', 'rasterMetadataDiagnostics', 'true']
    command += [RUNNER]
    receipt = dict(phase=args.phase, selection=args.selection, expected=args.expected,
                   diagnostics=args.diagnostics, command=command, adb_serial=SERIAL,
                   script_sha256=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
                   status='SETUP_PENDING', outer_timeout_seconds=1800, passed=False,
                   environment='API35 x86_64 software Android emulator; no KVM; not physical-device performance')
    setup = []
    with paths[0].open('x') as setup_file, paths[1].open('x') as log, paths[2].open('x') as result_file:
        def save():
            for stream, value in [(setup_file, setup), (result_file, receipt)]:
                stream.seek(0)
                json.dump(value, stream, indent=2)
                stream.write('\n')
                stream.truncate()
                stream.flush()

        save()
        try:
            for tail in [['input', 'keyevent', 'KEYCODE_WAKEUP'], ['wm', 'dismiss-keyguard'], ['dumpsys', 'power']]:
                argv = [ADB, '-s', SERIAL, 'shell', *tail]
                run = subprocess.run(argv, capture_output=True, text=True, timeout=30)
                content = run.stdout
                if tail[0] == 'dumpsys':
                    content = '\n'.join(line for line in content.splitlines() if any(key in line for key in
                                        ['mWakefulness=', 'mIsPowered=', 'mStayOn=', 'mScreenOffTimeoutSetting=']))
                setup.append(dict(command=argv, utc=utc(), exit_code=run.returncode, stdout=content, stderr=run.stderr))
                save()
                if run.returncode != 0:
                    raise RuntimeError('Owned emulator awake setup failed; instrumentation not started')
            if 'mWakefulness=Awake' not in setup[-1]['stdout']:
                raise RuntimeError('Owned emulator is not awake; instrumentation not started')
            receipt.update(status='RUNNING', started_utc=utc())
            save()
            before = time.monotonic()
            try:
                code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=1800).returncode
            except subprocess.TimeoutExpired:
                log.write('\nRUNNER_TIMEOUT\n')
                code = 124
            log.flush()
            raw = paths[1].read_text(errors='replace')
            receipt.update(parse_junit(raw, code, args.expected))
            receipt.update(status='PASS' if receipt['passed'] else 'FAIL', exit_code=code,
                           seconds=time.monotonic() - before, completed_utc=utc(),
                           requires_device_process_review_before_next_run=(code == 124),
                           log_sha256=hashlib.sha256(paths[1].read_bytes()).hexdigest())
        except Exception as error:
            receipt.update(status='ERROR', error=type(error).__name__ + ': ' + str(error), completed_utc=utc())
        save()
    print(json.dumps(receipt))
    return 0 if receipt['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
