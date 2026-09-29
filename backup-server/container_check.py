"""Opt-in isolated Docker probe. Own random volume/container only; no deployment or host changes."""
import argparse,json,subprocess,tempfile,time,uuid
from pathlib import Path

def run(*args):return subprocess.check_output(['docker',*args],text=True).strip()
def main(output):
    root=Path(__file__).resolve().parent
    name='inkweft-check-'+uuid.uuid4().hex[:12];volume=name+'-data'
    report={'status':'RUNNING','scope':'isolated container, no TLS','schema':1}
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
        before=identity();run('restart',name)
        for i in range(30):
            try:after=identity();break
            except subprocess.CalledProcessError:time.sleep(1)
        assert before==after;report['persistent_identity']=json.loads(before)
        run('exec',name,'python','server.py','--db','/state/backup.db','--backup-to','/state/recovery.db')
        report['status']='PASS'
    except Exception as e:
        report['status']='FAIL';report['error_type']=type(e).__name__;raise
    finally:
        subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['docker','volume','rm',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        Path(output).write_text(json.dumps(report,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);args=p.parse_args();main(args.output)
