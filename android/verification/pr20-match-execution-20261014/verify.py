#!/usr/bin/env python3
"""Recheck archived bytes and replay the frozen log extractor offline.

This reproduces the original classifier output; it does not choose a new rule,
run Android, inspect APK bytes, or promote screening to final acceptance.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parent
FROZEN = ROOT.parent / 'pr20-match-candidate-20261014'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if not __debug__:
        raise RuntimeError('Do not use Python optimized mode')
    manifest = {}
    for line in (ROOT / 'SHA256SUMS').read_text().splitlines():
        digest, name = line.split('  ', 1)
        path = ROOT / name
        assert path.resolve().is_relative_to(ROOT), name
        assert name not in manifest and sha(path) == digest, name
        manifest[name] = digest
    actual = {str(p.relative_to(ROOT)) for p in ROOT.rglob('*')
              if p.is_file() and p.name != 'SHA256SUMS'}
    assert actual == set(manifest), (actual - set(manifest), set(manifest) - actual)
    binding = json.loads((ROOT / 'binding.json').read_bytes())
    with tempfile.TemporaryDirectory(prefix='inkweft-match-replay-') as temp:
        out = Path(temp) / 'extracted'
        args = [sys.executable, '-B', str(FROZEN / 'log-analysis/analyze_logs.py')]
        for key, value in {
            'job-log': ROOT / 'official/job-decoded.log',
            'frozen': FROZEN, 'control-root': ROOT / 'control',
            'binding': ROOT / 'binding.json',
            'run-metadata': ROOT / 'official/run-terminal.json',
            'jobs-metadata': ROOT / 'official/jobs-terminal.json',
            'out': out, 'run-id': '38520196523', 'job-id': '115707901766',
            'workflow-sha': '115734b0ed9aab07a1100087ce76978c0d829e0f',
            'source-commit': 'cb7e14218ac83aa8cf6b9d7906051a10da1a6547',
            'readiness-git': 'd70fcb23afecb6f80e8fc716a5145ecf851dd68e',
        }.items():
            args.extend(['--' + key, str(value)])
        subprocess.run(args, check=True)
        old = ROOT / 'extracted'
        files = {p.relative_to(old) for p in old.rglob('*') if p.is_file()}
        assert files == {p.relative_to(out) for p in out.rglob('*') if p.is_file()}
        for name in files:
            if str(name) != 'analysis.json':
                assert (old / name).read_bytes() == (out / name).read_bytes(), name
                continue
            a, b = (json.loads((p / name).read_bytes()) for p in (old, out))
            # Only input path spelling changes when moved out of scratch.
            af, bf = a.pop('files'), b.pop('files')
            assert sorted(af.values(), key=lambda x: x['sha256']) == sorted(
                bf.values(), key=lambda x: x['sha256'])
            assert a == b, name
            assert a['statistics_sha256'] == binding['statistics_sha256']
            assert a['analysis_status'] == 'COMPLETE_SCOPED_MATCH_LOG_EVIDENCE'
            assert a['issues'] == []
    print(json.dumps({'status': 'ARCHIVE_AND_FROZEN_REPLAY_VERIFIED',
                      'archived_files': len(manifest), 'extracted_files': len(files),
                      'native_execution': False, 'final_acceptance': False}))


if __name__ == '__main__':
    main()
