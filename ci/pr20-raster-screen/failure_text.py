"""Proposed failure-only encoding of existing synthetic UTF-8 log bytes. No I/O/download.

This is NOT a controller or PASS validator. Runtime path/hash/identity checks remain
the reviewed run.py caller's responsibility. Review before choosing/integrating.
"""
import base64
import hashlib
import json
import re

CHUNK_BYTES = 32768
SECRET = re.compile(rb'gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ssh-rsa|ssh-ed25519) [A-Za-z0-9+/]{40,}')
PREFIX = 'INKWEFT_CAUSAL_FAILURE_TEXT '
BINDING_KEYS = {'run_id','run_attempt','workflow_sha','source_commit','source_spec_sha256',
                'method_plan_sha256','test_source_sha256','ordinal','identity','selection',
                'status','junit_status','adb_exit','process_exit_outcome'}

def encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'))

def wire_bytes(records):
    return sum(len((PREFIX + encoded(record) + '\n').encode('utf-8')) for record in records)

def maximum_wire_bytes(raw_bytes):
    """Exact base64 payload factor plus deliberately bounded headers/line framing.

    32KiB parts are multiples of three only across some boundaries, so count each
    part's padding separately. 1024B/part and 8192B total headers are checked below.
    """
    sizes=[min(CHUNK_BYTES, raw_bytes-start) for start in range(0,raw_bytes,CHUNK_BYTES)]
    return sum(4*((n+2)//3)+1024 for n in sizes)+8192

def prepare_failure_text(data, binding, expected_sha256, raw_limit, wire_limit,
                         normal_forward_started=False):
    """All-or-refuse preparation; callers print only the returned complete list.

    Returned refusal contains no raw content and cannot change the original
    failure. Original data are never mutated or sliced away from retained logs.
    """
    assert type(data) is bytes
    assert set(binding)==BINDING_KEYS
    assert binding['status'] in ['FAIL','INCOMPLETE']
    assert type(raw_limit) is int and raw_limit>=0
    assert type(wire_limit) is int and wire_limit>=8192
    assert re.fullmatch('[0-9a-f]{64}',expected_sha256)
    assert len(encoded(binding).encode())<=4096, 'Binding exceeds fixed header budget'
    digest=hashlib.sha256(data).hexdigest()
    reason=None
    if normal_forward_started:reason='NORMAL_FORWARD_ALREADY_STARTED'
    elif len(data)>raw_limit:reason='RAW_OVERSIZE'
    elif digest!=expected_sha256:reason='HASH_MISMATCH'
    elif SECRET.search(data):reason='SENSITIVE'
    else:
        try:data.decode('utf-8',errors='strict')
        except UnicodeDecodeError:reason='INVALID_UTF8'
    common=dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',binding=binding,
                raw_bytes=len(data),raw_sha256=digest)
    def refuse(why):
        result=[dict(common,kind='REFUSED',reason=why)]
        assert wire_bytes(result)<=8192
        return result
    if reason:return refuse(reason)
    parts=(len(data)+CHUNK_BYTES-1)//CHUNK_BYTES
    begin=dict(common,kind='BEGIN',encoding='base64-utf8',chunk_bytes=CHUNK_BYTES,parts=parts)
    context=hashlib.sha256(encoded(begin).encode()).hexdigest()
    records=[begin]
    for part,start in enumerate(range(0,len(data),CHUNK_BYTES)):
        chunk=data[start:start+CHUNK_BYTES]
        record=dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='PART',context_sha256=context,
                    part=part,parts=parts,raw_bytes=len(chunk),raw_sha256=hashlib.sha256(chunk).hexdigest(),
                    base64=base64.b64encode(chunk).decode('ascii'))
        assert len((PREFIX+encoded(record)+'\n').encode())-len(record['base64'])<=1024
        records.append(record)
    end=dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='END',context_sha256=context,
             parts=parts,raw_bytes=len(data),raw_sha256=digest)
    records.append(end)
    assert wire_bytes([begin,end])<=8192
    assert wire_bytes(records)<=maximum_wire_bytes(len(data))
    if wire_bytes(records)>wire_limit:return refuse('ENCODED_OVERSIZE')
    return records

def reconstruct_failure_text(records, expected_binding):
    """Independent consumer skeleton: complete failure text only, never PASS closure."""
    assert len(records)>=2
    begin,end=records[0],records[-1]
    assert set(begin)=={'schema','scope','binding','raw_bytes','raw_sha256','kind','encoding','chunk_bytes','parts'}
    assert begin['schema']==2 and begin['scope']=='DIAGNOSTIC_FAILURE_ONLY' and begin['kind']=='BEGIN'
    assert begin['binding']==expected_binding and expected_binding['status'] in ['FAIL','INCOMPLETE']
    assert begin['encoding']=='base64-utf8' and begin['chunk_bytes']==CHUNK_BYTES
    assert type(begin['raw_bytes']) is int and begin['raw_bytes']>=0
    assert type(begin['parts']) is int and begin['parts']==(begin['raw_bytes']+CHUNK_BYTES-1)//CHUNK_BYTES
    assert len(records)==begin['parts']+2
    context=hashlib.sha256(encoded(begin).encode()).hexdigest()
    chunks=[]
    for index,record in enumerate(records[1:-1]):
        assert set(record)=={'schema','scope','kind','context_sha256','part','parts','raw_bytes','raw_sha256','base64'}
        assert record['schema']==2 and record['scope']=='DIAGNOSTIC_FAILURE_ONLY' and record['kind']=='PART'
        assert record['context_sha256']==context and type(record['part']) is int and record['part']==index
        assert record['parts']==begin['parts']
        data=base64.b64decode(record['base64'],validate=True)
        assert base64.b64encode(data).decode('ascii')==record['base64'], 'Noncanonical base64'
        assert type(record['raw_bytes']) is int and record['raw_bytes']==len(data)
        assert len(data)==min(CHUNK_BYTES,begin['raw_bytes']-index*CHUNK_BYTES)
        assert hashlib.sha256(data).hexdigest()==record['raw_sha256']
        chunks.append(data)
    assert set(end)=={'schema','scope','kind','context_sha256','parts','raw_bytes','raw_sha256'}
    assert end==dict(schema=2,scope='DIAGNOSTIC_FAILURE_ONLY',kind='END',context_sha256=context,
                    parts=begin['parts'],raw_bytes=begin['raw_bytes'],raw_sha256=begin['raw_sha256'])
    data=b''.join(chunks)
    assert len(data)==begin['raw_bytes'] and hashlib.sha256(data).hexdigest()==begin['raw_sha256']
    data.decode('utf-8',errors='strict')
    assert not SECRET.search(data)
    return data
