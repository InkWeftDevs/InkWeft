#!/usr/bin/env python3
"""Strict offline two-source screening from ordered, decoded official job logs.

No network, subprocess, raw-artifact reconstruction, or APK validation. Input files
are read only. Missing/invalid receipts produce INCOMPLETE and exit 1.
"""
import argparse
from collections import Counter
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics

from legacy_math import quantiles, classify

PREFIX = 'INKWEFT_SEARCH_SCREEN_'
SEEDS = (4101, 4102, 4103, 4104)
IDENTITIES = ('C0', 'C1')
METRICS = {f'library.{scenario}.{q}': 'save_' + q
           for scenario in ('direct', 'observe') for q in ('p50', 'p95', 'max')}
METRICS.update({f'library.{name}': 'search_and_index_completion'
                for name in ('coldMs', 'allIndexRowsCommittedMs', 'searchIndexReadyMs')})
CONTEXT_KEYS = ('scope run_id run_attempt workflow_sha source_commit source_spec_sha256 method_plan_sha256 '
                'probe_sha256 source_manifest_sha256 apk_sha256 signer_sha256 apk_receipt_sha256 '
                'installed_apk_sha256 install_log_sha256 environment_sha256 ordinal identity seed '
                'install_group selection raw_log_sha256 records_sha256 junit_status adb_exit process_exit_outcome')
METHOD_KEYS = 'ordinal phase identity selection status junit_status adb_exit process_exit_outcome error'
FINAL_METHOD_KEYS = 'ordinal identity phase selection seed status junit_status adb_exit process_exit_outcome log_sha256'
INPUT_FILES = dict(approved_plan_sha256='PLAN.md', source_spec_sha256='source-spec.json',
                   method_plan_sha256='method-plan.json', original_protocol_sha256='original-protocol.json',
                   marker_contract_sha256='marker-contract.json', prepare_sources_sha256='prepare_sources.py')

def require(condition, message):
    if not condition:
        raise ValueError(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def compact(value):
    return json.dumps(value, separators=(',', ':')).encode()

def strict_json(value):
    def pairs(items):
        result = {}
        for key, child in items:
            require(key not in result, f'Duplicate JSON key: {key}')
            result[key] = child
        return result
    def invalid(value):
        raise ValueError(f'Invalid JSON constant: {value}')
    return json.loads(value, object_pairs_hook=pairs, parse_constant=invalid)

def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected.split()), 'Unexpected/missing envelope keys')

def digest(value):
    require(type(value) is str and re.fullmatch('[0-9a-f]{64}', value), 'Invalid SHA256')

def shape(value, schema, depth=1):
    require(depth <= 12, 'Payload JSON depth exceeded')
    kind = schema['type']
    if kind == 'object':
        require(type(value) is dict and set(value) == set(schema['required']), 'Payload object keys differ')
        for key, child in value.items():
            shape(child, schema['properties'][key], depth + 1)
    elif kind == 'array':
        require(type(value) is list and len(value) == schema['length'], 'Payload array length differs')
        for child in value:
            shape(child, schema['items'], depth + 1)
    elif kind == 'number':
        require(type(value) in (int, float) and math.isfinite(value) and value >= schema.get('minimum', 0), 'Invalid finite number')
    elif kind == 'boolean':
        require(type(value) is bool, 'Invalid boolean')
    elif kind == 'string':
        require(type(value) is str, 'Invalid string')
        require(value in schema['enum'] if 'enum' in schema else re.fullmatch(schema['pattern'], value), 'Non-whitelisted string')
    else:
        require(kind == 'null' and value is None, 'Invalid null/schema')

def frozen_inputs(directory):
    values = {key: directory.joinpath(name).read_bytes() for key, name in INPUT_FILES.items()}
    plan = strict_json(values['method_plan_sha256'])
    spec = strict_json(values['source_spec_sha256'])
    contract = strict_json(values['marker_contract_sha256'])
    protocol = strict_json(values['original_protocol_sha256'])
    require(plan['instrumentation_calls'] == plan['expected_junit_methods'] == len(plan['steps']) == 18, 'Not the fixed 18-method plan')
    require(plan['correctness_methods'] == 10 and plan['common_methods'] == 8, 'Incorrect phase counts')
    require([s['ordinal'] for s in plan['steps']] == list(range(1, 19)), 'Plan order')
    require([(s['identity'], s['seed']) for s in plan['steps'][10:]] ==
            [('C0', 4101), ('C1', 4101), ('C1', 4102), ('C0', 4102),
             ('C0', 4103), ('C1', 4103), ('C1', 4104), ('C0', 4104)], 'Balanced order changed')
    require(all(s['phase'] == ('correctness' if s['ordinal'] <= 10 else 'common-comparison') for s in plan['steps']), 'Plan phase changed')
    require(set(spec['identities']) == set(IDENTITIES), 'Unexpected source identities')
    require(spec['identities']['C0']['git'] == 'd66b60d6cf9587f646acb76dd8a00910b73a6d70' and
            spec['identities']['C1']['git'] == 'a9ccf2cc727a7957e8fb00115f0060d80b287de1', 'Unexpected fixed source commits')
    require(len(spec['common_probes']) == 1 and spec['common_probes'][0]['sha256'] == contract['fixed_probe_sha256'] ==
            '90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c', 'Probe changed')
    require(contract['records_per_completed_library_method'] == sum(contract['allowed_marker_counts'].values()) == 12, 'Marker counts changed')
    require(contract['marker_payload_max_bytes'] == 65536 and contract['method_payload_max_bytes'] == 262144 and
            contract['json_depth_limit'] == 12 and contract['finite_numbers_only'] and contract['forbid_duplicate_keys'], 'Contract limits changed')
    require(sha(values['original_protocol_sha256']) == '1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba', 'Original protocol changed')
    return {'plan': plan, 'spec': spec, 'contract': contract, 'protocol': protocol,
            'hashes': {key: sha(data) for key, data in values.items()}}

def validate_binding(binding, frozen, run_id, workflow_sha, control_root=None):
    keys(binding, 'scope run_id job_id run_attempt workflow_sha run_status run_conclusion job_status job_conclusion input_hashes control_hashes')
    require(binding['scope'] == 'SCREENING_ONLY' and binding['run_id'] == run_id and str(binding['run_attempt']) == '1' and
            binding['workflow_sha'] == workflow_sha and re.fullmatch('[0-9]+', str(binding['job_id'])), 'Official run/job identity mismatch')
    require(binding['run_status'] == binding['job_status'] == 'completed' and
            binding['run_conclusion'] == binding['job_conclusion'] == 'success', 'Official run/job did not finish successfully')
    require(binding['input_hashes'] == frozen['hashes'], 'Official binding/frozen input mismatch')
    controls = binding['control_hashes']
    require(type(controls) is dict and {'run.py', 'package.py', 'cleanup.py'} <= {Path(p).name for p in controls} and
            len([p for p in controls if p.startswith('.github/workflows/') and p.endswith('.yml')]) == 1, 'Control files missing')
    for relative, expected in controls.items():
        digest(expected)
        path = Path(relative)
        require(not path.is_absolute() and '..' not in path.parts and '.' not in path.parts, 'Control path must be relative to repository root')
        if control_root is not None:
            target = control_root / path
            require(target.resolve().is_relative_to(control_root.resolve()) and sha(target.read_bytes()) == expected, 'Control file hash mismatch: ' + relative)

def parse_receipts(text, frozen, run_id, workflow_sha):
    plan, spec, contract = (frozen[k] for k in ('plan', 'spec', 'contract'))
    methods, blocks, active = [], [], None
    clock = final = None
    for line_number, raw in enumerate(text.splitlines(), 1):
        # Official decoded step logs may retain only their ISO timestamp prefix.
        line = re.sub(r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\s+', '', raw)
        if not line.startswith(PREFIX):
            continue
        tag, separator, encoded = line.partition(' ')
        require(separator and len(encoded.encode()) <= 524288, f'Malformed/oversize envelope at {line_number}')
        kind = tag[len(PREFIX):]
        require(kind in ['CLOCK', 'CONTEXT', 'RECORD', 'END', 'METHOD', 'FINAL'], f'Unknown receipt {kind}')
        value = strict_json(encoded)
        require(final is None, 'Receipt after FINAL')
        if kind == 'CLOCK':
            require(clock is None and not methods and active is None, 'Duplicate/late CLOCK')
            keys(value, 'status scope anchor_kind budget_anchor_epoch first_step_observed_epoch actual_job_started_epoch deadlines_minutes deadline_epochs')
            require(value['status'] == 'PASS' and value['scope'] == 'SCREENING_ONLY' and value['anchor_kind'] == 'runner_first_step', 'Invalid clock scope/status')
            start = value['budget_anchor_epoch']
            require(type(start) is int and start > 0 and type(value['first_step_observed_epoch']) in (int, float) and
                    start <= value['first_step_observed_epoch'] < start + 1 and value['actual_job_started_epoch'] is None, 'Clock anchor mismatch')
            require(value['deadlines_minutes'] == [30, 35, 50, 55, 60] and value['deadline_epochs'] ==
                    {k: start + m * 60 for k, m in [('setup', 30), ('correctness', 35), ('common', 50), ('work', 55)]}, 'Clock deadlines changed')
            clock = value
            continue
        require(clock is not None, 'Missing CLOCK')
        if kind == 'CONTEXT':
            require(active is None and len(methods) < 18, 'Nested/extra block')
            step = plan['steps'][len(methods)]
            keys(value, CONTEXT_KEYS)
            require(step['phase'] == 'common-comparison' and all(value[k] == step[k] for k in
                    ['ordinal', 'identity', 'seed', 'selection', 'install_group']), 'Block does not match next planned method')
            identity = value['identity']
            require(type(value['ordinal']) is int and type(value['seed']) is int, 'Invalid ordinal/seed type')
            require(value['scope'] == 'SCREENING_ONLY' and value['run_id'] == run_id and str(value['run_attempt']) == '1' and
                    value['workflow_sha'] == workflow_sha, 'Run/source context mismatch')
            require(value['source_commit'] == spec['identities'][identity]['git'] and
                    value['source_manifest_sha256'] == spec['identities'][identity]['canonical_manifest_sha256'], 'Source manifest mismatch')
            require(value['source_spec_sha256'] == frozen['hashes']['source_spec_sha256'] and
                    value['method_plan_sha256'] == frozen['hashes']['method_plan_sha256'] and
                    value['probe_sha256'] == contract['fixed_probe_sha256'], 'Frozen input hash mismatch')
            for k, v in value.items():
                if k.endswith('_sha256') and k != 'signer_sha256':
                    digest(v)
            require(type(value['signer_sha256']) is list and len(value['signer_sha256']) == 1, 'Signer missing')
            digest(value['signer_sha256'][0])
            require(value['apk_sha256'] == value['installed_apk_sha256'], 'Installed APK differs from built hash')
            require(value['junit_status'] == 'PASS' and type(value['adb_exit']) is int and value['adb_exit'] == 0 and
                    value['process_exit_outcome'] == 'NATURAL_EXIT', 'Block did not pass/isolate')
            active = {'context': value, 'records': [], 'ordered': [], 'original_json': [], 'ended': False}
        elif kind == 'RECORD':
            require(active is not None and not active['ended'], 'Record outside open block')
            keys(value, 'ordinal identity sequence marker payload_bytes payload_sha256 original_json')
            c = active['context']
            require(value['ordinal'] == c['ordinal'] and value['identity'] == c['identity'] and
                    type(value['sequence']) is int and value['sequence'] == len(active['records']) < 12, 'Record order/identity mismatch')
            marker, original = value['marker'], value['original_json']
            require(marker in contract['allowed_marker_counts'] and type(original) is str, 'Unknown marker/type')
            payload = original.encode()
            require(type(value['payload_bytes']) is int and len(payload) == value['payload_bytes'] <= contract['marker_payload_max_bytes'] and
                    sha(payload) == value['payload_sha256'], 'Payload byte count/hash mismatch')
            data = strict_json(original)
            shape(data, contract['schemas'][marker])
            active['records'].append({'marker': marker, 'data': data})
            active['original_json'].append(original)
            active['ordered'].append({k: value[k] for k in ['sequence', 'marker', 'payload_bytes', 'payload_sha256']})
        elif kind == 'END':
            require(active is not None and not active['ended'], 'Missing/duplicate block END')
            keys(value, 'ordinal identity count ordered_payloads ordered_payloads_sha256')
            c = active['context']
            require(value['ordinal'] == c['ordinal'] and value['identity'] == c['identity'] and type(value['count']) is int and value['count'] == len(active['records']) == 12, 'Incomplete block count')
            require(value['ordered_payloads'] == active['ordered'] and
                    sha(compact(value['ordered_payloads'])) == sha(compact(active['ordered'])) == value['ordered_payloads_sha256'], 'Footer order/hash mismatch')
            require(Counter(r['marker'] for r in active['records']) == contract['allowed_marker_counts'], 'Marker whitelist not closed')
            require(sum(x['payload_bytes'] for x in active['ordered']) <= contract['method_payload_max_bytes'], 'Block payload budget exceeded')
            record_bytes = (json.dumps(active['records'], ensure_ascii=False, indent=2) + '\n').encode()
            require(sha(record_bytes) == c['records_sha256'], 'Reconstructed records hash differs')
            active['ended'] = True
        elif kind == 'METHOD':
            keys(value, METHOD_KEYS)
            require(len(methods) < 18, 'Extra METHOD')
            step = plan['steps'][len(methods)]
            require(type(value['ordinal']) is int, 'Invalid METHOD ordinal type')
            require(all(value[k] == step[k] for k in ['ordinal', 'phase', 'identity', 'selection']), 'METHOD order/plan mismatch')
            require(value['status'] == value['junit_status'] == 'PASS' and type(value['adb_exit']) is int and value['adb_exit'] == 0 and
                    value['process_exit_outcome'] == 'NATURAL_EXIT' and value['error'] is None, 'METHOD failure/incomplete/isolation failure')
            if step['phase'] == 'common-comparison':
                require(active is not None and active['ended'] and active['context']['ordinal'] == step['ordinal'], 'Missing complete library block')
                blocks.append(active)
                active = None
            else:
                require(active is None, 'Correctness method has unexpected payload block')
            methods.append(value)
        else:
            require(active is None and len(methods) == 18 and len(blocks) == 8, 'FINAL before all methods/blocks')
            final = value
    require(clock is not None and final is not None and active is None and len(methods) == 18 and len(blocks) == 8, 'Missing/unfinished receipt set')
    keys(final, 'scope run_id workflow_sha input_hashes status cleanup_verified cleanup methods install_groups device_before_sha256 device_after_sha256 packaging_complete missing_files manifest_sha256 summary_sha256')
    require(final['scope'] == 'SCREENING_ONLY' and final['run_id'] == run_id and final['workflow_sha'] == workflow_sha and
            final['input_hashes'] == frozen['hashes'], 'FINAL source/plan/run binding mismatch')
    require(final['status'] == 'EXECUTION_COMPLETE_ANALYSIS_PENDING' and final['cleanup_verified'] is True and
            final['packaging_complete'] is True and final['missing_files'] == [], 'FINAL execution/packaging incomplete')
    for k in ['device_before_sha256', 'device_after_sha256', 'manifest_sha256', 'summary_sha256']:
        digest(final[k])
    require(final['device_before_sha256'] == final['device_after_sha256'], 'Device changed')
    keys(final['cleanup'], 'trap always')
    for cleanup in final['cleanup'].values():
        keys(cleanup, 'emulator command_groups acl error_count before_sha256 after_sha256 exact_match')
        require(cleanup['emulator'] == cleanup['command_groups'] == 'STOPPED_CONFIRMED' and
                cleanup['acl'] in ['RESTORED_EXACT', 'UNCHANGED_EXACT'] and type(cleanup['error_count']) is int and
                cleanup['error_count'] == 0 and cleanup['exact_match'] is True, 'Cleanup/ACL not confirmed')
        digest(cleanup['before_sha256']); digest(cleanup['after_sha256'])
        require(cleanup['before_sha256'] == cleanup['after_sha256'], 'ACL hash mismatch')
    require(final['cleanup']['trap']['before_sha256'] == final['cleanup']['always']['before_sha256'], 'ACL origins differ')
    require(type(final['methods']) is list and len(final['methods']) == 18, 'FINAL method count')
    for method, receipt, step in zip(methods, final['methods'], plan['steps']):
        keys(receipt, FINAL_METHOD_KEYS)
        require(all(receipt[k] == v for k, v in method.items() if k != 'error') and receipt['seed'] == step['seed'], 'METHOD differs from FINAL')
        digest(receipt['log_sha256'])
    groups = {s['install_group']: s['identity'] for s in plan['steps']}
    require(type(final['install_groups']) is dict and set(final['install_groups']) == set(groups), 'Install group set differs')
    for group, identity in groups.items():
        require(final['install_groups'][group] == {'identity': identity, 'status': 'PASS'}, 'Install group failed')
    for block in blocks:
        c = block['context']
        require(c['raw_log_sha256'] == final['methods'][c['ordinal'] - 1]['log_sha256'] and
                c['environment_sha256'] == final['device_before_sha256'], 'Block log/environment binding mismatch')
    for identity in IDENTITIES:
        group = [b['context'] for b in blocks if b['context']['identity'] == identity]
        require(len(group) == 4 and all(all(c[k] == group[0][k] for k in
                ['source_commit', 'source_manifest_sha256', 'apk_sha256', 'installed_apk_sha256', 'signer_sha256', 'apk_receipt_sha256']) for c in group), 'Per-source APK identity changed')
    require(blocks[0]['context']['signer_sha256'] == blocks[1]['context']['signer_sha256'], 'Signer differs by source')
    return {'clock': clock, 'methods': methods, 'blocks': blocks, 'final': final}

def calculate(receipts, frozen):
    raw_saves, distributions, library, observations = [], [], [], []
    for block in receipts['blocks']:
        c, records = block['context'], block['records']
        base = {k: c[k] for k in ['ordinal', 'identity', 'seed', 'source_commit', 'install_group', 'selection']}
        one = lambda marker: next(r['data'] for r in records if r['marker'] == marker)
        fixture, item, direct, index = [one(m) for m in ['KVM_PAIR_FIXTURE', 'LIBRARY_READ_EVIDENCE', 'KVM_DIRECT_INTERVALS', 'KVM_INDEX_INTERVALS']]
        observe = item['observeIndexConcurrentSaves']
        contract = frozen['contract']['extra_checks']
        require(fixture['seed'] == c['seed'] and fixture['rowCounts'] == contract['fixture_counts'] and
                fixture['writerSqlite'] == item['writerSqlite'] == contract['writerSqlite'], 'Fixture rows/seed/writer mismatch')
        require([r['data']['rows'] for r in records if r['marker'] == 'INDEX_SAVE_PROGRESS'] == contract['progress_rows'], 'Index progress order')
        for k, v in one('LIBRARY_DIRECT_SAVE_CHECKPOINT').items():
            require(item[k] == v, f'Direct checkpoint mismatch: {k}')
        for k, v in one('LIBRARY_INDEX_SAVE_CHECKPOINT').items():
            require(observe[k] == v, f'Index checkpoint mismatch: {k}')
        require(item['books'] == 500 and item['pages'] == 20000 and item['saveCount'] == observe['saveCount'] == 30, 'Workload/sample count changed')
        require(item['fullBodyOnDemandMatches'] is True and item['writerCompletedDuringPausedSearch'] is True, 'Candidate functional result false')
        require(statistics.median(item['warmSamplesMs']) == item['warmMedianMs'], 'Warm median mismatch')
        batches = index['batches']
        require([b['rows'] for b in batches] == list(range(64, 20000, 64)) + [20000] and
                all(a['committedNs'] <= b['committedNs'] for a, b in zip(batches, batches[1:])), 'Index batches incomplete/unordered')
        require(observe['indexRowsFinal'] == 20000, 'Incomplete index')
        def add(metric, value):
            require(metric in METRICS and type(value) in (int, float) and math.isfinite(value) and value >= 0, 'Invalid metric')
            observations.append(dict(base, metric=metric, value_ms=value))
        add('library.coldMs', item['coldMs'])
        for name in ['allIndexRowsCommittedMs', 'searchIndexReadyMs']:
            add('library.' + name, observe[name])
        for scenario, values, samples, start, end in [
            ('direct', item['saveSamplesMs'], direct['saves'], direct['coldStartNs'], direct['coldEndNs']),
            ('observe', observe['saveSamplesMs'], observe['saveProgressSamples'], index['observeStartNs'], batches[-1]['committedNs'])]:
            require(len(values) == len(samples) == 30 and end >= start, 'Save interval size/order')
            qs = quantiles(values)
            for q, v in qs.items():
                add(f'library.{scenario}.{q}', v)
            previous = None
            local = []
            for i, (v, sample) in enumerate(zip(values, samples)):
                a, b, revision = sample['startNs'], sample['endNs'], sample['committedRevision']
                require(sample['sample'] == i and sample['committed'] is True and b >= a and
                        math.isclose(v, (b - a) / 1e6, rel_tol=1e-9, abs_tol=1e-6) and
                        math.isclose(v, sample['elapsedMs'], rel_tol=1e-9, abs_tol=1e-6), 'Invalid ordered save sample')
                require(previous is None or revision == previous + 1, 'Save revision gap')
                previous = revision
                local.append(dict(base, scenario=scenario, sample=i, elapsed_ms=v, start_ns=a, end_ns=b,
                    committed=True, committed_revision=revision, background_start_ns=start, background_end_ns=end,
                    background_overlap=max(a, start) < min(b, end), wholly_within_background=a >= start and b <= end,
                    index_batch_commits_during_save=sum(a <= x['committedNs'] <= b for x in batches) if scenario == 'observe' else None,
                    source_checks_start=sample.get('checksStart', sample.get('sourceChecksStart')),
                    source_checks_end=sample.get('checksEnd', sample.get('sourceChecksEnd')),
                    index_rows_start=sample.get('indexRowsStart'), index_rows_end=sample.get('indexRowsEnd')))
            raw_saves.extend(local)
            exposed = [x['elapsed_ms'] for x in local if x['background_overlap']]
            distributions.append(dict(base, scenario=scenario, count=30, **qs, overlapping=len(exposed),
                exposed_max=max(exposed) if exposed else None, exposed_quantiles=quantiles(exposed) if len(exposed) >= 20 else None,
                exposure_status='DESCRIPTIVE_SUBSET_ONLY' if len(exposed) >= 20 else 'INSUFFICIENT_EXPOSURE'))
        library.append(dict(base, fixture=fixture, evidence=item, direct_intervals=direct, index_intervals=index,
                            warm_samples_ms=item['warmSamplesMs'], warm_median_ms=statistics.median(item['warmSamplesMs'])))
    for seed in SEEDS:
        fs = [x['fixture'] for x in library if x['seed'] == seed]
        require(len(fs) == 2 and fs[0] == fs[1], 'Paired fixtures differ')
    pairs_out, classifications, warm = [], [], []
    for metric, band_name in METRICS.items():
        band = frozen['protocol']['proposed_time_regression_bands'][band_name]
        pairs = []
        for seed in SEEDS:
            vals = [[r['value_ms'] for r in observations if r['seed'] == seed and r['identity'] == side and r['metric'] == metric] for side in IDENTITIES]
            require(all(len(v) == 1 for v in vals), 'Missing/duplicate metric')
            a, b = vals[0][0], vals[1][0]
            pairs.append(dict(seed=seed, left_ms=a, right_ms=b, delta_ms=b-a, relative_change=b/a-1 if a else None,
                              over_band=b-a > max(band['relative']*a, band['absolute_ms'])))
        classifications.append(dict(comparison='C0->C1', metric=metric, band=band, pairs=pairs, screening_status=classify(pairs, band),
            median_delta_ms=statistics.median(p['delta_ms'] for p in pairs),
            median_relative_change=statistics.median(p['relative_change'] for p in pairs) if all(p['relative_change'] is not None for p in pairs) else None))
        pairs_out.extend(dict(comparison='C0->C1', metric=metric, **p) for p in pairs)
    for seed in SEEDS:
        a, b = [next(x for x in library if x['seed'] == seed and x['identity'] == side) for side in IDENTITIES]
        av, bv = a['warm_median_ms'], b['warm_median_ms']
        warm.append(dict(seed=seed, left_raw_ms=a['warm_samples_ms'], right_raw_ms=b['warm_samples_ms'], left_median_ms=av,
                         right_median_ms=bv, delta_ms=bv-av, relative_change=bv/av-1 if av else None, status='DESCRIPTIVE_NO_PREDECLARED_BAND'))
    require((len(raw_saves), len(distributions), len(pairs_out), len(classifications)) == (480, 16, 36, 9), 'Output counts differ')
    return dict(raw_saves=raw_saves, save_distributions=distributions, paired_values=pairs_out, comparisons=classifications,
                warm_comparisons=warm, library=library)

def analyze(text, frozen, run_id, workflow_sha, binding=None):
    result = {'schema': 1, 'analysis_status': 'INCOMPLETE', 'run_id': run_id, 'workflow_sha': workflow_sha,
              'source_spec': frozen['spec'], 'input_hashes': frozen['hashes'], 'issues': [],
              'limitations': ['Official decoded log provenance is supplied externally, not authenticated by this parser.',
                  'Scoped 2-source data screening only; no original-B pooling, raster, GC, or full formal acceptance.',
                  'Raw ZIP/raw JUnit/APK binary and native/signature/ACL-file independent audits are outside this scope; hashes are compact receipts.',
                  'records_sha256 is reconstructed from all12 payloads; raw_log_sha256 only links CONTEXT to FINAL, since full instrumentation text is not forwarded.',
                  'CLOCK checks contract consistency, not independently measured job duration or all phase finish times.',
                  'Four seed blocks are comparison units; 480 saves and warm repeats are not independent trials.',
                  'Cold means unindexed query, not cold OS caches; overlap is not measured SQLite lock wait.',
                  'Warm has no added threshold. Legacy probe p50 is retained but quantiles use nearest rank indices14/28/29.']}
    try:
        validate_binding(binding, frozen, run_id, workflow_sha)
        receipts = parse_receipts(text, frozen, run_id, workflow_sha)
        result.update(calculate(receipts, frozen))
        result.update(receipts=receipts, official_binding=binding, analysis_status='COMPLETE_SCOPED_LOG_SCREENING')
    except (ValueError, AssertionError, KeyError, TypeError, StopIteration, ZeroDivisionError, OverflowError, RecursionError) as error:
        result['issues'].append(f'{type(error).__name__}: {error}')
    return result

def write_outputs(out, result):
    out.mkdir(exist_ok=False)
    (out/'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    for key in ['raw_saves', 'save_distributions', 'paired_values', 'comparisons', 'warm_comparisons']:
        rows = result.get(key, [])
        if not rows:
            continue
        fields = list(dict.fromkeys(k for r in rows for k in r))
        with (out/(key.replace('_', '-')+'.csv')).open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
            writer.writerows({k: json.dumps(v, ensure_ascii=False) if isinstance(v, (list, dict)) else v for k, v in row.items()} for row in rows)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--job-log', type=Path, nargs='+', required=True, help='Decoded official text files in chronological order; do not supply both aggregate and duplicate step logs')
    parser.add_argument('--frozen', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--workflow-sha', required=True)
    parser.add_argument('--binding', type=Path, required=True, help='Root-produced fixed official run/job metadata and exact control hashes')
    parser.add_argument('--control-root', type=Path, required=True, help='Final execution clone repository root, never an unbound template')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        require(re.fullmatch('[0-9]+', args.run_id) and re.fullmatch('[0-9a-f]{40}', args.workflow_sha), 'Invalid official identity')
        frozen = frozen_inputs(args.frozen)
        binding_bytes = args.binding.read_bytes()
        binding = strict_json(binding_bytes)
        data = [p.read_bytes() for p in args.job_log]
        result = analyze('\n'.join(x.decode('utf-8') for x in data), frozen, args.run_id, args.workflow_sha, binding)
        validate_binding(binding, frozen, args.run_id, args.workflow_sha, args.control_root)
        result['job_log_inputs'] = [{'path': str(p), 'bytes': len(v), 'sha256': sha(v)} for p, v in zip(args.job_log, data)]
        result['official_binding_input'] = {'path': str(args.binding), 'bytes': len(binding_bytes), 'sha256': sha(binding_bytes)}
    except (ValueError, OSError, TypeError, KeyError) as error:
        result = {'analysis_status': 'INCOMPLETE', 'issues': [str(error)], 'run_id': args.run_id, 'workflow_sha': args.workflow_sha}
    result['analyzer_sha256'] = sha(Path(__file__).read_bytes())
    write_outputs(args.out, result)
    print(json.dumps({'analysis_status': result['analysis_status'], 'issues': result['issues']}))
    return int(result['analysis_status'] != 'COMPLETE_SCOPED_LOG_SCREENING')

if __name__ == '__main__':
    raise SystemExit(main())
