import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
from fastapi.testclient import TestClient
from server import create_app, Store, CHUNK_LIMIT
from client import encrypt, decrypt, BackupClient


class BackupProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='inkweft-backup-test-')
        self.root=Path(self.temp.name).resolve();self.db=self.root/'server.db'
        self.app=create_app(self.db);self.http=TestClient(self.app)
        self.a=self.app.state.store.add_user('alice','test-alice-password-123')
        self.b=self.app.state.store.add_user('bob','test-bob-password-456')
        self.device=str(uuid.uuid4());self.library=str(uuid.uuid4())
        self.alice=self.login('alice','test-alice-password-123',self.device)
        self.bob=self.login('bob','test-bob-password-456')
        self.client=BackupClient(self.http,self.alice,self.library)
        self.client.request('PUT','')

    def tearDown(self): self.http.close();self.temp.cleanup()

    def login(self,name,password,device=None):
        r=self.http.post('/v1/sessions',json={'username':name,'password':password,'device':device or str(uuid.uuid4())})
        self.assertEqual(200,r.status_code,r.text);return r.json()

    def fixture(self):
        clear=self.root/'sample.iwbackup';clear.write_bytes(('合成资料 P(A | B) = 0.5\n'*50000).encode())
        key=os.urandom(32);encrypted=self.root/'encrypted.iwbk';encrypt(clear,encrypted,self.library,key)
        return clear,encrypted,key

    def test_roundtrip_restart_second_device_unknown_receipt_and_acl(self):
        clear,encrypted,key=self.fixture();queue=self.root/'queue.json'
        first=self.client.upload(encrypted,queue)
        self.assertEqual('PUBLISHED',first['state'])
        self.assertEqual(first,self.client.upload(encrypted,queue))
        self.assertEqual(1,len(self.client.request('GET','/versions').json()))
        op=first['operation'];prefix='/v1/libraries/'+self.library
        bob={'Authorization':'Bearer '+self.bob['token']}
        checks=[('get','/versions',None),('get','/versions/'+op,None),('get',f'/versions/{op}/chunks/0',None),('get','/operations/'+op,None),('put','',None),('post',f'/uploads/{op}/publish',None),('delete',f'/versions/{op}?operation_id={uuid.uuid4()}',None)]
        for method,path,body in checks:
            self.assertEqual(404,self.http.request(method,prefix+path,headers=bob).status_code,(method,path))
        m=json.loads(queue.read_text())['manifest']
        self.assertEqual(404,self.http.put(prefix+'/uploads/'+op,headers=bob,json=m).status_code)
        self.assertEqual(404,self.http.put(prefix+f'/uploads/{op}/chunks/0',headers=bob,content=b'intruder').status_code)
        # A fresh service instance reads committed state and issues another independent device session.
        restarted=TestClient(create_app(self.db));identity=restarted.post('/v1/sessions',json={'username':'alice','password':'test-alice-password-123','device':str(uuid.uuid4())}).json()
        other=BackupClient(restarted,identity,self.library);download=self.root/'download.iwbk';other.download(op,download)
        restored=self.root/'isolated-library.iwbackup';decrypt(download,restored,self.library,key)
        self.assertEqual(clear.read_bytes(),restored.read_bytes())
        with self.assertRaises(ValueError): BackupClient(restarted,dict(identity,server=str(uuid.uuid4())),self.library)
        restarted.close()

    def test_missing_changed_chunks_and_operation_payload_are_not_published(self):
        op=str(uuid.uuid4());data=b'ciphertext fixture';m={'format':'inkweft.encrypted-backup.v1','chunks':[hashlib.sha256(data).hexdigest()],'bytes':len(data)}
        self.client.request('PUT','/uploads/'+op,json=m)
        base='/v1/libraries/'+self.library+'/uploads/'+op
        self.assertEqual(409,self.http.post(base+'/publish',headers=self.client.headers).status_code)
        self.assertEqual(422,self.http.put(base+'/chunks/0',headers=self.client.headers,content=b'bad').status_code)
        self.assertEqual(409,self.http.put(base,headers=self.client.headers,json=dict(m,bytes=len(data)+1)).status_code)
        self.assertEqual([],self.client.request('GET','/versions').json())
        self.assertEqual('PENDING',self.client.request('GET','/operations/'+op).json()['state'])

    def test_revoke_expire_and_switch_preserve_queue_binding(self):
        _,encrypted,_=self.fixture();queue=self.root/'queue.json';self.client.upload(encrypted,queue)
        bob_client=BackupClient(self.http,self.bob,self.library)
        with self.assertRaises(ValueError): bob_client.upload(encrypted,queue)
        second=self.login('alice','test-alice-password-123')
        self.http.delete('/v1/devices/'+self.device,headers={'Authorization':'Bearer '+second['token']}).raise_for_status()
        self.assertEqual(401,self.http.get('/v1/libraries/'+self.library+'/versions',headers=self.client.headers).status_code)
        with Store(self.db).db() as c:c.execute('UPDATE sessions SET expires=0 WHERE device=?',(second['device'],))
        self.assertEqual(401,self.http.get('/v1/libraries/'+self.library+'/versions',headers={'Authorization':'Bearer '+second['token']}).status_code)
        self.assertTrue(queue.exists());self.assertTrue(encrypted.exists())

    def test_bad_recovery_tampering_truncation_and_existing_output_preserved(self):
        clear,encrypted,key=self.fixture()
        for label,content,use_key in [('key',encrypted.read_bytes(),os.urandom(32)),('truncated',encrypted.read_bytes()[:-20],key),('tampered',encrypted.read_bytes()[:-1]+bytes([encrypted.read_bytes()[-1]^1]),key)]:
            bad=self.root/(label+'.iwencrypted');bad.write_bytes(content);out=self.root/(label+'.restored')
            with self.assertRaises(Exception):decrypt(bad,out,self.library,use_key)
            self.assertFalse(out.exists())
        existing=self.root/'existing';existing.write_bytes(b'keep')
        with self.assertRaises(ValueError):encrypt(clear,existing,self.library,key)
        with self.assertRaises(ValueError):decrypt(encrypted,existing,self.library,key)
        self.assertEqual(b'keep',existing.read_bytes());self.assertEqual([],list(self.root.glob('*.partial')))

    def test_disk_failure_during_publication_keeps_previous_and_receipt_pending(self):
        _,encrypted,_=self.fixture();first=self.client.upload(encrypted,self.root/'q1')
        def disk_full():raise sqlite3.OperationalError('database or disk is full')
        failing=TestClient(create_app(self.db,before_publish=disk_full))
        second=BackupClient(failing,self.alice,self.library)
        with self.assertRaises(Exception):second.upload(encrypted,self.root/'q2')
        op=json.loads((self.root/'q2').read_text())['operation']
        self.assertEqual('PENDING',self.client.request('GET','/operations/'+op).json()['state'])
        self.assertEqual([first['operation']],[r['operation'] for r in self.client.request('GET','/versions').json()])
        self.assertEqual('PUBLISHED',self.client.upload(encrypted,self.root/'q2')['state']);failing.close()

    def test_delete_requires_new_explicit_operation_and_is_idempotent(self):
        _,encrypted,_=self.fixture();r=self.client.upload(encrypted,self.root/'queue');op=r['operation'];delete=str(uuid.uuid4())
        path=f'/versions/{op}?operation_id={delete}'
        first=self.client.request('DELETE',path).json();self.assertEqual(first,self.client.request('DELETE',path).json())
        self.assertEqual([],self.client.request('GET','/versions').json())
        self.assertEqual('DELETED',self.client.request('GET','/operations/'+op).json()['state'])

    def test_payload_size_and_password_reset_do_not_grant_decryption(self):
        self.assertEqual(413,self.http.post('/v1/sessions',content=b'x'*(CHUNK_LIMIT+32769)).status_code)
        _,encrypted,key=self.fixture();self.client.upload(encrypted,self.root/'q')
        with Store(self.db).db() as c:
            from server import HASHER
            c.execute('UPDATE users SET password=? WHERE id=?',(HASHER.hash('new-alice-password-789'),self.a))
            c.execute('UPDATE sessions SET revoked=1 WHERE user=?',(self.a,))
        identity=self.login('alice','new-alice-password-789');self.assertEqual(self.a,identity['user'])
        with self.assertRaises(Exception):decrypt(encrypted,self.root/'wrong-key',self.library,os.urandom(32))


if __name__=='__main__': unittest.main()
