"""Offline fault cases using historical process observations; not new Android evidence."""
import argparse,copy,hashlib,json
from pathlib import Path
import analyze_logs as A
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);a=p.parse_args();assert not a.out.exists();a.out.mkdir(parents=True)
original=Path('/workspace/scratch/pr20-candidate3-results-20261014/raw/summary.json');summary=json.loads(original.read_text());methods=summary['methods'];cases=[]
def run(name,ordinal,mutate=None):
 m=copy.deepcopy(methods[ordinal-1]);context={k:m.get(k)for k in ['process_exit_observations','process_exit_outcome','auxiliary_cleanup','process_exit_seconds']}
 if mutate:mutate(context,m)
 try:A.process_exit(context,m);passed=True
 except (ValueError,KeyError,TypeError,IndexError):passed=False
 assert passed==(mutate is None),name
 cases.append({'name':name,'status':'PASS','accepted':passed})
run('historical natural receipt',1);run('historical method17 helper receipt',17)
run('missing observations',1,lambda c,m:c.update(process_exit_observations=[]))
run('natural has auxiliary receipt',1,lambda c,m:c.update(auxiliary_cleanup={}))
run('wrong helper method',17,lambda c,m:m.update(ordinal=16))
run('wrong helper identity',17,lambda c,m:m.update(identity='C0'))
run('wrong helper runner',17,lambda c,m:m['args'].__setitem__(-1,'wrong'))
run('helper cleanup wrong command',17,lambda c,m:c['auxiliary_cleanup']['command'].__setitem__(5,'force-stop'))
run('helper nonzero command',17,lambda c,m:c['auxiliary_cleanup'].update(exit=1))
run('helper cleanup before natural window',17,lambda c,m:c['auxiliary_cleanup'].update(started_seconds=.1))
run('helper after five-second window',17,lambda c,m:c.update(process_exit_seconds=5.001))
run('natural after three-second window',1,lambda c,m:c.update(process_exit_seconds=3.001))
run('missing one process',1,lambda c,m:c['process_exit_observations'][-1]['packages'].pop(A.PACKAGES[0]))
run('stderr is not absence',1,lambda c,m:c['process_exit_observations'][-1]['packages'][A.PACKAGES[0]].update(stderr='transport error'))
run('exit0 PID zero invalid',1,lambda c,m:c['process_exit_observations'][-1]['packages'][A.PACKAGES[0]].update(exit=0,stdout='0'))
run('exit1 nonempty invalid',1,lambda c,m:c['process_exit_observations'][-1]['packages'][A.PACKAGES[0]].update(stdout='123'))
run('app remains before helper cleanup',17,lambda c,m:c['process_exit_observations'][-2]['packages'][A.PACKAGES[0]].update(exit=0,stdout='987'))
run('helper still alive after cleanup',17,lambda c,m:c['process_exit_observations'][-1]['packages'][A.HELPER].update(exit=0,stdout='987'))
run('process chronology reverses',17,lambda c,m:c['process_exit_observations'][-1].update(elapsed_seconds=.01))
run('infinite process timer',1,lambda c,m:c.update(process_exit_seconds=float('inf')))
result={'scope':'OFFLINE_PARSER_TESTS_NOT_NEW_NATIVE','status':'PASS','cases':cases,'historical_fixture':str(original),'historical_fixture_sha256':A.sha(original.read_bytes()),'parser_sha256':A.sha(Path(A.__file__).read_bytes()),'checker_sha256':A.sha(Path(__file__).read_bytes())}
(a.out/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(len(cases),'offline cases PASS')
