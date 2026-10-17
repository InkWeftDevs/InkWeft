"""Closed synthetic producer/contract upper bounds; no native performance claim."""
import json,re
from pathlib import Path
from failure_text import maximum_wire_bytes


# Mutually exclusive fixed-text buckets. The total remains exactly 3 MiB.
SHARED_BUCKETS = {
    'frozen_control': 768*1024,
    'build_dependency': 1024*1024,
    'source_install_environment': 256*1024,
    'summary': 512*1024,
    'cleanup_emulator': 128*1024,
    'method_receipts': 160*1024,
    'packaging': 96*1024,
    'official_fixed': 128*1024,
}
assert sum(SHARED_BUCKETS.values()) == 3*1024*1024
BUDGET_RECEIPT_ALLOWANCE = 16*1024
REFUSAL_WIRE_ALLOWANCE = 8192

def fixed_bucket(name):
    if re.fullmatch(r'method-\d{2}-C[12]-(?:instrumentation\.log|records\.json)',name):return None
    if name.startswith('frozen-') or name in ['authorization.json','EXECUTION_SHA256SUMS','run.py','markers.py','package.py','cleanup.py','failure_text.py','size_bounds.py','workflow.proposed.yml']:return 'frozen_control'
    if name in ['summary.json','summary-before-packaging.json','summary.tmp']:return 'summary'
    if re.fullmatch(r'method-\d{2}-C[12]-(?:junit\.xml|log-forwarding\.json)',name):return 'method_receipts'
    if re.fullmatch(r'(?:apk|dependencies|build)-C[12]\.(?:json|log)',name) or name in ['official-dependency-check.json','official-dependency-check.log','sdk.log']:return 'build_dependency'
    if name.startswith(('cleanup-','kvm-','emulator-')):return 'cleanup_emulator'
    if name.startswith('install-') or name in ['clock.json','host.json','host-after.json','device-before.json','device-after.json','source-fetch.log','source-reconstruction.log','acceleration.log','avd.log']:return 'source_install_environment'
    raise AssertionError('Unbudgeted fixed file: '+name)

def method_wire_bound(entry,rule,raw_exists):
    count=sum(v['max_count'] for v in rule['markers'].values())
    normal=2*rule['method_payload_max_bytes']+1024*count+16384
    if entry.get('normal_forward_started') or entry.get('status')=='PASS':
        extra=REFUSAL_WIRE_ALLOWANCE if entry.get('status')!='PASS' else 0
        return normal+extra,'NORMAL_OR_INTERRUPTED_NORMAL'
    # The failure helper can print before its final in-memory receipt is saved.
    # A killed RUNNING method or existing raw log must reserve the complete stream.
    if raw_exists or entry.get('status','NOT_RUN')!='NOT_RUN':return maximum_wire_bytes(1048576),'FAILURE_OR_INTERRUPTED_FAILURE'
    return 0,'NOT_STARTED'

def calculate(contract):
    rows=[]
    for key,rule in contract['methods'].items():
        count=sum(v['max_count'] for v in rule['markers'].values())
        p=rule['method_payload_max_bytes']
        # Producer JSON is ASCII (checked before forwarding); each raw JSON character
        # needs at most two bytes in the nested official JSON string. Framing/context
        # allowances are separate. Parsed output keeps all original typed values.
        rows.append(dict(ordinal=int(key),payload=p,records=count,raw_upper=p+count*512+16384,
            parsed_upper=p+16384,wire_upper=2*p+count*1024+16384))
    shared=3*1024*1024;limit=20*1024*1024
    success=sum(r['raw_upper']+r['parsed_upper']+r['wire_upper'] for r in rows)+shared
    failures=[]
    raw=contract['failure_text']['raw_max_bytes'];wire=maximum_wire_bytes(raw)
    assert wire==contract['failure_text']['wire_max_bytes']
    # Failure is an alternate terminal branch, not added to all twelve successes.
    # Full raw and a conservative doubled parsed representation are reserved, along
    # with full base64 failure wire. Source emitters have the same bounded marker
    # counts; unexpected huge/malformed output is fail-closed at actual packaging.
    for index,row in enumerate(rows):
        prior=sum(r['raw_upper']+r['parsed_upper']+r['wire_upper'] for r in rows[:index])
        failures.append(dict(ordinal=row['ordinal'],prior_complete_methods_bytes=prior,failure_raw_bytes=raw,failure_parsed_upper=2*raw+65536,failure_wire_upper=wire,shared_once=shared,bytes=prior+raw+2*raw+65536+wire+shared))
    normal_interruptions=[dict(ordinal=row['ordinal'],bytes=sum(r['raw_upper']+r['parsed_upper']+r['wire_upper'] for r in rows[:index+1])+REFUSAL_WIRE_ALLOWANCE+shared) for index,row in enumerate(rows)]
    total=max([success]+[f['bytes']for f in failures+normal_interruptions])
    return dict(scope='STATIC_BOUND_NOT_NATIVE',methods=rows,success_with_shared_reserve=success,
        failure_paths=failures,interrupted_normal_paths=normal_interruptions,shared_buckets=SHARED_BUCKETS,failure_raw_limit=raw,failure_wire_limit=wire,shared_reserve=shared,
        maximum_bytes=total,limit_bytes=limit,headroom_bytes=limit-total,within_limit=total<=limit,
        note='Includes raw + parsed + official stream together. Shared 3MiB is one reserve for all source/control/build/dependency/context/cleanup and packaging text. Runtime packager checks actual total, rejects oversize/missing/secret data without truncation. Arbitrary malformed producer output beyond the closed contract is not promised complete publication.')
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    result=calculate(json.loads((Path(__file__).parent/'marker-contract.template.json').read_text()))
    assert not a.out.exists();a.out.write_text(json.dumps(result,indent=2)+'\n')
    assert result['within_limit'],result
