#!/usr/bin/env python3
"""Recompute archived UTF-8 evidence offline; no network, Android, CI or controller execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out',type=Path,required=True,help='New scratch directory outside this archive')
args=parser.parse_args();out=args.out.resolve()
if out.exists() or out==ROOT or ROOT in out.parents:
    parser.error('Use a new output directory outside the archive')
out.mkdir(parents=True)
env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1')
def run(script,*arguments):
    subprocess.run([sys.executable,'-B',str(ROOT/script),*map(str,arguments)],check=True,env=env)
run('tools/log-analysis/analyze_logs.py',
 '--job-log',ROOT/'official/job-decoded.log','--run-metadata',ROOT/'official/run-terminal.json',
 '--jobs-metadata',ROOT/'official/jobs-terminal.json','--binding',ROOT/'official/binding.json',
 '--frozen',ROOT/'tools/frozen-readiness','--control-root',ROOT/'replay-inputs/control',
 '--run-id','38514139119','--job-id','115689021611','--workflow-sha','d4a107dd84c6e309b5dbbbfb3a57d69371024408',
 '--source-commit','78a5babeaeb92292026423222d5e08635989198b','--readiness-git','b2e1d9f9de66a26f5a4b122ea6754d77198005f8',
 '--out',out/'extracted')
record_matches=[]
for i in range(1,14):
    name=f'method-{i:02d}-C1-records.json';original=(ROOT/'extracted'/name).read_bytes();replayed=(out/'extracted'/name).read_bytes()
    assert original==replayed, name+' differs from archived original records'
    record_matches.append({'file':name,'bytes':len(original),'sha256':hashlib.sha256(original).hexdigest(),'exact_match':True})
run('tools/search-analysis/analyze_search.py',
 '--warm',out/'extracted/method-09-C1-records.json','--saves',out/'extracted/method-10-C1-records.json',
 '--unicode',out/'extracted/method-08-C1-records.json','--extraction-analysis',out/'extracted/analysis.json','--out',out/'search-analysis')
run('tools/raster-analysis/analyze.py','--records',out/'extracted/method-13-C1-records.json',
 '--integrity-report',out/'extracted/analysis.json','--out',out/'raster-analysis')
run('tools/gc-analysis/analyze_gc.py','--evidence',out/'extracted',
 '--terminal-analysis-json',out/'extracted/analysis.json','--expected-run-id','38514139119',
 '--expected-job-id','115689021611','--expected-workflow-sha','d4a107dd84c6e309b5dbbbfb3a57d69371024408','--output',out/'gc-analysis')
# This original review builder uses its own directory; copy only this one text script to scratch.
review=out/'gc-analysis/build_review.py';shutil.copyfile(ROOT/'gc-analysis/build_review.py',review)
subprocess.run([sys.executable,'-B',str(review)],check=True,env=env)
csv_matches=[]
for name in ['warm-udf.csv','save-samples.csv','save-phases.csv','original-events.csv']:
    a=(ROOT/'search-analysis'/name).read_bytes();b=(out/'search-analysis'/name).read_bytes()
    assert a==b, name+' differs from archived CSV'
    csv_matches.append({'file':name,'bytes':len(a),'sha256':hashlib.sha256(a).hexdigest(),'exact_match':True})
receipt={'scope':'OFFLINE_RECOMPUTATION_NOT_NEW_NATIVE_EVIDENCE','status':'PASS',
 'run_id':'38514139119','records_exact_matches':record_matches,'csv_exact_matches':csv_matches,
 'archive_outputs_unchanged':True,'controller_scripts_executed':False,'network_or_native_execution':False,
 'note':'Derived JSON input-path/provenance hashes change with local paths; original compact records and numerical CSVs must match byte-for-byte.'}
(out/'reproduction-check.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'status':'PASS','records_exact':len(record_matches),'csv_exact':len(csv_matches),'out':str(out)}))
