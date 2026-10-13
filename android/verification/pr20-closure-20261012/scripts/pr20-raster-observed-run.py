#!/usr/bin/env python3
"""Run one existing targeted app test between read-only environment receipts."""
import json, pathlib, subprocess, sys
phase, selection, expected, *flags=sys.argv[1:]
out=pathlib.Path('/workspace/scratch/pr20-closure-evidence')
root=pathlib.Path('/workspace/scratch')
for suffix in ['-environment.json','-environment-after.json','-targeted.json']:
    if (out/(phase+suffix)).exists():
        raise SystemExit('Refusing existing phase '+phase)
subprocess.run([sys.executable,str(root/'record-inkweft-performance-environment.py'),phase,str(out/(phase+'-environment.json'))],check=True)
code=subprocess.run([sys.executable,str(root/'pr20-raster-targeted-instrumentation.py'),phase,selection,expected,*flags]).returncode
subprocess.run([sys.executable,str(root/'record-inkweft-performance-environment.py'),phase+'-after',str(out/(phase+'-environment-after.json'))],check=True)
print(json.dumps(dict(phase=phase,instrumentation_exit_code=code)),flush=True)
raise SystemExit(code)
