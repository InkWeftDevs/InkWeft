import json, os, pathlib, signal, stat, subprocess, sys, time
root=pathlib.Path(os.environ['KVM_ONCE']); out=root/'evidence'
receipt={'emulator':'NOT_STARTED','command_groups':'NOT_STARTED','acl':'NOT_TOUCHED','errors':[]}
def identity(path):
    s=os.lstat(path)
    if not stat.S_ISCHR(s.st_mode): raise RuntimeError('KVM is not a character node')
    return [s.st_dev,s.st_ino,s.st_rdev]
def process(pid):
    p=pathlib.Path(f'/proc/{pid}')
    if not p.exists(): return None
    fields=(p/'stat').read_text().rsplit(')',1)[1].split()
    if fields[0]=='Z': return None
    return {'pid':pid,'uid':p.stat().st_uid,'start':fields[19]}
try:
    if (root/'emulator.json').exists():
        saved=json.loads((root/'emulator.json').read_text()); current=process(saved['pid'])
        if current is not None:
            if current!=saved: raise RuntimeError('Emulator PID identity changed; no signal sent')
            os.killpg(saved['pid'],signal.SIGTERM)
            for _ in range(100):
                if process(saved['pid']) is None: break
                time.sleep(.1)
            if process(saved['pid']) is not None:
                os.killpg(saved['pid'],signal.SIGKILL)
                time.sleep(1)
            if process(saved['pid']) is not None: raise RuntimeError('Owned emulator still running')
        receipt['emulator']='STOPPED_CONFIRMED'
except Exception as e: receipt['errors'].append(str(e))
try:
    if (root/'command-groups.json').exists():
        groups=json.loads((root/'command-groups.json').read_text())
        if (root/'emulator.json').exists(): groups.append(json.loads((root/'emulator.json').read_text()))
        def members(group):
            leader=process(group['pid'])
            if leader is not None and leader!=group: raise RuntimeError('Command group leader identity changed')
            found=[]
            for p in pathlib.Path('/proc').iterdir():
                if not p.name.isdecimal(): continue
                try:
                    f=(p/'stat').read_text().rsplit(')',1)[1].split()
                    if int(f[2])==group['pid'] and f[0]!='Z':
                        if p.stat().st_uid!=group['uid'] or int(f[19])<int(group['start']):
                            raise RuntimeError('Command group identity mismatch')
                        found.append(int(p.name))
                except (FileNotFoundError,ProcessLookupError): pass
            return found
        for group in groups:
            if members(group):
                os.killpg(group['pid'],signal.SIGTERM)
                for _ in range(20):
                    if not members(group): break
                    time.sleep(.1)
                if members(group): os.killpg(group['pid'],signal.SIGKILL)
        time.sleep(.2)
        if any(members(g) for g in groups): raise RuntimeError('Owned command group remains active')
        receipt['command_groups']='STOPPED_CONFIRMED'
except Exception as e:
    receipt['command_groups']='STOP_UNCONFIRMED'
    if (root/'emulator.json').exists(): receipt['emulator']='STOP_UNCONFIRMED'
    receipt['errors'].append(str(e))
try:
    if (out/'kvm-before.acl').exists():
        if identity('/dev/kvm')!=json.loads((out/'kvm-identity.json').read_text()):
            raise RuntimeError('KVM node identity changed; no restore attempted')
        if (root/'acl-may-have-changed').exists():
            subprocess.run(['sudo','-n','setfacl','--restore='+str(out/'kvm-before.acl')],check=True,timeout=15)
        after=subprocess.check_output(['getfacl','-pn','/dev/kvm'],timeout=15)
        (out/('kvm-after-'+sys.argv[1]+'.acl')).write_bytes(after)
        if after!=(out/'kvm-before.acl').read_bytes(): raise RuntimeError('ACL exact comparison failed')
        receipt['acl']='RESTORED_EXACT' if (root/'acl-may-have-changed').exists() else 'UNCHANGED_EXACT'
except Exception as e:
    receipt['acl']='RESTORATION_UNCONFIRMED';receipt['errors'].append(str(e))
(out/('cleanup-'+sys.argv[1]+'.json')).write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt));sys.exit(bool(receipt['errors']))
