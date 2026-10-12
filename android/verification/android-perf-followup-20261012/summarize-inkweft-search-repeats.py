import json
import pathlib
import re

root = pathlib.Path('/workspace/scratch/android-perf-evidence')
results = {}
receipts = []
for phase in ['baseline', 'candidate']:
    rows = []
    for label in ['library-probe', 'library-repeat']:
        receipt = json.loads((root / f'{phase}-{label}.json').read_text())
        assert receipt['passed'] and receipt['executed'] == 1
        receipts.append(dict(phase=phase, label=label, started_utc=receipt['started_utc']))
        matches = re.findall(r'LIBRARY_READ_EVIDENCE (\{[^\n]+\})', (root / f'{phase}-{label}.log').read_text())
        assert len(matches) == 1
        row = json.loads(matches[0])
        assert row['books'] == 500 and row['pages'] == 20000 and row['coldSourceChecks'] == 20000
        assert row['saveCount'] == 30 and row['fullBodyOnDemandMatches']
        if phase == 'candidate':
            assert row['writerCompletedDuringPausedSearch']
            assert row['headerReturnedBodyChars'] == 0 and row['shelfDirectoryBodyChars'] == 0
            assert row['projectionRows'] == 61 and row['projectionChars'] == 36018
        rows.append(dict(run=len(rows) + 1, label=label, **row))
    results[phase] = rows
pairs = []
for index in range(2):
    b, c = results['baseline'][index], results['candidate'][index]
    pairs.append(dict(run=index + 1,
                     cold_change_percent=100 * (c['coldMs'] / b['coldMs'] - 1),
                     warm_median_change_percent=100 * (c['warmMedianMs'] / b['warmMedianMs'] - 1),
                     save_p50_change_percent=100 * (c['saveP50Ms'] / b['saveP50Ms'] - 1),
                     save_p95_change_percent=100 * (c['saveP95Ms'] / b['saveP95Ms'] - 1),
                     save_max_change_percent=100 * (c['saveMaxMs'] / b['saveMaxMs'] - 1)))
output = dict(comparator='a128bb07 + identical common database-initialization fix',
              environment='Same API35 x86_64 TCG/SwiftShader Android emulator; no KVM; not physical-device performance',
              sampling='Two fresh fixture invocations per revision; one cold query, five warm queries (median only), and 30 sequential save requests per invocation. Do not pool medians or reconstruct raw vectors.',
              execution_order=sorted(receipts, key=lambda x: x['started_utc']),
              results=results, pair_changes=pairs,
              limits=['Two observations per revision do not establish statistical significance or a confidence interval.',
                      'Serial next-after-commit save arrivals create different actual overlap on blocked and unblocked implementations.',
                      'Warm queries run after all fixture writes and explicit index construction complete.',
                      'Fresh UUID identities differ between fixtures; row counts and deterministic body contents are the same.',
                      'See repeat-runtime-check.json for preflight metadata observations and candidate-repeat setup overlap.'])
(root / 'repeated-search-summary.json').write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
for phase, rows in results.items():
    for row in rows:
        print(json.dumps(dict(phase=phase, **{k: row[k] for k in ['run', 'coldMs', 'warmMedianMs', 'saveP50Ms', 'saveP95Ms', 'saveMaxMs', 'writerCompletedDuringPausedSearch']})))
print(json.dumps(pairs))
