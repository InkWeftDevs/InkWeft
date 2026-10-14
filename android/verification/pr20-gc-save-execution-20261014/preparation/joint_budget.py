"""One data-only job: mutually exclusive normal stream / first-failure full raw bytes.
All computations are local synthetic capacity arithmetic; not native evidence.
"""
import hashlib,json,pathlib,sys
ROOT=pathlib.Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'failure-proposal'))
from failure_text import maximum_wire_bytes
GC_PATH=pathlib.Path('/workspace/scratch/pr20-gc-author-only-budget-20261014/budget.json')
SAVE_PATH=pathlib.Path('/workspace/scratch/pr20-save-snapshot-control-20261014/contract.json')
gc=json.loads(GC_PATH.read_text());save=json.loads(SAVE_PATH.read_text())
def j(x):return json.dumps(x,ensure_ascii=True,separators=(',',':'))
def maxrow(kind):return [max(v,key=lambda n:len(str(n)))for v in save['ranges'][kind]]
def payloads(failed_last_arm=False):
    result=[]
    for i,mode in enumerate(save['order']):
        failed=failed_last_arm and i==2
        # Failure preserves full capacity even when complete()'s numeric bounds fail.
        br=[-9223372036854775808]*10 if failed else maxrow('boundary')
        rr=[-9223372036854775808]*7 if failed else maxrow('read')
        sr=[-9223372036854775808]*5 if failed else maxrow('save')
        e=save['expected']
        fixture=dict(rowCounts=e['rowCounts'],logicalSha256=e['fixtureSha256'],writerSqlite=e['writerSqlite'])
        result.append(('LIBRARY_SNAPSHOT_CONTROL_RESULT',dict(schema=1,diagnosticOnly=True,seed=4101,mode=mode,readiness=e['readiness'][i],fixture=fixture,startRevision=1,finalRevision=-9223372036854775808 if failed else 31,readerBeforeRevision=e['readerBeforeRevision'][i],readerAfterRevision=e['readerAfterRevision'][i],saves=[sr]*30)))
        for kind,row,n in [('EVENTS',br,1536),('READS',rr,128)]:
            for part,offset in enumerate(range(0,n,96)):
                result.append(('LIBRARY_SNAPSHOT_CONTROL_'+kind,dict(schema=1,mode=mode,part=part,events=[row]*min(96,n-offset))))
        result.append(('LIBRARY_SNAPSHOT_CONTROL_END',dict(schema=1,mode=mode,completed=False if failed else True,coverageValid=False if failed else True,committedSaves=30,recordedEvents=1536,recordedReadEvents=128,droppedEvents=-2147483648 if failed else 0,droppedReadEvents=-2147483648 if failed else 0,invalid=False,readerExited=False if failed else True,writersExited=False if failed else True,databaseClosed=False if failed else True,databaseDeleted=False if failed else True,cleanupErrors=-2147483648 if failed else 0)))
    return result
def measure(values):
    raw=forwarded=0;ordered=[];maximum={}
    for sequence,(marker,data) in enumerate(values):
        text=j(data);size=len(text.encode());maximum[marker]=max(maximum.get(marker,0),size)
        raw+=size+len(marker)+2+160
        item=dict(ordinal=8,identity='C1',sequence=sequence,marker=marker,payload_bytes=size,payload_sha256=hashlib.sha256(text.encode()).hexdigest(),original_json=text)
        forwarded+=len(('INKWEFT_CAUSAL_RECORD '+j(item)+'\n').encode())+64
        ordered.append({k:item[k]for k in ['sequence','marker','payload_bytes','payload_sha256']})
    end=dict(ordinal=8,identity='C1',count=len(ordered),ordered_payloads=ordered,ordered_payloads_sha256='0'*64)
    return dict(raw_framed_bytes=raw+16384,official_record_end_bytes=forwarded+len(('INKWEFT_CAUSAL_END '+j(end)+'\n').encode())+64,
                max_payload_bytes=maximum,payloads=len(values),derived_records_uploaded_bytes=0)
normal_save=measure(payloads());failed_save=measure(payloads(True))
normal_gc=gc['normal_capacity_model'];failed_gc=gc['last_trial_failed_capacity_model']
EXTRA=256*1024
# Shared fixed budget allocated once, not offered separately to every scope.
fixed={
    'source_frozen_protocol_controller_and_manifest_text':1024*1024,
    'build_and_sdk_source_setup_logs':1024*1024,
    'dependencies_apk_install_host_device_receipts':384*1024,
    'forwarding_inventory_context_method_final_and_summary_copies':384*1024,
    'guard_raw_junit_cleanup_acl_packaging_and_manifest':256*1024,
}
assert sum(fixed.values())==3*1024*1024
normal=normal_gc['total']+normal_save['raw_framed_bytes']+normal_save['official_record_end_bytes']+sum(fixed.values())
first=normal_gc['methods'][0]
previous_gc_total=first['raw_framed_bytes']+first['official_record_bytes']+first['official_end_bytes']
last_failed_gc=failed_gc['methods'][1]
gc_failed_raw=last_failed_gc['raw_framed_bytes']+EXTRA
gc_failed_total=previous_gc_total+gc_failed_raw+maximum_wire_bytes(gc_failed_raw)+sum(fixed.values())
save_failed_raw=failed_save['raw_framed_bytes']+EXTRA
save_failed_total=normal_gc['total']+save_failed_raw+maximum_wire_bytes(save_failed_raw)+sum(fixed.values())
# Earlier GC failure cannot exceed later one: same bounds and fewer positive prefix
# methods/trials. Earlier guard failures omit both GC methods and the save method.
guard_failed_raw=16384+EXTRA
guard_failed_total=guard_failed_raw+maximum_wire_bytes(guard_failed_raw)+sum(fixed.values())
cases=dict(all_methods_success=normal,first_failure_in_guards=guard_failed_total,
           first_failure_in_GC_worst_last_trial=gc_failed_total,first_failure_in_save_worst_last_arm=save_failed_total)
worst=max(cases.values());limit=20*1024*1024
result=dict(status='DRAFT_DATA_ONLY_CAPACITY_ARITHMETIC_NOT_NATIVE_NOT_FROZEN',
    input_sha256={str(GC_PATH):hashlib.sha256(GC_PATH.read_bytes()).hexdigest(),str(SAVE_PATH):hashlib.sha256(SAVE_PATH.read_bytes()).hexdigest()},
    source_test_sha256=dict(gc=gc['test_sha256'],save=save['sourceSha256']),
    scope='5 GC guards + 2 GC boundary methods + 1 snapshot-control method, one data APK, one install group',
    save_normal_capacity=normal_save,save_failed_last_arm_capacity=failed_save,
    normal_gc_original_and_forwarded_bytes=normal_gc['total'],
    failure_only_extra_raw_reserve_bytes=EXTRA,
    failure_raw_caps=dict(guard=guard_failed_raw,gc=gc_failed_raw,save=save_failed_raw),
    failure_wire_caps=dict(guard=maximum_wire_bytes(guard_failed_raw),gc=maximum_wire_bytes(gc_failed_raw),save=maximum_wire_bytes(save_failed_raw)),
    fixed_text_buckets_bytes=fixed,cases_bytes=cases,maximum_case_bytes=worst,limit_bytes=limit,remaining_bytes=limit-worst,
    assumptions=['First nonPASS stops the entire ordered plan; no successful successor payloads are reserved again on failure.',
        'Normal marker forwarding and full failure text forwarding are mutually exclusive per method, including exceptions after normal forwarding starts.',
        'Normal payload bytes retained once in raw instrumentation and forwarded once; derived records are not uploaded.',
        'Full failed method raw bytes replace that method normal forward stream; base64 uses exact chunk padding and bounded headers.',
        'Failure can violate successful numeric gates: last save arm reserves full Long widths in all rows; GC failed trial retains64 intervals.',
        'A further256KiB residual raw failure text reserve covers class/suppressed/JUnit stack data; excess is explicit failclosed, never truncate.',
        'Shared fixed3MiB has one allocation only; actual category and total text must be checked, no binary artifacts or extra download path.',
        'This is conditional on final source/schema/helper review and exact source hashes, not an activation permit.',
        'No MuPDF/app build or raster performance claim is part of this data-only scope.'])
(ROOT/'joint-budget.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(dict(cases=cases,maximum=worst,limit=limit,remaining=limit-worst,save_normal=normal_save,save_failed=failed_save)))
