"""Executed on stdin inside the disposable container by container_check.py."""
import httpx,json,uuid,tempfile,os,sys
from pathlib import Path
from client import BackupClient,encrypt,decrypt,sha,WIRE_BLOCK,b64,unb64
root=Path('/state');mode=sys.argv[1]
with httpx.Client(base_url='http://127.0.0.1:18751',timeout=15,follow_redirects=False) as http:
    state={'device':str(uuid.uuid4()),'library':str(uuid.uuid4()),'key':b64(os.urandom(32))} if mode=='prepare' else json.loads((root/'probe.json').read_text())
    login=http.post('/v1/sessions',json={'username':'synthetic-only','password':'synthetic-test-password-123','device':state['device']});login.raise_for_status();identity=login.json()
    client=BackupClient(http,identity,state['library'])
    with tempfile.TemporaryDirectory() as temp:
        t=Path(temp)
        if mode=='prepare':
            plain=t/'plain';plain.write_bytes(b'InkWeft synthetic encrypted restore\n'+os.urandom(2*WIRE_BLOCK));state['plain_sha256']=sha(plain.read_bytes())
            cipher=t/'cipher';encrypt(plain,cipher,state['library'],unb64(state['key']));data=cipher.read_bytes();chunks=[data[i:i+WIRE_BLOCK] for i in range(0,len(data),WIRE_BLOCK)]
            manifest={'format':'inkweft.encrypted-backup.v1','chunks':list(map(sha,chunks)),'bytes':len(data)};op=str(uuid.uuid4());client.request('PUT','');client.request('PUT','/uploads/'+op,json=manifest)
            client.request('PUT',f'/uploads/{op}/chunks/0',content=chunks[0]);assert client.request('GET','/uploads/'+op).json()['received']==[0]
            queue=t/'queue';queue.write_text(json.dumps({'binding':client.binding,'manifest':manifest,'operation':op}))
            receipt=client.upload(cipher,queue);assert receipt['state']=='PUBLISHED';assert client.upload(cipher,queue)==receipt
            state.update(operation=op,receipt=receipt,server=identity['server'],issuer=identity['issuer'],user=identity['user']);(root/'probe.json').write_text(json.dumps(state))
        else:
            assert all(state[k]==identity[k] for k in ('server','issuer','user'))
            assert client.request('GET','/operations/'+state['operation']).json()==state['receipt']
            cipher=t/'download';client.download(state['operation'],cipher);plain=t/'restored';decrypt(cipher,plain,state['library'],unb64(state['key']));assert sha(plain.read_bytes())==state['plain_sha256']
    print(json.dumps({'status':'PASS','phase':mode,'identity_preserved':mode!='prepare','operation':state['operation']}))
