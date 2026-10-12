"""Verify APK contents and emit a receipt; formal delivery also requires committed source."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = {'libandroidx.graphics.path.so', 'libink.so', 'libmupdf_java.so',
          'libonnxruntime.so', 'libonnxruntime4j_jni.so', 'libsqlite3x.so'}
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}


def sha_file(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_identity(root, build_commit, source_commit, formal):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
    head = git('rev-parse', 'HEAD')
    dirty = bool(git('status', '--porcelain', '--untracked-files=normal'))
    if formal and (dirty or any(not re.fullmatch('[0-9a-f]{40}', c) or c != head for c in [build_commit, source_commit])):
        raise ValueError('Formal artifact requires clean source and both commits equal to checked-out HEAD')
    return {'head': head, 'dirty': dirty, 'build_commit': build_commit,
            'source_commit': source_commit, 'formal': formal}


def inspect_apk(apk, manifest, expected_abis, expected_identity=None):
    if not expected_abis or not set(expected_abis) <= ABIS:
        raise ValueError('Explicit valid target ABIs required')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entry')
        actual = {n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')}
        if actual != set(expected_abis):
            raise ValueError(f'ABI mismatch: {sorted(actual)}')
        identity = None
        if expected_identity is not None:
            identity = json.loads(archive.read('assets/inkweft-build-identity.json'))
            if any(identity.get(key) != value for key, value in expected_identity.items()) or set(identity.get('target_abis', [])) != actual:
                raise ValueError('Embedded build identity mismatch')
        for abi in actual:
            native = {n.split('/')[-1] for n in names if n.startswith(f'lib/{abi}/') and n.endswith('.so')}
            if native != NATIVE:
                raise ValueError(f'Offline native dependency mismatch for {abi}: {sorted(native)}')
        assets = []
        for asset in manifest:
            entry = 'assets/' + asset['asset']
            info = archive.getinfo(entry)
            with archive.open(entry) as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            if info.file_size != asset['bytes'] or digest != asset['sha256']:
                raise ValueError('Offline asset changed: ' + asset['asset'])
            assets.append({'asset': asset['asset'], 'bytes': info.file_size, 'compressed_bytes': info.compress_size, 'sha256': digest})
        if any('TABLET-AUDIT-20260927' in n for n in names):
            raise ValueError('Local audit must not be packaged')
        return {'abis': sorted(actual), 'assets': assets, 'embedded_identity': identity,
                'native_compressed_bytes': sum(i.compress_size for i in archive.infolist() if i.filename.startswith('lib/'))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--abis', required=True)
    parser.add_argument('--build-commit', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--formal', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    receipt = source_identity(ROOT, args.build_commit, args.source_commit, args.formal)
    manifest = json.loads((ROOT / 'android/app/src/main/assets/asset-manifest.json').read_text())
    expected = {key: receipt[key] for key in ['build_commit', 'source_commit', 'formal']}
    receipt.update(inspect_apk(args.apk, manifest, args.abis.split(','), expected))
    receipt.update(apk=str(args.apk.resolve()), apk_bytes=args.apk.stat().st_size, apk_sha256=sha_file(args.apk))
    if args.output.exists():
        raise FileExistsError('Preserve previous artifact receipts')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2, ensure_ascii=False), encoding='utf-8')


if __name__ == '__main__':
    main()
