"""Host-only ownership tests. Synthetic foreground server; no adb, Android, KVM or network service."""
import ast,json,os,pathlib,re,signal,socket,subprocess,sys,tempfile,time,types,unittest
P=pathlib.Path;H=P(__file__).resolve().parent
FAKE='''#!{python}
import os,socket,sys,time
mode=os.environ.get('FAKE_ADB_MODE','normal')
if mode=='exit':sys.exit(2)
if mode=='hang':time.sleep(60);sys.exit(0)
s=socket.socket(fileno=int(sys.argv[sys.argv.index('-L')+1].split(':')[1]))
while True:
 c,_=s.accept()
 with c:
  data=b''
  while len(data)<16:
   part=c.recv(16-len(data))
   if not part:break
   data+=part
  if data==b'000chost:version':c.sendall(b'OKAY00040029')
'''
class AdbOwnershipTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='adb-owned-',dir='/workspace/scratch/caaa9f54a482');self.root=P(self.tmp.name);self.out=self.root/'evidence';self.out.mkdir();sdk=self.root/'sdk';(sdk/'platform-tools').mkdir(parents=True)
  adb=sdk/'platform-tools/adb';adb.write_text(FAKE.format(python=sys.executable));adb.chmod(0o700)
  self.children=[]
  def popen(*a,**k):
   p=subprocess.Popen(*a,**k);self.children.append(p);return p
  with socket.socket() as available:available.bind(('127.0.0.1',0));self.address=available.getsockname()
  proxy=types.SimpleNamespace(Popen=popen,DEVNULL=subprocess.DEVNULL,STDOUT=subprocess.STDOUT,PIPE=subprocess.PIPE,TimeoutExpired=subprocess.TimeoutExpired)
  self.ns={'Path':P,'json':json,'os':os,'re':re,'socket':socket,'subprocess':proxy,'time':time,'ROOT':self.root,'OUT':self.out,'SDK':sdk,'ADB_LISTEN':self.address,'ADB_SERVER_ENV':{'ADB_SERVER_SOCKET':'tcp:127.0.0.1:5037','ANDROID_ADB_SERVER_ADDRESS':'127.0.0.1','ANDROID_ADB_SERVER_PORT':'5037'},'PHASE':'setup','CUTOFFS':{'setup':time.time()+30},'DEADLINE':time.time()+30}
  tree=ast.parse((H/'run.py').read_text())
  for n in tree.body:
   if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in ['BudgetAbort','write_json','seconds','owned','run','start_adb_server']:exec(compile(ast.Module([n],type_ignores=[]),'actual-controller-functions','exec'),self.ns)
  self.previous=os.environ.get('FAKE_ADB_MODE');os.environ['FAKE_ADB_MODE']='normal'
  self.previous_user_home=os.environ.get('ANDROID_USER_HOME');os.environ['ANDROID_USER_HOME']=str(self.root/'android-user')
 def cleanup(self,tag='trap'):
  q=subprocess.run([sys.executable,H/'cleanup.py',tag],env=dict(os.environ,KVM_ONCE=str(self.root)),capture_output=True,text=True,timeout=8)
  return q,json.loads((self.out/f'cleanup-{tag}.json').read_text())
 def alive(self):
  p=self.root/'adb-server.json'
  if not p.exists():return False
  saved=json.loads(p.read_text());proc=P('/proc')/str(saved['pid'])/'stat'
  return proc.exists() and proc.read_text().rsplit(')',1)[1].split()[0]!='Z'
 def tearDown(self):
  p=self.root/'adb-server.json'
  if p.exists():
   saved=json.loads(p.read_text());proc=P('/proc')/str(saved['pid'])/'stat'
   if proc.exists():
    fields=proc.read_text().rsplit(')',1)[1].split()
    # Test-owned processes only, including a deliberately tampered receipt test.
    if P('/proc',str(saved['pid']),'cmdline').read_bytes().find(str(self.root).encode())>=0:
     if fields[0]!='Z':os.kill(saved['pid'],signal.SIGKILL)
     try:os.waitpid(saved['pid'],0)
     except ChildProcessError:pass
  if self.previous is None:os.environ.pop('FAKE_ADB_MODE',None)
  else:os.environ['FAKE_ADB_MODE']=self.previous
  if self.previous_user_home is None:os.environ.pop('ANDROID_USER_HOME',None)
  else:os.environ['ANDROID_USER_HOME']=self.previous_user_home
  for child in self.children:child.wait(timeout=2)
  self.tmp.cleanup()
 def test_foreground_owner_stops_and_repeated_always_cleanup_is_safe(self):
  self.ns['start_adb_server']();self.assertTrue(self.alive());receipt=json.loads((self.out/'adb-server.json').read_text());self.assertEqual('READY',receipt['status'])
  for tag in ['trap','always']:
   q,r=self.cleanup(tag);self.assertEqual(0,q.returncode,q.stderr);self.assertEqual('STOPPED_CONFIRMED',r['adb_server']);self.assertEqual([],r['errors']);self.assertFalse(self.alive())
 def test_foreign_default_listener_is_refused_without_start_or_signal(self):
  with socket.socket() as foreign:
   foreign.bind(self.address);foreign.listen(1)
   with self.assertRaises(OSError):self.ns['start_adb_server']()
   self.assertFalse((self.root/'adb-server.json').exists());self.assertEqual(self.address,foreign.getsockname())
   q,r=self.cleanup();self.assertEqual(0,q.returncode);self.assertEqual('NOT_STARTED',r['adb_server'])
 def test_foreign_config_directory_is_refused_before_start(self):
  foreign=self.root/'unowned-config';os.environ['ANDROID_USER_HOME']=str(foreign)
  with self.assertRaises(AssertionError):self.ns['start_adb_server']()
  self.assertFalse(foreign.exists());self.assertEqual([],self.children)
  q,r=self.cleanup();self.assertEqual(0,q.returncode);self.assertEqual('NOT_STARTED',r['adb_server'])
 def test_early_server_exit_is_not_ready(self):
  os.environ['FAKE_ADB_MODE']='exit'
  with self.assertRaises((AssertionError,FileNotFoundError,ProcessLookupError)):self.ns['start_adb_server']()
  self.assertFalse(self.alive());q,r=self.cleanup();self.assertEqual(0,q.returncode)
 def test_startup_phase_timeout_remains_owned_for_cleanup(self):
  os.environ['FAKE_ADB_MODE']='hang';self.ns['CUTOFFS']['setup']=time.time()+.2
  with self.assertRaises(self.ns['BudgetAbort']):self.ns['start_adb_server']()
  self.assertTrue(self.alive());q,r=self.cleanup();self.assertEqual(0,q.returncode);self.assertEqual('STOPPED_CONFIRMED',r['adb_server'])
 def test_cancellation_after_identity_write_remains_owned_for_cleanup(self):
  def cancel(*a,**k):raise KeyboardInterrupt
  self.ns['socket']=types.SimpleNamespace(socket=socket.socket,AF_INET=socket.AF_INET,SOCK_STREAM=socket.SOCK_STREAM,SOL_SOCKET=socket.SOL_SOCKET,SO_REUSEADDR=socket.SO_REUSEADDR,create_connection=cancel)
  with self.assertRaises(KeyboardInterrupt):self.ns['start_adb_server']()
  q,r=self.cleanup();self.assertEqual(0,q.returncode);self.assertEqual('STOPPED_CONFIRMED',r['adb_server'])
 def test_slow_trickle_after_late_retry_cannot_extend_startup_cap(self):
  base=time.time();clock={'offset':0.0,'attempts':0};self.ns['time']=types.SimpleNamespace(time=lambda:base+clock['offset'],sleep=lambda delay:clock.update(offset=clock['offset']+delay))
  class Connection:
   data=b'OKAY00040029'
   def __enter__(self):return self
   def __exit__(self,*args):pass
   def settimeout(self,value):self.timeout=value
   def sendall(self,data):pass
   def recv(self,count):
    if self.timeout<.9:clock['offset']+=self.timeout;raise TimeoutError
    clock['offset']+=.9;data=self.data[:1];self.data=self.data[1:];return data
  def connect(*a,**k):
   clock['attempts']+=1
   if clock['attempts']==1:clock['offset']=14.5;raise OSError('synthetic late retry')
   return Connection()
  self.ns['socket']=types.SimpleNamespace(socket=socket.socket,AF_INET=socket.AF_INET,SOCK_STREAM=socket.SOCK_STREAM,SOL_SOCKET=socket.SOL_SOCKET,SO_REUSEADDR=socket.SO_REUSEADDR,create_connection=connect)
  with self.assertRaises(self.ns['BudgetAbort']):self.ns['start_adb_server']()
  self.assertEqual(2,clock['attempts']);self.assertLessEqual(clock['offset'],15.000001)  # Float epoch subtraction tolerance only.
  self.assertEqual('STARTING',json.loads((self.out/'adb-server.json').read_text())['status'])
  q,r=self.cleanup();self.assertEqual(0,q.returncode);self.assertEqual('STOPPED_CONFIRMED',r['adb_server'])
 def test_changed_identity_refuses_cleanup_signal(self):
  self.ns['start_adb_server']();p=self.root/'adb-server.json';saved=json.loads(p.read_text());bad=dict(saved,start=str(int(saved['start'])+1));p.write_text(json.dumps(bad))
  q,r=self.cleanup();self.assertNotEqual(0,q.returncode);self.assertEqual('STOP_UNCONFIRMED',r['adb_server']);self.assertTrue(self.alive())
  p.write_text(json.dumps(saved));q,r=self.cleanup('always');self.assertEqual(0,q.returncode);self.assertFalse(self.alive())
 def test_client_without_owned_server_cannot_spawn(self):
  with self.assertRaises(FileNotFoundError):self.ns['run'](['adb','-H','127.0.0.1','devices'])
  self.assertFalse((self.root/'command-groups.json').exists())
 def test_client_with_changed_identity_cannot_spawn(self):
  self.ns['start_adb_server']();p=self.root/'adb-server.json';saved=json.loads(p.read_text());p.write_text(json.dumps(dict(saved,start='0')))
  with self.assertRaises(AssertionError):self.ns['run'](['adb','-H','127.0.0.1','devices'])
  self.assertFalse((self.root/'command-groups.json').exists());p.write_text(json.dumps(saved));self.cleanup()
if __name__=='__main__':unittest.main()
