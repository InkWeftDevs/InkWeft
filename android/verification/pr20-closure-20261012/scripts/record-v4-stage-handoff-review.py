#!/usr/bin/env python3
"""Read saved evidence; write new scratch-only v4 stage records, never final approval."""
import datetime
import hashlib
import json
import pathlib
import re
import subprocess

E = pathlib.Path('/workspace/scratch/pr20-closure-evidence')
R = pathlib.Path('/workspace/InkWeft')
B = pathlib.Path('/workspace/scratch/inkweft-baseline-closure-a128bb07')
D = R / 'android/verification/pr20-closure-20261012'
OUT = ['closure-curated-manifest-v4-stage-hold.json', 'closure-privacy-review-v4-stage-hold.json',
       'closure-source-evidence-v4-stage-hold.json', 'closure-source-evidence-v4-stage-hold.md']
for name in OUT:
    if (E / name).exists():
        raise SystemExit('Refusing to overwrite ' + name)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def read(name):
    return json.loads((E / name).read_text())

def git(*args):
    return subprocess.check_output(['git', '-C', str(R), *args])

def record(path, archive=None, role=None):
    data = path.read_bytes()
    return {'source': str(path), 'sha256': sha(data), 'bytes': len(data),
            **({'suggested_archive_path': archive} if archive else {}),
            **({'role': role} if role else {})}

utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
status = 'DELIVERY_ON_HOLD_RASTER_REVIEW'
old = read('closure-curated-manifest-v4-completed.json')
extension = read('closure-curation-extension-baseline-build-history.json')
selected = {x['suggested_archive_path']: dict(x) for x in old['files'] + extension['new_files']}
previous_mismatches = []
for archive, item in selected.items():
    actual = record(pathlib.Path(item['source']), archive, item['role'])
    if actual['sha256'] != item['sha256']:
        previous_mismatches.append({'path': item['source'], 'expected': item['sha256'], 'actual': actual['sha256']})
    selected[archive] = actual

extra = [p.name for p in E.glob('baseline-final-*') if p.suffix in ('.json', '.log')]
extra += ['candidate-v4-finalrestore-apk-installation.json', 'performance-summary-input.json',
          'paired-performance-summary-new-format.json', 'final-source-commits.json',
          'final-committed-source-binding.json', 'remaining-acceptance-gates.md',
          'final-publication-doc-review.md']
extra += ['final-paired-' + area + '-review.' + suffix
          for area in ['library', 'history', 'raster'] for suffix in ['json', 'md']]
extra += ['closure-curated-manifest-v4-completed.json', 'closure-evidence-privacy-review-v4-completed.json',
          'closure-evidence-curation-v4-completed.md', 'closure-curation-extension-baseline-build-history.json',
          'closure-curation-extension-baseline-build-history.md', 'closure-privacy-extension-baseline-build-history.json']
repeat_phases = ['candidate-v4-raster-repeat1', 'baseline-final-raster-repeat1',
                 'baseline-final-raster-repeat2', 'candidate-v4-raster-repeat2']
for phase in repeat_phases:
    extra += [phase + suffix for suffix in ['-environment.json', '-environment-after.json',
              '-results.json', '-tile-local-setup.json', '-tile-local.json', '-tile-local.log']]
extra += ['baseline-final-raster-repeat-apk-installation.json',
          'candidate-v4-raster-repeat2-apk-installation.json', 'raster-regression-followup-plan.json',
          'raster-repeat-plan.json', 'raster-single-tile-regression-investigation.md',
          'raster-repeat-review.md', 'raster-repeat-review.json']
for name in sorted(set(extra)):
    archive = 'evidence/' + name
    if archive not in selected:
        selected[archive] = record(E / name, archive, 'completed v4/baseline stage evidence; not final delivery approval')
selected['scripts/record-v4-stage-handoff-review.py'] = record(
    pathlib.Path(__file__), 'scripts/record-v4-stage-handoff-review.py',
    'scratch-only source/evidence/privacy recorder; no device/build operations')
files = sorted(selected.values(), key=lambda x: x['suggested_archive_path'])

# Only literal selected payloads, README, and the already-reviewed PR draft are scanned.
publication = [record(D / 'README.md'), record(pathlib.Path('/workspace/scratch/inkweft-pr-body-final-draft.md'))]
patterns = {
    'private_key_pem': r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'github_token': r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\b',
    'aws_access_key': r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    'credential_url': r'https?://[^\s/\"\']{1,200}:[^\s/\"\']{1,200}@',
    'bearer_value': r'(?i)\bBearer\s+[A-Za-z0-9._~+/-]{20,}',
    'sensitive_assignment': r'(?i)\b(?:API_KEY|ACCESS_TOKEN|AUTH_TOKEN|SECRET_KEY|CLIENT_SECRET|AWS_SECRET_ACCESS_KEY|PASSWORD)\s*[=:]\s*[\"\']?[A-Za-z0-9+/=_-]{16,}',
    'jwt': r'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b',
    'personal_home_path': r'/(?:Users|home)/(?!agent(?:/|\b)|runner(?:/|\b)|root(?:/|\b))[^\s/]+/',
    'environment_dump_hint': r'(?m)^(?:AWS_SECRET_ACCESS_KEY|OPENAI_API_KEY|GITHUB_TOKEN|GH_TOKEN|AZURE_CLIENT_SECRET|DATABASE_URL|HTTPS_PROXY|HTTP_PROXY)=',
}
findings, prohibited = [], []
for item in files + publication:
    path = pathlib.Path(item['source'])
    if path.suffix.lower() in ['.apk', '.aar', '.db', '.sqlite', '.sqlite3', '.jks', '.keystore', '.pem', '.key']:
        prohibited.append(str(path))
        continue
    data = path.read_bytes()
    body = data.decode('utf-8')
    if '\0' in body:
        prohibited.append(str(path))
    for category, pattern in patterns.items():
        for match in re.finditer(pattern, body):
            findings.append({'source': str(path), 'category': category, 'line': body.count('\n', 0, match.start()) + 1})
privacy = {'utc': utc, 'status': status, 'scan_result': 'NO_PATTERN_HITS' if not findings and not prohibited else 'REVIEW_REQUIRED',
           'scanned_literal_files': len(files) + len(publication), 'patterns': list(patterns), 'findings': findings,
           'prohibited_payloads': prohibited, 'files': files, 'publication_payloads_not_automatically_archived': publication,
           'gradle_runner_literal': next(x for x in files if pathlib.Path(x['source']).name == 'run-inkweft-gradle'),
           'gradle_runner_review': 'Literal has only public tool paths and runtime proxy hostname/port extraction. No username/password extraction, literal credential, env dump, or xtrace. Script was not executed. Runtime environment values were not read.',
           'boundary': 'Exact selected literal bytes only; pattern review is not a universal guarantee of absence. No credentials, environment dump, real notes, DB, APK/AAR, keystore or full device logcat were collected.'}

binding = read('final-committed-source-binding.json')
commit = binding['source_commit']
tree = {}
for entry in git('ls-tree', '-rz', commit).split(b'\0'):
    if entry:
        meta, path = entry.split(b'\t', 1)
        tree[path.decode()] = meta.split()[2].decode()
source_checks = []
for label, root, filename in [('candidate-v4', R, 'candidate-v4-source-sha256.json'),
                               ('baseline-final', B, 'baseline-final-source-sha256.json')]:
    manifest = read(filename)
    mismatches, git_mismatches = [], []
    for path, expected in manifest['files'].items():
        data = (root / path).read_bytes()
        if sha(data) != expected:
            mismatches.append(path)
        if label == 'candidate-v4':
            blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
            if tree.get(path) != blob:
                git_mismatches.append(path)
    source_checks.append({'label': label, 'manifest': record(E / filename), 'checked_files': len(manifest['files']),
                          'working_tree_mismatches': mismatches, 'committed_git_blob_mismatches': git_mismatches if label == 'candidate-v4' else None})
baseline_full = read('baseline-final-source-manifest.json')
baseline_full_bad = [x['path'] for x in baseline_full['files'] if sha((B / x['path']).read_bytes()) != x['sha256']]
shared = []
for path in read('baseline-final-provenance.json')['strict_changed_path_allowlist']:
    a, b = sha((R / path).read_bytes()), sha((B / path).read_bytes())
    shared.append({'path': path, 'candidate_sha256': a, 'baseline_sha256': b, 'match': a == b})
commits = read('final-source-commits.json')
actual_commits = git('rev-list', '--reverse', commits['previous_head'] + '..' + commit).decode().splitlines()
debug = binding['debug_build_identity']
debug_data = (R / debug['generated_file']).read_bytes()
docker_tree = git('rev-parse', commit + ':backup-server').decode().strip()
prior_docker_tree = git('rev-parse', commits['previous_head'] + ':backup-server').decode().strip()

summary = read('paired-performance-summary-new-format.json')
probe_status = {}
for name, entry in summary['pairs'][0]['probes'].items():
    probe_status[name] = {side: {'status': entry[side]['status'], 'issues': entry[side]['issues'],
                                'writerSqlite': entry[side].get('writerSqlite')}
                          for side in ['baseline', 'candidate']}
    probe_status[name]['comparison'] = {'status': entry['comparison']['status'], 'issues': entry['comparison']['issues']}
env = []
for name in summary['environmentReferences']:
    d = read(name)
    env.append({'file': name, 'status': d['status'], 'comparison_sha256': d['comparison_sha256'],
                'errors': d['errors'], 'comparison_hash_recomputed_match': sha(json.dumps(d['comparison_values'], sort_keys=True, separators=(',', ':')).encode()) == d['comparison_sha256']})

artifacts = {}
for phase in ['baseline-final', 'candidate-v4']:
    a = read(phase + '-artifacts.json')
    sig = read(phase + '-signatures.json')
    continuity = read(phase + '-performance-artifact-continuity.json')
    continuity_files = continuity.get('files', continuity.get('artifacts', []))
    install = read(phase + '-apk-installation.json')
    hashset = {x['sha256'] for x in a}
    results = read(phase + '-performance-results.json')
    log_checks = []
    for result in results:
        stem = phase + '-performance-' + result['label']
        leaf = read(stem + '.json')
        log = (E / (stem + '.log')).read_text()
        log_checks.append({'label': result['label'], 'summary_matches_leaf': leaf == {k: v for k, v in result.items() if k != 'label'},
                           'raw_ok_marker': bool(re.search(r'OK \(' + str(result['executed']) + r' tests?\)', log)),
                           'tests': result['executed'], 'passed': result['passed']})
    artifacts[phase] = {'build': read(phase + '-build.json'), 'recorded_apks': len(a),
                        'signatures_all_pass': len(sig) == 3 and all(x.get('status', 'PASS') == 'PASS' and x['exit_code'] == 0 and 'Verifies' in x['stdout'] for x in sig),
                        'continuity_status': continuity['status'],
                        'continuity_all_match': len(continuity_files) == 3 and all(x.get('matches', True) and x['sha256'] == x.get('expected', x.get('expected_sha256')) and x['sha256'] in hashset for x in continuity_files),
                        'initial_install_all_match': len(install['artifacts']) == 3 and all(x['exit_code'] == 0 and x['sha256'] in hashset for x in install['artifacts']),
                        'native_mupdf_abi_records': a[0]['mupdf_native'], 'performance_raw_log_checks': log_checks,
                        'performance_passed_tests': sum(x['executed'] for x in results if x['passed']),
                        'performance_failed_selections': [x['label'] for x in results if not x['passed']]}
restore = read('candidate-v4-finalrestore-apk-installation.json')
v4hashes = {x['sha256'] for x in read('candidate-v4-artifacts.json')}
repeat_checks, repeat_env = [], []
for phase in repeat_phases:
    result = read(phase + '-results.json')[0]
    leaf = read(phase + '-tile-local.json')
    body = (E / (phase + '-tile-local.log')).read_text()
    rows = [json.loads(line.split('TILE_LOCAL_EVIDENCE ', 1)[1]) for line in body.splitlines() if 'TILE_LOCAL_EVIDENCE ' in line]
    pan = [json.loads(line.split('TILE_PAN_EVIDENCE ', 1)[1]) for line in body.splitlines() if 'TILE_PAN_EVIDENCE ' in line]
    install_name = ('baseline-final-raster-repeat-apk-installation.json' if phase.startswith('baseline') else
                    ('candidate-v4-finalrestore-apk-installation.json' if phase.endswith('repeat1') else
                     'candidate-v4-raster-repeat2-apk-installation.json'))
    install_receipt = read(install_name)
    artifact_name = 'baseline-final-artifacts.json' if phase.startswith('baseline') else 'candidate-v4-artifacts.json'
    expected_hashes = {x['sha256'] for x in read(artifact_name)}
    repeat_checks.append({'phase': phase, 'results_receipt': phase + '-results.json',
                          'passed': result['passed'], 'executed': result['executed'], 'failures': result['failures'],
                          'summary_matches_leaf': leaf == {k: v for k, v in result.items() if k != 'label'},
                          'raw_ok_two_tests': 'OK (2 tests)' in body, 'pan_rows': pan, 'local_rows': rows,
                          'local_layers': [row['layers'] for row in rows],
                          'all_local_pixels_equal': all(row['pixelsEqual'] for row in rows),
                          'pre_run_installation_receipt': install_name, 'frozen_artifacts': artifact_name,
                          'installation_hashes_match_frozen_artifacts': len(install_receipt['artifacts']) == 3 and all(x['sha256'] in expected_hashes and x['exit_code'] == 0 for x in install_receipt['artifacts'])})
    for suffix in ['-environment.json', '-environment-after.json']:
        name = phase + suffix
        d = read(name)
        repeat_env.append({'file': name, 'status': d['status'], 'comparison_sha256': d['comparison_sha256'],
                           'errors': d['errors'], 'comparison_hash_recomputed_match': sha(json.dumps(d['comparison_values'], sort_keys=True, separators=(',', ':')).encode()) == d['comparison_sha256']})
original_append = {}
for side in ['baseline-final', 'candidate-v4']:
    body = (E / (side + '-performance-tile-local.log')).read_text()
    original_append[side] = [json.loads(line.split('TILE_LOCAL_EVIDENCE ', 1)[1]) for line in body.splitlines() if 'TILE_LOCAL_EVIDENCE ' in line]

readme = (D / 'README.md').read_text()
references = []
for target in re.findall(r'\]\(([^)]+)\)', readme):
    if re.match(r'[a-z]+://', target):
        continue
    clean = target.split('#')[0]
    resolved = (D / clean).resolve()
    source = pathlib.Path(selected[clean]['source']) if clean in selected else resolved
    references.append({'target': target, 'exists_in_repository': resolved.exists(),
                       'curated_target': clean in selected, 'source_exists': source.exists()})
bare = sorted(set(re.findall(r'`([^`\n]+\.(?:json|md|log|patch|py))`', readme)))
bare_refs = [{'reference': name, 'curated': any(pathlib.Path(x['source']).name == pathlib.Path(name).name for x in files)} for name in bare]
repo_state = []
for item in files:
    destination = D / item['suggested_archive_path']
    repo_state.append({'archive_path': item['suggested_archive_path'],
                       'state': 'ABSENT_COPY_NOT_AUTHORIZED_IN_THIS_TASK' if not destination.exists() else
                       ('MATCH' if sha(destination.read_bytes()) == item['sha256'] else 'DIFFERENT_BYTES_REVIEW_REQUIRED')})
held_reason = 'Parent paused final delivery after the v4 single-layer local append observation regressed about 4.5x. Four existing-APK repeat runs are complete; independent hotspot/minimal-change review remains pending. No final manifest freeze or acceptance closure is performed.'
verification = {'utc': utc, 'status': status, 'delivery_closed': False, 'reason': held_reason,
                'source_commit': commit, 'observed_head': git('rev-parse', 'HEAD').decode().strip(),
                'source_checks': source_checks, 'baseline_full_manifest_files_checked': len(baseline_full['files']),
                'baseline_full_manifest_mismatches': baseline_full_bad, 'four_shared_files': shared,
                'source_commits_match': actual_commits == [x['sha'] for x in commits['commits']],
                'binding_manifest_hash_match': sha((E / binding['frozen_source_manifest']).read_bytes()) == binding['frozen_source_manifest_sha256'],
                'debug_metadata': {'generated_file_sha256_match': sha(debug_data) == debug['file_sha256'],
                                   'selected_fields_present': all(x in debug_data.decode() for x in debug['selected_fields']),
                                   'meaning': debug['meaning']},
                'docker_read_only_git_tree_check': {'source_commit_tree': docker_tree, 'previous_head_tree': prior_docker_tree,
                                                   'matches_binding_and_previous': docker_tree == binding['docker_tree'] == prior_docker_tree},
                'previous_184_payload_hash_mismatches': previous_mismatches,
                'paired_summary_input_sha_match': summary['inputManifestSha256'] == sha((E / 'performance-summary-input.json').read_bytes()),
                'probe_status': probe_status, 'environments': env,
                'four_recorded_environment_hashes_equal': len({x['comparison_sha256'] for x in env}) == 1 and len(env) == 4,
                'original_append_observations_preserved': original_append,
                'repeat_checks': repeat_checks, 'repeat_environments': repeat_env,
                'eight_repeat_environment_hashes_equal_original_pair': len(repeat_env) == 8 and all(x['comparison_sha256'] == env[0]['comparison_sha256'] for x in repeat_env),
                'repeat_interpretation': 'Original single-layer ~4.5x observation is retained. Same candidate v4 APK repeat1 was ~4.177 s and repeat2 ~1.612 s. Neither faster samples nor eight matching configuration hashes establish a resolved regression, a unique hotspot, or stable statistical behavior.',
                'source_artifact_run_receipt_checks': artifacts,
                'candidate_v4_final_restore_receipt': {'recorded_installs': len(restore['artifacts']),
                    'all_match_original_v4': len(restore['artifacts']) == 3 and all(x['exit_code'] == 0 and x['sha256'] in v4hashes and 'Success' in x['stdout'] for x in restore['artifacts'])},
                'readme_markdown_references': references, 'readme_bare_filename_references': bare_refs,
                'repository_copy_state': repo_state,
                'limitations': ['Saved receipts and recorded environment settings only; no new device query or native/build execution.',
                               'APK binaries not recopied or rehashed here; this audit checks joins among saved artifact/signature/install/after-run continuity receipts.',
                               'Synthetic fixtures use generated IDs/timestamps: same fixture code and scale, not byte-identical databases.',
                               'Debug APK commit fields remain local-unknown; external manifests bind tested bytes to source. This is not binary reproducibility or embedded final Git SHA.',
                               'Recorded environment equality does not prove identical transient CPU scheduling, load, caches or performance.',
                               'Original 20k search 10s, backup cancellation 15s and dense frame 30s acceptance gaps remain; v4 local append regression now additionally pending investigation.',
                               'CI, physical-device performance and hardware-window screenshots remain unverified.'],
                'actions_not_performed': ['final delivery freeze', 'build', 'ADB', 'native execution', 'Docker operation', 'repository write', 'commit', 'push', 'remote PR mutation']}
manifest = {'utc': utc, 'status': status, 'delivery_closed': False, 'reason': held_reason,
            'previous_manifests': [record(E / 'closure-curated-manifest-v4-completed.json'), record(E / 'closure-curation-extension-baseline-build-history.json')],
            'files': files, 'selected_files': len(files), 'selected_bytes': sum(x['bytes'] for x in files),
            'retained_failure_receipts': old['retained_failure_receipts'],
            'privacy_review': 'closure-privacy-review-v4-stage-hold.json',
            'source_evidence_review': 'closure-source-evidence-v4-stage-hold.json',
            'explicit_exclusions': ['APK/AAR/native binary/DB/real notes/keystores/credentials/environment dumps/full logcat',
                                    'active or future raster hotspot investigation evidence beyond the four completed existing-APK repeats',
                                    'automatic inclusion of future modified sources, APKs or reviews'],
            'future_rule': 'This is an immutable record of the completed v4 stage, not the final delivery manifest. Final curation must wait for parent notice and bind any later source/APK/native results independently.'}
# Ensure no input changed while this short, read-only audit ran.
changed_during = [x['source'] for x in files + publication if sha(pathlib.Path(x['source']).read_bytes()) != x['sha256']]
verification['literal_payloads_changed_during_audit'] = changed_during
if changed_during or previous_mismatches or findings or prohibited:
    raise SystemExit('Audit requires follow-up: changed payloads, historical mismatch, or privacy finding; no records written')
outputs = dict(zip(OUT[:3], [manifest, privacy, verification]))
for name, content in outputs.items():
    with (E / name).open('x') as f:
        json.dump(content, f, ensure_ascii=False, indent=2)
        f.write('\n')
missing = [x['archive_path'] for x in repo_state if x['state'].startswith('ABSENT')]
md = f'''# v4 stage evidence audit — delivery on hold

Recorded {utc}. Final delivery is paused pending independent hotspot/minimal-change review of the roughly 4.5x single-layer local-append regression. Four existing-APK repeats are now complete. This is a historical v4-stage record; no final manifest was frozen and no acceptance gate was closed.

- Selected {len(files)} completed literal evidence/script payloads, including the previous 172 + 12 files, remaining baseline native/environment/artifact records, paired summaries, source bindings and final stage reviews. All 184 earlier payload hashes remain unchanged. Historical failures remain included.
- Scanned {len(files) + len(publication)} exact literal payloads with nine sensitive-data patterns: no hits, prohibited binaries or environment dumps. The Gradle runner literal remains safe at the recorded SHA; it was not executed and runtime proxy/environment values were not read.
- Candidate v4 744/744 frozen inputs match working-tree bytes and source commit `{commit}`; baseline 734/734 build inputs and all 1894 full-manifest source files match. All four shared factory/probe paths match. Docker Git tree remains unchanged.
- Both saved performance runs passed five tests across four selections. Each probe has `VERIFIED_NATIVE_RUN` on both sides and `PAIRED_RECORDED_ENVIRONMENT` with no parser issues. All four recorded environment fingerprints match. This does not establish identical transient scheduling or physical-device speed.
- Four additional TileLocalRedrawTest runs each passed 2/2; all eight new environment fingerprints match the original four. Original single-layer 1.436→6.428 s is retained, alongside same candidate APK repeat1 4.177 s and repeat2 1.612 s. All raw logs, receipts, setup, environment and installation records, plans and the source investigation are included. These observations do not establish resolution or a unique cause.
- Both sides' three APK artifact/signature/install/after-run continuity receipts join correctly; v4 final-restore receipts also match. No APK was installed, rebuilt, copied or freshly read from the device in this audit.
- Debug metadata remains `local-unknown`, `FORMAL_BUILD=false`. External source and APK manifests bind tested bytes; the APK does not embed the later source commit.
- README local links are mapped in the JSON. {len(missing)} selected files are not yet in the repository delivery directory; copying remains the parent's task, after the hold is resolved. This audit does not edit README or repository files.

Original search 10s, backup cancellation 15s and dense frame 30s gates remain open. All observed latency regressions are preserved. Future hotspot diagnostics or code changes are excluded until completed and separately bound. Any later production, probe or artifact change needs a new binding and updated final manifest.

Artifacts: `closure-curated-manifest-v4-stage-hold.json`, `closure-privacy-review-v4-stage-hold.json`, and `closure-source-evidence-v4-stage-hold.json`. Pattern scanning is limited to the exact selected bytes, not a universal absence guarantee. Synthetic fixtures share code/scale, not database bytes.
'''
with (E / OUT[3]).open('x') as f:
    f.write(md)
print(json.dumps({'status': status, 'selected_files': len(files), 'privacy_scanned': len(files) + len(publication),
                  'previous_hash_mismatches': previous_mismatches, 'source_checks': source_checks,
                  'baseline_full_mismatches': baseline_full_bad, 'missing_repo_copies': len(missing),
                  'outputs': [record(E / name) for name in OUT]}, indent=2))
