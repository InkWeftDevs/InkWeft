"""Protocol acceptance client for the Go service; not a second production backend."""
import json
import os
from pathlib import Path
import struct
import time
import uuid
from client import WIRE_BLOCK, sha, decrypt, encrypt


def canonical(v): return json.dumps(v, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def save(path, value):
    path=Path(path);temporary=path.with_name(path.name+'.'+str(uuid.uuid4())+'.partial')
    try:
        with temporary.open('x',encoding='utf-8') as f:
            f.write(canonical(value));f.flush();os.fsync(f.fileno())
        os.replace(temporary,path)
    finally: temporary.unlink(missing_ok=True)


class BackupClientV2:
    def __init__(self,http,identity,library,fault=lambda phase: None):
        self.http,self.identity,self.library,self.fault=http,identity,str(uuid.UUID(library)),fault
        discovery=http.get('/v2/identity');discovery.raise_for_status();cap=discovery.json()
        if any(cap[k]!=identity[k] for k in ('server','issuer')) or cap['maxPlainBytes']!=8*1024**3 or cap['maxChunks']!=8193: raise ValueError('Identity/capability changed')
        self.headers={'Authorization':'Bearer '+identity['token']}
        self.binding={k:identity[k] for k in ('server','issuer','user','device')}|{'library':self.library}
        self.transferred=0;self.local_read=0

    def request(self,method,path,**kwargs):
        r=self.http.request(method,'/v2/libraries/'+self.library+path,headers=self.headers,**kwargs)
        r.raise_for_status();return r

    def upload(self,path,queue_path,stop_after=None):
        path,queue_path=Path(path),Path(queue_path)
        if queue_path.exists():
            q=json.loads(queue_path.read_text(encoding='utf-8'))
            if q['binding']!=self.binding or q['file']!=str(path.resolve()) or q['manifest']['bytes']!=path.stat().st_size: raise ValueError('Queue binding/file differs')
        else:
            with path.open('rb') as f:
                if f.read(6)!=b'IWBK2\n':raise ValueError('Cipher format')
                length=struct.unpack('>I',f.read(4))[0]
                if not 1<=length<=4096:raise ValueError('Header size')
                header=json.loads(f.read(length))
                if header['format']!=2 or header['library']!=self.library:raise ValueError('Cipher library binding')
                f.seek(0);chunks=[]
                while data:=f.read(WIRE_BLOCK):chunks.append(sha(data))
            m={'bytes':path.stat().st_size,'chunks':chunks,'format':'inkweft.encrypted-backup.v2','plainBytes':header['plainBytes']}
            q={'operation':str(uuid.uuid4()),'binding':self.binding,'file':str(path.resolve()),'manifest':m}
            save(queue_path,q)
        op,m=q['operation'],q['manifest'];fingerprint=sha(canonical(m).encode())
        self.request('PUT','')
        receipt=self.request('PUT','/uploads/'+op,json=m).json()
        if receipt['digest']!=fingerprint or receipt['kind']!='UPLOAD':raise ValueError('Receipt mismatch')
        if receipt['state']=='PUBLISHED':return receipt
        inventory=self.request('GET','/uploads/'+op).json()
        if inventory['digest']!=fingerprint or inventory['manifest']!=m or inventory['state']!='PENDING':raise ValueError('Inventory mismatch')
        present=set(inventory['received'])
        if any(i not in range(len(m['chunks'])) for i in present):raise ValueError('Ordinal')
        count=0
        with path.open('rb') as f:
            for i,h in enumerate(m['chunks']):
                if i in present:continue
                f.seek(i*WIRE_BLOCK);data=f.read(WIRE_BLOCK);self.local_read+=len(data)
                if sha(data)!=h:raise ValueError('Cipher changed')
                self.request('PUT',f'/uploads/{op}/chunks/{i}',content=data);count+=1;self.transferred+=len(data);self.fault('upload-after-receipt')
                if stop_after is not None and count>=stop_after:return inventory
        while True:
            receipt=self.request('POST','/uploads/'+op+'/publish').json()
            if receipt['digest']!=fingerprint or receipt['kind']!='UPLOAD':raise ValueError('Publication receipt mismatch')
            if receipt['state']=='PUBLISHED':return receipt
            if receipt['state']!='PENDING':raise ValueError('Publication unavailable')

    def download(self,op,path,queue_path,stop_after=None):
        op=str(uuid.UUID(op));path,queue_path=Path(path),Path(queue_path)
        q=json.loads(queue_path.read_text(encoding='utf-8')) if queue_path.exists() else None
        if q and (q['binding']!=self.binding or q['operation']!=op or q['file']!=str(path.resolve())):raise ValueError('Download binding differs')
        if not q or q['expires']<=time.time():
            lease=self.request('POST','/versions/'+op+'/leases').json()
            if q and (q['digest']!=lease['digest'] or q['manifest']!=lease['manifest']):raise ValueError('Version changed')
            q=(q or {'binding':self.binding,'operation':op,'file':str(path.resolve()),'received':[]})|lease
            if sha(canonical(q['manifest']).encode())!=q['digest']:raise ValueError('Lease mismatch')
            save(queue_path,q)
        m=q['manifest'];present=set(q['received']);count=0
        if any(i not in range(len(m['chunks'])) for i in present):raise ValueError('Ordinal')
        with path.open('r+b' if path.exists() else 'w+b') as f:
            if present:
                last=max(present);f.seek(last*WIRE_BLOCK)
                if sha(f.read(min(WIRE_BLOCK,m['bytes']-last*WIRE_BLOCK)))!=m['chunks'][last]:present.remove(last)
            try:
                for i,h in enumerate(m['chunks']):
                    if i in present:continue
                    if q['expires']-time.time()<120:
                        lease=self.request('PUT',f"/versions/{op}/leases/{q['lease']}").json()
                        if lease['digest']!=q['digest']:raise ValueError('Lease changed')
                        q.update(lease)
                    data=self.request('GET',f"/versions/{op}/chunks/{i}?lease={q['lease']}").content
                    if sha(data)!=h or len(data)!=min(WIRE_BLOCK,m['bytes']-i*WIRE_BLOCK):raise ValueError('Chunk integrity')
                    f.seek(i*WIRE_BLOCK);f.write(data);self.fault('download-after-write');present.add(i);self.transferred+=len(data);count+=1
                    if count%16==0:
                        f.flush();os.fsync(f.fileno());self.fault('download-before-bitmap');q['received']=sorted(present);save(queue_path,q);self.fault('download-after-bitmap')
                    if stop_after is not None and count>=stop_after:return q
            finally:
                f.flush();os.fsync(f.fileno());q['received']=sorted(present);save(queue_path,q)
            if f.seek(0,2)!=m['bytes']:raise ValueError('Cipher extent')
            f.seek(0)
            for h in m['chunks']:
                if sha(f.read(WIRE_BLOCK))!=h:raise ValueError('Cached chunk changed')
        self.request('DELETE',f"/versions/{op}/leases/{q['lease']}")
        return q
