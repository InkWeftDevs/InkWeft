import json
import pathlib
import re
import statistics

root = pathlib.Path('/workspace/scratch/android-perf-evidence')
markers = {'history-probe': 'HISTORY_COLLECTION_EVIDENCE',
           'library-probe': 'LIBRARY_READ_EVIDENCE',
           'raster-probe': 'RASTER_PAIRED_EVIDENCE',
           'tile-local': 'TILE_LOCAL_EVIDENCE'}
data = {}
for phase in ['baseline', 'candidate']:
    data[phase] = {}
    for label, marker in markers.items():
        receipt = json.loads((root / f'{phase}-{label}.json').read_text())
        assert receipt['passed'], (phase, label, receipt)
        raw = (root / f'{phase}-{label}.log').read_text()
        values = [json.loads(value) for value in re.findall(re.escape(marker) + r' (\{[^\n]+\})', raw)]
        assert len(values) == {'history-probe': 1, 'library-probe': 1, 'raster-probe': 14, 'tile-local': 3}[label]
        data[phase][label] = values
    library = data[phase]['library-probe'][0]
    assert library['books'] == 500 and library['pages'] == 20000 and library['saveCount'] == 30
    assert library['fullBodyOnDemandMatches']
    history = data[phase]['history-probe'][0]
    assert history['historicalRoots'] == 2000 and history['detachedSources'] == 4096
    assert history['pausedScanSaves'] == 30 and history['liveFirstPassDeferred']
    assert all(row['pixelsEqual'] for row in data[phase]['raster-probe'] + data[phase]['tile-local'])
    assert [row['layers'] for row in data[phase]['tile-local']] == [1, 4, 8]
    assert [row['case'] for row in data[phase]['raster-probe']] == [row['case'] for row in data['baseline']['raster-probe']]

summary = dict(comparison_baseline='a128bb07 + identical common database-initialization fix',
               environment='API 35 x86_64 TCG/SwiftShader; no KVM; same emulator; not physical-device results',
               timing_repetitions='One paired run per probe; 30 saves per latency set; raster has 12 continuous transform steps',
               library={}, history={}, raster={}, local_redraw={})
for phase in data:
    summary['library'][phase] = data[phase]['library-probe'][0]
    summary['history'][phase] = data[phase]['history-probe'][0]
    rows = data[phase]['raster-probe']
    transforms = [row for row in rows if row['case'].startswith('transform-')]
    summary['raster'][phase] = dict(
        compared_frames=len(rows), all_pixels_equal=True,
        transforms=dict(count=len(transforms), median_complete_ms=statistics.median(row['completeMs'] for row in transforms),
                        decoded_total=sum(row['decoded'] for row in transforms),
                        snapshot_rows_total=sum(row['snapshotReadRows'] for row in transforms),
                        rebuilt_total=sum(row['rebuilt'] for row in transforms),
                        reused_strokes_total=sum(row.get('reusedStrokes', 0) for row in transforms)),
        separated_append=next(row for row in rows if row['case'] == 'separated-append'),
        cases=rows)
    summary['local_redraw'][phase] = data[phase]['tile-local']

base, candidate = summary['history']['baseline'], summary['history']['candidate']
summary['history']['collection_speedup'] = base['collectionMs'] / candidate['collectionMs']
summary['history']['collection_time_reduction_percent'] = 100 * (1 - candidate['collectionMs'] / base['collectionMs'])
base, candidate = summary['library']['baseline'], summary['library']['candidate']
summary['library']['cold_speedup'] = base['coldMs'] / candidate['coldMs']
summary['library']['cold_time_reduction_percent'] = 100 * (1 - candidate['coldMs'] / base['coldMs'])
summary['limits'] = [
    'Returned rows and UTF-16 character counts are measured; operating-system storage read bytes are not measured.',
    'Directory metadata still scans the full directory; nonempty body searches still scan candidate note bodies.',
    'SQLite fixtures use the same sizes/content and deterministic raster strokes, with fresh UUID database identities.',
    'The transform median combines the same 12 pan/zoom steps on both revisions; each local-layer timing is one sample.',
    'No independent-process initialization stress test or physical Android device performance run was performed.',
    'Improved collection duration does not imply all concurrent-save latency percentiles improved.'
]
(root / 'paired-performance-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({key: value for key, value in summary.items() if key not in ['raster', 'local_redraw']}, ensure_ascii=False))
