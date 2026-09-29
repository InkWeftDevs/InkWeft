"""Opt-in isolated Docker probe. Own random volume/container only; no deployment or host changes."""
import argparse,json,subprocess,tempfile,time,uuid
from pathlib import Path

def run(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def main(output):
    root=Path(__file__).resolve().parent
    name='inkweft-check-'+uuid.uuid4().hex[:12];volume=name+'-data';recovery_volume=name+'-recovery';recovery_name=name+'-restored'
    report={'status':'RUNNING','scope':'isolated encrypted business and independent-volume recovery; no TLS','schema':2}
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
        faults=(root/'container_faults.py').read_text()
        def fault(key,mode,*arguments):
            result=subprocess.run(['docker',*arguments,'python','-',mode],input=faults,text=True,stdout=subprocess.PIPE)
            if result.stdout.strip():report[key]=json.loads(result.stdout)
            result.check_returncode()
            return report[key]
        report['encrypted_upload']=business(name,'prepare')
        before=identity();run('restart',name)
        for i in range(30):
            try:after=identity();break
            except subprocess.CalledProcessError:time.sleep(1)
        assert before==after;report['persistent_identity']=json.loads(before)
        run('exec',name,'python','server.py','--db','/state/backup.db','--backup-to','/state/recovery.db')
        sealed=fault('sealed_snapshot','seal','exec','-i',name)
        report['live_journal_mode']=run('exec',name,'python','-c',"import sqlite3; c=sqlite3.connect('/state/backup.db'); print(c.execute('PRAGMA journal_mode').fetchone()[0]); c.close()")
        assert report['live_journal_mode']=='wal'
        report['restart_download']=business(name,'verify')
        readonly=fault('readonly_volume','readonly','run','--rm','-i','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','-v',volume+':/source:ro',image)
        assert readonly['contents']==sealed['contents']
        full=fault('full_private_volume','full','run','--rm','-i','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges','--tmpfs','/limited:size=8m,uid=10001,gid=10001,mode=0700','-v',volume+':/source:ro',image)
        assert full['contents']==sealed['contents']
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
