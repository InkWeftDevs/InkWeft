"""Verify the frozen changed source and the reported host evidence. No app/native target execution."""
import hashlib
import json
from pathlib import Path

here = Path(__file__).resolve().parent
repo = here.parents[2]
freeze = json.loads((here / 'source-freeze.json').read_text())
for entry in freeze['files']:
    path = repo / entry['path']
    data = path.read_bytes()
    assert len(data) == entry['bytes'], entry['path']
    assert hashlib.sha256(data).hexdigest() == entry['sha256'], entry['path']
scale = json.loads((here / 'storage-scale.json').read_text())
assert scale['status'] == 'PASS' and scale['heap_max_bytes'] <= 64 * 1024 * 1024
assert scale['pdf_bytes'] == 300_000_000 and scale['max_copy_write_bytes'] <= 65536
assert 1_073_741_824 < scale['archive_bytes'] <= 8_589_934_592
reference = json.loads((here / 'reference-pdf-evidence.json').read_text())
assert len(reference['functions']) == 4 and all(x['decompiled'] for x in reference['functions'])
assert reference['runtime_5704_import_pdf'] == 'NOT_CAPTURED'
print(json.dumps({'status': 'PASS', 'frozen_files': len(freeze['files']), 'android_device_tests': 'NOT_RUN'}))
