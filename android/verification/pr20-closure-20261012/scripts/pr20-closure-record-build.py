import hashlib
import json
import os
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

phase, root_arg = sys.argv[1:]
root = pathlib.Path(root_arg)
out = pathlib.Path('/workspace/scratch/pr20-closure-evidence')
paths = ['app/build/outputs/apk/debug/app-debug.apk',
         'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
         'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
artifacts, signatures, tests = [], [], []
environment = os.environ.copy()
environment['JAVA_HOME'] = '/workspace/.setup/tools/jdk-17.0.16+8'
for relative in paths:
    path = root / relative
    native = []
    with zipfile.ZipFile(path) as archive:
        for member in archive.namelist():
            if member.endswith('/libmupdf_java.so'):
                data = archive.read(member)
                assert data[:4] == b'\x7fELF'
                native.append(dict(path=member, size=len(data), sha256=hashlib.sha256(data).hexdigest()))
    artifacts.append(dict(path=relative, bytes=path.stat().st_size,
                          sha256=hashlib.sha256(path.read_bytes()).hexdigest(), mupdf_native=native))
    command = ['/workspace/.setup/tools/android-sdk/build-tools/36.0.0/apksigner', 'verify', '--verbose', str(path)]
    run = subprocess.run(command, capture_output=True, text=True, env=environment)
    signatures.append(dict(path=relative, exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr))
    assert run.returncode == 0
for module, directory in [('core-domain', 'test'), ('data-local', 'testDebugUnitTest')]:
    count = dict(module=module, suites=0, tests=0, failures=0, errors=0, skipped=0)
    for path in (root / module / 'build/test-results' / directory).glob('TEST-*.xml'):
        node = ET.parse(path).getroot()
        count['suites'] += 1
        for key in ['tests', 'failures', 'errors', 'skipped']:
            count[key] += int(node.get(key, '0'))
    tests.append(count)
lint = ET.parse(root / 'app/build/reports/lint-results-debug.xml').getroot()
severity = {}
for issue in lint.findall('issue'):
    key = issue.get('severity')
    severity[key] = severity.get(key, 0) + 1
for suffix, value in [('artifacts', artifacts), ('signatures', signatures),
                      ('jvm-and-lint-status', dict(tests=tests, lint=severity))]:
    (out / f'{phase}-{suffix}.json').write_text(json.dumps(value, indent=2)+'\n')
print(json.dumps(dict(phase=phase, apk_count=len(artifacts), tests=tests, lint=severity)))
