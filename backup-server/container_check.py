"""Opt-in isolated Docker probe. Own random volume/container only; no deployment or host changes."""
import argparse,json,subprocess,tempfile,time,uuid
from pathlib import Path

def run(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def main(output):
    root=Path(__file__).resolve().parent
    name='inkweft-check-'+uuid.uuid4().hex[:12];volume=name+'-data';recovery_volume=name+'-recovery';recovery_name=name+'-restored'
    report={'status':'RUNNING','scope':'isolated encrypted business and independent-volume recovery; no TLS','schema':1}
    try:
        with tempfile.TemporaryDirectory() as tmp:
            iid=Path(tmp)/'image-id';run('build','--iidfile',str(iid),str(root));image=iid.read_text().strip();report['image']=image
        run('volume','create',volume)
        run('run','--rm','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:size=16m','-v',volume+':/state',image,'python','-c',
            "from server import Store; s=Store('/state/backup.db'); s.add_user('synthetic-only','synthetic-test-password-123'); print(s.server_id)")
        run('run','-d','--name',name,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:size=16m','-v',volume+':/state',image)
        for i in range(30):
            try:
                health=run('exec',name,'python','-c',"import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:18751/health/ready').read().decode())")
                break
            except subprocess.CalledProcessError:
                if i==29:raise
                time.sleep(1)
        report['uid']=run('exec',name,'id','-u');assert report['uid']=='10001'
        report['health']=json.loads(health)
        identity=lambda:run('exec',name,'python','-c',"import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:18751/v1/identity').read().decode())")
        probe=(root/'container_business.py').read_text()
        def business(container,mode):return json.loads(subprocess.check_output(['docker','exec','-i',container,'python','-',mode],input=probe,text=True))
        report['encrypted_upload']=business(name,'prepare')
        before=identity();run('restart',name)
        for i in range(30):
            try:after=identity();break
            except subprocess.CalledProcessError:time.sleep(1)
        assert before==after;report['persistent_identity']=json.loads(before)
        run('exec',name,'python','server.py','--db','/state/backup.db','--backup-to','/state/recovery.db')
        report['restart_download']=business(name,'verify')
        readonly="""import sqlite3,json
c=sqlite3.connect('/source/recovery.db')
assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
try:
 c.execute('CREATE TABLE blocked_write(x)');raise AssertionError('readonly mount was writable')
except sqlite3.OperationalError as e:assert 'readonly' in str(e).lower()
print(json.dumps({'status':'PASS','mount':'read-only','original_database_readable':True}))
"""
        report['readonly_volume']=json.loads(run('run','--rm','--read-only','--cap-drop=ALL','-v',volume+':/source:ro',image,'python','-c',readonly))
        full="""import sqlite3,shutil,os,json
shutil.copyfile('/source/recovery.db','/limited/backup.db')
c=sqlite3.connect('/limited/backup.db');before=c.execute('SELECT COUNT(*) FROM operations').fetchone()[0]
try:
 with open('/limited/fill','wb') as f:
  while True:f.write(bytes(65536))
except OSError as e:assert e.errno==28
try:
 c.execute('CREATE TABLE must_not_commit(x)');c.commit();raise AssertionError('full mount accepted write')
except sqlite3.OperationalError:c.rollback()
os.unlink('/limited/fill')
assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert c.execute('SELECT COUNT(*) FROM operations').fetchone()[0]==before
print(json.dumps({'status':'PASS','scope':'8 MiB private tmpfs only','published_operations_preserved':True}))
"""
        report['full_private_volume']=json.loads(run('run','--rm','--read-only','--cap-drop=ALL','--tmpfs','/limited:size=8m,uid=10001,gid=10001,mode=0700','-v',volume+':/source:ro',image,'python','-c',full))
        report['original_after_faults']=business(name,'verify')
        run('volume','create',recovery_volume)
        run('run','--rm','--read-only','--cap-drop=ALL','-v',volume+':/source:ro','-v',recovery_volume+':/state',image,'python','-c',"import shutil; shutil.copyfile('/source/recovery.db','/state/backup.db'); shutil.copyfile('/source/probe.json','/state/probe.json')")
        run('run','-d','--name',recovery_name,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/tmp:size=16m','-v',recovery_volume+':/state',image)
        for i in range(30):
            try:report['independent_volume_download']=business(recovery_name,'verify');break
            except subprocess.CalledProcessError:
                if i==29:raise
                time.sleep(1)
        report['status']='PASS'
    except Exception as e:
        report['status']='FAIL';report['error_type']=type(e).__name__;raise
    finally:
        subprocess.run(['docker','rm','-f',recovery_name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','volume','rm',recovery_volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','volume','rm',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        Path(output).write_text(json.dumps(report,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);args=p.parse_args();main(args.output)
