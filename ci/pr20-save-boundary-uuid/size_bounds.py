"""Conservative JSON shape sizing only; not real diagnostic execution or performance."""
import json
from pathlib import Path

def maximum_shape(s):
    if 'enum' in s:return max(s['enum'],key=lambda v:len(json.dumps(v,ensure_ascii=False).encode()))
    kind=s['type'];kind=next(k for k in kind if k!='null') if isinstance(kind,list) else kind
    if 'const' in s:return s['const']
    if kind=='object':return {k:maximum_shape(v)for k,v in s['properties'].items()}
    if kind=='array':return [maximum_shape(s['items']) for _ in range(s.get('maxItems',s.get('maxLength',s.get('length',0))))]
    if kind in ['number','integer']:return s.get('maximum',9223372036854775807) if kind=='integer' else 1.2345678901234567e+100
    if kind=='string':return max(s['enum'],key=lambda v:len(json.dumps(v))) if 'enum'in s else '\\'*s['maxLength']
    if kind=='boolean':return False
    return None

def calculate(contract):
    methods=[]
    for ordinal,rule in contract['methods'].items():
        records=[];raw=0;forwarded=0;per_marker={}
        for marker,bound in rule['markers'].items():
            data=maximum_shape(contract['schemas'][marker]);original=json.dumps(data,separators=(',',':'),ensure_ascii=False)
            size=len(original.encode());per_marker[marker]=size
            # Status framing allowances per sendStatus are deliberately above existing logs.
            raw+=(size+len(marker)+160)*bound['max_count']
            records.extend([{'marker':marker,'data':data}]*bound['max_count'])
            # Official log JSON envelope escapes every original quote/backslash once more.
            forwarded+=(len(json.dumps({'original_json':original},ensure_ascii=False).encode())+300)*bound['max_count']
        parsed=len(json.dumps(records,ensure_ascii=False,separators=(',',':')).encode())+1
        methods.append({'ordinal':int(ordinal),'per_marker_upper_bytes':per_marker,'raw_framed_upper_bytes':raw+16384,'compact_records_upper_bytes':parsed,'forwarded_official_log_upper_bytes':forwarded})
    failure=contract['failure_log_forwarding']
    assert failure['max_count']==1 and failure['raw_max_bytes']==256*1024 and failure['envelope_max_bytes']==6*256*1024+8192
    return {'kind':'STATIC_SCHEMA_WORST_SHAPE_NOT_NATIVE','methods':methods,'raw_plus_records_bytes':sum(m['raw_framed_upper_bytes']+m['compact_records_upper_bytes']for m in methods),'official_record_stream_bytes':sum(m['forwarded_official_log_upper_bytes']for m in methods),'one_failure_raw_bytes':failure['raw_max_bytes'],'one_failure_envelope_bytes':failure['envelope_max_bytes'],'total_artifact_limit_bytes':20*1024*1024,'note':'One non-PASS raw log plus worst-case sixfold ASCII JSON escaping is reserved in addition to bounded diagnostic payloads. Build/dependency/source/host/cleanup text needs separate reserve; packager always checks real total and never truncates.'}

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('contract',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    assert not a.out.exists();a.out.write_text(json.dumps(calculate(json.loads(a.contract.read_text())),indent=2)+'\n')
