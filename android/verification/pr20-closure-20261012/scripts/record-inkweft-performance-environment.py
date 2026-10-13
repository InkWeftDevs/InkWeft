#!/usr/bin/env python3
"""Record the existing owned emulator before a phase; never change its settings."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys

SDK = Path('/workspace/.setup/tools/android-sdk')
ADB = SDK / 'platform-tools/adb'
SERIAL = 'emulator-5554'
AVD = 'inkweft-perf-api35'
SCRATCH = Path('/workspace/scratch').resolve()
SAFE_FLAGS = ('-avd', '-port', '-ports', '-accel', '-gpu', '-cores', '-memory')
PROPERTIES = ('ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.product.cpu.abilist',
              'ro.build.fingerprint', 'ro.hw_timeout_multiplier', 'ro.kernel.qemu',
              'sys.boot_completed')
ANIMATIONS = ('window_animation_scale', 'transition_animation_scale',
              'animator_duration_scale')


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def owned_emulator():
    matches = []
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if not (proc / 'comm').read_text().strip().startswith('qemu-system'):
                continue
            # Only inspect command lines of emulator processes; never record full argv.
            args = [x.decode(errors='replace') for x in (proc / 'cmdline').read_bytes().split(b'\0') if x]
            if not args or not Path(args[0]).is_absolute():
                continue
            binary = Path(args[0]).resolve()
            if not binary.is_relative_to(SDK / 'emulator'):
                continue
            flags = {key: [args[i + 1] for i, item in enumerate(args[:-1]) if item == key]
                     for key in SAFE_FLAGS}
            if flags['-avd'] != [AVD] or flags['-port'] != ['5554']:
                continue
            stat_fields = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
            row = dict(pid=int(proc.name), uid=proc.stat().st_uid, avd=AVD,
                       serial=SERIAL, argv0=str(binary), start_time_ticks=int(stat_fields[19]),
                       process_state=stat_fields[0], launch_flags={k: v for k, v in flags.items() if v})
            try:
                row['exe_link'] = str((proc / 'exe').resolve(strict=True))
                row['exe_link_status'] = 'READ'
            except OSError as error:
                row['exe_link'] = None
                row['exe_link_status'] = type(error).__name__
            matches.append(row)
        except (OSError, ValueError, IndexError):
            continue
    if len(matches) != 1:
        raise RuntimeError(f'Expected one matching local owned emulator, found {len(matches)}')
    return matches[0]


def host_snapshot():
    result = {'kernel': os.uname().release, 'architecture': os.uname().machine,
              'logical_cpu_count': os.cpu_count(), 'affinity_cpus': sorted(os.sched_getaffinity(0)),
              'kvm': {'path': '/dev/kvm', 'exists': Path('/dev/kvm').exists()}, 'cgroup': {}}
    # These are the current cloud container cgroup-v2 limits, not host-wide RAM/CPU.
    for name in ('cpu.max', 'cpuset.cpus.effective', 'cpu.stat', 'memory.max',
                 'memory.high', 'memory.current', 'memory.events'):
        try:
            result['cgroup'][name] = (Path('/sys/fs/cgroup') / name).read_text().strip()
        except OSError as error:
            result['cgroup'][name] = {'error': type(error).__name__}
    for line in Path('/proc/cpuinfo').read_text().splitlines():
        if line.startswith('model name'):
            result['cpu_model'] = line.split(':', 1)[1].strip()
            break
    return result


def dimensions(raw):
    return {kind.lower(): value.strip() for kind, value in
            re.findall(r'^(Physical|Override) (?:size|density):\s*(.+)$', raw, re.M)}


def comparison_values(record):
    emulator = record['emulator_before']
    host = record['host_before']
    device = record['device']
    return {'serial': SERIAL, 'pid': emulator['pid'], 'start_time_ticks': emulator['start_time_ticks'],
            'avd': emulator['avd'], 'launch_flags': emulator['launch_flags'],
            'properties': device['properties'], 'display': device['display'],
            'font_scale': device['font_scale'], 'animations': device['animations'],
            'shell_uid': device['shell_uid'], 'kvm_exists': host['kvm']['exists'],
            'host_architecture': host['architecture'], 'host_cpu_model': host.get('cpu_model'),
            'host_affinity_cpus': host['affinity_cpus'],
            'cgroup_limits': {key: host['cgroup'][key] for key in
                              ('cpu.max', 'cpuset.cpus.effective', 'memory.max', 'memory.high')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', help='Label, e.g. baseline-final-library')
    parser.add_argument('output', type=Path, help='New .json path under /workspace/scratch')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,95}', args.phase):
        parser.error('Invalid phase label')
    output = args.output.resolve()
    if not output.is_relative_to(SCRATCH) or output.suffix != '.json' or not output.parent.is_dir():
        parser.error('Output must be a .json file in an existing scratch directory')
    if args.output.exists() or args.output.is_symlink() or output.exists():
        parser.error('Refusing to overwrite an existing output; no ADB commands were run')
    record = {'schema': 1, 'phase': args.phase, 'started_utc': utc(), 'serial': SERIAL,
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'status': 'INCOMPLETE', 'commands': [], 'errors': [], 'device': {}}

    def adb(*parts):
        # Pin the existing local server and owned emulator; never target USB/network devices.
        command = [str(ADB), '-H', '127.0.0.1', '-P', '5037', '-s', SERIAL, *parts]
        item = {'argv': command}
        record['commands'].append(item)
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
            item.update(returncode=result.returncode, stdout=result.stdout.strip()[:16384],
                        stderr=result.stderr.strip()[:4096])
            if result.returncode != 0:
                raise RuntimeError(f'ADB read command failed: {parts[0]} (exit {result.returncode})')
            return result.stdout.strip()
        except subprocess.TimeoutExpired:
            item['error'] = 'TimeoutExpired'
            raise RuntimeError('ADB read command timed out') from None

    try:
        record['emulator_before'] = owned_emulator()
        record['host_before'] = host_snapshot()
        # Refuse a missing server instead of requesting that adb start a new daemon.
        with socket.create_connection(('127.0.0.1', 5037), timeout=2):
            pass
        if adb('get-state') != 'device':
            raise RuntimeError('Owned emulator is not in device state')
        device = record['device']
        device['properties'] = {key: adb('shell', 'getprop', key) for key in PROPERTIES}
        device['display'] = {'size': dimensions(adb('shell', 'wm', 'size')),
                             'density': dimensions(adb('shell', 'wm', 'density'))}
        device['font_scale'] = adb('shell', 'settings', 'get', 'system', 'font_scale')
        device['animations'] = {key: adb('shell', 'settings', 'get', 'global', key) for key in ANIMATIONS}
        device['shell_uid'] = adb('shell', 'id', '-u')
        record['emulator_after'] = owned_emulator()
        record['host_after'] = host_snapshot()
        for key in ('pid', 'start_time_ticks', 'avd', 'launch_flags'):
            if record['emulator_before'][key] != record['emulator_after'][key]:
                raise RuntimeError('Owned emulator identity changed during recording')
        if device['properties']['sys.boot_completed'] != '1':
            raise RuntimeError('Boot is not complete')
        if any(not value for value in device['properties'].values()):
            raise RuntimeError('A requested device property is missing')
        if not all(value.get('physical') for value in device['display'].values()):
            raise RuntimeError('Physical display size/density could not be parsed')
        if device['shell_uid'] != '2000':
            raise RuntimeError('ADB shell is not the expected ordinary shell user')
        if any(isinstance(value, dict) for value in record['host_before']['cgroup'].values()):
            raise RuntimeError('A requested cgroup observation is unavailable')
        record['comparison_values'] = comparison_values(record)
        record['comparison_sha256'] = digest(record['comparison_values'])
        record['status'] = 'RECORDED'
    except (OSError, RuntimeError, ValueError) as error:
        record['errors'].append({'type': type(error).__name__, 'message': str(error)})
        record['status'] = 'INCOMPLETE'
    record['finished_utc'] = utc()
    record['limitations'] = [
        'Run only before a phase with instrumentation/builds stopped. This script itself issues read-only ADB commands.',
        'The comparison hash includes settings and emulator PID/start time; dynamic CPU/memory counters and timestamps are excluded.',
        'Equal comparison hashes do not prove equal scheduler load, cache state, DB bytes, or settings throughout a later run.',
        'Literal null animation/font settings are recorded as null strings; no default effective value is inferred.',
        'Software TCG/SwiftShader emulator observations are not physical-device latency.',
        'Only selected process flags, selected properties/settings and cgroup files are read; no environment dump, credentials or notes are collected.',
        'A denied /proc/PID/exe link is recorded honestly; known SDK argv0, exact AVD/port, PID and start time provide the available process association.',
    ]
    # Exclusive creation also closes the race with another recorder choosing the same output.
    with output.open('x') as stream:
        json.dump(record, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'phase': args.phase, 'output': str(output), 'status': record['status'],
                      'comparison_sha256': record.get('comparison_sha256')}))
    return 0 if record['status'] == 'RECORDED' else 2


if __name__ == '__main__':
    sys.exit(main())
