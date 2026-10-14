#!/usr/bin/env python3
"""Record/verify only app and appTest APKs; install only with explicit --install."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

OUT = pathlib.Path('/workspace/scratch/pr20-closure-evidence')
ADB = '/workspace/.setup/tools/android-sdk/platform-tools/adb'
SERIAL = 'emulator-5554'
JAVA = '/workspace/.setup/tools/jdk-17.0.16+8/bin/java'
APKSIGNER = '/workspace/.setup/tools/android-sdk/build-tools/36.0.0/lib/apksigner.jar'
ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86': (1, 3), 'x86_64': (2, 62)}


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def native_entries(path, require_four):
    entries = []
    with zipfile.ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.endswith('/libmupdf_java.so')]
        expected = {'lib/' + abi + '/libmupdf_java.so' for abi in ABIS}
        if require_four and (set(names) != expected or len(names) != 4):
            raise RuntimeError('App APK does not contain exactly four expected MuPDF ABI entries')
        for name in names:
            data = archive.read(name)
            abi = name.split('/')[1]
            elf = (len(data) >= 20 and data[:4] == b'\x7fELF' and data[5] == 1
                   and abi in ABIS and (data[4], int.from_bytes(data[18:20], 'little')) == ABIS[abi])
            if not elf:
                raise RuntimeError('Invalid ELF/ABI entry: ' + name)
            entries.append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), elf=True))
    return entries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', help='Unique output prefix; existing evidence is never overwritten')
    parser.add_argument('app_apk', type=pathlib.Path)
    parser.add_argument('app_test_apk', type=pathlib.Path)
    parser.add_argument('--install', action='store_true', help='Install the two verified APKs on emulator-5554')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,100}', args.phase):
        parser.error('phase must be a safe unique name')
    paths = [args.app_apk.resolve(), args.app_test_apk.resolve()]
    if paths[0] == paths[1] or not all(path.is_file() for path in paths):
        parser.error('Two distinct existing APK files are required')
    output = OUT / (args.phase + '-targeted-apks.json')
    if output.exists():
        parser.error('Refusing to overwrite existing phase evidence')
    OUT.mkdir(exist_ok=True)
    receipt = dict(phase=args.phase, status='RUNNING', started_utc=utc(), adb_serial=SERIAL,
                   install_requested=args.install, artifacts=[], installation=[],
                   script_sha256=digest(pathlib.Path(__file__)),
                   source_binding='This receipt binds APK bytes only. Use a separate frozen source/build manifest for source identity.',
                   jvm=dict(status='NOT_RUN', reason='No JVM task or prior XML report is read.'),
                   lint=dict(status='NOT_RUN', reason='No lint task or prior XML report is read.'),
                   dataTest=dict(status='NOT_RUN', reason='data-local test APK is not recorded, installed or executed by this tool.'),
                   instrumentation=dict(status='NOT_RUN', reason='Use the separate targeted instrumentation tool.'))
    with output.open('x') as stream:
        def save():
            stream.seek(0)
            json.dump(receipt, stream, indent=2)
            stream.write('\n')
            stream.truncate()
            stream.flush()

        save()
        try:
            for role, path in zip(['app', 'appTest'], paths):
                entry = dict(role=role, path=str(path), bytes=path.stat().st_size, sha256=digest(path))
                receipt['artifacts'].append(entry)
                save()
                entry['mupdf_native'] = native_entries(path, role == 'app')
                command = [JAVA, '-jar', APKSIGNER, 'verify', '--verbose', str(path)]
                run = subprocess.run(command, capture_output=True, text=True, timeout=120)
                entry['signature'] = dict(command=command, exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr)
                if run.returncode != 0 or 'Verifies' not in run.stdout:
                    raise RuntimeError('APK signature verification failed: ' + role)
                entry['unchanged_after_verification'] = digest(path) == entry['sha256']
                if not entry['unchanged_after_verification']:
                    raise RuntimeError('APK changed during verification: ' + role)
                save()
            if args.install:
                for entry in receipt['artifacts']:
                    path = pathlib.Path(entry['path'])
                    if digest(path) != entry['sha256']:
                        raise RuntimeError('APK changed before installation: ' + entry['role'])
                    command = [ADB, '-s', SERIAL, 'install', '-r', '-t', str(path)]
                    install = dict(role=entry['role'], sha256=entry['sha256'], command=command, started_utc=utc())
                    receipt['installation'].append(install)
                    save()
                    run = subprocess.run(command, capture_output=True, text=True, timeout=300)
                    install.update(exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr,
                                   completed_utc=utc(), unchanged_after_install=digest(path) == entry['sha256'])
                    save()
                    if run.returncode != 0 or 'Success' not in run.stdout or not install['unchanged_after_install']:
                        raise RuntimeError('APK installation failed or APK changed: ' + entry['role'])
            receipt['status'] = 'VERIFIED_AND_INSTALLED' if args.install else 'VERIFIED_ONLY'
        except Exception as error:
            receipt.update(status='FAILED', error=type(error).__name__ + ': ' + str(error))
        receipt['completed_utc'] = utc()
        save()
    print(json.dumps(receipt))
    return 0 if receipt['status'] in ['VERIFIED_ONLY', 'VERIFIED_AND_INSTALLED'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
