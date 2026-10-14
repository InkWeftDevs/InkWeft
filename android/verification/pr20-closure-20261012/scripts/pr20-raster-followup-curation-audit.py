#!/usr/bin/env python3
"""Draft preview by default; audit explicit text evidence only after root's native-finished notice."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re

SCRATCH = pathlib.Path('/workspace/scratch')
EVIDENCE = SCRATCH / 'pr20-closure-evidence'
DEFAULT_PLAN = EVIDENCE / 'raster-followup-curation-plan-draft.json'
PATTERNS = {
    'private_key': r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'github_token': r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\b',
    'aws_access_key': r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    'credential_url': r'https?://[^\s/\"\']{1,200}:[^\s/\"\']{1,200}@',
    'bearer_value': r'(?i)\bBearer\s+[A-Za-z0-9._~+/-]{20,}',
    'sensitive_assignment': r'(?i)\b(?:API_KEY|ACCESS_TOKEN|AUTH_TOKEN|SECRET_KEY|CLIENT_SECRET|AWS_SECRET_ACCESS_KEY|PASSWORD)\s*[=:]\s*[\"\']?[A-Za-z0-9+/=_-]{16,}',
    'jwt': r'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b',
    'personal_home': r'/(?:Users|home)/(?!agent(?:/|\b)|runner(?:/|\b)|root(?:/|\b))[^\s/]+/',
    'environment_dump': r'(?m)^(?:AWS_SECRET_ACCESS_KEY|OPENAI_API_KEY|GITHUB_TOKEN|GH_TOKEN|AZURE_CLIENT_SECRET|DATABASE_URL|HTTPS_PROXY|HTTP_PROXY)=',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def literal(path):
    path = pathlib.Path(path)
    if path.name == 'raster-diagnostic-v2-filtered-gc-observation.json':
        raise ValueError('Excluded cross-history GC file must not be read or curated')
    if not path.resolve().is_relative_to(SCRATCH) or path.is_symlink():
        raise ValueError('Only regular scratch evidence is allowed: ' + str(path))
    if path.suffix not in ['.json', '.md', '.log', '.patch', '.py', '.java'] and path.name != 'run-inkweft-gradle':
        raise ValueError('Prohibited payload extension: ' + str(path))
    before = path.stat()
    if before.st_size > 10 * 1024 * 1024:
        raise ValueError('Evidence exceeds the text review size limit: ' + str(path))
    data = path.read_bytes()
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError('Evidence changed while reading: ' + str(path))
    text = data.decode('utf-8')
    if '\0' in text:
        raise ValueError('Binary payload is not allowed: ' + str(path))
    return data, text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=pathlib.Path, default=DEFAULT_PLAN)
    parser.add_argument('--audit-after-root-native-finished', action='store_true')
    parser.add_argument('--output-prefix', default='raster-followup-completed-stage')
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    if not args.audit_after_root_native_finished:
        print(json.dumps(dict(status='DRAFT_PREVIEW_ONLY_NO_HASH_OR_AUDIT', plan=str(args.plan),
                              extension_entries=len(plan['entries']), audit_ready=plan['audit_ready'],
                              unresolved_slots=plan['unresolved_slots']), indent=2))
        return 0
    if not plan['audit_ready'] or plan['unresolved_slots'] or any(x['completion'] != 'RECORDED_COMPLETE' for x in plan['entries']):
        parser.error('Wait for root completion notice, then resolve explicit pending filenames/completion states in a reviewed plan')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,100}', args.output_prefix):
        parser.error('Invalid output prefix')
    outputs = [EVIDENCE / (args.output_prefix + suffix) for suffix in ['-manifest.json', '-privacy.json', '-audit.json']]
    if any(path.exists() for path in outputs):
        parser.error('Refusing to overwrite existing evidence')

    base_data, base_text = literal(plan['base_manifest'])
    if digest(base_data) != plan['base_manifest_sha256']:
        raise ValueError('Historical 247-entry manifest changed; retain and investigate before extending it')
    base = json.loads(base_text)
    selected = {x['suggested_archive_path']: dict(x, lineage='historical_c60_v4_stage') for x in base['files']}
    for item in plan['entries']:
        archive = item['archive_path']
        if not re.fullmatch(r'(?:evidence|scripts)/[A-Za-z0-9._-]+', archive):
            raise ValueError('Unsafe archive path: ' + archive)
        if archive in selected and selected[archive]['source'] != item['source']:
            raise ValueError('Conflicting explicit archive entry: ' + archive)
        if archive not in selected:
            selected[archive] = dict(item, suggested_archive_path=archive)

    files, privacy_hits, historical_changes, texts = [], [], [], {}
    for archive, item in sorted(selected.items()):
        data, body = literal(item['source'])
        actual = digest(data)
        if 'sha256' in item and item['sha256'] != actual:
            historical_changes.append(dict(source=item['source'], expected=item['sha256'], actual=actual))
        files.append(dict(source=item['source'], suggested_archive_path=archive, sha256=actual,
                          bytes=len(data), lineage=item['lineage']))
        texts[pathlib.Path(item['source']).name] = body
        for category, pattern in PATTERNS.items():
            for match in re.finditer(pattern, body):
                privacy_hits.append(dict(source=item['source'], category=category, line=body.count('\n', 0, match.start()) + 1))

    def obj(name):
        return json.loads(texts[name])

    source_deltas = []
    for delta in plan['source_deltas']:
        old, new = obj(delta['old'])['files'], obj(delta['new'])['files']
        changed = sorted(path for path in old.keys() | new.keys() if old.get(path) != new.get(path))
        source_deltas.append(dict(**delta, changed_paths=changed, matches_exact_allowlist=changed == sorted(delta['allowed_changed_paths'])))
    v1_build = obj('candidate-raster-diagnostic-v1-build.json')
    host = obj('raster-hex-v1-host-compatibility.json')
    native = []
    for run in plan['native_runs']:
        phase = run['phase']
        receipt = obj(phase + '-targeted.json')
        log = texts[phase + '-targeted.log']
        ok = re.findall(r'^OK \((\d+) tests?\)\s*$', log, re.M)
        failed = re.findall(r'Tests run: (\d+),\s+Failures: (\d+)', log)
        raw_pass = (receipt.get('exit_code') == 0 and len(ok) == 1 and int(ok[0]) == run['expected']
                    and 'FAILURES!!!' not in log and 'RUNNER_TIMEOUT' not in log)
        raw_executed = int(ok[0]) if len(ok) == 1 else (int(failed[-1][0]) if failed else None)
        raw_failures = int(failed[-1][1]) if failed else (0 if raw_pass else None)
        before, after = obj(run['environment_before']), obj(run['environment_after'])
        artifacts = obj(run['artifact_receipt'])
        native.append(dict(phase=phase, cohort=run.get('cohort', 'diagnostic_v2_prior_pair'),
                           expected=run['expected'], receipt_passed=receipt.get('passed'), raw_pass=raw_pass,
                           raw_and_receipt_agree=raw_pass == receipt.get('passed'),
                           raw_executed=raw_executed, raw_failures=raw_failures,
                           raw_counts_match_receipt=raw_executed == receipt.get('executed') and raw_failures == receipt.get('failures'),
                           raw_log_hash_matches_receipt=digest(log.encode()) == receipt.get('log_sha256'),
                           diagnostics_requested=run['diagnostics'], diagnostics_recorded=receipt.get('diagnostics'),
                           environment_before=before['comparison_sha256'], environment_after=after['comparison_sha256'],
                           recorded_environments_equal=before['comparison_sha256'] == after['comparison_sha256'],
                           environment_hashes_recomputed=all(digest(json.dumps(x['comparison_values'], sort_keys=True, separators=(',', ':')).encode()) == x['comparison_sha256'] for x in [before, after]),
                           artifact_receipt=run['artifact_receipt'], artifact_status=artifacts['status'],
                           artifact_hashes=[dict(role=x['role'], sha256=x['sha256']) for x in artifacts['artifacts']],
                           scope='Saved native receipts only; diagnostic-on observations retain overhead and do not replace default-off results.'))
    cohorts = {}
    for run in native:
        cohort = cohorts.setdefault(run['cohort'], dict(calls=0, executed=0, passed=0, failures=0, unknown_counts=[]))
        cohort['calls'] += 1
        if run['raw_executed'] is None or run['raw_failures'] is None:
            cohort['unknown_counts'].append(run['phase'])
        else:
            cohort['executed'] += run['raw_executed']
            cohort['failures'] += run['raw_failures']
            cohort['passed'] += run['raw_executed'] - run['raw_failures']
    strict = obj('candidate-raster-hex-v1-off-pair-strict-parser-receipt.json')
    strict_stdout = obj(strict['stdout_path'])
    strict_preserved = (strict['exit_code'] == 2 and strict['parser_status'] == strict_stdout['status'] == 'INCOMPLETE'
                        and not strict['summary_json_exists'] and not strict['summary_md_exists']
                        and digest(texts[strict['stdout_path']].encode()) == strict['stdout_sha256']
                        and digest(texts[strict['stderr_path']].encode()) == strict['stderr_sha256'])
    changed_during_audit = [x['source'] for x in files if digest(literal(x['source'])[0]) != x['sha256']]
    issues = []
    if historical_changes: issues.append('Historical payload hashes changed')
    if privacy_hits: issues.append('Sensitive-data pattern matches require review; matched values are not printed')
    if changed_during_audit: issues.append('Payloads changed during audit')
    if any(not x['matches_exact_allowlist'] for x in source_deltas): issues.append('Source manifest delta differs from its exact path allowlist')
    if v1_build['exit_code'] == 0: issues.append('Expected diagnostic-v1 failed build record was not preserved')
    if any(not x['raw_and_receipt_agree'] or not x['raw_counts_match_receipt'] or not x['raw_log_hash_matches_receipt']
           or x['diagnostics_recorded'] != x['diagnostics_requested'] for x in native):
        issues.append('Native receipt/log or diagnostic-mode mismatch')
    if any(not x['recorded_environments_equal'] or not x['environment_hashes_recomputed'] for x in native):
        issues.append('Recorded native environment mismatch')
    if not strict_preserved: issues.append('Strict INCOMPLETE parser result was not preserved exactly')
    utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
    status = 'EVIDENCE_REVIEW_REQUIRED' if issues else 'COMPLETED_EVIDENCE_AUDIT_ONLY_NOT_ACCEPTANCE_CLOSURE'
    manifest = dict(utc=utc, status=status, delivery_closed=False, base_manifest=plan['base_manifest'],
                    base_manifest_sha256=plan['base_manifest_sha256'], files=files, selected_files=len(files),
                    source_lineage_policy='Old c60/v4 is bound by unchanged historical manifests and reconstruction records. No current checkout is compared against old manifests. New diagnostic/hex source maps are compared separately.',
                    exclusions=['APK/AAR/DB/native binaries/keys/full logcat/environment dumps',
                                'raster-diagnostic-v2-filtered-gc-observation.json: 152 cross-history records; excluded in full',
                                'unlisted active or future evidence'], future_pending=plan.get('future_pending', []))
    privacy = dict(utc=utc, status=status, exact_payloads=files, patterns=list(PATTERNS), findings=privacy_hits,
                   boundary='Literal selected text only; not a universal privacy guarantee. No environment or credential values were collected.')
    audit = dict(utc=utc, status=status, issues=issues, old_source_commit=plan['old_source_commit'],
                 historical_payload_changes=historical_changes, payloads_changed_during_audit=changed_during_audit,
                 source_manifest_deltas=source_deltas, native_receipt_checks=native, native_cohort_totals=cohorts,
                 strict_parser_incomplete_preserved=strict_preserved,
                 incomplete_off_pair_policy='Coff2 local rows remain in raw ledger only; no completed paired statistics or substitution. Process absence is not normal-release assertion success.',
                 diagnostic_v1_build_exit_code=v1_build['exit_code'],
                 hex_host_checks=dict(status=host['status'], checks=host['result']['checks'], scope=host['result']['scope'], native_performance='NOT_ESTABLISHED_BY_HOST_CHECKS'),
                 cross_history_gc='Excluded entirely; file was not read by this audit. Cgroup deltas alone are not causal proof.',
                 preserved_rules=plan['preservation_rules'], actions_not_performed=plan['audit_actions_not_allowed'])
    for path, value in zip(outputs, [manifest, privacy, audit]):
        with path.open('x') as stream:
            json.dump(value, stream, indent=2)
            stream.write('\n')
    print(json.dumps(dict(status=status, files=len(files), issues=issues, outputs=[str(x) for x in outputs]), indent=2))
    return 1 if issues else 0


if __name__ == '__main__':
    raise SystemExit(main())
