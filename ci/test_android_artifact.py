import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_android_artifact import NATIVE, inspect_apk, source_identity


class ArtifactTest(unittest.TestCase):
    def test_offline_contents_abis_and_tamper(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / 'app.apk'
            body = b'offline model'
            manifest = [{'asset': 'ocr/model.onnx', 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()}]
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/ocr/model.onnx', body)
                for name in NATIVE:
                    archive.writestr('lib/arm64-v8a/' + name, b'native')
            self.assertEqual(['arm64-v8a'], inspect_apk(apk, manifest, ['arm64-v8a'])['abis'])
            self.assertRaises(ValueError, inspect_apk, apk, manifest, ['x86_64'])
            bad = [{**manifest[0], 'sha256': '0' * 64}]
            self.assertRaises(ValueError, inspect_apk, apk, bad, ['arm64-v8a'])
            identity = {'build_commit': 'a' * 40, 'source_commit': 'a' * 40, 'formal': False, 'target_abis': ['arm64-v8a']}
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('assets/inkweft-build-identity.json', json.dumps(identity))
            expected = {key: identity[key] for key in ['build_commit', 'source_commit', 'formal']}
            self.assertEqual(identity, inspect_apk(apk, manifest, ['arm64-v8a'], expected)['embedded_identity'])
            self.assertRaises(ValueError, inspect_apk, apk, manifest, ['arm64-v8a'], {**expected, 'formal': True})
            self.assertRaises(ValueError, inspect_apk, apk, manifest, ['arm64-v8a'], {**expected, 'source_commit': 'b' * 40})

    def test_formal_refuses_unknown_wrong_commit_and_dirty_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()
            git('init', '--quiet')
            git('config', 'user.name', 'Artifact Test')
            git('config', 'user.email', 'artifact@example.invalid')
            (root / 'source').write_text('verified')
            git('add', 'source');git('commit', '--quiet', '-m', '合成身份夹具')
            head = git('rev-parse', 'HEAD')
            self.assertTrue(source_identity(root, head, head, True)['formal'])
            self.assertRaises(ValueError, source_identity, root, 'local-unknown', head, True)
            self.assertRaises(ValueError, source_identity, root, 'a' * 40, head, True)
            (root / 'source').write_text('changed')
            self.assertRaises(ValueError, source_identity, root, head, head, True)
            self.assertTrue(source_identity(root, 'local-unknown', head, False)['dirty'])
