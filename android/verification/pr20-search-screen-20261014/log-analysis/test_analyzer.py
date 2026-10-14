#!/usr/bin/env python3
"""Offline synthetic envelope validation ONLY; not a new Android result."""
from copy import deepcopy
import json
from pathlib import Path
import re

import analyze_logs as a

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
OLD = Path('/workspace/scratch/pr20-candidate3-results-20261014')
RUN = '99999999999'
WORKFLOW = 'f' * 40

def synthetic(frozen):
    # Payloads retain real previous 45b/d66 measurements. Envelope labels are
    # deliberately synthetic d66/a9cc bindings to exercise the next protocol.
    # This transformation is a parser fixture, never candidate performance.
    lines = []
    def emit(kind, value):
        lines.append(a.PREFIX + kind + ' ' + a.compact(value).decode())
    start = 1700000000
    emit('CLOCK', dict(status='PASS', scope='SCREENING_ONLY', anchor_kind='runner_first_step', budget_anchor_epoch=start,
                       first_step_observed_epoch=start+.1, actual_job_started_epoch=None, deadlines_minutes=[30,35,50,55,60],
                       deadline_epochs={k:start+m*60 for k,m in [('setup',30),('correctness',35),('common',50),('work',55)]}))
    old = json.loads((OLD/'analysis/analysis.json').read_text())
    final_methods = []
    for step in frozen['plan']['steps']:
        identity, ordinal = step['identity'], step['ordinal']
        loghash = a.sha(f'synthetic-method-{ordinal}'.encode())
        if step['phase'] == 'common-comparison':
            old_method = next(m for m in old['methods'] if '.KvmLibraryPairProbe#' in m['selection'] and m['identity'] == identity and m['seed'] == step['seed'])
            log = (OLD/'raw'/f"method-{old_method['ordinal']:02}-{identity}-instrumentation.log").read_text()
            payloads = [(m, p) for m,p in re.findall(r'^INSTRUMENTATION_STATUS: stream=((?:KVM|LIBRARY|INDEX)_[A-Z_]+) (\{.*\})$', log, re.M)]
            records = [{'marker':m,'data':json.loads(p)} for m,p in payloads]
            require = a.require
            require(len(records) == 12, 'Synthetic old marker count')
            apk = a.sha(('synthetic-apk-'+identity).encode())
            context = dict(scope='SCREENING_ONLY', run_id=RUN, run_attempt='1', workflow_sha=WORKFLOW,
                source_commit=frozen['spec']['identities'][identity]['git'], source_spec_sha256=frozen['hashes']['source_spec_sha256'],
                method_plan_sha256=frozen['hashes']['method_plan_sha256'], probe_sha256=frozen['contract']['fixed_probe_sha256'],
                source_manifest_sha256=frozen['spec']['identities'][identity]['canonical_manifest_sha256'], apk_sha256=apk,
                signer_sha256=['1'*64], apk_receipt_sha256=a.sha(('synthetic-receipt-'+identity).encode()), installed_apk_sha256=apk,
                install_log_sha256=a.sha(('synthetic-install-'+step['install_group']).encode()), environment_sha256='2'*64,
                ordinal=ordinal, identity=identity, seed=step['seed'], install_group=step['install_group'], selection=step['selection'],
                raw_log_sha256=loghash, records_sha256=a.sha((json.dumps(records,ensure_ascii=False,indent=2)+'\n').encode()),
                junit_status='PASS', adb_exit=0, process_exit_outcome='NATURAL_EXIT')
            emit('CONTEXT', context)
            ordered = []
            for sequence,(marker,payload) in enumerate(payloads):
                record = dict(ordinal=ordinal, identity=identity, sequence=sequence, marker=marker, payload_bytes=len(payload.encode()),
                              payload_sha256=a.sha(payload.encode()), original_json=payload)
                emit('RECORD',record)
                ordered.append({k:record[k] for k in ['sequence','marker','payload_bytes','payload_sha256']})
            emit('END',dict(ordinal=ordinal,identity=identity,count=12,ordered_payloads=ordered,ordered_payloads_sha256=a.sha(a.compact(ordered))))
        method = {k:step[k] for k in ['ordinal','phase','identity','selection']}
        method.update(status='PASS',junit_status='PASS',adb_exit=0,process_exit_outcome='NATURAL_EXIT',error=None)
        emit('METHOD',method)
        final_methods.append({k:step[k] for k in ['ordinal','identity','phase','selection','seed']} |
                             {k:method[k] for k in ['status','junit_status','adb_exit','process_exit_outcome']} | {'log_sha256':loghash})
    clean = dict(emulator='STOPPED_CONFIRMED',command_groups='STOPPED_CONFIRMED',acl='RESTORED_EXACT',error_count=0,
                 before_sha256='3'*64,after_sha256='3'*64,exact_match=True)
    emit('FINAL',dict(scope='SCREENING_ONLY',run_id=RUN,workflow_sha=WORKFLOW,input_hashes=frozen['hashes'],
        status='EXECUTION_COMPLETE_ANALYSIS_PENDING',cleanup_verified=True,cleanup={'trap':clean,'always':clean},methods=final_methods,
        install_groups={s['install_group']:{'identity':s['identity'],'status':'PASS'} for s in frozen['plan']['steps']},
        device_before_sha256='2'*64,device_after_sha256='2'*64,packaging_complete=True,missing_files=[],manifest_sha256='4'*64,summary_sha256='5'*64))
    return lines, old

def main():
    frozen = a.frozen_inputs(ROOT/'frozen-readiness')
    binding = dict(scope='SCREENING_ONLY',run_id=RUN,job_id='99999999998',run_attempt='1',workflow_sha=WORKFLOW,
                   run_status='completed',run_conclusion='success',job_status='completed',job_conclusion='success',
                   input_hashes=frozen['hashes'],control_hashes={p:'7'*64 for p in
                       ['ci/pr20-search-screen/run.py','ci/pr20-search-screen/package.py','ci/pr20-search-screen/cleanup.py',
                        '.github/workflows/pr20-search-screen.yml']})
    lines, old = synthetic(frozen)
    text = '\n'.join(lines)
    result = a.analyze(text,frozen,RUN,WORKFLOW,binding)
    assert result['analysis_status'] == 'COMPLETE_SCOPED_LOG_SCREENING',result['issues']
    # Compare all 36 pairs, 9 classifications and all480 raw ordered values to the
    # previous immutable analyzer output, without calling that analyzer again.
    for c in result['comparisons']:
        prior = next(x for x in old['comparisons'] if x['comparison']=='C0->C1' and x['metric']==c['metric'])
        for k in ['pairs','screening_status','median_delta_ms','median_relative_change']:
            assert c[k] == prior[k],(c['metric'],k)
    for r in result['raw_saves']:
        prior = next(x for x in old['raw_saves'] if x['identity']==r['identity'] and x['seed']==r['seed'] and x['scenario']==r['scenario'] and x['sample']==r['sample'])
        for k in ['elapsed_ms','start_ns','end_ns','committed','committed_revision','background_overlap','wholly_within_background']:
            assert r[k]==prior[k],k
    for d in result['save_distributions']:
        prior = next(x for x in old['save_distributions'] if x['identity']==d['identity'] and x['seed']==d['seed'] and x['scenario']==d['scenario'])
        for k in ['p50','p95','max','overlapping','exposed_quantiles','exposure_status']:
            assert d[k]==prior[k],k
    for w in result['warm_comparisons']:
        prior = next(x for x in old['warm_comparisons'] if x['comparison']=='C0->C1' and x['seed']==w['seed'])
        for k,v in w.items():assert prior[k]==v,k
    parsed = [(line.split(' ',1)[0][len(a.PREFIX):],json.loads(line.split(' ',1)[1])) for line in lines]
    first_record = next(i for i,(kind,_) in enumerate(parsed) if kind=='RECORD')
    first_context = first_record-1
    first_end = next(i for i,(kind,_) in enumerate(parsed) if kind=='END')
    def change(index,key,value):
        copy=deepcopy(parsed);copy[index][1][key]=value
        return [a.PREFIX+k+' '+a.compact(v).decode() for k,v in copy]
    faults = {
        'missing-record':lines[:first_record]+lines[first_record+1:],
        'duplicate-record':lines[:first_record]+[lines[first_record]]+lines[first_record:],
        'payload-hash-wrong':change(first_record,'payload_sha256','0'*64),
        'payload-bytes-wrong':change(first_record,'payload_bytes',1),
        'record-order-wrong':change(first_record,'sequence',1),
        'footer-hash-wrong':change(first_end,'ordered_payloads_sha256','0'*64),
        'missing-footer':lines[:first_end]+lines[first_end+1:],
        'unknown-marker':change(first_record,'marker','LIBRARY_SECRET'),
        'source-commit-wrong':change(first_context,'source_commit','0'*40),
        'source-manifest-wrong':change(first_context,'source_manifest_sha256','0'*64),
        'plan-hash-wrong':change(first_context,'method_plan_sha256','0'*64),
        'probe-hash-wrong':change(first_context,'probe_sha256','0'*64),
        'installed-hash-wrong':change(first_context,'installed_apk_sha256','0'*64),
        'records-hash-wrong':change(first_context,'records_sha256','0'*64),
        'method-status-fail':change(1,'status','FAIL'),
        'method-isolation-fail':change(1,'process_exit_outcome','UNKNOWN'),
        'missing-method':lines[:1]+lines[2:],
        'missing-clock':lines[1:],
        'missing-final':lines[:-1],
        'duplicate-final':lines+[lines[-1]],
        'unknown-receipt':lines+[a.PREFIX+'WHATEVER {}'],
        'packaging-incomplete':change(len(parsed)-1,'packaging_complete',False),
        'packaging-missing-file':change(len(parsed)-1,'missing_files',['method-18-C0-instrumentation.log']),
        'cleanup-unverified':change(len(parsed)-1,'cleanup_verified',False),
        'device-changed':change(len(parsed)-1,'device_after_sha256','0'*64),
    }
    # An otherwise correctly hashed payload still rejects unknown keys/arrays/NaN.
    for name,payload in [('unknown-payload-key','{"unexpected":1}'),('array-payload','[]'),
                         ('duplicate-key','{"label":"library-before","label":"library-before"}'),
                         ('nonfinite','{"seed":NaN}'),('truncated-payload','{"label":')]:
        copy=deepcopy(parsed);v=copy[first_record][1];v.update(original_json=payload,payload_bytes=len(payload.encode()),payload_sha256=a.sha(payload.encode()))
        faults[name]=[a.PREFIX+k+' '+a.compact(v).decode() for k,v in copy]
    validations=[]
    for name,bad in faults.items():
        failed=a.analyze('\n'.join(bad),frozen,RUN,WORKFLOW,binding)
        assert failed['analysis_status']=='INCOMPLETE' and failed['issues'],name
        assert 'raw_saves' not in failed and 'comparisons' not in failed,name
        validations.append({'fault':name,'status':'REJECTED_INCOMPLETE','issues':failed['issues']})
    prefixed='\n'.join('2026-10-14T12:00:00.0000000Z '+line for line in lines)
    assert a.analyze(prefixed,frozen,RUN,WORKFLOW,binding)['analysis_status']=='COMPLETE_SCOPED_LOG_SCREENING'
    for field, bad_value in [('job_conclusion','failure'),('run_conclusion','failure'),('workflow_sha','0'*40)]:
        bad_binding=deepcopy(binding);bad_binding[field]=bad_value
        failed=a.analyze(text,frozen,RUN,WORKFLOW,bad_binding)
        assert failed['analysis_status']=='INCOMPLETE' and failed['issues']
        validations.append({'fault':'official-binding-'+field,'status':'REJECTED_INCOMPLETE','issues':failed['issues']})
    report={'status':'SYNTHETIC_VALIDATION_ONLY_NOT_NATIVE_PERFORMANCE','positive_cases':2,
            'fault_cases':len(validations),'fault_results':validations,
            'counts':{k:len(result[k]) for k in ['raw_saves','save_distributions','paired_values','comparisons','warm_comparisons']},
            'old_input':str(OLD),'old_analysis_sha256':a.sha((OLD/'analysis/analysis.json').read_bytes()),
            'payload_origins':{'synthetic_C0':'old run38498392929 C0=45b','synthetic_C1':'old run38498392929 C1=d66'},
            'warning':'Synthetic d66/a9cc envelope identities and receipt hashes are fabricated parser fixtures. No result is evidence for a9cc performance or any new native run.',
            'all_36_pairs_9_classifications_480_saves_16_distributions_warm_match_previous_frozen_analysis':True,
            'frozen_input_hashes':frozen['hashes'],
            'files':{n:a.sha((HERE/n).read_bytes()) for n in ['analyze_logs.py','legacy_math.py','test_analyzer.py']}}
    (HERE/'selftest-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['status','positive_cases','fault_cases','counts']}))

if __name__=='__main__':
    main()
