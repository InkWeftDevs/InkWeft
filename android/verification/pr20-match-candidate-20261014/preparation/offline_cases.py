"""Pure/mocked old-controller regressions and actual text packager cases; never Android/network."""
import ast,copy,datetime,hashlib,json,os,re,shutil,subprocess,sys,types
from pathlib import Path
import yaml

def run_checks(C,F,W):
    W.mkdir();text=(C/'run.py').read_text();tree=ast.parse(text)
    def function(name):return next(n for n in tree.body if isinstance(n,ast.FunctionDef)and n.name==name)
    def execute_nodes(nodes,scope,label):exec(compile(ast.Module(body=nodes,type_ignores=[]),label,'exec'),scope)
    def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
    results=[]
    # Reuse all19 old helper synthetic cases unchanged; old broad source-diff prelude is
    # replaced by check_preparation's direct AST equality of the exact helper function.
    original=ast.parse(Path('/workspace/scratch/pr20-candidate3-prep/check-pr20-candidate3.py').read_text())
    for name,first in [('process_exit_checks','original'),('official_dependency_gate_checks','cases')]:
        n=copy.deepcopy(next(n for n in original.body if isinstance(n,ast.FunctionDef)and n.name==name))
        start=next(i for i,x in enumerate(n.body)if isinstance(x,ast.Assign)and any(isinstance(t,ast.Name)and t.id==first for t in x.targets))
        n.body=n.body[start:]
        if name=='official_dependency_gate_checks':
            original_node=next(n for n in original.body if isinstance(n,ast.FunctionDef)and n.name==name)
            n.body=copy.deepcopy(original_node.body[:2])+n.body
        scope=dict(Path=Path,ast=ast,copy=copy,json=json,hashlib=hashlib,re=re,subprocess=subprocess,WORK=W,HERE=C,function=function,execute_nodes=execute_nodes,sha=sha)
        execute_nodes([n],scope,'<old-synthetic-cases-actual-new-functions>');result=scope[name]();results.append({'name':name,'status':'PASS','detail':result})
    # Actual new phase seconds() at each boundary; no real sleeping.
    for phase,minutes in [('setup',38),('correctness',41),('common',53),('diagnostics',55)]:
        for delta in [-1,0,1]:
            class Abort(RuntimeError):pass
            now=1000+minutes*60+delta
            scope={'PHASE':phase,'CUTOFFS':{k:1000+m*60 for k,m in [('setup',38),('correctness',41),('common',53),('diagnostics',55)]},'DEADLINE':4300,'time':types.SimpleNamespace(time=lambda:now),'BudgetAbort':Abort}
            execute_nodes([function('seconds')],scope,'<actual-phase-boundary>')
            try:value=scope['seconds'](3600);assert delta<0 and value==1
            except Abort:assert delta>=0
    results.append({'name':'phase_boundaries','status':'PASS','cases':12,'cutoffs':[38,41,53,55,60]})
    # Parse/bashedcheck actual workflow and execute its first clock using isolated
    # synthetic literals only. Original workflow remains inert.
    workflow=yaml.safe_load((C/'workflow.proposed.yml').read_text());steps=workflow['jobs']['triple']['steps'];blocks=[]
    for step in steps:
        if 'run'not in step:continue
        script=step['run'];p=subprocess.run(['bash','-n'],input=script,text=True,capture_output=True,timeout=10);assert p.returncode==0,p.stderr
        lines=script.splitlines()
        for i,line in enumerate(lines):
            m=re.search(r"python3 - <<'([A-Za-z]+)'",line)
            if m:
                end=lines.index(m[1],i+1);source='\n'.join(lines[i+1:end]);ast.parse(source);blocks.append((m[1],source))
    lint=subprocess.run(['/workspace/scratch/actionlint-1.7.12/actionlint','-shellcheck=',str(C/'workflow.proposed.yml')],capture_output=True,text=True,timeout=30);assert lint.returncode==0,lint.stdout+lint.stderr
    assert workflow['permissions']=={'contents':'read'} and workflow['jobs']['triple']['timeout-minutes']==60
    assert len(blocks)==2 and '0000000000000000000000000000000000000000'in workflow['jobs']['triple']['if']
    clocksource=next(s for label,s in blocks if label=='PYCODE');anchor=1791942801;updated='2026-10-14T01:53:21Z'
    for name,observed,valid in [('valid',anchor+100,True),('future',anchor-1,False),('expired',anchor+38*60,False)]:
        n=ast.parse(clocksource)
        for node in n.body:
            if isinstance(node,ast.Assign)and isinstance(node.targets[0],ast.Name):
                key=node.targets[0].id
                if key in ['anchor_literal','updated_at','contract_sha','receipt_sha']:node.value=ast.Constant({'anchor_literal':str(anchor),'updated_at':updated,'contract_sha':'a'*64,'receipt_sha':'b'*64}[key])
                elif key=='observed':node.value=ast.Constant(observed)
        ast.fix_missing_locations(n);root=W/('clock-'+name);(root/'evidence').mkdir(parents=True);envfile=root/'github-env';envfile.touch()
        script=root/'first-step.py';script.write_text(ast.unparse(n))
        p=subprocess.run([sys.executable,str(script)],env={'PATH':os.environ['PATH'],'KVM_ONCE':str(root),'GITHUB_ENV':str(envfile)},capture_output=True,text=True,timeout=10)
        assert (p.returncode==0)==valid;clock=json.loads((root/'evidence/clock.json').read_text());assert clock['status']==('PASS'if valid else'FAIL')
        if valid:assert len(envfile.read_text().splitlines())==5
    results.append({'name':'workflow_and_first_clock','status':'PASS','embedded_python':2,'first_clock_cases':3,'actionlint':True,'network_calls':0})
    # Package actual script in a scratch control. Only UNBOUND source hash is filled;
    # no executable controller / valid auth / actual anchor is created.
    root=W/'package-control';here=root/'ci/pr20-kvm-triple';here.mkdir(parents=True);frozen=root/'android/verification/pr20-match-candidate-20261014';shutil.copytree(F,frozen)
    for p in C.iterdir():
        if p.is_file():shutil.copyfile(p,here/p.name)
    (here/'EXECUTION_SHA256SUMS').write_text('SYNTHETIC_INPUT_NOT_EXECUTION_MANIFEST\n')
    (frozen/'C1-source-manifest.json').write_text('{"status":"SYNTHETIC_NOT_A_SOURCE_MANIFEST"}\n')
    pkg=(here/'package.py').read_text().replace("FROZEN_SOURCE_SPEC_SHA256='UNBOUND'","FROZEN_SOURCE_SPEC_SHA256='"+sha(frozen/'source-spec.json')+"'");(here/'package.py').write_text(pkg)
    spec=json.loads((frozen/'source-spec.json').read_text());plan=json.loads((frozen/'method-plan.json').read_text())
    for probe in spec['common_probes']:
        p=root/probe['from_path'];p.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(Path('/workspace/InkWeft')/probe['from_path'],p)
    # Read the actual fixed-file enumerator from the package AST, not a second list.
    ptree=ast.parse(pkg);scope={'plan':plan}
    begin=next(i for i,n in enumerate(ptree.body)if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='fixed'for t in n.targets))
    end=next(i for i,n in enumerate(ptree.body)if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='missing'for t in n.targets))
    execute_nodes(ptree.body[begin:end],scope,'<actual-package-whitelist>');fixed=scope['fixed']
    def complete():
        return {'run_id':'SYNTHETIC','workflow_sha':'a'*40,'input_hashes':{},'status':'EXECUTION_COMPLETE_ANALYSIS_PENDING','actions':{'setup':{'status':'PASS'}},'phases':[],
                'methods':[dict(s,status='PASS',junit_status='PASS',adb_exit=0)for s in plan['steps']],
                'install_groups':{s['install_group']:{'identity':s['identity'],'status':'PASS'}for s in plan['steps']}}
    def invoke(label,report,missing=None,cleanup_fail=False,secret=False,oversize=False,fill=True):
        once=W/('package-'+label);e=once/'evidence';e.mkdir(parents=True)
        if fill:
            for name in fixed:
                if name in ['summary.json','summary-before-packaging.json']:continue
                (e/name).write_text('{}\n'if name.endswith('.json')else'SYNTHETIC_SAFE_TEXT\n')
        for tag in ['trap','always']:
            receipt={'errors':['synthetic']if cleanup_fail and tag=='always'else[],'emulator':'STOPPED_CONFIRMED','command_groups':'STOPPED_CONFIRMED','acl':'RESTORED_EXACT'}
            (e/f'cleanup-{tag}.json').write_text(json.dumps(receipt))
        raw=(json.dumps(report,indent=2)+'\n').encode();(e/'summary.json').write_bytes(raw)
        if missing and (e/missing).exists():(e/missing).unlink()
        if secret:(e/'host.json').write_text('gh'+'p_'+'A'*36)
        if oversize:(e/'build-B.log').write_bytes(b'X'*(20*1024*1024))
        for name in ['bad.apk','bad.db','full-logcat.log']:(e/name).write_text('MUST_NOT_UPLOAD')
        done=subprocess.run([sys.executable,str(here/'package.py')],env={'PATH':os.environ['PATH'],'KVM_ONCE':str(once)},capture_output=True,text=True,timeout=30)
        (once/'stdout.log').write_text(done.stdout);(once/'stderr.log').write_text(done.stderr)
        assert done.returncode==(1 if secret or oversize else 0),done.stderr
        assert (e/'summary-before-packaging.json').read_bytes()==raw
        normalized=json.loads((e/'summary.json').read_text());assert normalized['methods']==report['methods']or any(m.get('status')in ['RUNNING','INSTALLED']for m in report['methods'])
        assert not any((once/'upload'/name).exists()for name in ['bad.apk','bad.db','full-logcat.log'])
        return normalized,once
    cases=[]
    r,p=invoke('complete62',complete());assert r['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING'and r['cleanup_verified']is True and r['missing_files']==[];cases.append('complete62-analysis-pending')
    r,p=invoke('missing-raw',complete(),missing='method-33-B-instrumentation.log');assert r['status']=='EVIDENCE_INCOMPLETE'and r['missing_files']==['method-33-B-instrumentation.log'];cases.append('missing-raw-incomplete')
    r,p=invoke('cleanup-failed',complete(),cleanup_fail=True);assert r['status']=='CLEANUP_UNCONFIRMED';cases.append('cleanup-failed')
    for state in ['PREPARING','SOURCES_FROZEN','MEASURING']:
        q=complete();q['status']=state;q['methods']=[dict(s,status='NOT_RUN')for s in plan['steps']]
        r,p=invoke(state,q,fill=False);assert r['status']=='INCOMPLETE_EXECUTION_NOT_FINISHED'and r['methods']==q['methods'];cases.append(state)
    q=complete();q['status']='MEASURING';q['actions']['next']={'status':'RUNNING','started_epoch':7};q['install_groups']['C1-diagnostics']['status']='INSTALLED';q['methods'][-1].update(status='RUNNING',junit_status='INCOMPLETE')
    r,p=invoke('interrupted',q);assert r['status']=='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION'and r['actions']['next']=={'status':'INCOMPLETE','status_before_packaging':'RUNNING','started_epoch':7};assert r['methods'][:-1]==q['methods'][:-1];cases.append('interrupted-no-invented-times')
    q=complete();q['status']='BLOCKED_OR_FAILED';q['methods'][-1].update(status='FAIL',junit_status='FAIL');r,p=invoke('failure',q);assert r['status']=='BLOCKED_OR_FAILED'and r['methods']==q['methods'];cases.append('original-failure')
    q=complete();q['status']='MEASURING';q['install_groups']['C1-diagnostics']['status']='INSTALLED';r,p=invoke('post-method-interrupted',q);assert r['status']=='INCOMPLETE_OUTER_TIMEOUT_OR_INTERRUPTION'and r['methods']==q['methods'];cases.append('all-methods-pass-but-postcondition-incomplete')
    r,p=invoke('secret',complete(),secret=True);assert json.loads((p/'upload/packaging.json').read_text())['complete']is False;assert not(p/'upload/host.json').exists();cases.append('secret-rejected')
    r,p=invoke('oversize',complete(),oversize=True);assert json.loads((p/'upload/packaging-failure.json').read_text())['status']=='EVIDENCE_OVERSIZE_NOT_UPLOADED';cases.append('oversize-no-truncation')
    r,p=invoke('missing-forwarding',complete(),missing='method-01-C1-log-forwarding.json');assert r['status']=='EVIDENCE_INCOMPLETE';cases.append('zero-record-method-still-needs-closure')
    results.append({'name':'actual_package_cases','status':'PASS','fresh_cases':len(cases),'cases':cases,'source_script_sha256':sha(C/'package.py'),'fixture_script_only_replaces_UNBOUND_source_hash':True})
    return results
