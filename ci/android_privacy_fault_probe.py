"""Run explicit privacy/temporary-space probes on a verified disposable emulator.

Uses the insertion test package without clearing it. The only filled filesystem is
a newly mounted, private 8 MiB tmpfs inside that package's cache directory.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import time
import uuid

from device_fault_probe import require_disposable

PACKAGE = 'org.inkweft.app.a0.insertion'
PACKAGES = (PACKAGE, 'org.inkweft.app.a0.workspace')


def mount_path(data, run, package=PACKAGE):
    if package not in PACKAGES or data not in (f'/data/user/0/{package}', f'/data/data/{package}'):
        raise ValueError('Unexpected private application directory')
    if str(uuid.UUID(run)) != run:
        raise ValueError('Expected canonical run UUID')
    return f'{data}/cache/v47-space-{run}'


def validate_mount(mounts, path):
    rows = [line.split() for line in mounts.splitlines()]
    selected = [row for row in rows if len(row) >= 4 and row[1] == path]
    if len(selected) != 1 or selected[0][2] != 'tmpfs' or 'rw' not in selected[0][3].split(','):
        raise ValueError('The exact private writable tmpfs was not mounted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb'); parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True); parser.add_argument('--test-apk', required=True)
    parser.add_argument('--package', choices=PACKAGES, default=PACKAGE)
    parser.add_argument('--output', required=True)
    parser.add_argument('--probe', choices=('all', 'privacy', 'space'), default='all')
    parser.add_argument('--avd-name', choices=('InkWeft-Test', 'InkWeft-TrustedTLS'), default='InkWeft-Test'); parser.add_argument('--mumu-manager'); parser.add_argument('--mumu-index', default='5')
    parser.add_argument('--root-mode', choices=('su', 'adbd'), default='su', help='adbd requires adb root to be enabled already')
    args = parser.parse_args(); package = args.package
    adb = [args.adb, '-s', args.serial]
    require_disposable(adb, args.serial, args.mumu_manager, args.mumu_index, args.avd_name)

    def command(*words, timeout=30):
        return subprocess.check_output(adb + list(words), text=True, encoding='utf-8', timeout=timeout).strip()

    identities = {}
    for name, local in ((package, args.apk), (package + '.test', args.test_apk)):
        digest = hashlib.sha256(Path(local).read_bytes()).hexdigest()
        installed = command('shell', 'pm', 'path', name).splitlines()
        assert len(installed) == 1 and installed[0].startswith('package:'), 'Expected one installed APK'
        assert command('shell', 'sha256sum', installed[0].removeprefix('package:')).split()[0] == digest
        identities[name] = digest
    output = Path(args.output); output.mkdir(parents=True, exist_ok=False)
    result = {'format': 'inkweft.android-fault-privacy.v1', 'apk_sha256': identities, 'checks': [],
              'package_cleared': False, 'raw_private_payloads_exported': False}

    def invoke(name, flags, report_name, while_running=None):
        # Remove only this probe's previous sanitized report, preventing stale success.
        report_path = f'/sdcard/Android/data/{package}/files/{report_name}.json'
        command('shell', 'rm', '-f', report_path)
        words = ['shell', 'am', 'instrument', '-w', '-e', 'class', 'org.inkweft.app.' + name,
                 '-e', 'deviceGuard', 'verified-disposable-emulator']
        for key, value in flags.items():
            words += ['-e', key, value]
        log_path = output / (report_name + '.log')
        with log_path.open('wb') as log:
            call = subprocess.Popen(adb + words + [package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                                    stdout=log, stderr=subprocess.STDOUT)
            try:
                if while_running:
                    while_running(call)
            finally:
                call.wait(timeout=180)
        # Probe assertions suppress raw trees/values; scan results contain only outlet and hit type.
        passed = call.returncode == 0 and re.search(rb'OK \(1 test\)', log_path.read_bytes()) is not None
        data = command('shell', 'cat', report_path)
        report = json.loads(data)
        (output / (report_name + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        result['checks'].append({'probe': name, 'passed': passed and report.get('status') == 'PASS', 'report': report_name + '.json'})
        assert result['checks'][-1]['passed'], f'{name} failed; see its sanitized evidence'

    def root(*words):
        script = shlex.join(words)
        if args.root_mode == 'su':
            return command('shell', 'su', '0', 'sh', '-c', shlex.quote(script))
        return command('shell', 'sh', '-c', shlex.quote(script))

    try:
        if args.probe in ('privacy', 'all'):
            invoke('PrivacyExitProbe', {'privacyProbe': 'dedicated-emulator'}, 'v47-privacy-exits')
        if args.probe in ('space', 'all'):
            assert root('id', '-u') == '0', 'Root is required only for the isolated tmpfs mount'
            run = str(uuid.uuid4())
            data = command('shell', 'run-as', package, 'pwd')
            path = mount_path(data, run, package)
            command('shell', 'run-as', package, 'mkdir', path)
            def mounted_probe(call):
                def read_marker(suffix):
                    marker = path + '.' + suffix
                    return command('shell', 'run-as', package, 'sh', '-c', shlex.quote(
                        f'if test -f {shlex.quote(marker)}; then cat {shlex.quote(marker)}; fi'))

                def wait_marker(suffix):
                    deadline = time.monotonic() + 90
                    while time.monotonic() < deadline:
                        value = read_marker(suffix)
                        if value:
                            return value
                        assert call.poll() is None, 'Probe exited before namespace handshake completed'
                        time.sleep(.1)
                    raise TimeoutError('Probe namespace handshake timed out: ' + suffix)

                def signal(suffix, value):
                    marker = path + '.' + suffix
                    command('shell', 'run-as', package, 'sh', '-c', shlex.quote(
                        f'printf %s {shlex.quote(value)} > {shlex.quote(marker + ".tmp")} && mv {shlex.quote(marker + ".tmp")} {shlex.quote(marker)}'))

                mounted = False
                try:
                    hello = json.loads(wait_marker('hello'))
                    pid = hello['pid']
                    app_path = hello['path']
                    assert type(pid) is int and pid > 1 and hello['run'] == run
                    assert app_path == mount_path(str(PurePosixPath(app_path).parent.parent), run, package)
                    assert root('cat', f'/proc/{pid}/cmdline').rstrip('\0') == package
                    canonical = root('readlink', '-f', path)
                    assert canonical == mount_path(str(PurePosixPath(canonical).parent.parent), run, package)
                    owner = root('stat', '-c', '%u:%g', path)
                    assert re.fullmatch(r'[1-9][0-9]*:[1-9][0-9]*', owner), 'Expected application ownership'
                    uid, gid = owner.split(':')
                    assert hello['uid'] == int(uid)
                    proc_uid = re.search(r'^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)', root('cat', f'/proc/{pid}/status'), re.M)
                    assert proc_uid and set(proc_uid.groups()) == {uid}, 'Handshake process does not own the private cache'
                    namespace = root('readlink', f'/proc/{pid}/ns/mnt')

                    def inside(*words):
                        assert root('readlink', f'/proc/{pid}/ns/mnt') == namespace, 'Probe namespace changed'
                        return root('nsenter', '-t', str(pid), '-m', '--', *words)

                    assert inside('readlink', '-f', app_path) == app_path
                    assert inside('stat', '-c', '%d:%i:%u:%g', app_path) == root('stat', '-c', '%d:%i:%u:%g', path), 'Application namespace points at a different private directory'
                    context = inside('ls', '-Zd', app_path).split()[0]
                    assert re.fullmatch(r'[A-Za-z0-9_:,.-]+|\?', context), 'Unexpected SELinux label'
                    inside('mount', '-t', 'tmpfs', '-o', f'size=8m,mode=0700,uid={uid},gid={gid}', 'inkweft-v47-' + run, app_path)
                    mounted = True
                    if context != '?':
                        inside('chcon', context, app_path)
                    validate_mount(inside('cat', '/proc/mounts'), app_path)
                    signal('ready', run)
                    assert wait_marker('done') == run
                finally:
                    if mounted:
                        inside('umount', app_path)
                        assert all(len(row.split()) < 2 or row.split()[1] != app_path for row in inside('cat', '/proc/mounts').splitlines())
                    command('shell', 'run-as', package, 'rmdir', path)
                    if mounted:
                        result['space_mount_cleanup'] = 'unmounted-and-directory-removed'
                    signal('ready', 'aborted')
                    signal('release', run)
                result['space_namespace'] = {'pid': pid, 'uid': int(uid), 'mount_namespace': namespace,
                                             'capacity_limit_bytes': 8 * 1024 * 1024}

            invoke('AndroidTemporarySpaceProbe', {'spaceProbe': 'dedicated-emulator', 'spaceRoot': path},
                   'v47-android-space', mounted_probe)
        result['status'] = 'PASS'
    except BaseException:
        result['status'] = 'FAIL'
        raise
    finally:
        (output / 'summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
