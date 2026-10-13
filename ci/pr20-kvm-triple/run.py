"""One authorized, deadline-bounded B/C0/C1 run of the frozen 44-method plan."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
CONTROL = HERE.parents[1]
FROZEN = CONTROL / 'android/verification/pr20-native-readiness-20261013'
ROOT = Path(os.environ['KVM_ONCE'])
OUT = ROOT / 'evidence'
OUT.mkdir(parents=True, exist_ok=True)
SDK = Path(os.environ['ANDROID_HOME'])
START = int(os.environ['KVM_JOB_STARTED'])
CUTOFFS = {name: START + minutes * 60 for name, minutes in
           [('setup', 32), ('correctness', 38), ('common', 52), ('diagnostics', 55)]}
DEADLINE = CUTOFFS['diagnostics']
assert CUTOFFS == {name: int(os.environ[key]) for name, key in
                   [('setup', 'KVM_SETUP_CUTOFF'), ('correctness', 'KVM_CORRECTNESS_CUTOFF'),
                    ('common', 'KVM_COMMON_CUTOFF'), ('diagnostics', 'KVM_WORK_DEADLINE')]}, 'Workflow/controller cutoff mismatch'
PHASE = 'setup'
os.environ['PATH'] = str(SDK / 'platform-tools') + ':' + str(SDK / 'cmdline-tools/latest/bin') + ':' + os.environ['PATH']
ADB = ['adb', '-s', 'emulator-5554']
PACKAGES = ['org.inkweft.app.a0', 'org.inkweft.app.a0.test', 'org.inkweft.data.test']
IDENTITIES = ['B', 'C0', 'C1']
PHASE_NAMES = {'correctness': 'correctness', 'common': 'common-comparison', 'diagnostics': 'candidate-diagnostics'}
SPEC = json.loads((FROZEN / 'source-spec.json').read_text())
PLAN = json.loads((FROZEN / 'method-plan.json').read_text())
INPUT_HASHES = {key: hashlib.sha256((FROZEN / name).read_bytes()).hexdigest() for key, name in
                [('approved_plan_sha256', 'PLAN.md'), ('source_spec_sha256', 'source-spec.json'),
                 ('method_plan_sha256', 'method-plan.json'), ('original_protocol_sha256', 'original-protocol.json')]}
SUMMARY = OUT / 'summary.json'
REPORT = json.loads(SUMMARY.read_text()) if SUMMARY.exists() else {
    'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'],
    'workflow_sha': os.environ['GITHUB_SHA'], 'source_spec': SPEC, 'input_hashes': INPUT_HASHES,
    'job_started_epoch': START, 'phase_cutoff_epochs': CUTOFFS, 'work_deadline_epoch': DEADLINE,
    'status': 'PREPARING', 'actions': {}, 'phases': [], 'install_groups': {},
    'methods': [dict(step, status='NOT_RUN') for step in PLAN['steps']],
    'scope': 'One KVM emulator, three frozen source identities, synthetic debug workloads. '
             'Completion is not a claim that all performance regressions are absent.'}


class BudgetAbort(RuntimeError):
    pass


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def save():
    temporary = SUMMARY.with_suffix('.tmp')
    write_json(temporary, REPORT)
    temporary.replace(SUMMARY)


def seconds(limit=3600):
    left = min(CUTOFFS[PHASE], DEADLINE) - time.time()
    if left <= 0:
        raise BudgetAbort(PHASE + ' absolute cutoff reached')
    return min(limit, left)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            seconds()
            result.update(chunk)
    seconds()
    return result.hexdigest()


def owned(pid):
    path = Path(f'/proc/{pid}')
    fields = (path / 'stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': pid, 'uid': path.stat().st_uid, 'start': fields[19]}


def run(argv, limit=60, check=True, **kwargs):
    """Every subprocess is bounded by its command cap AND the current absolute phase cap."""
    argv = [str(value) for value in argv]
    timeout = seconds(limit)
    data = kwargs.pop('input', None)
    if data is not None:
        kwargs['stdin'] = subprocess.PIPE
    process = subprocess.Popen(argv, start_new_session=True, **kwargs)
    groups = ROOT / 'command-groups.json'
    values = json.loads(groups.read_text()) if groups.exists() else []
    try:
        values.append(owned(process.pid))
    except FileNotFoundError:
        if process.poll() is None:
            raise
    temporary = groups.with_suffix('.tmp')
    write_json(temporary, values)
    temporary.replace(groups)
    try:
        stdout, stderr = process.communicate(input=data, timeout=min(timeout, seconds()))
    except (subprocess.TimeoutExpired, BudgetAbort):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        # The outer cleanup owns any remaining process; never wait beyond a phase cutoff.
        remaining = min(CUTOFFS[PHASE], DEADLINE) - time.time()
        if remaining > 0:
            try:
                process.wait(timeout=min(1, remaining))
            except subprocess.TimeoutExpired:
                pass
        raise BudgetAbort(PHASE + ' command/deadline limit: ' + argv[0])
    result = subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)
    if check:
        result.check_returncode()
    return result


def capture(argv, limit=30):
    return run(argv, limit, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout.strip()


def shell(*args):
    return capture(ADB + ['shell', *args])


def phase(name, argv, limit=600, **kwargs):
    item = {'name': name, 'budget_phase': PHASE, 'status': 'RUNNING', 'started_epoch': time.time()}
    REPORT['phases'].append(item)
    save()
    try:
        with (OUT / (name + '.log')).open('x') as log:
            result = run(argv, limit, check=False, stdout=log, stderr=subprocess.STDOUT, text=True, **kwargs)
        item['exit_code'] = result.returncode
        if result.returncode:
            raise RuntimeError(name + ' failed; see raw stage log; no retry')
        seconds()
        item['status'] = 'PASS'
    except Exception as error:
        item['status'] = 'INCOMPLETE' if isinstance(error, BudgetAbort) else 'FAIL'
        raise
    finally:
        item['seconds'] = time.time() - item['started_epoch']
        save()


def host():
    memory = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        if key in ['MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree']:
            memory[key] = value.strip()
    disk = shutil.disk_usage(ROOT)
    return {'epoch': time.time(), 'uid': os.getuid(), 'cpu_count': os.cpu_count(),
            'kernel': os.uname().release, 'load_1_5_15': list(os.getloadavg()),
            'cpu_model': next((line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines()
                               if line.startswith('model name')), None),
            'memory': memory, 'disk_bytes': {'total': disk.total, 'used': disk.used, 'free': disk.free}}


def environment():
    return {'sdk': shell('getprop', 'ro.build.version.sdk'), 'abi': shell('getprop', 'ro.product.cpu.abi'),
            'fingerprint': shell('getprop', 'ro.build.fingerprint'), 'qemu': shell('getprop', 'ro.kernel.qemu'),
            'hw_timeout_multiplier': shell('getprop', 'ro.hw_timeout_multiplier'),
            'size': shell('wm', 'size'), 'density': shell('wm', 'density'),
            'font_scale': shell('settings', 'get', 'system', 'font_scale'),
            'animations': {key: shell('settings', 'get', 'global', key) for key in
                           ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']}}


def source_manifest(identity):
    root = ROOT / 'sources' / identity
    expected = json.loads((FROZEN / f'{identity}-source-manifest.json').read_text())
    assert expected['canonical_manifest_sha256'] == SPEC['identities'][identity]['canonical_manifest_sha256']
    for name, item in expected['files'].items():
        path = root / name
        assert path.stat().st_size == item['bytes'] and digest(path) == item['sha256'], 'Source changed: ' + name
    return expected['canonical_manifest_sha256']


def official_dependency_check():
    """One official POM health gate; Gradle still resolves all real dependencies itself."""
    url = 'https://maven.ghostscript.com/com/artifex/mupdf/fitz/1.28.5/fitz-1.28.5.pom'
    target = ROOT / 'official-mupdf-check.pom'
    receipt = {'url': url, 'status': 'FAIL', 'started_epoch': time.time(), 'max_attempts': 1,
               'expected_bytes': 662,
               'expected_sha256': 'd66b443446cb2bb992c0de60115ae4988584bd44d16c877d0ba5e2d400b416ff',
               'scope': 'POM availability only; no cache injection or proof of Gradle/AAR/native success.'}
    try:
        with (OUT / 'official-dependency-check.log').open('x') as log:
            result = run(['curl', '--disable', '--proto', '=https', '--connect-timeout', '10',
                          '--max-time', '23', '--max-filesize', '4096', '--fail', '--silent', '--show-error',
                          '--output', target, '--write-out', '%{http_code}', url],
                         25, check=False, stdout=subprocess.PIPE, stderr=log, text=True)
        receipt.update(exit_code=result.returncode, http_code=result.stdout.strip())
        assert result.returncode == 0 and receipt['http_code'] == '200', 'Official POM request failed; stop without retry'
        receipt.update(bytes=target.stat().st_size, sha256=digest(target))
        assert receipt['bytes'] == receipt['expected_bytes'] and receipt['sha256'] == receipt['expected_sha256'], 'Official POM bytes differ from verified successful run; stop'
        receipt['status'] = 'PASS'
    finally:
        receipt['seconds'] = time.time() - receipt['started_epoch']
        write_json(OUT / 'official-dependency-check.json', receipt)


def setup():
    assert REPORT['run_attempt'] == '1', 'No automatic rerun authorized'
    auth = json.loads((HERE / 'authorization.json').read_text())
    assert auth['approved'] is True
    assert auth['phase_cutoff_minutes'] == {name: (end - START) // 60 for name, end in CUTOFFS.items()}
    assert auth['budget_revision_sha256'] == digest(HERE / 'budget-revision.json'), 'Budget amendment hash mismatch'
    assert all(auth.get(key) == value for key, value in INPUT_HASHES.items()), 'Authorization/input mismatch'
    assert len(PLAN['steps']) == 44 and [step['ordinal'] for step in PLAN['steps']] == list(range(1, 45))
    assert [sum(step['phase'] == value for step in PLAN['steps']) for value in PHASE_NAMES.values()] == [17, 24, 3]
    assert len(set(step['install_group'] for step in PLAN['steps'])) == 14
    for step in PLAN['steps']:
        assert step['expected_junit_tests'] == 1 and step['identity'] in IDENTITIES
        assert step['args'][:8] == ADB + ['shell', 'am', 'instrument', '-w', '-r']
        assert step['args'][-2] == step['selection'] and step['args'][-3] == 'class'
    assert not capture(['git', '-C', CONTROL, 'status', '--porcelain']), 'Execution checkout must be clean'
    official_dependency_check()
    refs = [item['git'] for item in SPEC['identities'].values()]
    refs += [patch['from_git'] for item in SPEC['identities'].values() for patch in item['production_overrides']]
    refs += [probe['from_git'] for probe in SPEC['common_probes']]
    refs = list(dict.fromkeys(refs))
    REPORT['fetched_fixed_refs'] = refs
    phase('source-fetch', ['git', '-C', CONTROL, 'fetch', '--no-tags', '--depth=1', 'origin', *refs], 180)
    phase('source-reconstruction', ['python3', FROZEN / 'prepare_sources.py', '--repo', CONTROL,
                                    '--out', ROOT / 'sources'], 180)
    initial_host = host()
    initial_host['java'] = capture(['java', '-version'])
    write_json(OUT / 'host.json', initial_host)
    phase('sdk', ['sdkmanager', 'platforms;android-36', 'build-tools;36.0.0', 'platform-tools', 'emulator',
                  'system-images;android-35;aosp_atd;x86_64'], 900)
    REPORT['status'] = 'SOURCES_FROZEN'


def build(identity):
    root = ROOT / 'sources' / identity
    before = source_manifest(identity)
    env = os.environ.copy()
    env['INKWEFT_HEAD_SHA'] = SPEC['identities'][identity]['git']
    phase('build-' + identity, ['./gradlew', '--no-daemon', '--max-workers=1', '--no-build-cache',
          '-Pkotlin.compiler.execution.strategy=in-process',
          '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8',
          ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':data-local:assembleDebugAndroidTest'],
          1440, cwd=root / 'android', env=env)
    assert source_manifest(identity) == before
    paths = ['app/build/outputs/apk/debug/app-debug.apk',
             'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
             'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
    receipts = []
    for path in paths:
        apk = root / 'android' / path
        item = {'path': path, 'bytes': apk.stat().st_size, 'sha256': digest(apk)}
        item['signature'] = capture([SDK / 'build-tools/36.0.0/apksigner', 'verify', '--print-certs', apk], 60)
        item['signer_sha256'] = re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\S+)$', item['signature'], re.M)
        assert item['signer_sha256'], 'Missing verified signer digest'
        receipts.append(item)
    assert all(item['signer_sha256'] == receipts[0]['signer_sha256'] for item in receipts)
    with zipfile.ZipFile(root / 'android' / paths[0]) as archive:
        embedded = json.loads(archive.read('assets/inkweft-build-identity.json'))
        assert embedded['source_commit'] == env['INKWEFT_HEAD_SHA']
        assert embedded['build_commit'] == os.environ['GITHUB_SHA'] and embedded['formal'] is False
        receipts[0]['build_identity'] = embedded
        native = []
        for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
            names = [name for name in archive.namelist() if name.startswith('lib/' + abi + '/')
                     and 'mupdf' in name and name.endswith('.so')]
            assert names, 'Missing MuPDF ABI ' + abi
            for name in names:
                seconds()
                data = archive.read(name)
                assert data.startswith(b'\x7fELF') and len(data) > 100000
                native.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        receipts[0]['mupdf_native'] = native
    deps = []
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    for path in sorted(cache.rglob('*')):
        seconds()
        if path.is_file():
            deps.append({'path': path.relative_to(cache).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest(path)})
    assert deps, 'No dependency provenance recorded'
    write_json(OUT / ('dependencies-' + identity + '.json'), deps)
    write_json(OUT / ('apk-' + identity + '.json'), {
        'identity': identity, 'base_commit': env['INKWEFT_HEAD_SHA'], 'source_manifest_sha256': before,
        'overlay_identity': 'Embedded source_commit is the Git base; source-spec plus source manifest bind overlays.',
        'apks': receipts, 'jvm_tests': 'NOT_RUN', 'lint': 'NOT_RUN'})
    for prior_identity in IDENTITIES[:IDENTITIES.index(identity)]:
        prior_apks = json.loads((OUT / ('apk-' + prior_identity + '.json')).read_text())['apks']
        assert prior_apks[0]['mupdf_native'] == native, 'MuPDF bytes differ between identities'
        assert prior_apks[0]['signer_sha256'] == receipts[0]['signer_sha256'], 'APK signer changed'
        prior = {value['path']: value for value in json.loads((OUT / ('dependencies-' + prior_identity + '.json')).read_text())}
        now = {value['path']: value for value in deps}
        assert all(now.get(path) == value for path, value in prior.items()), 'Dependency changed or disappeared'


def start_emulator():
    seconds()
    node = os.lstat('/dev/kvm')
    assert stat.S_ISCHR(node.st_mode)
    write_json(OUT / 'kvm-identity.json', [node.st_dev, node.st_ino, node.st_rdev])
    (OUT / 'kvm-before.acl').write_bytes(run(['getfacl', '-pn', '/dev/kvm'], 15, stdout=subprocess.PIPE).stdout)
    if not (os.access('/dev/kvm', os.R_OK) and os.access('/dev/kvm', os.W_OK)):
        (ROOT / 'acl-may-have-changed').touch()
        run(['sudo', '-n', 'setfacl', '--no-mask', '-m', f'u:{os.getuid()}:rw', '/dev/kvm'], 15)
    assert os.access('/dev/kvm', os.R_OK) and os.access('/dev/kvm', os.W_OK), 'Stop without widening KVM ACL mask'
    (OUT / 'kvm-granted.acl').write_bytes(run(['getfacl', '-pn', '/dev/kvm'], 15, stdout=subprocess.PIPE).stdout)
    phase('acceleration', [SDK / 'emulator/emulator', '-accel-check'], 30)
    phase('avd', ['avdmanager', 'create', 'avd', '--name', 'pr20-kvm-triple', '--package',
                  'system-images;android-35;aosp_atd;x86_64', '--device', 'pixel_2'], 120, input='no\n')
    command = [str(SDK / 'emulator/emulator'), '-avd', 'pr20-kvm-triple', '-port', '5554', '-no-window',
               '-no-snapshot', '-noaudio', '-no-boot-anim', '-gpu', 'swiftshader', '-accel', 'on',
               '-cores', '2', '-memory', '2048']
    seconds()
    with (ROOT / 'emulator-private.log').open('x') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    write_json(ROOT / 'emulator.json', owned(process.pid))
    until = min(CUTOFFS['setup'], time.time() + 240)
    while time.time() < until:
        if process.poll() is not None:
            raise RuntimeError('Emulator exited before boot')
        result = run(ADB + ['shell', 'getprop', 'sys.boot_completed'], 10, check=False,
                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        if result.returncode == 0 and result.stdout.strip() == '1':
            break
        time.sleep(min(2, seconds()))
    else:
        raise BudgetAbort('Emulator boot/setup cutoff')
    assert shell('getprop', 'ro.build.version.sdk') == '35' and shell('getprop', 'ro.kernel.qemu') == '1'
    for args in [('wm', 'size', '1920x1200'), ('wm', 'density', '240'), ('settings', 'put', 'system', 'font_scale', '1.0'),
                 ('settings', 'put', 'global', 'window_animation_scale', '1.0'),
                 ('settings', 'put', 'global', 'transition_animation_scale', '1.0'),
                 ('settings', 'delete', 'global', 'animator_duration_scale'), ('svc', 'power', 'stayon', 'true'),
                 ('input', 'keyevent', 'KEYCODE_WAKEUP'), ('wm', 'dismiss-keyguard')]:
        shell(*args)
    initial = environment()
    assert initial['abi'] == 'x86_64' and initial['font_scale'] == '1.0'
    assert initial['size'].splitlines()[-1].endswith('1920x1200') and initial['density'].splitlines()[-1].endswith('240')
    assert initial['animations'] == {'window_animation_scale': '1.0', 'transition_animation_scale': '1.0',
                                     'animator_duration_scale': 'null'}
    write_json(OUT / 'device-before.json', initial)
    log = (ROOT / 'emulator-private.log').read_text(errors='replace')
    selected = [line for line in log.splitlines() if 'Android emulator version ' in line or 'Boot completed in ' in line]
    (OUT / 'emulator-selected.log').write_text('\n'.join(selected) + '\n')
    write_json(OUT / 'emulator-configuration.json', {
        'command_flags': command[1:],
        'emulator_version': next((line for line in selected if 'Android emulator version ' in line), None),
        'emulator_source_properties': (SDK / 'emulator/source.properties').read_text(),
        'image_source_properties': (SDK / 'system-images/android-35/aosp_atd/x86_64/source.properties').read_text()})
    seconds()
    REPORT['setup_seconds'] = time.time() - START
    REPORT['status'] = 'MEASURING'


def raw_result(entry, raw, returncode):
    ok = re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M)
    codes = [int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)', raw, re.M)]
    classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.*)$', raw, re.M)
    methods = re.findall(r'^INSTRUMENTATION_STATUS: test=(.*)$', raw, re.M)
    counts = re.findall(r'^INSTRUMENTATION_STATUS: numtests=(.*)$', raw, re.M)
    cls, method = entry['selection'].split('#')
    # Probe sendStatus(0) records are additional zero codes, not additional JUnit tests.
    valid = (returncode == 0 and ok == ['1'] and codes.count(1) == 1 and 0 in codes
             and all(code in [0, 1] for code in codes)
             and len(classes) == 2 and all(value.strip() == cls for value in classes)
             and len(methods) == 2 and all(value.strip() == method for value in methods)
             and len(counts) == 2 and all(value.strip() == '1' for value in counts)
             and re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', raw, re.M) == ['-1']
             and 'FAILURES!!!' not in raw and 'INSTRUMENTATION_FAILED' not in raw)
    entry.update(junit_status='PASS' if valid else ('INCOMPLETE' if returncode is None else 'FAIL'),
                 adb_exit=returncode, junit_ok_counts=ok, status_codes=codes,
                 reported_classes=classes, reported_methods=methods, reported_test_counts=counts)
    records = []
    for line in raw.splitlines():
        match = re.search(r'((?:KVM|LIBRARY|HISTORY|TILE|INDEX)_[A-Z0-9_]+) (\{.*\}|\[.*\])\s*$', line)
        if match:
            try:
                records.append({'marker': match[1], 'data': json.loads(match[2])})
            except json.JSONDecodeError as error:
                records.append({'marker': match[1], 'raw': match[2], 'parse_error': str(error)})
    return records


def processes_after(entry):
    entry['processes_after'] = {}
    for package in PACKAGES:
        result = run(ADB + ['shell', 'pidof', package], 10, check=False,
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        entry['processes_after'][package] = {'exit': result.returncode, 'stdout': result.stdout.strip(),
                                           'stderr': result.stderr.strip()}
    assert all(value['exit'] == 1 and not value['stdout'] and not value['stderr']
               for value in entry['processes_after'].values()), 'Owned package process cleanup unconfirmed'


def common_fixtures(entry, records):
    if entry['phase'] != 'common-comparison':
        return
    local = '.KvmLocalAppendPairProbe#' in entry['selection']
    entry['fixtures'] = [record['data'] for record in records if record['marker'] == 'KVM_PAIR_FIXTURE']
    assert len(entry['fixtures']) == (3 if local else 1), 'Missing common fixture evidence'
    for other in REPORT['methods']:
        if (other['status'] == 'PASS' and other['phase'] == 'common-comparison' and other['seed'] == entry['seed']
                and other['selection'] == entry['selection'] and other['identity'] != entry['identity']):
            assert entry['fixtures'] == other['fixtures'], 'Three-way fixture hash/rows/writer settings mismatch'
    if not local:
        final = [record['data'] for record in records if record['marker'] == 'LIBRARY_READ_EVIDENCE']
        assert len(final) == 1 and final[0]['writerSqlite'] == entry['fixtures'][0]['writerSqlite'], 'Writer settings changed'


def junit(entry, raw, path):
    failed = entry.get('junit_status') == 'FAIL'
    incomplete = entry['status'] != 'PASS' and not failed
    suite = ET.Element('testsuite', name=entry['selection'], tests='1', failures=str(int(failed)),
                       errors=str(int(incomplete)), time=str(entry['seconds']))
    cls, method = entry['selection'].split('#')
    case = ET.SubElement(suite, 'testcase', classname=cls, name=method, time=str(entry['seconds']))
    if failed:
        ET.SubElement(case, 'failure', message='Original instrumentation/JUnit did not pass').text = raw[-8192:]
    elif incomplete:
        ET.SubElement(case, 'error', type=entry.get('error_type', 'Incomplete'),
                      message=entry.get('error', 'Host validation incomplete')).text = 'See complete raw instrumentation and summary.'
    ET.ElementTree(suite).write(path, encoding='utf-8', xml_declaration=True)


def method(entry):
    assert entry['status'] == 'NOT_RUN', 'No method retry permitted'
    assert all(other['status'] == 'PASS' for other in REPORT['methods'] if other['ordinal'] < entry['ordinal'])
    seconds()
    entry.update(status='RUNNING', started_epoch=time.time())
    save()
    prefix = f"method-{entry['ordinal']:02d}-{entry['identity']}"
    logpath = OUT / (prefix + '-instrumentation.log')
    returncode = None
    records = []
    raw = ''
    try:
        try:
            with logpath.open('x') as log:
                result = run(entry['args'], 3600, check=False, stdout=log, stderr=subprocess.STDOUT, text=True)
            returncode = result.returncode
        finally:
            if logpath.exists():
                raw = logpath.read_text(errors='replace')
                entry['log_sha256'] = hashlib.sha256(logpath.read_bytes()).hexdigest()
                records = raw_result(entry, raw, returncode)
                write_json(OUT / (prefix + '-records.json'), records)
        # Check process exit even when the original JUnit reports failure; never force-stop to manufacture PASS.
        processes_after(entry)
        assert entry['junit_status'] == 'PASS', 'Original method/JUnit failed; stop without retry'
        assert not any('parse_error' in record for record in records), 'Malformed evidence marker; raw record retained'
        common_fixtures(entry, records)
        seconds()
        entry['status'] = 'PASS'
    except Exception as error:
        entry.update(status='INCOMPLETE' if isinstance(error, BudgetAbort) else 'FAIL',
                     error_type=type(error).__name__, error=str(error))
        raise
    finally:
        entry['seconds'] = time.time() - entry['started_epoch']
        junit(entry, raw, OUT / (prefix + '-junit.xml'))
        save()
        print(json.dumps({'ordinal': entry['ordinal'], 'phase': entry['phase'], 'identity': entry['identity'],
                          'selection': entry['selection'], 'status': entry['status']}, ensure_ascii=False), flush=True)


def install(group, identity):
    assert group not in REPORT['install_groups'], 'No install-group retry permitted'
    item = {'identity': identity, 'status': 'RUNNING', 'started_epoch': time.time()}
    REPORT['install_groups'][group] = item
    save()
    initial = json.loads((OUT / 'device-before.json').read_text())
    before = environment()
    write_json(OUT / f'install-{group}-environment-before.json', before)
    write_json(OUT / f'install-{group}-host-before.json', host())
    assert before == initial, 'Device settings changed before install group'
    apks = json.loads((OUT / ('apk-' + identity + '.json')).read_text())['apks']
    with (OUT / f'install-{group}.log').open('x') as log:
        for package in PACKAGES:
            probe = run(ADB + ['shell', 'pm', 'path', package], 30, check=False,
                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            log.write(json.dumps({'package': package, 'presence_exit': probe.returncode,
                                 'stdout': probe.stdout, 'stderr': probe.stderr}) + '\n')
            log.flush()
            assert probe.returncode in [0, 1] and not probe.stderr.strip(), 'Package presence transport failed'
            locations = probe.stdout.strip()
            assert not locations or (probe.returncode == 0 and all(line.startswith('package:') for line in locations.splitlines()))
            if locations:
                result = capture(ADB + ['uninstall', package], 60)
                log.write(package + ': ' + result + '\n')
                log.flush()
                assert result == 'Success', 'Uninstall failed'
        for apk_item in apks:
            apk = ROOT / 'sources' / identity / 'android' / apk_item['path']
            assert digest(apk) == apk_item['sha256'], 'APK changed after signature verification'
            result = capture(ADB + ['install', '-t', apk], 120)
            log.write(apk_item['path'] + ': ' + result + '\n')
            log.flush()
            assert result.endswith('Success'), 'Install failed'
    item['status'] = 'INSTALLED'
    save()


def methods(name):
    entries = [entry for entry in REPORT['methods'] if entry['phase'] == PHASE_NAMES[name]]
    initial = json.loads((OUT / 'device-before.json').read_text())
    for group in dict.fromkeys(entry['install_group'] for entry in entries):
        grouped = [entry for entry in entries if entry['install_group'] == group]
        install(group, grouped[0]['identity'])
        for entry in grouped:
            method(entry)
        after = environment()
        write_json(OUT / f'install-{group}-environment-after.json', after)
        write_json(OUT / f'install-{group}-host-after.json', host())
        assert after == initial, 'Device settings changed after install group'
        REPORT['install_groups'][group].update(status='PASS', seconds=time.time() - REPORT['install_groups'][group]['started_epoch'])
        save()
    if name == 'diagnostics':
        assert len(REPORT['install_groups']) == 14 and all(entry['status'] == 'PASS' for entry in REPORT['methods'])
        write_json(OUT / 'device-after.json', after)
        write_json(OUT / 'host-after.json', host())
        REPORT['status'] = 'EXECUTION_COMPLETE_ANALYSIS_PENDING'


def main():
    global PHASE
    action = sys.argv[1:]
    sequence = [('setup',), ('build', 'B'), ('build', 'C0'), ('build', 'C1'), ('emulator',),
                ('methods', 'correctness'), ('methods', 'common'), ('methods', 'diagnostics')]
    key = tuple(action)
    assert key in sequence, 'Expected setup | build B/C0/C1 | emulator | methods correctness/common/diagnostics'
    name = '-'.join(action)
    PHASE = action[1] if action[0] == 'methods' else 'setup'
    assert REPORT['input_hashes'] == INPUT_HASHES and REPORT['source_spec'] == SPEC
    assert REPORT['job_started_epoch'] == START and REPORT['workflow_sha'] == os.environ['GITHUB_SHA']
    assert REPORT['phase_cutoff_epochs'] == CUTOFFS and REPORT['work_deadline_epoch'] == DEADLINE
    assert REPORT['run_id'] == os.environ['GITHUB_RUN_ID'] and REPORT['run_attempt'] == os.environ['GITHUB_RUN_ATTEMPT'] == '1'
    assert len(REPORT['methods']) == 44
    for entry, step in zip(REPORT['methods'], PLAN['steps']):
        assert all(entry.get(field) == value for field, value in step.items()), 'Frozen method plan/state mismatch'
    assert name not in REPORT['actions'], 'Action already attempted; no retries'
    for previous in sequence[:sequence.index(key)]:
        assert REPORT['actions'].get('-'.join(previous), {}).get('status') == 'PASS', 'Earlier phase incomplete or failed'
    assert REPORT['status'] not in ['BUDGET_ABORTED', 'BLOCKED_OR_FAILED'], 'Execution already stopped'
    item = {'status': 'RUNNING', 'budget_phase': PHASE, 'started_epoch': time.time()}
    REPORT['actions'][name] = item
    save()
    try:
        seconds()
        if action[0] == 'setup':
            setup()
        elif action[0] == 'build':
            build(action[1])
        elif action[0] == 'emulator':
            start_emulator()
        else:
            methods(action[1])
        seconds()
        item['status'] = 'PASS'
    except Exception as error:
        item['status'] = 'INCOMPLETE' if isinstance(error, BudgetAbort) else 'FAIL'
        raise
    finally:
        item['seconds'] = time.time() - item['started_epoch']
        save()


try:
    main()
except Exception as error:
    REPORT['status'] = 'BUDGET_ABORTED' if isinstance(error, BudgetAbort) else 'BLOCKED_OR_FAILED'
    REPORT['error'] = type(error).__name__ + ': ' + str(error)
    if isinstance(error, subprocess.CalledProcessError):
        REPORT['failed_command_output'] = str(error.stdout or '')[-8192:]
    for item in list(REPORT['install_groups'].values()) + REPORT['methods']:
        if item['status'] in ['RUNNING', 'INSTALLED']:
            item.update(status='INCOMPLETE', seconds=time.time() - item['started_epoch'])
    write_json(OUT / 'host-after.json', host())
    save()
    print(json.dumps({'status': REPORT['status'], 'error': REPORT['error']}, ensure_ascii=False), flush=True)
    raise SystemExit(1)
save()
