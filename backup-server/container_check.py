"""Local Docker acceptance for the Go-only image. All containers/volumes are uniquely owned."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import httpx
from client import encrypt, decrypt, WIRE_BLOCK
from client_v2 import BackupClientV2

def run(*args):return subprocess.check_output(['docker',*args],text=True).strip()

def main(output):
    root=Path(__file__).resolve().parent;name='inkweft-go-check-'+uuid.uuid4().hex[:12]
    volume=name+'-data';recovery_volume=name+'-recovery';recovery=name+'-restored';limited=name+'-limited'
    report={'status':'RUNNING','scope':'Go container, private-volume recovery and bounded-disk rejection','schema':2003}
    daemon=False
    try:
        run('info','--format','{{.ServerVersion}}')
        daemon=True
        with tempfile.TemporaryDirectory(prefix='inkweft-go-container-') as directory:
            scratch=Path(directory);iid=scratch/'image-id';run('build','--iidfile',str(iid),str(root));image=iid.read_text().strip();report['image']=image
            run('volume','create',volume)
            options=['--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:size=16m']
            run('run','-d','--name',name,*options,'-p','127.0.0.1::18751','-v',volume+':/state',image,'inkweft-backup','--db','/state/backup-v2.db','--host','0.0.0.0','--fixture')
            def ready(container):
                for _ in range(40):
                    try:run('exec',container,'inkweft-backup','--check-ready','http://127.0.0.1:18751/health/ready');return
                    except subprocess.CalledProcessError:time.sleep(.25)
                raise RuntimeError('Go container did not become ready')
            def http(container):
                port=int(run('port',container,'18751').rsplit(':',1)[1]);return httpx.Client(base_url=f'http://127.0.0.1:{port}',timeout=30,trust_env=False,follow_redirects=False)
            ready(name)
            # Synthetic initialization is one-shot; restart the actual service without --fixture.
            run('stop',name);run('rm',name)
            run('run','-d','--name',name,*options,'-p','127.0.0.1::18751','-v',volume+':/state',image,'inkweft-backup','--db','/state/backup-v2.db','--host','0.0.0.0')
            ready(name);report['uid']=run('exec',name,'id','-u');assert report['uid']=='10001'
            device,library=str(uuid.uuid4()),str(uuid.uuid4());key=os.urandom(32);plain=scratch/'plain';plain.write_bytes(os.urandom(2*WIRE_BLOCK));cipher=scratch/'cipher'
            encrypt(plain,cipher,library,key,format=2)
            def login(h):
                response=h.post('/v1/sessions',json={'username':'synthetic-alice','password':'synthetic-alice-password-123','device':device});response.raise_for_status();return response.json()
            with http(name) as h:
                identity=login(h);client=BackupClientV2(h,identity,library);queue=scratch/'upload.json';client.upload(cipher,queue,stop_after=1);receipt=client.upload(cipher,queue);assert receipt['state']=='PUBLISHED'
            run('restart',name);ready(name)
            def verify(container,label):
                with http(container) as h:
                    who=login(h);assert all(who[k]==identity[k] for k in ('server','issuer','user'))
                    client=BackupClientV2(h,who,library);assert client.request('GET','/operations/'+receipt['operation']).json()==receipt
                    download=scratch/(label+'.iwbk');client.download(receipt['operation'],download,scratch/(label+'.json'));clear=scratch/(label+'.clear');decrypt(download,clear,library,key)
                    with plain.open('rb') as a,clear.open('rb') as b:assert hashlib.file_digest(a,'sha256').digest()==hashlib.file_digest(b,'sha256').digest()
                report[label]='PASS'
            verify(name,'restart_download')
            run('stop',name);run('volume','create',recovery_volume)
            # Stop first; copy metadata, WAL/SHM and immutable files as one private directory.
            run('run','--rm','--read-only','--cap-drop=ALL','-v',volume+':/source:ro','-v',recovery_volume+':/state',image,'cp','-a','/source/.','/state/')
            run('run','-d','--name',recovery,*options,'-p','127.0.0.1::18751','-v',recovery_volume+':/state',image);ready(recovery);verify(recovery,'independent_volume_restore')
            run('run','-d','--name',limited,'--read-only','--cap-drop=ALL','-p','127.0.0.1::18751','--tmpfs','/state:size=48m,uid=10001,gid=10001,mode=0700',image,'inkweft-backup','--db','/state/backup-v2.db','--host','0.0.0.0','--fixture');ready(limited)
            with http(limited) as h:
                who=login(h);client=BackupClientV2(h,who,library)
                limited_queue=scratch/'limited-upload.json';client.upload(cipher,limited_queue,stop_after=1)
                operation=json.loads(limited_queue.read_text())['operation'];before=client.request('GET','/uploads/'+operation).json()
                # Fill only the owned 48 MiB tmpfs. No host volume or author directory is touched.
                filled=subprocess.run(['docker','exec',limited,'dd','if=/dev/zero','of=/state/test-fill.bin','bs=1M','count=64'],capture_output=True,text=True)
                assert filled.returncode!=0 and 'No space left on device' in filled.stderr
                report['full_volume_available_bytes']=int(run('exec',limited,'df','-B1','--output=avail','/state').splitlines()[-1])
                assert report['full_volume_available_bytes']<4096
                try:client.upload(cipher,limited_queue);raise AssertionError('Full physical volume accepted another chunk')
                except httpx.HTTPStatusError as error:assert error.response.status_code==507
                after=client.request('GET','/uploads/'+operation).json()
                assert before['received']==after['received']==[0] and after['state']=='PENDING'
                run('exec',limited,'rm','/state/test-fill.bin')
                assert client.upload(cipher,limited_queue)['state']=='PUBLISHED'
                restored=scratch/'after-full.iwbk';client.download(operation,restored,scratch/'after-full.json');clear=scratch/'after-full.clear';decrypt(restored,clear,library,key)
                assert plain.read_bytes()==clear.read_bytes()
            report['physical_full_volume_and_resume']='PASS';verify(recovery,'original_after_full_volume');report['status']='PASS'
    except Exception as error:
        report['status']='FAIL';report['error_type']=type(error).__name__;raise
    finally:
        if daemon:
            for container in (limited,recovery,name):subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            for item in (recovery_volume,volume):subprocess.run(['docker','volume','rm',item],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        Path(output).parent.mkdir(parents=True,exist_ok=True);Path(output).write_text(json.dumps(report,indent=2),encoding='utf-8')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);main(parser.parse_args().output)
