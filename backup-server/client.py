"""Reference streaming client for synthetic fixtures; recovery material stays with the user."""
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import uuid
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC=b'IWBK1\n'
BLOCK=262144
WIRE_BLOCK=1048576


def b64(value): return base64.b64encode(value).decode('ascii')
def unb64(value): return base64.b64decode(value,validate=True)
def sha(value): return hashlib.sha256(value).hexdigest()


def encrypt(source, output, library, recovery_key):
    uuid.UUID(library)
    if len(recovery_key)!=32: raise ValueError('Invalid recovery key')
    source, output=Path(source),Path(output)
    if output.exists(): raise ValueError('Destination exists')
    data_key=os.urandom(32);wrap_nonce=os.urandom(12);prefix=os.urandom(8);backup=str(uuid.uuid4())
    size=source.stat().st_size
    if not 0<size<=500*WIRE_BLOCK: raise ValueError('Backup size limit')
    aad=f'inkweft-backup-key-v1|{library}|{backup}'.encode()
    header=json.dumps({'format':1,'library':library,'backup':backup,'plainBytes':size,'chunkSize':BLOCK,
        'noncePrefix':b64(prefix),'wrapNonce':b64(wrap_nonce),'wrappedKey':b64(AESGCM(recovery_key).encrypt(wrap_nonce,data_key,aad))},separators=(',',':')).encode()
    h=hashlib.sha256(header).digest();cipher=AESGCM(data_key)
    created=False
    try:
        with source.open('rb') as src,output.open('xb') as dst:
            created=True
            dst.write(MAGIC+struct.pack('>I',len(header))+header)
            for i in range(math.ceil(size/BLOCK)):
                chunk=src.read(BLOCK);encrypted=cipher.encrypt(prefix+struct.pack('>I',i),chunk,h+struct.pack('>I',i))
                dst.write(struct.pack('>I',len(encrypted))+encrypted)
            if src.read(1): raise ValueError('Source changed during encryption')
            dst.flush();os.fsync(dst.fileno())
    except BaseException:
        if created: output.unlink(missing_ok=True)
        raise


def decrypt(source, output, library, recovery_key):
    """Never publish a partial plaintext file, including bad keys, truncation and trailing data."""
    output=Path(output);temporary=output.with_name(output.name+'.'+str(uuid.uuid4())+'.partial')
    if output.exists(): raise ValueError('Destination exists')
    def exact(stream,n):
        data=stream.read(n)
        if len(data)!=n: raise ValueError('Truncated backup')
        return data
    try:
        with open(source,'rb') as src,temporary.open('xb') as dst:
            if exact(src,6)!=MAGIC: raise ValueError('Invalid format')
            length=struct.unpack('>I',exact(src,4))[0]
            if not 1<=length<=4096: raise ValueError('Invalid header size')
            header=exact(src,length);j=json.loads(header)
            if j['format']!=1 or j['library']!=library or j['chunkSize']!=BLOCK or not 0<j['plainBytes']<=500*WIRE_BLOCK: raise ValueError('Invalid header binding')
            uuid.UUID(j['backup']);uuid.UUID(library)
            prefix=unb64(j['noncePrefix']);nonce=unb64(j['wrapNonce'])
            if len(prefix)!=8 or len(nonce)!=12 or len(recovery_key)!=32: raise ValueError('Invalid key envelope')
            aad=f"inkweft-backup-key-v1|{library}|{j['backup']}".encode()
            key=AESGCM(recovery_key).decrypt(nonce,unb64(j['wrappedKey']),aad)
            h=hashlib.sha256(header).digest();cipher=AESGCM(key);remaining=j['plainBytes']
            for i in range(math.ceil(remaining/BLOCK)):
                expected=min(BLOCK,remaining)
                n=struct.unpack('>I',exact(src,4))[0]
                if n!=expected+16: raise ValueError('Invalid encrypted chunk length')
                clear=cipher.decrypt(prefix+struct.pack('>I',i),exact(src,n),h+struct.pack('>I',i))
                dst.write(clear);remaining-=len(clear)
            if remaining or src.read(1): raise ValueError('Invalid file extent')
            dst.flush();os.fsync(dst.fileno())
        # A hard link publishes the completed file atomically without replacing a concurrent destination.
        os.link(temporary,output)
    finally: temporary.unlink(missing_ok=True)


class BackupClient:
    """Transport is bound to server+issuer+user+device+library; never to an email alone."""
    def __init__(self,http,identity,library):
        self.http=http;self.identity=dict(identity);self.library=str(uuid.UUID(library))
        discovery=http.get('/v1/identity');discovery.raise_for_status()
        if any(discovery.json()[k]!=identity[k] for k in ('server','issuer')): raise ValueError('Server identity changed')
        self.headers={'Authorization':'Bearer '+identity['token']}
        self.binding={k:identity[k] for k in ('server','issuer','user','device')}|{'library':self.library}

    def request(self,method,path,**kwargs):
        response=self.http.request(method,'/v1/libraries/'+self.library+path,headers=self.headers,**kwargs)
        response.raise_for_status();return response

    def upload(self,path,queue_path):
        path,queue_path=Path(path),Path(queue_path)
        with path.open('rb') as stream:
            chunks=[]
            while chunk:=stream.read(WIRE_BLOCK): chunks.append(sha(chunk))
        manifest={'format':'inkweft.encrypted-backup.v1','chunks':chunks,'bytes':path.stat().st_size}
        if queue_path.exists():
            queue=json.loads(queue_path.read_text())
            if queue['binding']!=self.binding or queue['manifest']!=manifest: raise ValueError('Queue binding/payload differs')
        else:
            queue={'binding':self.binding,'manifest':manifest,'operation':str(uuid.uuid4())}
            with queue_path.open('x',encoding='utf-8') as f: json.dump(queue,f);f.flush();os.fsync(f.fileno())
        op=queue['operation']
        # Server receipts make an unknown response queryable without a second publication.
        lookup=self.http.get('/v1/libraries/'+self.library+'/operations/'+op,headers=self.headers)
        if lookup.status_code!=404:
            lookup.raise_for_status()
            expected=sha(json.dumps(manifest,sort_keys=True,separators=(',',':')).encode())
            if lookup.json()['digest']!=expected or lookup.json()['kind']!='UPLOAD': raise ValueError('Receipt payload differs')
            if lookup.json()['state']=='PUBLISHED': return lookup.json()
        self.request('PUT','')
        self.request('PUT','/uploads/'+op,json=manifest)
        inventory=self.request('GET','/uploads/'+op).json()
        expected=sha(json.dumps(manifest,sort_keys=True,separators=(',',':')).encode())
        if inventory['digest']!=expected or inventory['state']!='PENDING':raise ValueError('Upload inventory mismatch')
        present=set(inventory['received'])
        if any(i not in range(len(chunks)) for i in present):raise ValueError('Invalid chunk index')
        with path.open('rb') as stream:
            for i in range(len(chunks)):
                data=stream.read(WIRE_BLOCK)
                if sha(data)!=chunks[i]:raise ValueError('Cipher file changed')
                if i not in present:self.request('PUT',f'/uploads/{op}/chunks/{i}',content=data)
        return self.request('POST',f'/uploads/{op}/publish').json()

    def download(self,op,path):
        m=self.request('GET','/versions/'+str(uuid.UUID(op))).json();path=Path(path)
        if path.exists(): raise ValueError('Destination exists')
        created=False
        try:
            with path.open('xb') as f:
                created=True
                for i,expected in enumerate(m['chunks']):
                    data=self.request('GET',f'/versions/{op}/chunks/{i}').content
                    if sha(data)!=expected or len(data)>WIRE_BLOCK: raise ValueError('Download integrity')
                    f.write(data)
                if f.tell()!=m['bytes']: raise ValueError('Download size')
                f.flush();os.fsync(f.fileno())
        except BaseException:
            if created: path.unlink(missing_ok=True)
            raise
