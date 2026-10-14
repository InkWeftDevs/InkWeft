"""Record an already-finished baseline APK build; never build or access a device."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def junit(root, module, task):
    files = sorted((root / module / 'build/test-results' / task).glob('TEST-*.xml'))
    result = dict(module=module, task=task, reports=[str(p.relative_to(root)) for p in files])
    if not files:
        return dict(result, status='NOT_RUN', suites=None, tests=None, failures=None,
                    errors=None, skipped=None, reason='No JUnit XML report exists; no test execution claimed.')
    counts = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    try:
        for path in files:
            node = ET.parse(path).getroot()
            suites = [node] if node.tag == 'testsuite' else list(node.iter('testsuite'))
            for suite in suites:
                counts['suites'] += 1
                for key in ('tests', 'failures', 'errors', 'skipped'):
                    counts[key] += int(suite.get(key, '0'))
        status = ('REPORTED_FAILURE' if counts['failures'] or counts['errors'] else
                  'REPORTED_PASS' if counts['tests'] else 'NO_TESTS_RECORDED')
        return dict(result, status=status, **counts,
                    scope='Existing XML reports only; invocation/freshness must be tied to the separate build receipt.')
    except (OSError, ValueError, ET.ParseError) as error:
        return dict(result, status='REPORT_ERROR', counts=None, error=str(error))


def lint(root):
    path = root / 'app/build/reports/lint-results-debug.xml'
    if not path.is_file():
        return dict(status='NOT_RUN', severities=None,
                    reason='No lint XML report exists; no lint execution claimed.')
    try:
        severities = {}
        for issue in ET.parse(path).getroot().findall('issue'):
            severity = issue.get('severity', 'Unknown')
            severities[severity] = severities.get(severity, 0) + 1
        failed = severities.get('Error', 0) + severities.get('Fatal', 0)
        return dict(status='REPORTED_FAILURE' if failed else 'REPORTED_PASS',
                    report=str(path.relative_to(root)), sha256=digest(path), severities=severities,
                    scope='Existing XML report only; invocation/freshness must be tied to the separate build receipt.')
    except (OSError, ET.ParseError) as error:
        return dict(status='REPORT_ERROR', severities=None, error=str(error))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', help='A new evidence prefix, e.g. baseline-closure-v2')
    parser.add_argument('android_root', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.phase):
        parser.error('phase must be a simple evidence prefix')
    root = args.android_root.resolve()
    expected_root = Path('/workspace/scratch/inkweft-baseline-closure-a128bb07/android')
    if root != expected_root:
        parser.error('This recorder is restricted to the prepared baseline-closure Android tree')
    out = Path('/workspace/scratch/pr20-closure-evidence')
    outputs = {suffix: out / f'{args.phase}-{suffix}.json'
               for suffix in ('artifacts', 'signatures', 'jvm-and-lint-status', 'recording')}
    existing = [str(path) for path in outputs.values() if path.exists()]
    if existing:
        parser.error('Historical evidence would be overwritten; use a new phase: ' + ', '.join(existing))

    artifacts, signatures, problems = [], [], []
    environment = os.environ.copy()
    environment['JAVA_HOME'] = '/workspace/.setup/tools/jdk-17.0.16+8'
    paths = ['app/build/outputs/apk/debug/app-debug.apk',
             'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
             'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk']
    for relative in paths:
        path = root / relative
        if not path.is_file():
            artifacts.append(dict(path=relative, status='MISSING', bytes=None, sha256=None))
            signatures.append(dict(path=relative, status='NOT_RUN', reason='APK is missing'))
            problems.append(relative + ': APK missing')
            continue
        before = digest(path)
        native = []
        try:
            with zipfile.ZipFile(path) as archive:
                for member in archive.namelist():
                    if member.endswith('/libmupdf_java.so'):
                        data = archive.read(member)
                        elf = data[:4] == b'\x7fELF'
                        native.append(dict(path=member, bytes=len(data),
                                           sha256=hashlib.sha256(data).hexdigest(), elf=elf))
                        if not elf:
                            problems.append(relative + ': MuPDF member is not an ELF: ' + member)
            if relative == paths[0] and not native:
                problems.append(relative + ': native MuPDF library missing')
            artifacts.append(dict(path=relative, status='RECORDED', bytes=path.stat().st_size,
                                  sha256=before, mupdf_native=native))
        except (OSError, zipfile.BadZipFile) as error:
            artifacts.append(dict(path=relative, status='ARCHIVE_ERROR', sha256=before, error=str(error)))
            problems.append(relative + ': APK archive could not be inspected')
        command = ['/workspace/.setup/tools/android-sdk/build-tools/36.0.0/apksigner',
                   'verify', '--verbose', str(path)]
        try:
            run = subprocess.run(command, capture_output=True, text=True, env=environment, timeout=120)
            signatures.append(dict(path=relative, status='PASS' if run.returncode == 0 else 'FAIL',
                                   command=command, exit_code=run.returncode,
                                   stdout=run.stdout, stderr=run.stderr))
            if run.returncode:
                problems.append(relative + ': signature verification failed')
        except (OSError, subprocess.TimeoutExpired) as error:
            signatures.append(dict(path=relative, status='ERROR', command=command, error=str(error)))
            problems.append(relative + ': signature verification could not complete')
        if digest(path) != before:
            problems.append(relative + ': APK changed during recording')

    tests = [junit(root, 'core-domain', 'test'), junit(root, 'data-local', 'testDebugUnitTest')]
    lint_result = lint(root)
    verification = dict(tests=tests, lint=lint_result,
                        native_tests=dict(status='NOT_RUN', reason='This recorder does not run instrumentation.'),
                        build_exit_status='NOT_DETERMINED: consult the separate build receipt/log')
    for item in [*tests, lint_result]:
        if item['status'] in ('REPORTED_FAILURE', 'REPORT_ERROR'):
            problems.append('Existing JVM/lint report: ' + item['status'])
    recording = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), phase=args.phase,
                     android_root=str(root), status='RECORDED' if not problems else 'RECORDING_WITH_ERRORS',
                     problems=problems, apk_count=len(artifacts),
                     missing_jvm_or_lint_is_not_a_zero_test_pass=True,
                     build_started=False, adb_used=False,
                     scope='Artifact and signature recording only; not a build, native-test or performance pass.')
    out.mkdir(parents=True, exist_ok=True)
    values = dict(artifacts=artifacts, signatures=signatures,
                  **{'jvm-and-lint-status': verification, 'recording': recording})
    for suffix, value in values.items():
        # Exclusive creation protects history even if another recorder starts concurrently.
        with outputs[suffix].open('x') as stream:
            stream.write(json.dumps(value, indent=2) + '\n')
    print(json.dumps(dict(recording=recording, tests=tests, lint=lint_result), indent=2))
    return 1 if problems else 0


if __name__ == '__main__':
    raise SystemExit(main())
