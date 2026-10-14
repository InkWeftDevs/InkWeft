#!/usr/bin/env python3
"""Read-only independent dual-rule audit from official compact JSON markers."""
import argparse
import collections
import hashlib
import json
import math
import statistics
import subprocess
from pathlib import Path

def sha(data):
    return hashlib.sha256(data).hexdigest()

def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()

def load(path):
    return json.loads(path.read_bytes())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', type=Path, default=Path('/workspace/InkWeft'))
    ap.add_argument('--out', type=Path, default=Path(__file__).parent / 'review.json')
    args = ap.parse_args()
    repo = args.repo
    evidence = repo / 'android/verification/pr20-match-execution-20261014'
    frozen = repo / 'android/verification/pr20-match-candidate-20261014'
    raw = (evidence / 'official/job-decoded.log').read_bytes()
    assert sha(raw) == 'd1634bbeb118100737e2c9d6d6111fca972f2fb05999b5604ff34f0d0e7c9f62'
    contexts, records, ends = {}, collections.defaultdict(list), {}
    payloads = 0
    for line in raw.decode('utf-8-sig').splitlines():
        for kind, destination in [('CONTEXT', contexts), ('END', ends)]:
            token = 'INKWEFT_MATCH_' + kind + ' '
            if token in line:
                value = json.loads(line.split(token, 1)[1])
                assert value['ordinal'] not in destination
                destination[value['ordinal']] = value
        token = 'INKWEFT_MATCH_RECORD '
        if token in line:
            value = json.loads(line.split(token, 1)[1])
            payload = value['original_json'].encode()
            assert len(payload) == value['payload_bytes']
            assert sha(payload) == value['payload_sha256']
            ordinal = value['ordinal']
            assert value['sequence'] == len(records[ordinal])
            assert value['identity'] == contexts[ordinal]['identity']
            records[ordinal].append({'marker': value['marker'], 'data': json.loads(payload)})
            payloads += 1
    assert set(contexts) == set(ends) == set(range(1, 63))
    assert payloads == 365
    for ordinal, context in contexts.items():
        actual = records[ordinal]
        assert sha(compact(actual) + b'\n') == context['records_sha256']
        assert len(actual) == ends[ordinal]['count']
        extracted = evidence / f'extracted/method-{ordinal:02d}-{context["identity"]}-records.json'
        assert actual == load(extracted)

    protocol_path = frozen / 'original-protocol.json'
    protocol_bytes = protocol_path.read_bytes()
    assert sha(protocol_bytes) == '1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba'
    bands = json.loads(protocol_bytes)['proposed_time_regression_bands']
    values = {}
    metric_bands = {}
    saves_checked = 0
    def add(side, seed, name, value, band):
        key = (side, seed, name)
        assert key not in values and math.isfinite(value) and value >= 0
        values[key] = value
        assert metric_bands.setdefault(name, band) == band
    for ordinal in range(32, 56):
        context = contexts[ordinal]
        side, seed = context['identity'], context['seed']
        rows = records[ordinal]
        def one(marker):
            selected = [r['data'] for r in rows if r['marker'] == marker]
            assert len(selected) == 1
            return selected[0]
        raster = [r['data'] for r in rows if r['marker'] == 'TILE_LOCAL_EVIDENCE']
        if raster:
            assert sorted(t['layers'] for t in raster) == [1, 4, 8]
            for tile in raster:
                for phase in ('firstCompleteMs', 'appendCompleteMs', 'forcedFullCompleteMs'):
                    add(side, seed, f'raster.{tile["layers"]}.{phase}', tile[phase], 'raster_all_phase_times')
        else:
            item = one('LIBRARY_READ_EVIDENCE')
            observe = item['observeIndexConcurrentSaves']
            direct = one('KVM_DIRECT_INTERVALS')
            for scenario, source, samples in [('direct', item, direct['saves']), ('observe', observe, observe['saveProgressSamples'])]:
                samples_ms = source['saveSamplesMs']
                assert len(samples_ms) == len(samples) == 30
                for i, (value, sample) in enumerate(zip(samples_ms, samples)):
                    assert sample['committed'] is True and sample['sample'] == i
                    assert math.isclose(value, (sample['endNs'] - sample['startNs']) / 1e6, rel_tol=1e-9, abs_tol=1e-6)
                saves_checked += len(samples)
                ordered = sorted(samples_ms)
                for name, index in [('p50', 14), ('p95', 28), ('max', 29)]:
                    add(side, seed, f'library.{scenario}.{name}', ordered[index], 'save_' + name)
            add(side, seed, 'library.coldMs', item['coldMs'], 'search_and_index_completion')
            for name in ('allIndexRowsCommittedMs', 'searchIndexReadyMs'):
                add(side, seed, 'library.' + name, observe[name], 'search_and_index_completion')
    assert saves_checked == 720 and len(metric_bands) == 18 and len(values) == 216
    frozen_result = load(evidence / 'extracted/statistics/analysis.json')
    frozen_rows = {(x['comparison'], x['metric']): x for x in frozen_result['comparisons']}
    comparisons = []
    for left, right in [('B', 'C0'), ('B', 'C1'), ('C0', 'C1')]:
        for metric in sorted(metric_bands):
            band = bands[metric_bands[metric]]
            pairs = []
            for seed in [4101, 4102, 4103, 4104]:
                a, b = values[left, seed, metric], values[right, seed, metric]
                pairs.append(dict(seed=seed, left_ms=a, right_ms=b, delta_ms=b-a, relative_change=b/a-1,
                                  over_band=b-a > max(band['absolute_ms'], band['relative']*a)))
            positive = sum(p['delta_ms'] > 0 for p in pairs)
            over = sum(p['over_band'] for p in pairs)
            median_delta = statistics.median(p['delta_ms'] for p in pairs)
            median_relative = statistics.median(p['relative_change'] for p in pairs)
            both = median_delta > band['absolute_ms'] and median_relative > band['relative']
            def classification(count):
                if count >= 3 and both:
                    return 'REGRESSION_SIGNAL'
                return 'INCONCLUSIVE' if over else 'WITHIN_PROPOSED_BAND'
            original, alternate = classification(positive), classification(over)
            existing = frozen_rows[(left + '->' + right, metric)]
            assert existing['pairs'] == pairs and existing['band'] == band
            assert existing['median_delta_ms'] == median_delta and existing['median_relative_change'] == median_relative
            assert existing['screening_status'] == original
            comparisons.append(dict(comparison=left + '->' + right, metric=metric, band=band, pairs=pairs,
                positive_pairs=positive, over_band_pairs=over, median_delta_ms=median_delta,
                median_relative_change=median_relative, original_frozen_status=original,
                alternative_three_over_band_status=alternate, original_output_exact_match=True))
    original_commit = 'c37ba03232b1a0985e85cbaec4d349dbab945919'
    history = []
    for name in ['PLAN.md', 'protocol.json', 'analyze.py']:
        path = 'ci/pr20-kvm-paired/' + name
        data = subprocess.check_output(['git', 'show', original_commit + ':' + path], cwd=repo)
        history.append(dict(git=original_commit, path=path, bytes=len(data), sha256=sha(data)))
        if name == 'protocol.json':
            assert data == protocol_bytes
        if name == 'PLAN.md':
            assert data == (repo / 'android/verification/pr20-kvm-paired-20261013/evidence/frozen-PLAN.md').read_bytes()
    counts = lambda key: dict(collections.Counter(row[key] for row in comparisons))
    output = dict(scope='Independent numeric and original rule audit; not a complete execution/acceptance audit',
        run_id=38520196523, official_log_sha256=sha(raw), payload_hashes_verified=payloads,
        method_record_hashes_verified=len(contexts), original_ordered_saves_checked=saves_checked,
        metric_count=len(metric_bands), comparison_count=len(comparisons), history_sources=history,
        original_rule='>=3 positive deltas AND median relative > band AND median absolute > band',
        alternative_rule='>=3 individually over-band deltas AND median relative > band AND median absolute > band',
        alternate_rule_scope='Sensitivity only: not present in original frozen PLAN/analyzer',
        original_counts=counts('original_frozen_status'), alternative_counts=counts('alternative_three_over_band_status'),
        changed=[r for r in comparisons if r['original_frozen_status'] != r['alternative_three_over_band_status']],
        comparisons=comparisons)
    args.out.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: output[k] for k in ['payload_hashes_verified', 'method_record_hashes_verified', 'comparison_count', 'original_counts', 'alternative_counts']}, ensure_ascii=False))

if __name__ == '__main__':
    main()
