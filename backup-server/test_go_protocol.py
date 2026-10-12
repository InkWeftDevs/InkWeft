"""Existing streaming crypto/client fixtures, exercised against the actual Go executable."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import uuid
import httpx
from client import encrypt, decrypt, WIRE_BLOCK
from client_v2 import BackupClientV2, canonical


class GoProcess:
    def __init__(self,root):
        self.root=Path(root);self.db=self.root/'go.db'
        self.binary=os.environ.get('INKWEFT_BACKUP_BIN')
        if not self.binary:raise unittest.SkipTest('Build Go and set INKWEFT_BACKUP_BIN to run real-service acceptance')
        with socket.socket() as port:port.bind(('127.0.0.1',0));self.port=port.getsockname()[1]
        self.base=f'http://127.0.0.1:{self.port}';self.log=(self.root/'go-service.log').open('ab');self.process=None
        self.start()

    def start(self,crash=None,quotas=None,publication_budget=None):
        command=[self.binary,'--db',str(self.db),'--port',str(self.port)]
        if not self.db.exists():command.append('--fixture')
        if crash:command.extend(['--test-mode','--test-crash',crash])
        if publication_budget:command.extend(['--test-mode','--test-publication-budget',publication_budget])
        env=dict(os.environ,GOMEMLIMIT='384MiB');env.update(quotas or {})
        self.process=subprocess.Popen(command,stdout=self.log,stderr=self.log,env=env,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        for _ in range(100):
            if self.process.poll() is not None:raise RuntimeError('Go service stopped; inspect owned go-service.log')
            try:
                with httpx.Client(timeout=1,trust_env=False) as h:h.get(self.base+'/health/ready').raise_for_status()
                return
            except httpx.HTTPError:time.sleep(.05)
        raise RuntimeError('Go service readiness timeout')

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait(timeout=5)

    def restart(self,**kwargs):self.stop();self.start(**kwargs)
    def close(self):self.stop();self.log.close()


class GoProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='inkweft-go-acceptance-');self.root=Path(self.temp.name)
        try:self.server=GoProcess(self.root)
        except BaseException:self.temp.cleanup();raise
        self.http=httpx.Client(base_url=self.server.base,timeout=10,follow_redirects=False,trust_env=False)
        self.device=str(uuid.uuid4());self.library=str(uuid.uuid4());self.key=os.urandom(32)
        self.identity=self.login('synthetic-alice','synthetic-alice-password-123')
        self.client=BackupClientV2(self.http,self.identity,self.library)
        self.plain=self.root/'author.bin';self.plain.write_bytes(b'Synthetic protocol fixture\n'+os.urandom(4*WIRE_BLOCK))
        self.cipher=self.root/'author.iwbk';encrypt(self.plain,self.cipher,self.library,self.key,format=2)
        self.queue=self.root/'upload.json'

    def tearDown(self):
        self.http.close();self.server.close();self.temp.cleanup()

    def login(self,name,password):
        r=self.http.post('/v1/sessions',json=dict(username=name,password=password,device=self.device));r.raise_for_status();return r.json()

    def test_resume_seek_download_restart_crypto_and_isolation(self):
        self.client.upload(self.cipher,self.queue,stop_after=1);self.server.restart()
        resumed=BackupClientV2(self.http,self.identity,self.library);receipt=resumed.upload(self.cipher,self.queue)
        self.assertEqual('PUBLISHED',receipt['state']);self.assertEqual(self.cipher.stat().st_size-WIRE_BLOCK,resumed.local_read)
        self.assertEqual(receipt,resumed.upload(self.cipher,self.queue))
        downloaded=self.root/'download.iwbk';bitmap=self.root/'download.json'
        resumed.download(receipt['operation'],downloaded,bitmap,stop_after=1);self.server.restart()
        fresh=BackupClientV2(self.http,self.identity,self.library);fresh.download(receipt['operation'],downloaded,bitmap)
        self.assertEqual(self.cipher.stat().st_size-WIRE_BLOCK,fresh.transferred)
        clear=self.root/'restore.bin';decrypt(downloaded,clear,self.library,self.key)
        self.assertEqual(self.plain.read_bytes(),clear.read_bytes())
        wrong=self.root/'wrong.bin'
        with self.assertRaises(Exception):decrypt(downloaded,wrong,self.library,os.urandom(32))
        self.assertFalse(wrong.exists())
        with downloaded.open('r+b') as f:f.seek(500);v=f.read(1);f.seek(500);f.write(bytes([v[0]^1]))
        with self.assertRaises(Exception):decrypt(downloaded,wrong,self.library,self.key)
        self.assertFalse(wrong.exists())
        bob=self.login('synthetic-bob','synthetic-bob-password-456');foreign=BackupClientV2(self.http,bob,self.library)
        with self.assertRaises(httpx.HTTPStatusError) as error:foreign.request('GET','/uploads/'+receipt['operation'])
        self.assertEqual(404,error.exception.response.status_code)
        self.http.delete('/v1/devices/'+self.device,headers=self.client.headers).raise_for_status()
        self.assertEqual(401,self.http.get('/v2/capacity',headers=self.client.headers).status_code)

    def test_real_process_crashes_keep_only_durable_receipts(self):
        self.client.upload(self.cipher,self.queue,stop_after=1)
        q=json.loads(self.queue.read_text());op=q['operation'];chunk=self.cipher.read_bytes()[WIRE_BLOCK:2*WIRE_BLOCK]
        # Each crash is a process exit, not an in-memory exception masquerading as restart.
        for phase in ('before-file-publish','after-file-publish','before-chunk-commit','after-chunk-commit'):
            with self.subTest(phase=phase):
                # A new operation makes every checkpoint execute even after a previous committed block.
                target=str(uuid.uuid4());self.client.request('PUT','/uploads/'+target,json=q['manifest'])
                self.server.restart(crash=phase)
                with self.assertRaises(httpx.TransportError):self.client.request('PUT',f'/uploads/{target}/chunks/1',content=chunk)
                self.assertEqual(92,self.server.process.wait(timeout=10));self.server.start()
                inventory=self.client.request('GET','/uploads/'+target).json()
                self.assertEqual([1] if phase=='after-chunk-commit' else [],inventory['received'])
                self.client.request('PUT',f'/uploads/{target}/chunks/1',content=chunk)
                self.assertEqual([1],self.client.request('GET','/uploads/'+target).json()['received'])
                self.client.request('DELETE',f'/versions/{target}?operation_id={uuid.uuid4()}&expected_state=PENDING')
        self.client.upload(self.cipher,self.queue)
        for phase in ('before-publish-commit','after-publish-commit','after-delete-commit','after-payload-retire','before-retirement-commit'):
            with self.subTest(phase=phase):
                other=self.root/(str(uuid.uuid4())+'.json');receipt=self.client.upload(self.cipher,other,stop_after=len(q['manifest']['chunks']) if 'publish' in phase else None);target=receipt['operation'];delete_op=str(uuid.uuid4())
                self.server.restart(crash=phase)
                with self.assertRaises(httpx.TransportError):
                    if 'publish' in phase:self.client.request('POST',f'/uploads/{target}/publish')
                    else:self.client.request('DELETE',f'/versions/{target}?operation_id={delete_op}')
                self.assertEqual(92,self.server.process.wait(timeout=10));self.server.start()
                found=self.client.request('GET',f'/operations/{target if "publish" in phase else delete_op}').json()
                self.assertEqual('PENDING' if phase=='before-publish-commit' else 'PUBLISHED' if phase=='after-publish-commit' else 'DELETED',found['state'])
                if phase=='before-publish-commit':self.client.request('POST',f'/uploads/{target}/publish')

    def test_publication_progress_survives_real_process_exit_without_rereading_cipher(self):
        self.client.upload(self.cipher,self.queue,stop_after=(self.cipher.stat().st_size+WIRE_BLOCK-1)//WIRE_BLOCK)
        q=json.loads(self.queue.read_text());op=q['operation'];m=q['manifest']
        self.server.restart(crash='after-publication-progress-commit',publication_budget='1ns')
        with self.assertRaises(httpx.TransportError):self.client.request('POST',f'/uploads/{op}/publish')
        self.assertEqual(92,self.server.process.wait(timeout=10));self.server.start(publication_budget='1ns')
        receipt=self.client.request('GET',f'/operations/{op}').json()
        self.assertEqual('PENDING',receipt['state']);self.assertEqual(1,receipt['verified'])
        for expected in range(2,len(m['chunks'])+1):
            receipt=self.client.request('POST',f'/uploads/{op}/publish').json()
            self.assertEqual(expected,receipt['verified'])
            self.assertEqual('PUBLISHED' if expected==len(m['chunks']) else 'PENDING',receipt['state'])
        self.assertEqual(receipt,self.client.request('POST',f'/uploads/{op}/publish').json())

    def test_parallel_quota_missing_blocks_changed_payload_and_log_privacy(self):
        self.client.upload(self.cipher,self.queue,stop_after=1);q=json.loads(self.queue.read_text());op=q['operation']
        self.assertEqual(409,self.http.post(f'/v2/libraries/{self.library}/uploads/{op}/publish',headers=self.client.headers).status_code)
        changed=dict(q['manifest'],plainBytes=q['manifest']['plainBytes']-1)
        self.assertEqual(409,self.http.put(f'/v2/libraries/{self.library}/uploads/{op}',headers=self.client.headers,json=changed).status_code)
        limit=q['manifest']['bytes']*2
        self.server.restart(quotas={'INKWEFT_BACKUP_ACCOUNT_BYTES':str(limit),'INKWEFT_BACKUP_GLOBAL_BYTES':str(limit*2)})
        def reserve(_):return self.http.put(f'/v2/libraries/{self.library}/uploads/{uuid.uuid4()}',headers=self.client.headers,json=q['manifest']).status_code
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:statuses=list(pool.map(reserve,range(4)))
        self.assertEqual(1,statuses.count(200));self.assertEqual(3,statuses.count(507))
        self.server.stop();self.server.log.flush();logs=(self.root/'go-service.log').read_text()
        for secret in ('synthetic-alice','synthetic-alice-password-123',self.library,self.identity['token'],self.identity['user']):self.assertNotIn(secret,logs)


if __name__=='__main__':unittest.main()
