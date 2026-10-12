"""Read-only resource evidence for one explicitly identified acceptance process."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
import time

def sample(pid,binary):
    if os.name=='nt':
        from ctypes import wintypes as w
        k=ctypes.WinDLL('kernel32',use_last_error=True);k.OpenProcess.argtypes=[w.DWORD,w.BOOL,w.DWORD];k.OpenProcess.restype=w.HANDLE
        handle=k.OpenProcess(0x1010,False,pid)
        if not handle:raise ProcessLookupError(pid)
        try:
            size=w.DWORD(32768);name=ctypes.create_unicode_buffer(size.value)
            k.QueryFullProcessImageNameW.argtypes=[w.HANDLE,w.DWORD,w.LPWSTR,ctypes.POINTER(w.DWORD)]
            if not k.QueryFullProcessImageNameW(handle,0,name,ctypes.byref(size)):raise ctypes.WinError(ctypes.get_last_error())
            if Path(name.value).resolve()!=Path(binary).resolve():raise RuntimeError('PID image identity changed')
            class Counters(ctypes.Structure):
                _fields_=[('cb',w.DWORD),('faults',w.DWORD)]+[(field,ctypes.c_size_t) for field in ('peak','working','peakPaged','paged','peakNonPaged','nonPaged','pagefile','peakPagefile','private')]
            c=Counters();c.cb=ctypes.sizeof(c);p=ctypes.WinDLL('psapi',use_last_error=True);p.GetProcessMemoryInfo.argtypes=[w.HANDLE,ctypes.c_void_p,w.DWORD]
            if not p.GetProcessMemoryInfo(handle,ctypes.byref(c),c.cb):raise ctypes.WinError(ctypes.get_last_error())
            return {'residentBytes':c.working,'peakResidentBytes':c.peak,'privateBytes':c.private}
        finally:k.CloseHandle.argtypes=[w.HANDLE];k.CloseHandle(handle)
    if Path(f'/proc/{pid}/exe').resolve()!=Path(binary).resolve():raise RuntimeError('PID image identity changed')
    values={line.split(':')[0]:line.split(':')[1].strip() for line in Path(f'/proc/{pid}/status').read_text().splitlines() if ':' in line}
    return {'residentBytes':int(values['VmRSS'].split()[0])*1024,'peakResidentBytes':int(values['VmHWM'].split()[0])*1024}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--pid',type=int,required=True);p.add_argument('--binary',required=True);p.add_argument('--storage',required=True);p.add_argument('--output',required=True);p.add_argument('--seconds',type=int,default=3600);a=p.parse_args()
    samples=[];start=time.monotonic();target=Path(a.output);target.parent.mkdir(parents=True,exist_ok=True)
    while time.monotonic()-start<a.seconds:
        try:values=sample(a.pid,a.binary)
        except ProcessLookupError:break
        values.update(elapsedSeconds=round(time.monotonic()-start,3),hostFreeBytes=shutil.disk_usage(a.storage).free);samples.append(values)
        if len(samples)%10==0:
            target.write_text(json.dumps({'pid':a.pid,'binary':a.binary,'samples':samples,'peakResidentBytes':max(s['peakResidentBytes'] for s in samples)},indent=2),encoding='utf-8')
        time.sleep(1)
    target.write_text(json.dumps({'pid':a.pid,'binary':a.binary,'samples':samples,'peakResidentBytes':max((s['peakResidentBytes'] for s in samples),default=0)},indent=2),encoding='utf-8')
